package com.lifetrack.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.data.*
import com.lifetrack.domain.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhotoPreview(file: File) {
    val bitmap by
        produceState<ImageBitmap?>(null, file) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
                        .getOrNull()
                }
        }
    bitmap?.let {
        Image(
            it,
            "Фото блюда",
            modifier =
                Modifier.fillMaxWidth()
                    .height(260.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
fun FoodPhotoScreen(vm: NutritionViewModel, initial: String, onDone: () -> Unit) {
    val state by vm.photo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var meal by remember { mutableStateOf("LUNCH") }
    var edit by remember { mutableStateOf<Pair<Int, FoodLog>?>(null) }
    var manual by remember { mutableStateOf<FoodLog?>(null) }
    var saving by remember { mutableStateOf(false) }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok)
                vm.cameraFile?.let {
                    vm.selectPhoto(
                        androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "com.lifetrack.photos",
                            it,
                        )
                    )
                }
            else Unit
        }
    fun launchCamera() {
        try {
            camera.launch(vm.cameraUri())
        } catch (e: Exception) {
            vm.launch { vm.messages.emit("Камера недоступна. Выберите фото из галереи.") }
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
            else
                vm.launch {
                    vm.messages.emit(
                        "Камера не разрешена. Можно выбрать фото или добавить вручную."
                    )
                }
        }
    fun requestCamera() {
        if (
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
            launchCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) vm.selectPhoto(uri)
        }
    fun pick() {
        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    LaunchedEffect(Unit) {
        vm.resetPhoto()
        if (initial == "camera") requestCamera() else if (initial == "gallery") pick()
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LifeIconButton(Icons.Outlined.ChevronLeft, "Назад", onDone)
                Spacer(Modifier.width(12.dp))
                Text("Фото еды", style = MaterialTheme.typography.headlineSmall)
            }
            vm.currentFile()?.let { PhotoPreview(it) }
            when (val p = state) {
                PhotoUiState.Idle -> {
                    LifeEmptyState(
                        "Что сегодня на тарелке?",
                        "Сделайте снимок или выберите фотографию. Перед сохранением сможете исправить порцию и состав.",
                        icon = Icons.Outlined.PhotoCamera,
                    )
                    LifePrimaryButton(
                        "Сфотографировать",
                        { requestCamera() },
                        Modifier.fillMaxWidth(),
                        Icons.Outlined.PhotoCamera,
                    )
                    SecondaryButton({ pick() }, Modifier.fillMaxWidth()) { Text("Выбрать фото") }
                }
                PhotoUiState.Preparing -> {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Подготавливаем фото…", style = MaterialTheme.typography.bodyMedium)
                }
                is PhotoUiState.Preview -> {
                    Text("Готово к проверке", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "При анализе фото отправляется на ваш настроенный сервер и vision-провайдеру. Оценка приблизительная: проверьте состав и порции. Нажатие «Отправить на анализ» подтверждает отправку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LifePrimaryButton(
                        "Отправить на анализ",
                        { vm.recognize() },
                        Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton({ requestCamera() }) { Text("Переснять") }
                        TextButton({ pick() }) { Text("Другое фото") }
                    }
                }
                is PhotoUiState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("Анализируем блюдо…", style = MaterialTheme.typography.titleMedium)
                    }
                }
                is PhotoUiState.Offline -> {
                    Text("Пока без распознавания", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        p.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LifePrimaryButton(
                        "Заполнить вручную",
                        {
                            manual =
                                FoodLog(
                                    customName = "",
                                    mealType = meal,
                                    amount = 100000,
                                    calories = 0,
                                    protein = 0,
                                    fat = 0,
                                    carbs = 0,
                                    source = "PHOTO",
                                )
                        },
                        Modifier.fillMaxWidth(),
                    )
                    TextButton({ pick() }) { Text("Другое фото") }
                }
                is PhotoUiState.Error -> {
                    LifeEmptyState(
                        "Не получилось открыть фото",
                        p.message,
                        "Выбрать другое",
                        { pick() },
                        Icons.Outlined.PhotoCamera,
                    )
                    if (p.file != null) TextButton({ vm.recognize() }) { Text("Повторить") }
                }
                is PhotoUiState.Result -> {
                    val totals = NutritionCalculator.totals(p.result.items.map { it.food })
                    Text(
                        p.result.items.take(3).joinToString(", ") { it.food.customName },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "≈ ${NutritionNumber.rounded(totals.calories)}",
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Column {
                            Text("ккал", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Примерная оценка",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    MacroStrip(totals)
                    if (p.result.demonstration)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "Демонстрация — не анализ вашего фото. Проверьте пример перед сохранением.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    Choice("Приём пищи", meal, MEALS) { meal = it }
                    Section("Состав") {
                        p.result.items.forEachIndexed { i, item ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(
                                    Modifier.weight(1f).clickable { edit = i to item.food },
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        item.food.customName,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        "≈ ${NutritionNumber.format(item.food.amount)} ${item.food.unit} · " +
                                            when (item.confidence) {
                                                "HIGH" -> "Высокая уверенность"
                                                "MEDIUM" -> "Средняя уверенность"
                                                else -> "Уточните порцию"
                                            },
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                            if (item.confidence == "LOW") lifeColors.warning
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "${NutritionNumber.rounded(item.food.calories)} ккал",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                LifeOverflow(
                                    listOf(
                                        (if (item.confidence == "LOW") "Уточнить"
                                        else "Изменить") to { edit = i to item.food },
                                        "Удалить из состава" to { vm.removeRecognition(i) },
                                    )
                                )
                            }
                        }
                    }
                    Text(
                        "Оценка по фото может быть неточной. Проверьте порции и состав.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton({ requestCamera() }) { Text("Переснять") }
                }
            }
        }
        val result = state as? PhotoUiState.Result
        if (result != null)
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 0.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(
                            onClick = {
                                val t =
                                    NutritionCalculator.totals(result.result.items.map { it.food })
                                manual =
                                    FoodLog(
                                        customName =
                                            result.result.items.joinToString(", ") {
                                                it.food.customName
                                            },
                                        mealType = meal,
                                        amount =
                                            sumExact(result.result.items.map { it.food.amount }),
                                        calories = t.calories,
                                        protein = t.protein,
                                        fat = t.fat,
                                        carbs = t.carbs,
                                        composition =
                                            result.result.items.joinToString("; ") {
                                                it.food.customName
                                            },
                                        source = "PHOTO",
                                        recognitionDemo = result.result.demonstration,
                                    )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Изменить")
                        }
                        LifePrimaryButton(
                            "Добавить в дневник",
                            {
                                saving = true
                                vm.saveRecognition(meal, onFinished = { saving = false }) {
                                    onDone()
                                }
                            },
                            Modifier.weight(1.5f),
                            enabled = result.result.items.isNotEmpty() && !saving,
                        )
                    }
                }
            }
    }
    edit?.let { (i, log) ->
        FoodEditor(
            log,
            onDismiss = { edit = null },
            onSave = {
                vm.editRecognition(i, it)
                edit = null
            },
            saveLabel = "Обновить состав",
        )
    }
    manual?.let { l ->
        FoodEditor(
            l,
            onDismiss = { manual = null },
            onSave = {
                vm.save(
                    it.copy(
                        dateTime =
                            vm.date.value
                                .atStartOfDay(java.time.ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                    )
                ) {
                    manual = null
                    onDone()
                }
            },
        )
    }
}
