# Аудит Material 3 Expressive — OpenCodePhone

Дата: 2026-11-08. Срез: `dcde872575aac5d106eb585df636c6e3343cd892`.
Проверены исходники `app`, `core/designsystem`, зависимости, CI и 7 предоставленных JPG. UI не изменялся.

## 1. Executive Summary

**Вердикт: частично expressive, с expressive-ready фундаментом. Это не классический M3 под новым названием.** В `Theme.kt:19–40` действительно используются `MaterialExpressiveTheme`, `MotionScheme.expressive()`, собственные typography/shapes/colorScheme. В bootstrap, чате и Files уже есть волновые progress-индикаторы. Однако авторские компоненты, плотные строки и состояния часто не используют этот фундамент: тема сама не анимирует произвольные `if`, не делает `clickable` статусный бейдж доступной кнопкой и не решает адаптивность контента.

### Установленная конфигурация и границы проверки

- `gradle/libs.versions.toml`: Compose BOM **2026.09.00**, явный `androidx.compose.material3:material3` **1.5.0-rc01**; зависимость экспортируется через `core/designsystem/build.gradle.kts`. Явный version.ref — не предположение о версии из BOM. Dependency resolution в этой аудит-сессии не запускался.
- `app/build.gradle.kts`: compileSdk 37, minSdk 26, targetSdk 28. Target 28 намеренно связан с запуском PRoot; обновлять его ради M3E нельзя.
- Экраны — Compose. XML `themes.xml` — оконная оболочка, не параллельный набор View-экранов.
- Dynamic color реализован для API 31+, **по умолчанию выключен** (`Theme.kt:16–31`): осознанная teal-айдентика, не дефект.
- Системный шрифт `FontFamily.Default`; собственного variable font нет. Его отсутствие не делает приложение не-M3E.
- Уровень для продукта: **subtle** в настройках/терминале, **balanced** в bootstrap/пустых состояниях. Bold-декор в каждом поле здесь вреден.
- Figma и требования к допустимому experimental API не предоставлены. Уже используемые expressive API локально opt-in. Новые экспериментальные зависимости предлагаются только отдельными шагами.
- Скриншоты показывают версию 0.1.5; checkout имеет default versionName 0.1.3 и CI override. Тождество APK этому commit не установлено. Перенос placeholder в диалоге на снимках нельзя автоматически объяснить текущим кодом: в `CloneDialog` и `NewFileDialog` стоит `singleLine = true`.
- Статический JPG не подтверждает движение, hit targets, TalkBack, IME или reduced motion. Light/dynamic/tablet screenshots не предоставлены.
- Текущая официальная [страница релизов](https://developer.android.com/jetpack/androidx/releases/compose-material3#1.5.0-rc01) содержит раздел 1.5.0-rc01. Исторические сигнатуры всех новых expressive-компонентов не извлекались; ниже для непроверенных API используется **паттерн**, а не выдуманный вызов. RC библиотеки не означает stable-статус каждого API.

### Матрица готовности

Оценки — экспертная оценка покрытия, не доля пройденных тестов.

| Область | 0–5 | Комментарий |
|---|---:|---|
| 1. Инфраструктура | 4.5 | Expressive-тема, motion, light/dark и opt-in уже есть |
| 2. Цвет | 4 | Семантические роли, surfaceContainer и парные on-colors; нет доказанного контрастного дефекта |
| 3. Типографика | 3 | Хорошие централизованные роли, но терминал/редактор обходят их |
| 4. Формы | 3 | Семейство 8/12/20/32/48 dp и асимметричные bubbles; кастомные роли не токенизированы, morph нет |
| 5. Motion | 2.5 | Expressive scheme, wavy progress и AnimatedVisibility есть; большинство пользовательских переходов snap |
| 6. Компоненты | 3.5 | Реальные M3 кнопки, chips, sheets, dialogs, navigation; проблемные авторские header/status/info rows |
| 7. Иконки | 2.5 | Векторные, согласованные по tint, но неверная метафора некоторых действий, нет state-вариантов |
| 8. Адаптивность | 2.5 | Bar→rail на 600 dp и edge-to-edge; нет адаптивности самого контента/редактора |
| 9. Состояния/ритм | 2.5 | Есть empty/error/loading, но offline CTA не подключён, reasoning конкурирует с ответом |
| 10. Бренд | 4 | Teal+amber и независимые light/dark, согласованность с обычными тёмными снимками |

**Топ-3:** (1) доступные интерактивные строки и устойчивые заголовки — E-001/002/003/004; (2) иерархия ответа и настроек — E-006/008/016; (3) довести существующий motion до раскрытий и переходов, а не добавлять декоративные пружины — E-009/014.

## 2. Таблица рекомендаций

ADD = механизм отсутствует; FIX = есть, но неполон; ENH = корректен, возможна полезная полировка. XS <30 мин, S <2 ч, M <дня, L >дня. Effort — для локального изменения; полная device-матрица оценивается отдельно. Сокращения путей: `DS/` = `core/designsystem/src/main/java/io/github/oustery/opencodephone/core/designsystem/`; `APP/` = `app/src/main/java/io/github/oustery/opencodephone/`.

| ID | Тип | Область | Location | Сейчас → целевое решение | Priority | Effort | Conf |
|---|---|---|---|---|---|---|---|---|
| E-001 | FIX | 6 | APP/ui/chat/ChatScreen.kt:70–75; DS/Components.kt:26–55 | Clickable status chip → отдельный action chip с hit target/ролью | P0 | XS | high |
| E-002 | FIX | 6 | APP/ui/files/FilesScreen.kt:190–225 | Компактная clickable Row → минимум 48 dp и семантика | P0 | XS | high |
| E-004 | FIX | 8 | DS/Components.kt:115–136 | Две неограниченные Text → распределение ширины label/value | P1 | XS | high |
| E-003 | FIX | 8 | APP/AppRoot.kt:180–207; APP/ui/settings/SettingsScreen.kt:146–155 | Конкурирующие элементы одной строки → ограниченная ширина/перенос действий | P1 | S | high |
| E-005 | FIX | 9 | APP/ui/chat/ChatScreen.kt:96–101 | Offline actionText без callback → рабочий переход в настройки | P1 | S | high |
| E-006 | FIX | 6,9 | APP/ui/settings/SettingsScreen.kt:141–173 | Apply/Test равной силы и раздробленные controls → одна primary action по состоянию | P1 | S | high |
| E-007 | FIX | 3 | APP/ui/terminal/TerminalScreen.kt:81–88,128; APP/ui/files/FilesScreen.kt:294–301 | Локальные 11/12/14 sp → типографические code-роли | P1 | S | high |
| E-008 | FIX | 9 | APP/ui/chat/ChatScreen.kt:286–298 | Reasoning постоянно раскрыт → отдельное сворачиваемое вторичное содержимое | P1 | S | high |
| E-009 | FIX | 5 | APP/ui/chat/ChatScreen.kt:308–348 | Tool output появляется через if → короткое осмысленное раскрытие | P1 | S | high |
| E-016 | FIX | 9 | APP/ui/chat/ChatScreen.kt:128–158; APP/ui/chat/ChatViewModel.kt:255–263 | reverseLayout + raw map → явный контракт порядка сообщений | P1 | S | medium |
| E-010 | FIX | 8 | APP/ui/files/FilesScreen.kt:282–310 | Редактор 420 dp внутри AlertDialog → IME-aware адаптивный редактор | P1 | M | high |
| E-015 | ADD | 1–10 | .github/workflows/ci.yml; app/build.gradle.kts:162; DS/Components.kt | Нет визуальных/семантических гейтов → previews, screenshot и UI tests | P1 | M | high |
| E-014 | ENH | 5,6 | APP/ui/update/AppUpdateCard.kt:34–38 | Классический determinate progress → общий wavy-паттерн и видимый checking | P2 | XS | high |
| E-012 | FIX | 7 | APP/ui/terminal/TerminalScreen.kt:55–61; APP/ui/chat/ChatScreen.kt:76–80 | Stop-иконка очищает, папка открывает сессии → точные метафоры | P2 | S | high |
| E-013 | ENH | 4 | APP/ui/chat/ChatScreen.kt:230,268–273; DS/Shape.kt | Корректная асимметрия локально → роли composer/message shapes | P2 | S | high |
| E-011 | ENH | 8 | APP/AppRoot.kt:93–108; APP/ui/chat/ChatScreen.kt:276 | Только bar/rail и 320 dp bubble → контентные expanded-layouts | P2 | L | medium |

## 3. Детальный разбор

Диффы ниже — предложения для будущей реализации, **не применённый и не скомпилированный патч**. Новые строки/параметры требуется добавить в RU/EN и протянуть через call sites. Если фрагмент обозначен как паттерн, он не обещает готовую сигнатуру API.

### 1. Инфраструктура темы — соответствует

`DS/Theme.kt:34–39` уже задаёт все четыре основы. Не нужно повторно «переходить на MaterialExpressiveTheme». Expressive-тема и `MotionScheme.expressive()` в текущем коде требуют `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` (`:19`). Минимальную историческую alpha для этого набора здесь не утверждаю; рабочая целевая конфигурация аудита — 1.5.0-rc01.

`values/themes.xml` использует platform `Theme.Material.NoActionBar` только для окна. Есть `values/colors.xml` и `values-night/colors.xml`; нет основания объявлять это смешением M2 и M3. `MainActivity` включает edge-to-edge. Контраст/system-bar icon appearance при старте нужно проверить на light/dark device; доказанного рассинхрона XML с Compose нет.

#### E-015 — ADD: визуальная и семантическая регрессия

**Evidence:** CI выполняет Gradle test и assembleDebug; app test дополнительно зависит от lintDebug, nativeRegressions и assembleRelease. Это полезные реальные гейты, но не screenshot/Compose accessibility tests. В просмотренных UI/DS исходниках нет `@Preview` gallery и screenshot-тестов.

**Польза:** ошибки узкого status chip, больших шрифтов и ошибочных ролей не ловятся JVM unit tests. Не заменять существующий CI, а дополнить.

```diff
+ @Preview(name = "Status / light", showBackground = true)
+ @Composable
+ private fun StatusPreview() = OpenCodePhoneTheme(darkTheme = false) {
+     OcStatusChip("OpenCode подключён", tone = OcChipTone.PRIMARY)
+ }
```

Это базовый пример, не полная матрица. Добавить dark, fontScale 2, narrow и длинные RU строки; зафиксировать версии renderer/SDK/font. `@Preview` — tooling, не M3 expressive API; доступен в подключённом tooling-preview. Paparazzi/Roborazzi требуют отдельного выбора версии под AGP 9.1/Kotlin 2.4/Compose BOM; совместимость не проверена. CI-gate задавать только после воспроизводимых baseline, не включать непроверенный плагин наугад.

### 2. Цвет — соответствует с оговорками проверки

`DS/Color.kt` централизует hex; это правильное место для брендовых токенов, не анти-M3 хардкод. В просмотренных экранных composables цвета идут через colorScheme. Status chip использует парные container/onContainer роли; permission-panel — tertiaryContainer, ошибки — errorContainer. В терминале применён surfaceContainerLowest, tool card — surfaceContainer, сообщения — primaryContainer/surfaceContainerHigh. Тональные поверхности используются вместо избыточных теней.

Не нужно применять все роли одновременно, чтобы «набрать M3E». `surfaceDim/Bright` и inverse-роли не заданы вручную, значит используются библиотечные defaults; это не доказанный баг, но при расширении палитры их нужно включить в color-gallery. Формальная WCAG-проверка пар и disabled/pressed состояний не выполнялась. Затемнение под открытым диалогом/sheet на JPG — scrim, **не доказательство плохого контраста основной темы**. Dynamic palette также не измерялась.

### 3. Типографика

`DS/Type.kt` — сильная сторона: body/title/headline/display и веса централизованы. Размеры sp внутри файла темы допустимы. В Settings спокойный масштаб уместнее огромных display-заголовков. Нет необходимости навязывать сетевой шрифт или Google Sans Flex ради соответствия стилю.

#### E-007 — FIX: code typography вне шкалы

**Evidence:** Terminal output задаёт самостоятельный `TextStyle(11.sp, 15.sp)`, input — 14.sp; EditorDialog — 12/17 sp. Моноширинность обоснована, обход согласованной шкалы и очень мелкий output — нет. На снимке виден только banner, поэтому читабельность длинного вывода не подтверждена.

```diff
- style = TextStyle(fontFamily = FontFamily.Monospace,
-     fontSize = 11.sp, lineHeight = 15.sp)
+ style = MaterialTheme.typography.bodySmall.copy(
+     fontFamily = FontFamily.Monospace)
```

Аналогично input/editor наследовать от bodyMedium с monospace; если плотность требует отдельной роли, централизовать `CodeTypography`, а не менять основной body во всём приложении. Минимальное соответствие: M3 Text/typography stable с 1.0; `TextStyle.copy` — stable Compose UI. Без expressive opt-in. Критерий: 1×/1.3×/2× fontScale, длинные команды, selection и IME без clipping.

### 4. Формы

#### E-013 — ENH: токенизировать корректный брендовый кастом

**Evidence:** `AppShapes` уже задаёт семейство. Bubble использует 20/4 dp асимметрию по роли — полезное различие автора, не дефект. Composer отдельно задаёт 24 dp. Именно эти повторяемые смысловые роли лучше вынести, а не заменять все bubbles на одинаковый theme.medium.

```diff
- shape = RoundedCornerShape(24.dp)
+ shape = AppMessageShapes.composer
```

`AppMessageShapes` — **новый проектный объект**, не библиотечный API: перенести туда текущие формы `composer`, `incoming`, `outgoing` без визуального изменения. RoundedCornerShape — stable Foundation, не expressive API, исторический минимум M3 не относится к нему.

`MaterialShapes`, фигурные контейнеры и polygon morph в проекте не обнаружены. Их отсутствие не ошибка для IDE-клиента. Опциональный следующий шаг: один выразительный icon-container в `OcEmptyState`, статичный на reduced motion. Паттерн shape morph/конвертации polygon **проверить в официальных docs для 1.5.0-rc01 и выбранной androidx.graphics.shapes**; `toShape()` и конкретные фигуры не объявляю доступными без проверки артефакта. Не добавлять clover в каждую карточку настроек.

### 5. Motion

Есть expressive scheme и wavy progress; **анимации не отсутствуют**. `AnimatedVisibility` используется для permission-panel, списки вызывают animateScrollToItem, стандартные navigation/buttons/sheets имеют собственные библиотечные переходы. Нельзя по отсутствию локального `spring()` утверждать, что навигационный индикатор не анимируется. Ручных tween/easing в просмотренных экранах не обнаружено.

#### E-009 — FIX: раскрытие ToolCard и состояние expanded

**Evidence:** `ToolCard` меняет `expanded` через clickable, а output рендерится `if` (`:342`). Нет stateDescription/отдельного индикатора раскрытия. Подключённая тема не делает этот if пространственной анимацией автоматически.

```diff
- if (expanded.value && !output.isNullOrBlank()) {
+ AnimatedVisibility(visible = expanded.value && !output.isNullOrBlank()) {
      Text(text = output.orEmpty().take(4000), ...)
  }
```

Вместо `...` сохранить имеющиеся аргументы Text; добавить localized expanded/collapsed stateDescription и иконку chevron. Для кастомного раскрытия обеспечить минимум 48 dp, роль Button и доступное управление. `AnimatedVisibility` — stable Compose Animation (1.0+), не требует M3 expressive opt-in. Это минимальный шаг: его default spec **не считается автоматически привязанным к MotionScheme**. На следующем шаге использовать spatial/effects specs темы; точные accessor-сигнатуры проверить в API 1.5.0-rc01, не копировать непроверенный вызов. Анимировать раскрытие блока, не каждый streaming-токен. Проверить system animator scale 0 и быстрое многократное раскрытие.

#### E-014 — ENH: общий паттерн progress

**Evidence:** bootstrap/chat/files используют wavy, но update-card — обычный LinearProgressIndicator; CHECKING показывает текст и Cancel без progress. Обычный индикатор корректен, замена — консистентность, не устранение функционального бага.

```diff
- LinearProgressIndicator(progress = { state.progress / 100f })
+ LinearWavyProgressIndicator(progress = { state.progress / 100f })
```

Сигнатура с progress lambda уже используется в `BootstrapScreen.kt:130–134`; целевая версия 1.5.0-rc01, локальный `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` как в bootstrap. Для CHECKING добавить indeterminate overload, тоже уже имеющийся в проекте. Не подменять неопределённую загрузку фиктивным процентом. `LoadingIndicator` и другие новые loaders здесь не нужны ради количества; статус их API отдельно не проверялся.

### 6. Компоненты и доступность

#### E-001 — FIX: action chip не равен status chip

**Evidence:** `OcStatusChip` — non-clickable Surface с Row padding 6 dp по вертикали и labelMedium 16 sp lineHeight. В header чата ему снаружи добавлен clickable без роли. Визуальная высота ориентировочно 28 dp при 1×; Compose может расширять hit area, поэтому фактический hit rect нужно проверить, а не объявлять измеренным 28 dp. Компонент сам не гарантирует 48 dp и кнопку для accessibility.

Минимальное исправление **только интерактивного применения**, не всех пассивных статусов:

```diff
- modifier = Modifier.clickable { vm.showModels(true) }
+ modifier = Modifier.heightIn(min = 48.dp)
+     .clickable(role = Role.Button) { vm.showModels(true) }
```

Импорты layout.heightIn и semantics.Role; stable Compose Foundation/UI, не expressive opt-in. Предпочтительный итог — отдельный `AssistChip(onClick=...)` с понятным action label и minimum hit target; AssistChip stable M3 1.0+. Не превращать server-version label в фальшивую кнопку. Критерий: TalkBack сообщает действие и роль, 48×48 dp, длинный model ID не выталкивает sessions action (E-003).

#### E-002 — FIX: FileRow

**Evidence:** высота задаётся иконкой 20 dp и padding 6+6 dp; Row с clickable не штатный ListItem. В обычной строке геометрия порядка 32–36 dp, в плотном списке это риск соседних touch targets.

```diff
  .fillMaxWidth()
+ .heightIn(min = 48.dp)
- .clickable(onClick = onClick)
+ .clickable(role = Role.Button, onClick = onClick)
```

Добавить expanded/collapsed description для папки из реального состояния; сейчас `FileEntry` в этом фрагменте не предоставляет такой флаг, его потребуется протянуть. Не выставлять ложное «свёрнуто» для всех. Stable Foundation/UI, без expressive opt-in; штатный M3 ListItem — возможная, но не обязательная замена. Проверка через semantics tree и adjacent hit targets обязательна.

#### E-006 — FIX: иерархия server controls

**Evidence:** Settings содержит filled Apply и filled Test, ниже отдельные Stop и Start/Restart; длинный operational hint постоянно виден. Снимок подтверждает визуально разрозненный набор действий, но работоспособность их не опровергает.

```diff
- Button(onClick = { vm.testConnection() }, enabled = !state.serverDirty)
+ OutlinedButton(onClick = { vm.testConnection() }, enabled = !state.serverDirty)
```

Apply выделять при dirty, в clean-состоянии делать primary текущий Start/Stop, Restart оставить вторичным; состояние брать из runtime, а не менять серверную логику в дизайн-патче. Подробное пояснение вынести в secondary supporting text/раскрытие. Button/OutlinedButton stable M3 1.0+; не нужен экспериментальный ButtonGroup. Связанная группа действий — допустимый следующий паттерн, но `ButtonGroup`/split API нужно отдельно сверить для 1.5.0-rc01. Нельзя объединять stop и destructive reinstall под одним неясным primary действием.

### 7. Иконки

#### E-012 — FIX: метафоры действий, не погоня за variable font

**Evidence:** Terminal clear использует `ic_stop`, хотя onClick вызывает `vm.clear()`, а Chat sessions использует `ic_tab_files`. На снимках это читается как остановка процесса и открытие файлов.

```diff
- painterResource(R.drawable.ic_stop) // vm.clear()
+ painterResource(R.drawable.ic_clear_output)
```

`ic_clear_output` — **предлагаемый новый vector resource**, не существующий файл. Аналогично добавить `ic_sessions`; оставить корректные локализованные contentDescription. Находка исправляется новым вектором без material-icons-extended и без обновления material3. Ресурсный Icon/painterResource — stable Compose/M3 baseline 1.0.

Иконки проекта — XML vectors, не растровый legacy-набор. Чёрный fillColor исходного path сам по себе не анти-M3: Icon применяет tint. Navigation icons имеют null descriptions, потому что есть label — это правильно. Material Symbols variable и animated FILL — optional ENH после проверки способа рендера/лицензии/offline packaging; нельзя обещать анимацию осей обычному vector drawable. Отсутствие variable-иконок не ошибка.

### 8. Адаптивность

#### E-003 — FIX: header и строка проверки соединения

**Evidence:** `ScreenHeader` кладёт title и неограниченную actions Row в одну Row со SpaceBetween; у title нет weight/maxLines. Settings сжимает status chip рядом с длинной кнопкой; на снимке `opencode serve :4096` распался на узкий вертикальный столбец. Это конкретная проблема ширины, не «слишком мало Expressive».

```diff
  Text(text = title,
+     modifier = Modifier.weight(1f),
+     maxLines = 1, overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.headlineSmall)
```

Это только предохранитель: дополнительно ограничить ширину длинного action/model label и на узкой ширине вынести его второй строкой. В Settings минимально перенести status под Test:

```diff
- Row(verticalAlignment = Alignment.CenterVertically, ...) {
+ Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      /* existing Test and status */
```

layout/ellipsis stable Compose; M3 Text stable 1.0+. Библиотечный TopAppBar — альтернативный способ получить slots/scroll/insets, но не автоматически решение огромного actions-slot. Варианты large/medium и expressive overloads выбирать после проверки конкретных API 1.5.0-rc01. Критерий: 320 dp, RU, 2× fontScale, длинный model ID и sessions action доступны без горизонтального переполнения.

#### E-004 — FIX: OcInfoRow

**Evidence:** два Text внутри SpaceBetween без весов/ограничений. Path/version/repository value может забирать всю ширину; в снимке длинные пути перенесены неуправляемо.

```diff
- Text(label, ...)
+ Text(label, modifier = Modifier.weight(0.4f), ...)
- Text(value, ..., textAlign = TextAlign.End)
+ Text(value, modifier = Modifier.weight(0.6f), ..., textAlign = TextAlign.End)
```

Сохранить переносы, не прятать рабочий путь за ellipsis без возможности прочесть/скопировать. Для особенно длинных value лучше вертикальный layout, особенно при fontScale 2. Stable layout и Text, без opt-in. 40/60 — стартовая пропорция для проверки, не универсальный дизайн-токен.

#### E-010 — FIX: редактор и IME

**Evidence:** `EditorDialog` — AlertDialog с полем `.height(420.dp)`. Это фиксированная высота редактируемого контента внутри ограниченного окна; доступность нижних действий в landscape/при IME требует проверки.

Локальный предохранитель:

```diff
- modifier = Modifier.fillMaxWidth().height(420.dp)
+ modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)
```

Это не завершённое решение: верхняя граница должна учитывать доступную высоту, IME и actions, иначе heightIn тоже не гарантирует fit. Целевой паттерн: полноэкранный editor на compact-height, dialog/pane на большом окне; scroll content и действия доступны над клавиатурой. Stable BoxWithConstraints/Dialog/insets доступны независимо от expressive API; точную DialogProperties-конфигурацию проверить на текущем Compose UI. Не заменять редактор произвольным FlexibleBottomSheet: его наличие в material3 не подтверждено, это не обязательный официальный компонент.

#### E-011 — ENH: adaptive content, а не только navigation

**Evidence:** `AppRoot` уже переключает bar→rail по `maxWidth >= 600.dp`. Это корректная работающая адаптация, не нулевое покрытие. Но Chat остаётся одной полосой, bubbles ограничены 320 dp; Files editor всегда dialog. Fold posture/hinge отдельно не учитываются.

Паттерн будущего локального изменения:

```diff
- /* same single-pane chat at every width */
+ /* compact: existing chat; expanded: sessions pane + same chat body */
```

Это **псевдодифф архитектуры**, не имя библиотечного API. Использовать текущий BoxWithConstraints для первого шага; adaptive navigation/list-detail — отдельные артефакты со своими версиями, BOM material3 не доказывает их наличие. `NavigationSuiteScaffold` здесь не требуется для работоспособности и не добавляется без проверки совместимости. На tablet сохранять удобную длину строк, а не растягивать bubble на весь экран. Confidence medium: tablet/fold screenshots отсутствуют.

### 9. Состояния и ритм

#### E-005 — FIX: offline CTA отсутствует фактически

**Evidence:** ChatScreen DISCONNECTED передаёт actionText, но не onAction. `OcEmptyState` рисует кнопку только когда оба ненулевые (`Components.kt:107–109`). Это не «кнопка без эффекта», а **непоявляющаяся обещанная кнопка**.

```diff
- fun ChatScreen(graph: AppGraph) {
+ fun ChatScreen(graph: AppGraph, onOpenSettings: () -> Unit) {
...
  actionText = stringResource(R.string.chat_offline_settings),
+ onAction = onOpenSettings,
```

Протянуть callback из AppNavHost в Settings-route. Это проектное изменение, не новый M3 API; TextButton stable M3 1.0+. На ERROR добавить осмысленное retry/settings действие с настоящим callback, не пустой заглушкой. Проверить offline→settings без keyboard focus loss/stack duplication.

#### E-008 — FIX: reasoning не должен конкурировать с ответом

**Evidence:** `PartView` постоянно рисует `UiPart.Reasoning` как bodySmall перед обычным Text; на снимке длинное английское рассуждение занимает больше площади, чем итоговый русский ответ. Это не нарушение палитры и не необходимость удалять серверные данные — это проблема иерархии.

```diff
- is UiPart.Reasoning -> Text(text = part.text, ...)
+ is UiPart.Reasoning -> ReasoningDisclosure(text = part.text)
```

`ReasoningDisclosure` — **новый проектный composable**, не M3 API: локализованный TextButton «Рассуждение», rememberSaveable collapsed по умолчанию, expanded stateDescription и AnimatedVisibility с вторичным bodySmall. Не смешивать reasoning с копированием итогового ответа. Stable TextButton + Compose Animation/Runtime; expressive opt-in не нужен. Streaming reasoning не должен перезапускать раскрытие/прыгать по размеру на каждый токен. Критерий: ответ — главный элемент, reasoning доступен по запросу и TalkBack не проговаривает скрытый блок.

#### E-016 — FIX: порядок диалога, отдельный UX-дефект

**Evidence:** на JPG ответ расположен над «привет». В UI `reverseLayout=true`, `items(state.messages)` получает список без разворота; ViewModel сохраняет raw.map из API, не нормализуя время. Это не само по себе доказательство того, что API всегда отдаёт старые→новые, но объясняет риск. Также connection version badge — первый reverse item, поэтому оказывается под последним сообщением.

Условный минимальный дифф **только после подтверждения oldest→newest API-контракта**:

```diff
- items(state.messages, key = { it.id })
+ items(state.messages.asReversed(), key = { it.id })
```

Предпочтительнее нормализовать порядок один раз в слое данных по server timestamps/порядку, явно описав newest-first для reverse layout. Нельзя слепо разворачивать, если upstream уже newest-first. Переместить connection status из истории в отдельный header/status-slot; затем item 0 снова должен означать последнее сообщение, иначе animateScrollToItem(0) продолжит якориться на badge. Stable LazyColumn, M3-версия N/A. Тест: минимум две пары user/assistant, streaming, reload, reconnect и сохранение scroll position при чтении старых сообщений. Это функциональная иерархия разговора, не эффект оформления.

### 10. Бренд — соответствует

Teal primary, amber tertiary, самостоятельные светлая/тёмная палитры и системный шрифт согласованы с инструментальным продуктом. Отказ от dynamic color объяснён в Theme.kt, не требует исправления. Тёмные обычные screenshots согласуются по ролям и характеру с темой; pixel-perfect соответствие не установлено. Google Sans Flex, крупные cookie-формы и bouncing в каждой кнопке не являются условием соответствия M3E.

### Компонентный чек-лист по сценариям

| Экран/механизм | Оценка |
|---|---|
| Chat | M3 TextField/IconButton, ModalBottomSheet, wavy progress; FIX E-001/003/005/008/009/016 |
| Files | AlertDialog, Button, OutlinedTextField, wavy progress; FIX E-002/010; пустое состояние настоящее |
| Terminal | SuggestionChip и LazyRow для команд **соответствуют**; это не карусель медиа. FIX E-007/012 |
| Settings | Card, FilterChip, Checkbox, обычные M3 кнопки/диалоги; FIX E-003/004/006 |
| Bootstrap | Display/hero surface, wavy determinate/indeterminate, error+retry — соответствует; device insets/fontScale ещё проверить |
| Update | Реальные checking/downloading/ready/error и Cancel; ENH E-014 |
| Navigation | NavigationBar/Rail, selected state и восстановление destination; соответствует базовому сценарию, ENH E-011 |
| FAB / FAB menu | N/A: нет доказанной потребности в постоянном плавающем primary action; не дублировать Files Add только ради FAB |
| Split/group buttons | Опциональный паттерн для server controls; не обязательная замена простого working layout |
| SearchBar | N/A для пустого/малого списка; поиск файлов/моделей добавлять после подтверждения масштаба, не как M3E-декор |
| Carousel | N/A: нет галереи/медиа; command LazyRow корректен |
| Pull-to-refresh | Не обязательный: чат обновляется SSE, Files локальные; оценивать реальные потребности refresh отдельно |
| DatePicker/TimePicker/Slider | N/A: нет соответствующих данных/задач |
| Snackbar | Inline errors допустимы; transient success можно вынести в Snackbar, но это не обязательная находка |
| Segmented selection | Local/Remote сейчас FilterChip; это корректно. Single-choice segmented pattern может усилить взаимоисключение, но не критичен |
| M2/View duplication/ripple | M2 imports в app/core не найдены, дублирующие View-экраны не обнаружены, ручных Canvas/ripple-замен нет |
| Haptics/shared elements | Не обнаружены. Не требуются для каждой команды/сообщения; добавлять только подтверждённое действие, не streaming-токены |

## 4. План внедрения

| Этап | Зачем / IDs | Суть | Риск бездействия | Критерий готовности |
|---|---|---|---|---|
| 1. Фундамент | E-001/002/004/007/013/015 | Сохранить 1.5.0-rc01 и существующую expressive-тему; action/status роли, code typography, shape роли, preview gallery | Мелкие hit targets и локальные стили продолжают обходить тему | 48 dp interactive geometry, stable semantics, light/dark/2× previews; baseline dependency report |
| 2. Компоненты | E-003/005/006/010/012/014 | Устойчивый header/status, рабочий offline CTA, control hierarchy, adaptive editor, точные иконки, общий progress | Переносы, потеря действий при IME, пользователь не понимает Clear/Stop | RU на 320 dp, landscape+IME, TalkBack; все действия реально исполняются; проверены opt-in/signatures |
| 3. Выразительность | E-008/009/011/013 | Controlled disclosure, короткий motion, expanded sessions/content panes; optional одна hero shape | Expressive-фундамент почти не проявляется в пользовательских переходах; tablet остаётся растянутым телефоном | Rapid toggles не ломают состояние, reduced motion не теряет смысл, tablet/fold layout без hinge overlap |
| 4. Полировка | E-015/016 + предыдущие | История и autoscroll, screenshot gates, reading rhythm; haptics только для осмысленного подтверждения | Красивый motion маскирует неправильную последовательность разговора/регрессии | Две пары сообщений в верном порядке, reading-scroll не сбрасывается; visual/semantics CI зелёные |

Порядок E-016 можно поднять в этап 1 после подтверждения API-контракта: восстановление правильного разговора важнее morphing. Variable fonts/Symbols — optional после проверки Cyrillic, лицензий, размера APK и offline fallback. Не добавлять библиотечные компоненты, не относящиеся к задачам приложения.

## 5. Регресс-контроль и профилактика

### Визуальная матрица

- Light/dark, branded/dynamic на API 31+; API 26 fallback; compact 320/360 dp и expanded; landscape, split-screen/fold.
- RU/EN, fontScale 1/1.3/2; длинные model IDs, URL и workspace path. Учитывать, что по текущему коду placeholder singleLine, а снимки не гарантированно от этого commit.
- Header, info row, action/status chips, message text/reasoning/tool раскрыт/закрыт, empty/error/loading, server stopped/starting/running/failed, update checking/downloading/error/ready.
- Для snapshots фиксировать progress и animation time; не сравнивать случайный кадр wavy animation. Отдельно runtime-тесты движения с system animator scale 0/1 и быстрыми повторными нажатиями.

### CI и статические правила

1. Сохранить существующие unit/lint/native/R8/debug build гейты. В этом аудите сборки не запускались, новый код не внедрён.
2. Выбрать совместимый Roborazzi **или** Paparazzi, небольшой smoke-набор на ключевых composables и управляемое обновление baseline через review. Не включать оба стека без причины. Реальный IME/TalkBack/insets требуют device/emulator tests, snapshots их не заменяют.
3. Детект M2 import `androidx.compose.material.` отдельно от `material3`; не банить XML window theme или vector fillColor.
4. Detekt/Lint: экранные hex/ручные TextStyle(sp) вне DS, clickable без role/минимальной геометрии; whitelist code/layout/brand exceptions с объяснением. Не банить все dp — padding/icon dimensions не обязаны быть theme.shapes.
5. Compile-проверка каждой новой experimental-signature на закреплённой зависимости, opt-in локально, запрет широкого отключения lint ради API.

### Чек-лист PR design review

- Одна главная задача и понятный primary action; utility controls не конкурируют с ответом/редактором.
- Парные цветовые роли, нормальный контраст без опоры только на цвет; scrim не оценивается как цвет самой темы.
- Минимум 48×48 dp для действий, правильные Button/selected/expanded semantics; decorative icons без лишнего произношения.
- Длинные RU тексты/2× fontScale/IME не скрывают действия; status и path не сжимаются в столбик.
- Loading/error/empty имеют правдивый прогресс и работающие callbacks, а не только подпись действия.
- Motion сообщает изменение состояния, не анимирует каждый токен и не мешает чтению; reduced motion сохраняет полную функциональность.
- Порядок сообщений, autoscroll, выделение/копирование текста и focus проверены отдельно от оформления.
- У новых expressive/adaptive API указан артефакт, версия, статус и opt-in; неподтверждённые API остаются паттернами.

**Итог:** сначала сделать имеющийся expressive-слой устойчивым и доступным, затем добавлять выразительные переходы. Новый шрифт, FAB-меню и десятки фигур сейчас дадут меньше пользы, чем несколько локальных исправлений header, disclosure и touch targets.
