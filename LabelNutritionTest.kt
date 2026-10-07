package com.lifetrack.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lifetrack.data.*
import com.lifetrack.domain.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*

@Composable
fun LabelScreen(vm: ProductViewModel, date: LocalDate) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<CatalogFood?>(null) }
    var basisKnown by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<File?>(null) }
    fun read(uri: Uri) {
        scope.launch {
            busy = true
            error = null
            try {
                val nutrition =
                    withContext(Dispatchers.IO) {
                        val file = FoodPhotoProcessor.prepare(context, uri)
                        val recognizer =
                            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        val bitmap =
                            BitmapFactory.decodeFile(file.path)
                                ?: error("Не удалось прочитать фото")
                        try {
                            val text =
                                Tasks.await(
                                        recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                                        30,
                                        java.util.concurrent.TimeUnit.SECONDS,
                                    )
                                    .text
                            LabelNutritionMapper.parse(text)
                        } finally {
                            bitmap.recycle()
                            recognizer.close()
                            file.delete()
                        }
                    }
                require(
                    listOf(nutrition.calories, nutrition.protein, nutrition.fat, nutrition.carbs)
                        .any { it != null }
                ) {
                    "Не удалось прочитать БЖУ. Попробуйте чёткое фото или введите значения вручную."
                }
                basisKnown = nutrition.basis == 100
                acknowledged = basisKnown
                result =
                    CatalogFood(
                        "custom:${java.util.UUID.randomUUID()}",
                        name = "",
                        baseUnit = nutrition.unit ?: "г",
                        calories = nutrition.calories,
                        protein = nutrition.protein,
                        fat = nutrition.fat,
                        carbs = nutrition.carbs,
                        source = "LABEL",
                        override = true,
                    )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "Не удалось прочитать этикетку"
            } finally {
                busy = false
                capture?.delete()
                capture = null
            }
        }
    }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            it?.let(::read)
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok)
                capture?.let {
                    read(FileProvider.getUriForFile(context, "com.lifetrack.photos", it))
                }
            else {
                capture?.delete()
                capture = null
            }
        }
    fun launchCamera() {
        runCatching {
                val dir = File(context.cacheDir, "food_photos").apply { mkdirs() }
                capture = File.createTempFile("label-", ".jpg", dir)
                camera.launch(
                    FileProvider.getUriForFile(context, "com.lifetrack.photos", capture!!)
                )
            }
            .onFailure { error = "Камера недоступна. Выберите фото из галереи." }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) launchCamera()
            else error = "Камера не разрешена. Можно выбрать фото из галереи."
        }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LifeTopBar("Этикетка")
        Text("БЖУ с упаковки", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Снимите таблицу пищевой ценности целиком, без бликов. Фото обрабатывается на устройстве. Сейчас доступны этикетки с латинским текстом; русские значения можно ввести вручную."
        )
        PrimaryButton(
            {
                if (
                    context.checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                )
                    launchCamera()
                else permission.launch(Manifest.permission.CAMERA)
            },
            Modifier.fillMaxWidth(),
            enabled = !busy,
        ) {
            Text("Сфотографировать")
        }
        TextButton(
            {
                gallery.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            enabled = !busy,
        ) {
            Text("Выбрать фото")
        }
        TextButton({
            basisKnown = true
            acknowledged = true
            result =
                CatalogFood("custom:${java.util.UUID.randomUUID()}", name = "", override = true)
        }) {
            Text("Ввести вручную")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (result != null && !acknowledged) {
            Text(
                "Не удалось определить основу таблицы. Перед сохранением пересчитайте значения на 100 г или 100 мл."
            )
            TextButton({ acknowledged = true }) { Text("Проверить и пересчитать") }
        }
    }
    if (acknowledged)
        result?.let {
            CatalogPortionSheet(
                vm,
                it,
                date,
                {
                    result = null
                    acknowledged = false
                },
            )
        }
}
