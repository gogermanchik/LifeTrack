package com.lifetrack.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lifetrack.domain.*
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType

/** Device bearer is encrypted in a noBackup file, and never exported with Room. */
class DeviceTokenStore(context: Context) {
    private val file = java.io.File(context.noBackupFilesDir, "device-session")

    private fun key(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("lifetrack.session", null) as? javax.crypto.SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "lifetrack.session",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    fun save(value: String, origin: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val encrypted =
            c.doFinal(
                buildJsonObject {
                        put("token", value)
                        put("origin", origin)
                    }
                    .toString()
                    .toByteArray()
            )
        file.writeBytes(c.iv + encrypted)
    }

    fun read(origin: String? = null): String? =
        runCatching {
                if (!file.exists()) return null
                val bytes = file.readBytes()
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                Json.parseToJsonElement(String(c.doFinal(bytes.copyOfRange(12, bytes.size))))
                    .jsonObject
                    .let {
                        if (origin == null || it["origin"]?.jsonPrimitive?.content == origin)
                            it["token"]?.jsonPrimitive?.content
                        else null
                    }
            }
            .getOrNull()

    fun clear() {
        file.delete()
    }
}

@Serializable data class Pairing(val deviceCode: String, val userCode: String, val expiresIn: Int)

@Serializable
data class VisionItem(
    val name: String,
    val grams: Long,
    val calories: Long,
    val protein: Long,
    val fat: Long,
    val carbs: Long,
    val confidence: String,
)

@Serializable data class VisionResponse(val items: List<VisionItem>, val uncertain: Boolean)

class BackendClient(private val context: Context) {
    val tokens = DeviceTokenStore(context)
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(65, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    suspend fun url(): String =
        LifeStore.get(context)
            .productDao()
            .preferences()
            .first()
            .firstOrNull { it.key == "backend_url" }
            ?.value
            .orEmpty()

    suspend fun request(
        path: String,
        payload: JsonObject? = null,
        authenticate: Boolean = true,
    ): JsonObject {
        val base = url().trimEnd('/')
        require(base.startsWith("https://")) { "Укажите HTTPS адрес сервера в Подключениях" }
        val b = Request.Builder().url(base + path)
        if (authenticate)
            b.header(
                "Authorization",
                "Bearer ${tokens.read(base) ?:error("Подключите устройство к серверу")}",
            )
        if (payload != null)
            b.post(RequestBody.create("application/json".toMediaType(), payload.toString()))
        return Json.parseToJsonElement(client.newCall(b.build()).awaitText()).jsonObject
    }

    suspend fun pair(): Pairing =
        Json.decodeFromJsonElement(request("/pair/start", JsonObject(emptyMap()), false))

    suspend fun poll(code: String): Boolean {
        val response = request("/pair/poll", buildJsonObject { put("deviceCode", code) }, false)
        val token = response["token"]?.jsonPrimitive?.contentOrNull
        if (token != null) {
            tokens.save(token, url().trimEnd('/'))
            return true
        }
        return false
    }

    suspend fun revoke() {
        try {
            request("/session/revoke", JsonObject(emptyMap()))
        } finally {
            tokens.clear()
        }
    }

    suspend fun configuration() = request("/config")
}

class BackendFoodRecognitionService(private val context: Context) : FoodRecognitionService {
    override val sendsImageToServer = true

    override suspend fun recognizeFood(image: RecognitionImage): RecognitionOutcome {
        val backend = BackendClient(context)
        if (backend.url().isBlank())
            return RecognitionOutcome.Offline(
                "Для анализа нужен ваш сервер и ключ vision-провайдера. Настройте сервер в Ещё → Подключения. Фото пока остаётся на устройстве."
            )
        if (backend.tokens.read() == null)
            return RecognitionOutcome.Offline(
                "Подключите это устройство к серверу в разделе Подключения. Фото не отправлено."
            )
        require(image.jpeg.size in 1..2_000_000)
        val response =
            backend.request(
                "/recognize",
                buildJsonObject { put("image", Base64.encodeToString(image.jpeg, Base64.NO_WRAP)) },
            )
        val parsed = Json.decodeFromJsonElement<VisionResponse>(response)
        require(parsed.items.size in 1..30)
        val result =
            FoodRecognitionResult(
                parsed.items.map { v ->
                    require(
                        v.name.isNotBlank() &&
                            v.name.length <= 120 &&
                            v.grams in 1..10_000_000 &&
                            listOf(v.calories, v.protein, v.fat, v.carbs).all {
                                it in 0..1_000_000_000
                            } &&
                            v.confidence in listOf("HIGH", "MEDIUM", "LOW")
                    )
                    RecognizedFood(
                        FoodLog(
                            customName = v.name,
                            mealType = "LUNCH",
                            amount = v.grams,
                            calories = v.calories,
                            protein = v.protein,
                            fat = v.fat,
                            carbs = v.carbs,
                            source = "PHOTO",
                        ),
                        v.confidence,
                    )
                }
            )
        return if (parsed.uncertain || parsed.items.any { it.confidence != "HIGH" })
            RecognitionOutcome.LowConfidence(result)
        else RecognitionOutcome.Success(result)
    }
}
