package com.lifetrack.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.lifetrack.banking.BankViewModel
import com.lifetrack.data.*
import com.lifetrack.domain.*
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Green = Color(0xFF287A61)

@Composable
fun LifeApp(vm: LifeViewModel, initialAction: String? = null, actionNonce: Long = 0) {
    val s by vm.data.collectAsStateWithLifecycle()
    val prefs by vm.settings.collectAsStateWithLifecycle()
    val nvm: NutritionViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val pvm: ProductViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val bvm: BankViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val nutritionDate by nvm.date.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val dark = prefs.theme == "DARK" || (prefs.theme == "SYSTEM" && isSystemInDarkTheme())
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
    }
    LifeTheme(dark) {
        val nav = rememberNavController()
        val snack = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var form by remember { mutableStateOf<Any?>(null) }
        var deletion by remember { mutableStateOf<Any?>(null) }
        var clear by remember { mutableStateOf(false) }
        var quickAdd by remember { mutableStateOf(false) }
        var foodAddRequested by remember { mutableStateOf(false) }
        var weightRequested by remember { mutableStateOf(false) }
        var waterRequested by remember { mutableStateOf(false) }
        LaunchedEffect(initialAction, actionNonce, ready, prefs.onboarded) {
            if (ready && prefs.onboarded && initialAction != null) {
                if (initialAction == "water") waterRequested = true
                else if (initialAction in listOf("today", "nutrition-catalog", "finance-inbox"))
                    nav.navigate(initialAction) { launchSingleTop = true }
            }
        }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val context = LocalContext.current
        val export =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                if (uri != null)
                    vm.run {
                        val text = vm.export()
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use {
                                it.write(text.toByteArray())
                            }
                        }
                        vm.messages.emit("Резервная копия сохранена")
                    }
            }
        val import =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null)
                    vm.run {
                        val text =
                            withContext(Dispatchers.IO) {
                                context.contentResolver
                                    .openInputStream(uri)
                                    ?.bufferedReader()
                                    ?.use { it.readText() } ?: error("Файл недоступен")
                            }
                        vm.import(text)
                        vm.messages.emit("Данные восстановлены")
                    }
            }
        val permission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted
                ->
                vm.saveSettings(prefs.copy(notifications = granted))
            }
        LaunchedEffect(vm) { vm.messages.collect { snack.showSnackbar(it) } }
        LaunchedEffect(nvm) { nvm.messages.collect { snack.showSnackbar(it) } }
        LaunchedEffect(pvm) { pvm.messages.collect { snack.showSnackbar(it) } }
        LaunchedEffect(bvm) { bvm.messages.collect { snack.showSnackbar(it) } }
        val tabs =
            listOf(
                "today" to "Сегодня",
                "nutrition" to "Питание",
                "habits" to "Привычки",
                "finance" to "Финансы",
                "more" to "Ещё",
            )
        val back by nav.currentBackStackEntryAsState()
        val route = back?.destination?.route ?: "today"
        Scaffold(
            snackbarHost = {
                SnackbarHost(snack) { data ->
                    Snackbar(
                        data,
                        shape = MaterialTheme.shapes.medium,
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                        actionColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
            },
            floatingActionButton = {
                if (prefs.onboarded && route in listOf("today", "finance"))
                    FloatingActionButton(
                        { quickAdd = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Icon(Icons.Outlined.Add, "Добавить", Modifier.size(26.dp))
                    }
            },
            bottomBar = {
                if (prefs.onboarded && route != "nutrition-barcode")
                    LifeNavigationBar(tabs, route) { destination ->
                        if (lifeSection(route) == destination && route != destination) {
                            if (!nav.popBackStack(destination, false))
                                nav.navigate(destination) {
                                    popUpTo("today") { saveState = true }
                                    launchSingleTop = true
                                }
                        } else
                            nav.navigate(destination) {
                                popUpTo("today") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                    }
            },
        ) { padding ->
            Surface(
                Modifier.fillMaxSize().padding(padding),
                color = MaterialTheme.colorScheme.background,
            ) {
                if (!ready && loadError != null)
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(loadError ?: "Ошибка")
                        PrimaryButton(onClick = { vm.load() }) { Text("Повторить") }
                    }
                else if (!ready)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                else if (!prefs.onboarded)
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.TrendingUp,
                            LifeBrand.name,
                            Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(LifeBrand.name, style = MaterialTheme.typography.displaySmall)
                        Text(
                            "Твой день.\nТвой ритм.",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Личные данные остаются на устройстве. Регистрация не нужна.")
                        Spacer(Modifier.height(24.dp))
                        PrimaryButton(onClick = { vm.start(false) }, Modifier.fillMaxWidth()) {
                            Text("Начать")
                        }
                        SecondaryButton(onClick = { vm.start(true) }, Modifier.fillMaxWidth()) {
                            Text("Посмотреть пример")
                        }
                    }
                else
                    NavHost(
                        nav,
                        startDestination = "today",
                        enterTransition = { fadeIn(tween(180)) },
                        exitTransition = { fadeOut(tween(150)) },
                    ) {
                        composable("today") {
                            DashboardScreen(
                                s,
                                prefs.currency,
                                { form = it },
                                { vm.toggle(it) },
                                { nav.navigate(it) },
                            )
                        }
                        composable("habits") {
                            HabitsScreen(
                                s,
                                { form = it },
                                { habit, date -> vm.toggle(habit, date) },
                                { nav.navigate("habit/${it.id}") },
                                { deletion = it },
                                { vm.saveHabit(it.copy(archived = !it.archived)) },
                            )
                        }
                        composable("habit/{id}") { entry ->
                            val h =
                                s.habits.firstOrNull {
                                    it.id.toString() == entry.arguments?.getString("id")
                                }
                            if (h != null)
                                HabitDetail(h, s, prefs.monday, { vm.toggle(h, it) }, { form = h })
                            else
                                Page("Привычка удалена") {
                                    TextButton(onClick = { nav.popBackStack() }) { Text("Назад") }
                                }
                        }
                        composable("finance") {
                            FinanceScreen(
                                s,
                                prefs.currency,
                                { form = it },
                                { deletion = it },
                                { nav.navigate(it) },
                            )
                        }
                        composable("nutrition") {
                            NutritionScreen(
                                nvm,
                                { nav.navigate("nutrition-photo/$it") },
                                { nav.navigate("nutrition-analytics") },
                                foodAddRequested,
                                { foodAddRequested = false },
                                { nav.navigate(it) },
                                { pvm.copyYesterday(nutritionDate) },
                            )
                        }
                        composable("nutrition-photo/{initial}") { entry ->
                            FoodPhotoScreen(
                                nvm,
                                entry.arguments?.getString("initial") ?: "gallery",
                                { nav.popBackStack() },
                            )
                        }
                        composable("nutrition-analytics") {
                            val n by nvm.data.collectAsStateWithLifecycle()
                            NutritionAnalytics(n)
                        }
                        composable("analytics") { AnalyticsHub(s, prefs.currency, nvm) }
                        composable("report") { AnalyticsScreen(s, prefs.currency, true) }
                        composable("goals") { GoalsScreen(s, { form = it }, { deletion = it }) }
                        composable("budgets") {
                            BudgetsScreen(s, prefs.currency, { form = it }, { deletion = it })
                        }
                        composable("accounts") { AccountsScreen(s, { form = it }) }
                        composable("categories") { CategoriesScreen(s, { form = it }) }
                        composable("more") { MoreHubScreen { nav.navigate(it) } }
                        composable("organization") {
                            Page("Организация") {
                                Section("Ваш ритм") {
                                    LifeSettingRow(
                                        "Напоминания",
                                        "",
                                        { nav.navigate("product-notifications") },
                                    )
                                    LifeSettingRow(
                                        "Ритм привычек",
                                        "Типы и автопрогресс",
                                        { nav.navigate("habit-rules") },
                                    )
                                    LifeSettingRow(
                                        "Связанные цели",
                                        "Из ваших записей",
                                        { nav.navigate("linked-goals") },
                                    )
                                    LifeSettingRow(
                                        "Связи в истории",
                                        "",
                                        { nav.navigate("insights") },
                                    )
                                }
                                Section("Деньги") {
                                    LifeSettingRow(
                                        "Подписки",
                                        "",
                                        { nav.navigate("subscriptions") },
                                    )
                                    LifeSettingRow("Бюджеты", "", { nav.navigate("budgets") })
                                    LifeSettingRow("Счета", "", { nav.navigate("accounts") })
                                    LifeSettingRow(
                                        "Автоматизация",
                                        "",
                                        { nav.navigate("bank-automation") },
                                    )
                                    LifeSettingRow(
                                        "Импорт выписки",
                                        "CSV / XLSX",
                                        { nav.navigate("statement-import") },
                                    )
                                    LifeSettingRow("Отчёт за месяц", "", { nav.navigate("report") })
                                }
                            }
                        }
                        composable("nutrition-catalog") {
                            CatalogScreen(
                                pvm,
                                nutritionDate,
                                { nav.navigate("nutrition-barcode") },
                                { nav.navigate("nutrition-label") },
                            )
                        }
                        composable("nutrition-barcode") { BarcodeScreen(pvm, nutritionDate) }
                        composable("nutrition-caffeine") { CaffeineScreen(pvm, nutritionDate) }
                        composable("nutrition-label") { LabelScreen(pvm, nutritionDate) }
                        composable("nutrition-recipes") { RecipeScreen(pvm, nutritionDate) }
                        composable("health") { HealthScreen(pvm) { nav.navigate(it) } }
                        composable("connections") { ConnectionsScreen(pvm) }
                        composable("profile") { ProfileScreen(pvm) }
                        composable("subscriptions") { SubscriptionScreen(pvm, s) }
                        composable("review") { ReviewScreen(s, prefs.currency) }
                        composable("habit-rules") { HabitRulesScreen(pvm, s) }
                        composable("linked-goals") { LinkedGoalsScreen(pvm, s) }
                        composable("product-notifications") { ProductNotificationScreen(pvm) }
                        composable("insights") { InsightsScreen(s) }
                        composable("bank-automation") {
                            BankAutomationScreen(bvm, s) { nav.navigate(it) }
                        }
                        composable("finance-inbox") { FinanceInboxScreen(bvm, s) }
                        composable("statement-import") { StatementImportScreen(bvm, s) }
                        composable("settings") {
                            SettingsScreen(
                                prefs,
                                { vm.saveSettings(it) },
                                {
                                    if (it && Build.VERSION.SDK_INT >= 33)
                                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else vm.saveSettings(prefs.copy(notifications = it))
                                },
                                { export.launch("LifeTrack-${LocalDate.now()}.json") },
                                { form = "IMPORT" },
                                { vm.run { vm.repo.demo() } },
                                {
                                    vm.run {
                                        vm.repo.removeDemo()
                                        vm.refreshReminders()
                                    }
                                },
                                { clear = true },
                            )
                        }
                    }
            }
        }
        if (quickAdd)
            LifeBottomSheet({ quickAdd = false }) {
                Text("Добавить", style = MaterialTheme.typography.headlineSmall)
                if (route == "finance") {
                    listOf("EXPENSE" to "Расход", "INCOME" to "Доход", "TRANSFER" to "Перевод")
                        .forEach { (kind, label) ->
                            LifeSheetAction(
                                Icons.Outlined.AccountBalanceWallet,
                                label,
                                onClick = {
                                    quickAdd = false
                                    form = kind
                                },
                            )
                        }
                } else {
                    LifeSheetAction(
                        Icons.Outlined.PhotoCamera,
                        "Сфотографировать еду",
                        "Камера или галерея",
                        {
                            quickAdd = false
                            nav.navigate("nutrition-photo/camera")
                        },
                    )
                    LifeSheetAction(
                        Icons.Outlined.AccountBalanceWallet,
                        "Перевод",
                        onClick = {
                            quickAdd = false
                            form = "TRANSFER"
                        },
                    )
                    LifeSheetAction(
                        Icons.Outlined.PhotoCamera,
                        "Сканировать штрихкод",
                        onClick = {
                            quickAdd = false
                            nav.navigate("nutrition-barcode")
                        },
                    )
                    LifeSheetAction(
                        Icons.Outlined.Search,
                        "Найти продукт",
                        onClick = {
                            quickAdd = false
                            nav.navigate("nutrition-catalog")
                        },
                    )
                    LifeSheetAction(
                        Icons.Outlined.WaterDrop,
                        "Добавить воду",
                        onClick = {
                            quickAdd = false
                            waterRequested = true
                        },
                    )
                    LifeSheetAction(
                        Icons.Outlined.CheckCircle,
                        "Записать вес",
                        onClick = {
                            quickAdd = false
                            weightRequested = true
                        },
                    )
                    LifeSheetAction(
                        Icons.Outlined.Restaurant,
                        "Добавить еду",
                        "В дневник питания",
                        {
                            foodAddRequested = true
                            quickAdd = false
                            nav.navigate("nutrition")
                        },
                    )
                    LifeSheetAction(
                        Icons.Outlined.AccountBalanceWallet,
                        "Добавить расход",
                        onClick = {
                            quickAdd = false
                            form = "EXPENSE"
                        },
                    )
                    LifeSheetAction(
                        Icons.Outlined.AccountBalanceWallet,
                        "Добавить доход",
                        onClick = {
                            quickAdd = false
                            form = "INCOME"
                        },
                    )
                    LifeSheetAction(
                        Icons.Outlined.CheckCircle,
                        "Добавить привычку",
                        onClick = {
                            quickAdd = false
                            form = "HABIT"
                        },
                    )
                    LifeSheetAction(
                        Icons.Outlined.CheckCircle,
                        "Добавить цель",
                        onClick = {
                            quickAdd = false
                            form = "GOAL"
                        },
                    )
                }
            }
        if (weightRequested) WeightSheet(pvm) { weightRequested = false }
        if (waterRequested)
            LifeBottomSheet({ waterRequested = false }) {
                Text("Вода", style = MaterialTheme.typography.headlineSmall)
                listOf(250L, 500L).forEach { amount ->
                    LifeSheetAction(
                        Icons.Outlined.WaterDrop,
                        "$amount мл",
                        onClick = {
                            nvm.launch {
                                nvm.repository.water(WaterLog(amountMl = amount))
                                waterRequested = false
                            }
                        },
                    )
                }
            }
        if (form == "IMPORT")
            AlertDialog(
                onDismissRequest = { form = null },
                title = { Text("Восстановить резервную копию?") },
                text = {
                    Text(
                        "Импорт заменит текущие данные. Сначала сохраните экспорт, если они нужны."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            form = null
                            import.launch(arrayOf("application/json", "text/plain"))
                        }
                    ) {
                        Text("Выбрать файл")
                    }
                },
                dismissButton = { TextButton(onClick = { form = null }) { Text("Отмена") } },
            )
        else
            form?.let { value ->
                Editor(
                    value,
                    s,
                    prefs.currency,
                    onDismiss = { form = null },
                    onSave = { item ->
                        vm.run {
                            when (item) {
                                is TransactionEntity -> vm.repo.transaction(item)
                                is HabitEntity -> {
                                    vm.repo.habit(item)
                                    vm.refreshReminders()
                                }
                                is GoalEntity -> vm.repo.goal(item)
                                is BudgetEntity -> vm.repo.budget(item)
                                is AccountEntity -> vm.repo.account(item)
                                is CategoryEntity -> vm.repo.category(item)
                            }
                            form = null
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm
                            )
                            vm.messages.emit("Сохранено")
                        }
                    },
                )
            }
        deletion?.let { value ->
            AlertDialog(
                onDismissRequest = { deletion = null },
                title = { Text("Удалить запись?") },
                text = { Text("После удаления можно отменить действие.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deletion = null
                            vm.run {
                                val removed = vm.repo.delete(value)
                                vm.refreshReminders()
                                scope.launch {
                                    if (
                                        snack.showSnackbar(
                                            "Запись удалена",
                                            "Отменить",
                                            duration = SnackbarDuration.Long,
                                        ) == SnackbarResult.ActionPerformed
                                    ) {
                                        vm.run {
                                            vm.repo.restore(removed)
                                            vm.refreshReminders()
                                        }
                                    }
                                }
                            }
                        }
                    ) {
                        Text("Удалить")
                    }
                },
                dismissButton = { TextButton(onClick = { deletion = null }) { Text("Отмена") } },
            )
        }
        if (clear)
            AlertDialog(
                onDismissRequest = { clear = false },
                title = { Text("Очистить все данные?") },
                text = {
                    Text(
                        "Привычки, финансы и цели будут удалены. Сохраните резервную копию перед очисткой."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clear = false
                            vm.run {
                                ReminderScheduler.sync(context, s.habits, false)
                                vm.repo.clear()
                                vm.messages.emit("Данные очищены")
                            }
                        }
                    ) {
                        Text("Очистить")
                    }
                },
                dismissButton = { TextButton(onClick = { clear = false }) { Text("Отмена") } },
            )
    }
}
