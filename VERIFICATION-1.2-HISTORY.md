# Проверка LifeTrack 1.2.0 — 6 октября 2026

BUILD STATUS: PASS. `test`, `assembleDebug`, `lintDebug`, `connectedDebugAndroidTest` выполнены на том же существующем проекте. Промежуточные build/cache каталоги временно вынесены в `/private/tmp`, чтобы исключить дубли генерируемых файлов из синхронизируемой Documents. Это не новая версия data layer и не новый Android-проект.

- 30 unit-тестов debug и те же 30 release: 0 failures/errors/skipped.
- 10 инструментальных тестов Android 15 / API35 ARM64, AVD LifeTrackTest: 0 failures/errors/skipped.
- Lint: 0 errors, 17 warnings, 3 hints. Предупреждения относятся к обновлениям закреплённых зависимостей и существующему UseKtx. Baseline и подавление ошибок не добавлялись.
- APK: `../LifeTrack.apk`; package `com.lifetrack`, versionCode 3 / versionName 1.2.0, minSdk 26 / targetSdk 36.
- APK подписан прежним debug certificate; apksigner v2 verify PASS. APK пригоден для установки; Google Play release signing не выполнялся.
- SHA-256: `f7d5793c2acf528bb190f9a99a3db26aaaf89003d3f00cc887d7f0d74b866e65`.

## Сохранение данных и логики

Все 9 исходников data/domain совпали с контрольными SHA-256 до redesign. `LifeViewModel.kt` и `NutritionViewModel.kt` также совпали побайтово. Room остаётся schema 2: новых миграций, destructive migration, сбросов базы и изменений repositories не вводилось.

Обновление 1.1 → 1.2 через `adb install -r` сохранено. До и после обновления сравнивались все строки:3 accounts,16 categories,8 transactions,1 budget,4 habits,128 completions,2 goals,13 food_items,4 food_logs,1 nutrition_goals,2 water_logs. Вместе с android_metadata и room_master_table все 13 таблиц совпали. После тестов исходный тестовый экземпляр восстановлен, сравнение повторено и совпало.

Room tests повторно проверили миграцию 1 → 2, CRUD, transfer, habit undo, backup round trip, старый backup без nutrition, отказ плохого импорта без очистки, immutable nutritional snapshots, atomic photo save с явным подтверждением, favorite/water/goals, параллельную инициализацию каталога и EXIF/bounded bitmap.

## Проверка UI реального APK

Основные Today, Habits, Nutrition, Finance, Analytics, Goals, Settings проверены на 360 dp/390dp и большом 420 dp телефоне, в светлой и тёмной темах. Снимки — `../redesign/`. Они показывают данные тестового экземпляра, а не макеты. Превью четырёх экранов — `../LifeTrack-redesign.png`.

- Проверены первая установка/onboarding и пустые состояния четырёх главных экранов на 360 dp.
- Привычка отмечена:3/4→4/4; отмена вернула3/4. Details отображает реальный календарь и серии. Bottom navigation корректно выделяет Habits в details.
- Глобальный плюс Today→Добавить еду открыл выбор способа добавления. Finance плюс открыл Расход/Доход/Перевод.
- Нулевая сумма отклонена без сохранения. На отдельной свежей тестовой базе новый UI сохранил расход 123,45 ₽ как 12345 minor units и ручное блюдо620 ккал/Б 54/Ж 16/У 67 как 620000/54000/16000/67000. Значения проверены непосредственно в копии Room DB; временные записи не оставлены в исходном тестовом экземпляре.
- Android photo picker выбирает конкретное фото без разрешения на весь медиакаталог. Обычное использование фото возвращает честный Offline. Явная демонстрация показывает фиксированный620 ккал пример и не сохраняется автоматически. Проверены прокрутка состава, редактирование и закреплённое подтверждение.
- Отдельно проверены Budgets и вкладки аналитики питания/привычек/финансов на 360 dp.
- Длинные денежные значения масштабируются целиком с валютой. Нижние элементы доступны прокруткой; формы учитывают клавиатуру.

## Ограничения проверки

Проверка выполнена на эмуляторе; физический Android-телефон не использовался. Изображение в photo QA — тестовый кадр виртуального устройства. Реальный food recognition backend НЕ подключён; демонстрация не анализирует выбранное изображение. Иностранные assets/UI и вымышленные личные данные в продукт не добавлены.

## Доказательства

`../verification/redesign-build.txt`, `redesign-unit-tests.json`, `redesign-lint.txt`, `redesign-apk-signature.txt`, `redesign-data-domain.json`, `redesign-persistence.json`, `redesign-ui-saves.json`. Архив проекта: `../LifeTrack-source.zip`, без build/cache/toolchain/local.properties.
