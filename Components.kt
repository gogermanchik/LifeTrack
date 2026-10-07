package com.lifetrack.data

import com.lifetrack.domain.BarcodeValue
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*

suspend fun Call.awaitText(): String = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    try {
                        if (!r.isSuccessful) {
                            val errorText =
                                r.body
                                    ?.source()
                                    ?.readUtf8(
                                        minOf(
                                            r.body?.contentLength()?.takeIf { it >= 0 } ?: 0,
                                            2048,
                                        )
                                    )
                                    .orEmpty()
                            val message =
                                runCatching {
                                        Json.parseToJsonElement(errorText)
                                            .jsonObject["message"]
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                    }
                                    .getOrNull()
                            throw CatalogException(
                                message
                                    ?: if (r.code == 429)
                                        "Слишком много запросов. Попробуйте через минуту"
                                    else "Сервис временно недоступен (${r.code})"
                            )
                        }
                        val body = r.body ?: error("Пустой ответ")
                        if (body.contentLength() > 3_000_000) error("Ответ слишком велик")
                        val source = body.source()
                        source.request(3_000_001)
                        require(source.buffer.size <= 3_000_000) { "Ответ слишком велик" }
                        val text = source.readUtf8()
                        if (cont.isActive) cont.resume(text)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        }
    )
}

class CatalogException(message: String) : Exception(message)

interface FoodCatalogProvider {
    suspend fun getByBarcode(code: String): CatalogFood?

    suspend fun search(query: String): List<CatalogFood>
}

object OpenFoodFactsMapper {
    fun product(p: JsonObject): CatalogFood? {
        fun text(key: String) = p[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        val name = text("product_name_ru").ifBlank { text("product_name") }
        if (name.isBlank()) return null
        val barcode = text("code").ifBlank { text("_id") }
        if (barcode.isBlank()) return null
        val n = p["nutriments"] as? JsonObject ?: JsonObject(emptyMap())
        fun milli(key: String): Long? =
            runCatching {
                    n[key]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let {
                            BigDecimal(it)
                                .multiply(BigDecimal(1000))
                                .setScale(0, RoundingMode.HALF_UP)
                                .longValueExact()
                        }
                        ?.takeIf { it in 0..1_000_000_000L }
                }
                .getOrNull()
        val serving = text("serving_size")
        val unit =
            if (Regex("(?i)(ml|мл|cl|l\\b)").containsMatchIn(serving.ifBlank { text("quantity") }))
                "мл"
            else "г"
        val servingAmount =
            Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(g|г|ml|мл)\\b")
                .find(serving)
                ?.groupValues
                ?.get(1)
                ?.replace(',', '.')
                ?.toBigDecimalOrNull()
                ?.multiply(BigDecimal(1000))
                ?.toLong()
                ?.takeIf { it > 0 }
        val kcal =
            milli("energy-kcal_100g")
                ?: milli("energy-kj_100g")?.let {
                    BigDecimal(it).divide(BigDecimal("4.184"), 0, RoundingMode.HALF_UP).toLong()
                }
        return CatalogFood(
            key = "off:$barcode",
            barcode = runCatching { BarcodeValue.normalized(barcode) }.getOrDefault(barcode),
            name = name,
            brand = text("brands"),
            baseUnit = unit,
            calories = kcal,
            protein = milli("proteins_100g"),
            fat = milli("fat_100g"),
            carbs = milli("carbohydrates_100g"),
            servingAmount = servingAmount,
            servingLabel = serving,
            imageUrl = text("image_front_small_url").takeIf { it.startsWith("https://") }.orEmpty(),
            quantity = text("quantity"),
            source = "OFF",
        )
    }
}

class OpenFoodFactsProvider(
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(18, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build()
) : FoodCatalogProvider {
    private val fields =
        "code,product_name,product_name_ru,brands,quantity,serving_size,nutriments,image_front_small_url"

    private suspend fun get(url: HttpUrl): JsonObject =
        Json.parseToJsonElement(
                client
                    .newCall(
                        Request.Builder()
                            .url(url)
                            .header("User-Agent", "LifeTrack/1.3 (Android; com.lifetrack)")
                            .build()
                    )
                    .awaitText()
            )
            .jsonObject

    override suspend fun getByBarcode(code: String): CatalogFood? {
        val b = BarcodeValue.normalized(code)
        val root =
            get(
                HttpUrl.Builder()
                    .scheme("https")
                    .host("world.openfoodfacts.org")
                    .addPathSegments("api/v2/product/$b.json")
                    .addQueryParameter("fields", fields)
                    .build()
            )
        return (root["product"] as? JsonObject)?.let { OpenFoodFactsMapper.product(it) }
    }

    override suspend fun search(query: String): List<CatalogFood> {
        if (query.length < 2) return emptyList()
        val root =
            get(
                HttpUrl.Builder()
                    .scheme("https")
                    .host("world.openfoodfacts.org")
                    .addPathSegments("cgi/search.pl")
                    .addQueryParameter("search_terms", query)
                    .addQueryParameter("search_simple", "1")
                    .addQueryParameter("action", "process")
                    .addQueryParameter("json", "1")
                    .addQueryParameter("page_size", "20")
                    .addQueryParameter("fields", fields)
                    .build()
            )
        return (root["products"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let { OpenFoodFactsMapper.product(it) } }
            .orEmpty()
    }
}

class LocalCatalogProvider(
    private val db: LifeDatabase,
    private val remote: FoodCatalogProvider = OpenFoodFactsProvider(),
) : FoodCatalogProvider {
    private val dao = db.productDao()

    suspend fun local(): List<CatalogFood> {
        val n =
            db.nutritionDao()
                .items()
                .first()
                .filter { it.servingUnit in listOf("г", "мл") }
                .map {
                    CatalogFood(
                        key = "local:${it.id}",
                        name = it.name,
                        brand = it.brand,
                        baseUnit = it.servingUnit,
                        calories =
                            com.lifetrack.domain.Portions.scaled(
                                it.calories,
                                100_000,
                                it.servingSize,
                            ),
                        protein =
                            com.lifetrack.domain.Portions.scaled(
                                it.protein,
                                100_000,
                                it.servingSize,
                            ),
                        fat = com.lifetrack.domain.Portions.scaled(it.fat, 100_000, it.servingSize),
                        carbs =
                            com.lifetrack.domain.Portions.scaled(it.carbs, 100_000, it.servingSize),
                        servingAmount = it.servingSize,
                        favorite = it.favorite,
                    )
                }
        return dao.catalog().first() + n
    }

    override suspend fun getByBarcode(code: String): CatalogFood? {
        val b = BarcodeValue.normalized(code)
        local()
            .firstOrNull { it.barcode == b }
            ?.let {
                return it
            }
        return remote.getByBarcode(b)?.also { cache(it) }
    }

    override suspend fun search(query: String): List<CatalogFood> {
        val local = local().filter { ("${it.name} ${it.brand}").contains(query, true) }
        val remote = remote.search(query)
        remote.forEach { cache(it) }
        val overrides = dao.catalog().first().associateBy { it.barcode }
        return (local + remote.map { overrides[it.barcode]?.takeIf { v -> v.override } ?: it })
            .distinctBy { it.barcode ?: it.key }
    }

    suspend fun cache(v: CatalogFood) {
        val old =
            dao.catalog().first().firstOrNull {
                it.key == v.key || (v.barcode != null && it.barcode == v.barcode)
            }
        if (old?.override != true)
            dao.put(v.copy(key = old?.key ?: v.key, favorite = old?.favorite ?: false))
    }

    suspend fun saveOverride(v: CatalogFood) {
        require(v.name.isNotBlank())
        v.barcode?.let { BarcodeValue.normalized(it) }
        dao.put(v.copy(override = true, source = "LOCAL", updatedAt = System.currentTimeMillis()))
    }
}
