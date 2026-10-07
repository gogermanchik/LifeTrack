package com.lifetrack.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.data.*
import com.lifetrack.domain.*
import java.time.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

@Composable
fun HealthSummaryCard(p: ProductSnapshot, navigate: () -> Unit) {
    val date = LocalDate.now()
    val steps = HealthMath.daily(p.health, "STEPS", date)
    val sleep = HealthMath.daily(p.health, "SLEEP", date)
    val resting = HealthMath.daily(p.health, "RESTING_HR", date)
    if (steps != null || sleep != null || resting != null)
        Surface(
            onClick = navigate,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("В вашем ритме", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (steps != null)
                        Column {
                            Text("$steps", style = MaterialTheme.typography.headlineLarge)
                            Text("шагов", style = MaterialTheme.typography.bodySmall)
                        }
                    if (sleep != null)
                        Column {
                            Text(
                                "${sleep/3_600_000} ч ${sleep%3_600_000/60_000} м",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text("сна", style = MaterialTheme.typography.bodySmall)
                        }
                    if (resting != null)
                        Column {
                            Text("${resting/1000}", style = MaterialTheme.typography.titleLarge)
                            Text("пульс покоя", style = MaterialTheme.typography.bodySmall)
                        }
                }
            }
        }
}

@Composable
fun HealthScreen(vm: ProductViewModel, navigate: (String) -> Unit) {
    val p by vm.data.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Обзор") }
    var period by rememberSaveable { mutableStateOf("30 дней") }
    var add by remember { mutableStateOf(false) }
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val date = LocalDate.now()
    val length =
        when (period) {
            "7 дней" -> 7
            "90 дней" -> 90
            "Год" -> 365
            else -> 30
        }
    val records = p.health.filter { HealthMath.day(it) >= date.minusDays(length - 1L) }
    Page("Здоровье", action = { add = true }) {
        Text(
            "Наблюдайте за собой. Без оценок личности и медицинских выводов.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton({ navigate("connections") }) { Text("Источники") }
            TextButton({ vm.sync() }) { Text(if (syncing) "Обновляем…" else "Обновить") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Обзор", "Сон", "Активность", "Сердце", "Тело").forEach { v ->
                FilterChip(tab == v, { tab = v }, { Text(v) }, Modifier.padding(end = 6.dp))
            }
        }
        LifeSegmentedControl(listOf("7 дней", "30 дней", "90 дней", "Год"), period) { period = it }
        if (tab == "Обзор") {
            HealthSummaryCard(p) { tab = "Активность" }
            val recovery = HealthMath.recovery(p.health, date)
            Panel {
                Text("Восстановление", style = MaterialTheme.typography.titleLarge)
                if (recovery == null) {
                    Text("Пока мало данных")
                    Text(
                        "Нужны сегодняшний сон и пульс покоя, а также минимум 7 дней истории пульса. Индикатор появится, когда данных будет достаточно.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("${recovery.score} / 100", style = MaterialTheme.typography.displaySmall)
                    Text(recovery.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val metrics =
            when (tab) {
                "Сон" -> listOf("SLEEP" to "Длительность сна")
                "Активность" ->
                    listOf(
                        "STEPS" to "Шаги",
                        "DISTANCE" to "Расстояние",
                        "ACTIVE_CALORIES" to "Активные калории",
                        "TOTAL_CALORIES" to "Все калории",
                    )
                "Сердце" ->
                    listOf(
                        "RESTING_HR" to "Пульс покоя",
                        "HEART_RATE" to "Пульс",
                        "SPO2" to "Кислород",
                        "VO2MAX" to "VO₂ max",
                    )
                "Тело" ->
                    listOf("WEIGHT" to "Вес", "BODY_FAT" to "Жировая масса", "HEIGHT" to "Рост")
                else -> listOf("WEIGHT" to "Последний вес")
            }
        metrics.forEach { (kind, title) ->
            val values =
                (length - 1 downTo 0).mapNotNull {
                    HealthMath.daily(records, kind, date.minusDays(it.toLong()))
                }
            Panel {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (values.isEmpty())
                    Text("Записей пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    val latest = values.last()
                    Text(
                        if (kind == "SLEEP") "${latest/3_600_000} ч ${latest%3_600_000/60_000} мин"
                        else if (kind == "STEPS") "$latest"
                        else
                            "${latest/1000.0} ${when(kind){"DISTANCE"->"м"
"WEIGHT"->"кг"
"BODY_FAT","SPO2"->"%"
"RESTING_HR","HEART_RATE"->"уд/мин"
"HEIGHT"->"см"
"VO2MAX"->"мл/кг/мин"
else->"ккал"}}",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    LineChart(values, "$title: ${values.size} дней с записями")
                    Text(
                        "${values.size} дней с данными · пробелы в истории не считаются нулём",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (tab == "Сон")
            Section("Ночи") {
                val nights =
                    records
                        .filter { it.kind == "SLEEP_SESSION" }
                        .sortedByDescending { it.end }
                        .take(14)
                if (nights.isEmpty()) Text("История ночей появится после подключения сна.")
                nights.forEach { e ->
                    val bed = Instant.ofEpochMilli(e.start).atZone(ZoneId.systemDefault())
                    val wake = Instant.ofEpochMilli(e.end).atZone(ZoneId.systemDefault())
                    LifeSettingRow(
                        "${HealthMath.day(e)} · ${e.value/3600000} ч ${e.value%3600000/60000} мин",
                        "${bed.toLocalTime().withSecond(0).withNano(0)} — ${wake.toLocalTime().withSecond(0).withNano(0)} · ${e.origin.ifBlank{e.source}}",
                        {},
                    )
                }
            }
        if (tab == "Активность")
            Section("Тренировки") {
                val workouts = HealthMath.workouts(records)
                if (workouts.isEmpty()) Text("Подключите источник тренировок через Health Connect")
                else
                    workouts.take(40).forEach { e ->
                        LifeSettingRow(
                            e.detail.ifBlank { "Тренировка" },
                            "${e.value} мин · ${HealthMath.day(e)} · ${e.origin.ifBlank{e.source}}",
                            {},
                        )
                    }
            }
        if (p.health.isEmpty())
            LifeEmptyState(
                "Начните с того, что важно вам",
                "Добавьте вес вручную или подключите выбранные категории Health Connect.",
                "Подключить источник",
                { navigate("connections") },
            )
        if (tab == "Тело")
            Section("История измерений") {
                records
                    .filter { it.kind in listOf("WEIGHT", "BODY_FAT") }
                    .take(30)
                    .forEach { e ->
                        LifeSettingRow(
                            "${e.value/1000.0} ${e.unit}",
                            "${HealthMath.day(e)} · ${e.source}",
                            {},
                        )
                        if (e.source == "LOCAL")
                            TextButton({ vm.run { vm.repo.dao.delete(e) } }) {
                                Text("Удалить измерение")
                            }
                    }
            }
    }
    if (add) WeightSheet(vm) { add = false }
}

@Composable
fun WeightSheet(vm: ProductViewModel, dismiss: () -> Unit) {
    var weight by rememberSaveable { mutableStateOf("") }
    var fat by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    LifeBottomSheet(dismiss) {
        Text("Новое измерение", style = MaterialTheme.typography.headlineSmall)
        ProductField("Вес, кг", weight, { weight = it }, true)
        ProductField("Жировая масса, % · необязательно", fat, { fat = it }, true)
        ProductField("Дата, ГГГГ-ММ-ДД", day, { day = it })
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        PrimaryButton(
            {
                val date = runCatching { LocalDate.parse(day) }.getOrNull()
                val value = milli(weight)
                val bodyFat = if (fat.isBlank()) null else milli(fat)
                if (
                    date == null ||
                        date > LocalDate.now() ||
                        value == null ||
                        value !in 20_000..500_000 ||
                        (fat.isNotBlank() && (bodyFat == null || bodyFat !in 1_000..75_000))
                )
                    error = "Проверьте вес, процент и дату"
                else {
                    vm.weight(value, bodyFat, date)
                    dismiss()
                }
            },
            Modifier.fillMaxWidth(),
        ) {
            Text("Сохранить")
        }
    }
}

@Composable
fun ConnectionsScreen(vm: ProductViewModel) {
    val context = LocalContext.current
    val p by vm.data.collectAsStateWithLifecycle()
    val granted by vm.granted.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var paired by remember { mutableStateOf(vm.backend.tokens.read() != null) }
    var delete by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    val permission =
        rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) {
            vm.refreshPermissions()
            vm.sync()
        }
    LaunchedEffect(Unit) { vm.refreshPermissions() }
    LaunchedEffect(p.preferences) {
        if (url.isBlank())
            url = p.preferences.firstOrNull { it.key == "backend_url" }?.value.orEmpty()
    }
    LaunchedEffect(pairing) {
        val value = pairing ?: return@LaunchedEffect
        repeat(value.expiresIn / 3) {
            delay(3000)
            try {
                if (vm.backend.poll(value.deviceCode)) {
                    paired = true
                    pairing = null
                    return@LaunchedEffect
                }
            } catch (e: Exception) {
                return@LaunchedEffect
            }
        }
        pairing = null
    }
    Page("Подключения") {
        Section("Health Connect") {
            val status = vm.health.status
            Text(
                when (status) {
                    HealthConnectClient.SDK_AVAILABLE ->
                        if (granted.isEmpty()) "Выберите категории, которыми хотите делиться"
                        else "Доступно ${granted.size} разрешений"
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                        "Нужно установить или обновить Health Connect"
                    else -> "Health Connect недоступен на этом устройстве"
                }
            )
            if (status == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectSource.groups.keys.forEach { group ->
                    val permissions = HealthConnectSource.permissions(group)
                    LifeSettingRow(
                        group,
                        if (granted.containsAll(permissions)) "Доступ разрешён"
                        else if (granted.any { it in permissions }) "Частичный доступ"
                        else "Нет доступа",
                        { permission.launch(permissions) },
                    )
                }
                TextButton({ vm.sync() }) { Text("Обновить данные") }
                if (
                    androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
                        .features
                        .getFeatureStatus(
                            androidx.health.connect.client.HealthConnectFeatures
                                .FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
                        ) ==
                        androidx.health.connect.client.HealthConnectFeatures
                            .FEATURE_STATUS_AVAILABLE
                )
                    TextButton({
                        permission.launch(
                            setOf("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND")
                        )
                    }) {
                        Text("Разрешить обновление в фоне")
                    }
                if (vm.health.status == HealthConnectClient.SDK_AVAILABLE)
                    TextButton({
                        runCatching {
                            context.startActivity(
                                Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                            )
                        }
                    }) {
                        Text("Управлять разрешениями")
                    }
            } else if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
                TextButton({
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"
                            ),
                        )
                    )
                }) {
                    Text("Открыть Health Connect")
                }
            p.preferences
                .firstOrNull { it.key == "health_last_sync" }
                ?.value
                ?.toLongOrNull()
                ?.let {
                    Text(
                        "Обновлено: ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
        }
        Section("Samsung Health") {
            Text(
                if (p.health.any { it.origin.contains("shealth") })
                    "Получены записи Samsung через Health Connect"
                else
                    "Через Health Connect: включите обмен в Samsung Health и разрешите нужные категории в LifeTrack."
            )
            TextButton({
                val intent =
                    context.packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth")
                if (intent != null) context.startActivity(intent)
                else
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                "https://play.google.com/store/apps/details?id=com.sec.android.app.shealth"
                            ),
                        )
                    )
            }) {
                Text("Открыть Samsung Health")
            }
        }
        Section("Распознавание фото") {
            Text(
                "Фото отправляется только после вашего подтверждения. Ключ провайдера хранится на вашем сервере. Дневник и здоровье туда не отправляются."
            )
            ProductField("HTTPS адрес сервера", url, { url = it })
            PrimaryButton({
                if (url.startsWith("https://") && runCatching { java.net.URI(url) }.isSuccess)
                    vm.run {
                        vm.repo.dao.put(ProductPreference("backend_url", url.trimEnd('/')))
                        vm.backend.tokens.clear()
                        paired = false
                        pairing = vm.backend.pair()
                    }
                else vm.error.value = "Нужен корректный HTTPS адрес"
            }) {
                Text("Подключить устройство")
            }
            if (pairing != null) {
                Text("Код ${pairing!!.userCode}", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Владелец сервера подтверждает этот код командой approve. Код действителен 10 минут."
                )
            }
            if (paired) {
                Text("Устройство подключено")
                TextButton({ vm.run { config = vm.backend.configuration() } }) {
                    Text("Проверить сервер")
                }
                TextButton({
                    vm.run {
                        vm.backend.revoke()
                        paired = false
                    }
                }) {
                    Text("Отключить сервер")
                }
            }
            if (config != null)
                Text(
                    if (
                        config?.get("PHOTO_RECOGNITION_ENABLED")?.jsonPrimitive?.booleanOrNull ==
                            true
                    )
                        "Распознавание на сервере включено"
                    else "Для распознавания владелец сервера должен добавить ключ провайдера",
                    style = MaterialTheme.typography.bodySmall,
                )
        }
        Section("Garmin") {
            if (config?.get("GARMIN_CONNECT_ENABLED")?.jsonPrimitive?.booleanOrNull == true) {
                TextButton({
                    vm.run {
                        val link = GarminAuthManager(vm.backend).authorize()
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    }
                }) {
                    Text("Подключить Garmin")
                }
                TextButton({
                    vm.run {
                        GarminSyncManager(GarminApiClient(vm.backend), GarminRepository(vm.db))
                            .sync()
                        vm.updateRules()
                        vm.messages.emit("Записи Garmin обновлены")
                    }
                }) {
                    Text("Обновить Garmin")
                }
                TextButton({
                    vm.run {
                        GarminSyncManager(GarminApiClient(vm.backend), GarminRepository(vm.db))
                            .disconnect()
                        vm.messages.emit("Garmin отключён")
                    }
                }) {
                    Text("Отключить Garmin")
                }
            }
            Text(
                "Интеграция подготовлена. Для включения нужны одобрение Garmin Developer Program и клиентские credentials на сервере. Подключение появится после активации сервером.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        TextButton({ delete = true }) { Text("Удалить локальную историю Health Connect") }
    }
    if (delete)
        AlertDialog(
            { delete = false },
            title = { Text("Удалить историю источника?") },
            text = {
                Text(
                    "Записи Health Connect исчезнут из LifeTrack. Ручные измерения сохранятся. Для остановки чтения отзовите разрешения в Health Connect."
                )
            },
            confirmButton = {
                TextButton({
                    vm.run { vm.repo.dao.disconnect("HC") }
                    delete = false
                }) {
                    Text("Удалить")
                }
            },
            dismissButton = { TextButton({ delete = false }) { Text("Отмена") } },
        )
}
