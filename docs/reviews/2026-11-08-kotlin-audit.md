# Аудит OpenCodePhone — 2026-11-08

Объект: commit `ad28432f61b6962ddbc314a748c79f577093d8cc`, ветка `arena/f22ec720-opencodephone`.

Аудит исходного кода, а не внесение исправлений. Строки относятся к этому commit. В примерах «после» показаны предлагаемые изменения, а не уже существующие API проекта. Если предлагается новый метод/компонент, это отмечено явно. Оценки effort относятся к исправлению с адресными тестами, без полноценного security/device QA.

## 1. Executive Summary

1. Это Android/Compose-приложение с шестью модулями: app, model, opencode, linuxenv, settings, designsystem. По сборке: Kotlin 2.4.10, Gradle 9.9.0, AGP 9.1.0, CI JDK 21, JVM target 17, minSdk 26 / targetSdk 28 / compileSdk 37; coroutines 1.10.2, serialization 1.12.0, OkHttp 4.12.0, Commons Compress 1.27.1. Профиль нагрузки предполагается интерактивным Android; SLA, допустимость изменений API и запрещённые области пользователь не задал.
2. Главный риск конфиденциальности: удалённый API всегда HTTP, включая Basic Auth и передачу provider API keys (F-001). Второй — PAT сохраняется Git в `.git/config` при клонировании с авторизованным URL (F-002). Третий — локальный API по умолчанию не требует пароля; loopback не является границей между Android-приложениями (F-003).
3. Риски корректности жизненного цикла: reset/retry не дожидаются отменённой установки, блокирующие процессы/HTTP не связаны с cancellation, stop может проиграть незавершённому launch (F-006–F-008, F-011).
4. Доставка обновлений чата ненадёжна: SSE теряет события/не переподключается, а debounce способен откладывать отрисовку на всё время непрерывной генерации. Ответы предыдущей сессии могут попасть в текущую (F-009, F-010, F-012).
5. Три наиболее выгодных направления: безопасные credentials/transport; единый владелец процессов с отменой и сериализацией; регрессионные тесты поведения ViewModel и Android, а не только парсинга/сборки. Переписывать проект на DI-фреймворк, KMP или другой HTTP-стек для этого не требуется.
6. Положительные стороны: модульность, manual DI без Activity-context в singleton, StateFlow + `collectAsStateWithLifecycle`, явные sealed-состояния, сериализуемые DTO, `use` для HTTP/архивов, проверки CLI SRI и layer SHA-256, тестируемые ManagedServer/ServerAutostart, тесты seccomp и разделение V1/V2 XDG. Старые исправленные ошибки автозапуска/HTML-парсинга не записаны как новые находки.
7. Границы проверки: изучены ключевые production Kotlin-пути, сборки, manifest, CI и существующие тесты; 7 нативных тестов повторно прошли на Linux x86_64. Локальный `./gradlew test --stacktrace` не запустился из-за отсутствия Java/JAVA_HOME — это ограничение среды, не дефект проекта. Android/ARM, UI, release-R8 и реальные провайдеры здесь не проверены. Нельзя считать их подтверждёнными прежним зелёным debug CI.

### Карта охвата

| Раздел | Результат |
|---|---|
| 1. Корректность | F-005, F-006, F-010, F-012–F-014, F-016–F-019, F-022 |
| 2. Корутины/потоки | F-006–F-012, F-015, F-018. `GlobalScope` и production `runBlocking` не обнаружены; application scope сам по себе уместен. |
| 3. Идиоматичность | Самостоятельных значимых стилевых проблем не обнаружено. Пустые catch/runCatching рассмотрены как реальные дефекты, не как стиль (F-009/F-011/F-013). Java-facing API-аннотации — N/A для текущего приложения. |
| 4. Типы/модели | Endpoint без схемы связан с F-001; draft формы смешан с применённым ServerConfig (F-018). Serializable DTO и sealed UI parts уместны; массовая замена всех String на value class не обоснована. |
| 5. Архитектура | Владение процессами и конфигурацией — F-006/F-008/F-012/F-018; testability — F-020. Manual DI не является дефектом. Spring/Ktor server layers и KMP expect/actual — N/A. |
| 6. Производительность | Неограниченный stdout F-007, файловый обход Main F-015, перезапуски на каждый символ F-018. Backend event loop/пулы БД — N/A. |
| 7. Безопасность | F-001–F-005, F-017, F-019, F-021. SQL-инъекции — N/A: SQL-слоя приложения не обнаружено. |
| 8. Ошибки/наблюдаемость | F-007, F-009, F-011, F-013, F-020. Отсутствие crash backend само по себе не записано как баг. |
| 9. Тесты | F-020 и критерии регрессии каждого дефекта. Реальные process tests не следует механически заменять MockK: нужны и fake-based unit, и integration. |
| 10. Сборка/инструменты | F-020. Version catalog уже есть, целевую API 28 нельзя бездумно повышать: причина execve/SELinux записана в app/build.gradle.kts:14–18. |

Проверка CVE ограничена: запросы GitHub Global Security Advisories API с `ecosystem=maven` и `affects=org.apache.commons:commons-compress@1.27.1` / `com.squareup.okhttp3:okhttp@4.12.0` не вернули advisory. Это не подтверждение отсутствия CVE во всём графе: транзитивные зависимости, Debian toolset, Bun/OpenCode и PRoot требуют отдельного SBOM/SCA. Номера CVE без подтверждения не приписываются.

## 2. Сводная таблица

Порядок: Critical → Major → Minor; внутри severity — effort XS → S → M.

Обозначения путей: `App/` = `app/src/main/java/io/github/oustery/opencodephone/`; `Linux/` = `core/linuxenv/src/main/java/io/github/oustery/opencodephone/core/linuxenv/`; `API/` = `core/opencode/src/main/java/io/github/oustery/opencodephone/core/opencode/`; `Settings/` = `core/settings/src/main/java/io/github/oustery/opencodephone/core/settings/`.

| ID | Sev | Category | Location | Проблема | Effort |
|---|---|---|---|---|---|
| F-001 | 🔴 Critical | security | API/OpenCodeClient.kt:44–50,82–85; App/ui/chat/ChatViewModel.kt:112–123 | Basic Auth и provider keys отправляются по HTTP на удалённый сервер | M |
| F-002 | 🔴 Critical | security | App/ui/files/FilesViewModel.kt:155–159,275–289 | PAT в clone URL сохраняется в Git config и передаётся в argv | M |
| F-011 | 🟠 Major | concurrency | App/ui/chat/ChatViewModel.kt:112–151,204–225 | CancellationException подавляется; неуспех маскируется подключением | XS |
| F-019 | 🟠 Major | security | Linux/DockerRegistry.kt:47–62 | Digest manifest запрошен, но байты ответа против него не проверены | XS |
| F-003 | 🟠 Major | security | Settings/SettingsStore.kt:11–16; Linux/EnvironmentRuntime.kt:33–39 | Локальный API без пароля доступен другим приложениям с сетью | S |
| F-004 | 🟠 Major | security | Settings/SettingsStore.kt:42–55 | Любая ошибка шифрования молча переключает секреты в plaintext | S |
| F-008 | 🟠 Major | concurrency | Linux/ManagedServer.kt:52–59,121–129 | stop во время launch не мешает публикации нового процесса | S |
| F-010 | 🟠 Major | correctness | App/ui/chat/ChatViewModel.kt:63,82–87,170 | collectLatest + delay откладывает reload при непрерывных событиях | S |
| F-012 | 🟠 Major | concurrency | App/ui/chat/ChatViewModel.kt:145–149,213–225,247–249 | Старый ответ/ID сессии применяется к новому выбору или серверу | S |
| F-013 | 🟠 Major | correctness | App/ui/settings/SettingsViewModel.kt:133–143; App/ui/chat/ChatViewModel.kt:91–123 | Изменение provider keys не синхронизируется с работающим сервером | S |
| F-014 | 🟠 Major | correctness | App/ui/files/FilesViewModel.kt:147–161,194–200,227–232,269–272 | Clone создаёт вложенный repo, Git-команды выполняются в workspace | S |
| F-015 | 🟠 Major | performance | App/ui/files/FilesViewModel.kt:51–53,70–87,91–99 | Файловое сканирование и сортировка выполняются на Main | S |
| F-017 | 🟠 Major | security | App/ui/files/FilesViewModel.kt:176–189 | Создание/редактирование файлов не ограничено workspace | S |
| F-018 | 🟠 Major | architecture | App/ui/settings/SettingsViewModel.kt:82–101; App/OpenCodePhoneApp.kt:88–90 | Каждый символ формы сохраняет конфигурацию и меняет соединение | S |
| F-020 | 🟠 Major | build | app/build.gradle.kts:82–88; .github/workflows/ci.yml:31–35 | Нет blocking lint/native gate и поведенческих Android/ViewModel тестов | S |
| F-005 | 🟠 Major | security | Linux/RootfsExtractor.kt:38–40,55–59,73–99 | Неверные границы путей и разрешение ссылок при распаковке | M |
| F-006 | 🟠 Major | concurrency | Linux/BootstrapManager.kt:57–77 | retry/reset отменяют job без join и меняют те же каталоги | M |
| F-007 | 🟠 Major | concurrency | Linux/EnvironmentRuntime.kt:100–121; API/OpenCodeClient.kt:95–104 | Блокирующий процесс/HTTP переживает отмену; stdout не ограничен при накоплении | M |
| F-009 | 🟠 Major | errors | API/OpenCodeClient.kt:163–185; App/ui/chat/ChatViewModel.kt:155–165 | SSE теряет события и не восстанавливается после обрыва | M |
| F-016 | 🟠 Major | correctness | App/ui/files/FilesViewModel.kt:102–106,123–128 | Двоичный файл открывается как текст; save неатомарен и не проверяет конфликты | M |
| F-021 | 🟡 Minor | security | App/ui/settings/SettingsScreen.kt:110–116,204–211,348–355 | Пароль, PAT и ввод API key отображаются открыто | XS |
| F-022 | 🟡 Minor | correctness | App/ui/chat/ChatScreen.kt:431 | Модели после первых 12 нельзя выбрать в UI | XS |

## 3. Детальный разбор

### 🔴 Critical

#### F-001 — удалённые секреты передаются без TLS

**Evidence:** `OpenCodeClient.kt:50`: `val baseUrl: String get() = "http://$host:$port"`; `withAuth():82–85` добавляет Basic Auth. `ChatViewModel.syncProviderKeys():112–123` отправляет API keys через тот же клиент. Manifest разрешает cleartext глобально (`app/src/main/AndroidManifest.xml:17`).

**Impact:** на REMOTE-соединении пароль и ключи, а также промпты/ответы доступны сетевому перехвату и подмене. В UI нет схемы/HTTPS: ввод `https://...` в host не делает соединение защищённым. Риск относится к удалённому режиму, не к HTTP на loopback.

**Fix, до → после:**
```kotlin
// До
val baseUrl: String get() = "http://$host:$port"

// После: новое поле схемы и HttpUrl вместо конкатенации.
// Миграция SettingsStore/UI обязательна; однострочная замена недостаточна.
val baseUrl: HttpUrl get() = HttpUrl.Builder()
    .scheme(scheme)
    .host(host)
    .port(port)
    .build()
```
Разрешать `http` по умолчанию только для loopback; для REMOTE — HTTPS с обычной проверкой сертификатов, либо явно описанный SSH/VPN tunnel к loopback. Не добавлять trust-all HostnameVerifier. Перед отправкой сохранённых ключей новому endpoint запрашивать согласие/выбор ключей: сейчас синхронизируются сразу все. HttpUrl builder одновременно устраняет ручное форматирование IPv6.

**Confidence:** high для HTTP-передачи; перехват зависит от сетевой среды. **Effort:** M. **Регрессия:** REMOTE HTTP с секретами отклоняется до запроса; HTTPS проверяет сертификат; local HTTP работает; смена endpoint не отправляет ключи без разрешения.

#### F-002 — PAT попадает в `.git/config`

**Evidence:** `FilesViewModel.kt:155–159`: `git clone ${shellEscape(authUrl)} ...`; `withGithubToken():287–289` создаёт `https://x-access-token:$token@github.com/`. `buildPushCommand():275–278` также передаёт токен в командной строке.

**Impact:** Git сохраняет исходный clone URL как `remote.origin.url`; шифрование SettingsStore уже не защищает эту копию. Конфиг доступен инструментам/агенту внутри workspace, может попасть в диагностические архивы; токен также находится в argv процесса. Shell escaping предотвращает shell-инъекцию, но не эту утечку.

**Подтверждение:** офлайн-клонирование пустого локального repo через `url.<file://...>.insteadOf` с синтетическим URL `https://x-access-token:AUDIT_FAKE@github.com/origin.git` сохранило ровно этот URL в `remote.origin.url`. Реальные credentials не использовались.

**Fix, до → после:**
```kotlin
// До
val authUrl = withGithubToken(trimmed)
"git clone ${shellEscape(authUrl)} ${shellEscape(name)}"

// После: URL без userinfo; аутентификация вынесена из URL/argv.
listOf("git", "clone", "--", trimmed, name)
```
Для credential helper/ASKPASS нужен новый управляемый bridge: секрет передавать через приватный короткоживущий IPC/канал, ограничивать выдачу `protocol=https, host=github.com`, отключать интерактивные зависания `GIT_TERMINAL_PROMPT=0`. Не переносить токен в `git -c http.extraHeader=...` argv — это лишь другая утечка. Мигрировать уже созданные `remote.origin.url`, удалив userinfo; предложить ротацию потенциально раскрытого PAT.

**Confidence:** high. **Effort:** M. **Регрессия:** тест clone/push с фальшивым токеном: его нет в argv, `.git/config`, persist/log/output; другой host не получает credential.

### 🟠 Major

#### F-011 — отмена и реальные ошибки поглощаются в ChatViewModel

**Evidence:** `syncProviderKeys():114–122` использует `runCatching` без обработки результата; `loadProviders():137–138` — пустой catch; `loadSessions():150–151`, `loadMessages():224–225` ловят `Exception`. Все вызывают suspend-сеть. `connect():99–100` затем выставляет CONNECTED.

**Impact:** CancellationException превращается в ошибку UI/игнорируется. Ошибка загрузки ключей/провайдеров может оставить зелёное подключение с неполными данными. Не всякий `runCatching` плох: синхронный tolerant JSON parser в Parts не нужно механически менять на coroutine-wrapper.

**Fix:**
```kotlin
// До
catch (_: Exception) { }
// После — для suspend-пути
catch (e: CancellationException) { throw e }
catch (e: Exception) {
    _state.update { it.copy(error = e.message) }
    throw e // если это обязательный шаг connect, а не optional capability
}
```
Для опционального шага явно вернуть результат/ошибку и показать degraded state; обязательный шаг не должен завершаться CONNECTED. Сохранить cause в diagnostic error, но не логировать credentials/body целиком.

**Confidence:** high. **Effort:** XS. **Регрессия:** отмена на каждом сетевом шаге не публикует CONNECTED и не отображает cancellation как пользовательский сбой; auth failure виден.

#### F-019 — цепочка проверки rootfs не привязана к pinned manifest

**Evidence:** `DockerRegistry.fetchManifest():47–62` использует digest только в URL, затем сразу парсит тело; `downloadLayer():108–111` сравнивает SHA-256 слоя с digest, полученным из этого непроверенного тела.

**Impact:** приложение доверяет корректности registry/TLS, а не полностью проверяет обещанный content-addressed pin. Если registry вернёт другое содержимое manifest по этому URL, произвольный новый layer со своим matching checksum будет принят. Это не утверждение о текущей компрометации Debian/registry.

**Fix:**
```kotlin
// До
json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
// После — до JSON parsing
val bytes = checkNotNull(resp.body).bytes()
val actual = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
check(digest == "sha256:$actual") { "Manifest digest mismatch" }
json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
```
**Confidence:** high для отсутствия проверки; эксплуатация требует неправильного/подменённого ответа registry. **Effort:** XS. **Регрессия:** MockWebServer возвращает другой валидный JSON manifest с валидным checksum слоя — загрузка останавливается до layer.

#### F-003 — пароль локального API по умолчанию пуст

**Evidence:** `ServerConfig.password` в `SettingsStore.kt:16` — `""`; `EnvironmentRuntime.kt:33–39` устанавливает `OPENCODE_SERVER_PASSWORD` только для непустого пароля, затем запускает loopback listener (`:42–43`).

**Impact:** loopback ограничивает внешние интерфейсы, но не идентифицирует вызывающее Android-приложение. Другое приложение с INTERNET может обращаться к локальному API, читать доступные серверу сессии/файлы. Это не требует ROOT. Проверка доступности другим APK на конкретном устройстве ещё нужна; отсутствие секрета в текущем launch очевидно.

**Fix:**
```kotlin
// До: пустой пароль означает отсутствие auth
if (!cfg.password.isNullOrBlank()) { /* set env */ }
// После: в LOCAL-ветке требовать отдельный случайный local secret.
check(!cfg.password.isNullOrBlank()) { "Local server secret is required" }
```
До этого guard добавить генерацию/сохранение 256-битного случайного секрета при миграции LOCAL, использовать его и для probe/client, и для child env. Guard сам по себе без миграции сломает запуск. Не генерировать новый секрет на каждый recomposition/start и не применять это требование безусловно к REMOTE.

**Confidence:** high для конфигурации, medium для device exploit без испытания вторым APK. **Effort:** S. **Регрессия:** fresh install генерирует стабильный secret; неавторизованный клиент получает 401; приложение и probe работают.

#### F-004 — тихая деградация encrypted storage в plaintext

**Evidence:** `SettingsStore.kt:53–55`: `catch (_: Exception) { context.getSharedPreferences("opencodephone_fallback", MODE_PRIVATE) }`.

**Impact:** любая ошибка, включая повреждение хранилища/инвалидацию ключа, выбирает новое незашифрованное хранилище. Пользователь не узнаёт, что новые PAT/API keys пишутся открыто; при следующем успешном открытии secure prefs приложение может снова показать старые значения. MODE_PRIVATE не является общедоступным файлом, но это всё равно другой security contract и потеря согласованности.

**Fix:**
```kotlin
// До
catch (_: Exception) { context.getSharedPreferences("opencodephone_fallback", Context.MODE_PRIVATE) }
// После: явный тип ошибки, без записи секретов в новый plaintext store.
catch (e: Exception) {
    throw IllegalStateException("Secure settings unavailable", e)
}
```
Обязательно обрабатывать ошибку создания SettingsStore на уровне startup/recovery UI, иначе эта минимальная правка даст startup crash. Предлагать безопасное восстановление/повторный ввод; старый store не стирать без согласия. Если development fallback нужен, сделать его явным opt-in и запрещать в release.

**Confidence:** high. **Effort:** S. **Регрессия:** ошибка MasterKey не создаёт plaintext prefs, recovery не теряет существующие настройки.

#### F-008 — stop проигрывает незавершённому launch

**Evidence:** `ManagedServer.kt:54` вызывает `launch(options)` вне `guard`; только `:59` публикует `process`. `stop():121–129` не берёт `startLock` и может увидеть `process == null` в этом промежутке.

**Impact:** последовательность launch-enter → stop/Stopped → launch-return → process assignment → Running оставляет живой процесс после запроса остановки. В текущих tests есть отмена startup во время probe, но нет stop во время ещё не вернувшегося launch.

**Fix:** добавить новый lifecycle generation под тем же guard. До launch сохранить generation; stop увеличивает generation; после launch принимать процесс только при совпадении. Проигравший proc обязательно terminate вне guard.
```kotlin
// До
synchronized(guard) { process = proc; config = options }
// После: ticket/generation — новые поля протокола владения.
val accepted = synchronized(guard) {
    if (ticket != generation) false
    else { process = proc; config = options; true }
}
if (!accepted) { terminate(proc); return@withLock }
```
Generation надо захватывать после внутреннего stop при старте; иначе собственный stop инвалидирует ticket. Альтернатива — suspend stop через общий Mutex/actor, но не держать JVM monitor на время ProcessBuilder.start/waitFor.

**Confidence:** high. **Effort:** S. **Регрессия:** barrier в launch fake, stop до release barrier, затем ни Running, ни живого child.

#### F-010 — live-обновления откладываются до паузы в генерации

**Evidence:** `ChatViewModel.kt:84–86`: `reloadSignal.collectLatest { delay(200); ...loadMessages(...) }`; message events вызывают `tryEmit` (`:170`).

**Impact:** каждое событие раньше 200 ms отменяет прошлый delay. Во время непрерывного потока текст может не отображаться до окончания генерации. Это debounce, не ограниченная частота обновления. После delay новый event также отменяет текущий reload, а из-за F-007 блокирующая сеть может продолжать работу.

**Fix:**
```kotlin
// До
reloadSignal.collectLatest { delay(200); loadMessages(/* active id */) }
// После: новая политика conflation/throttling, не restart delay.
reloadSignal.conflate().collect {
    delay(200)
    _state.value.activeSessionId?.let { loadMessages(it) }
}
```
Это обеспечивает прогресс, не накапливая все уведомления. Окончательный session.idle должен инициировать итоговую reconciliation, даже если промежуточные invalidation coalesced. Контроль session/endpoint generation — F-012.

**Confidence:** high. **Effort:** S. **Регрессия:** virtual-time events каждые 50 ms две секунды — несколько обновлений во время потока и полный итог после idle; число запросов ограничено.

#### F-012 — ответы и активная сессия не привязаны к текущему контексту

**Evidence:** `selectSession():247–249` запускает независимую job; `loadMessages():223` безусловно пишет `messages`. `loadSessions():146` сохраняет любой старый `activeSessionId`, даже если его нет в sessions нового endpoint. В `connect()` несколько раз читается заменяемый `graph.client` между suspend-вызовами.

**Impact:** медленный ответ A может заменить сообщения уже выбранной B; после изменения сервера UI запрашивает ID старой сессии на новом сервере. Кнопка Send использует activeSessionId, поэтому отображаемые сообщения и назначение запроса могут разойтись.

**Fix:**
```kotlin
// До
_state.update { it.copy(messages = ui) }
// После: ticket — новый epoch выбора endpoint/session.
_state.update {
    if (ticket == currentEpoch && it.activeSessionId == sessionId) it.copy(messages = ui) else it
}
```
Дополнительно cancel previous messages job, capture один client на connect/operation, сбрасывать sessions/messages/permission/busy при смене endpoint. При reload списка выбирать старый ID только если `sessions.any { it.id == state.activeSessionId }`, иначе первый/null. Одной проверки ID недостаточно: одинаковые ID могут встретиться на разных endpoints.

**Confidence:** high. **Effort:** S. **Регрессия:** A/B ответы в обратном порядке; endpoint switch во время запроса; удаление активной сессии — старые данные не применяются.

#### F-013 — provider credentials имеют несогласованные локальную и серверную копии

**Evidence:** `SettingsViewModel.saveProviderKey/removeProviderKey():133–143` меняют лишь SettingsStore и reload UI. Сетевая синхронизация есть только в `ChatViewModel.connect() → syncProviderKeys()`. Она делает PUT только существующих локальных ключей и игнорирует ошибки (`:114–122`).

**Impact:** новый ключ не начинает работать в уже подключённом чате без reconnect; удаление из UI не удаляет ранее переданную копию на сервере. Это удаление server-side credentials, а не отзыв ключа у самого AI-провайдера — последний требует отдельного действия у провайдера.

**Fix, до → после:**
```kotlin
// До
settings.setProviderKey(...)
reload()
// После: новый credentials coordinator с наблюдаемым статусом.
// coordinator.save(providerId, apiKey, endpoint) -> persist + sync + status
```
Ввести поток изменений credentials и синхронизировать с конкретным endpoint без перезапуска Linux-процесса. Для удаления реализовать поддерживаемую выбранным V1 сервером очистку auth; проверить контракт сервера, а не выдумывать client API. Если сервер недоступен, хранить pending delete и показывать, что удалена пока лишь локальная копия. Ошибки PUT/clear не проглатывать.

**Confidence:** high. **Effort:** S для базовой синхронизации; надёжная offline-очередь/несколько endpoints могут увеличить до M. **Регрессия:** add/update/remove после initial connect; отказ сети при удалении; ключи другого endpoint не затрагиваются.

#### F-014 — Git-команды выполняются не в клонированном репозитории

**Evidence:** clone с именем `name` запускается из `/root/projects` (`FilesViewModel.kt:147–161`), то есть создаёт `/root/projects/name`. `refreshGit`, commit, push и remote lookup используют всё тот же `WORKSPACE_MOUNT` (`:199,232,252,272`). Навигация по дереву не меняет cwd Git.

**Impact:** обычный сценарий Clone → Commit/Push не работает: workspace сам не является репозиторием. Если пользователь вручную сделал workspace Git repo, операции пойдут в него вместо выбранного вложенного проекта; `git add -A` может захватить чужие проекты.

**Fix:**
```kotlin
// До — commit/status/push
cwd = PRootCommand.WORKSPACE_MOUNT
// После: новое состояние выбранного project root, проверенного внутри workspace.
cwd = selectedGuestProjectRoot
```
Выбирать repo после успешного clone, явно отображать текущий проект; находить root через `git -C <selected path> rev-parse --show-toplevel`, а не произвольный каталог UI. Запускать Git argv напрямую там, где shell не нужен. Проверять exit code status/remote lookup, не только текст `not a git repository`.

**Confidence:** high. **Effort:** S. **Регрессия:** два репозитория — commit/push/status касаются только выбранного; workspace без `.git` поддерживается.

#### F-015 — файловый обход на Main

**Evidence:** `FilesViewModel.init` синхронно вызывает `refresh()`, тот вызывает `scan()` с `listFiles`, `isDirectory`, сортировкой (`:51–53,70–87`). `toggleDir()` из UI также синхронно вызывает refresh (`:91–99`).

**Impact:** раскрытие большого дерева блокирует UI на metadata I/O и сортировке, особенно в node_modules/.git. Наличие IO-dispatcher у clone не исправляет вызовы refresh из constructor/click.

**Fix:**
```kotlin
// До
val entries = mutableListOf<FileEntry>()
scan(workspace, 0, expanded, entries)
// После — внутри управляемой refresh job
val entries = withContext(ioDispatcher) {
    buildList { scan(workspace, 0, expanded, this) }
}
```
Внедрить IO dispatcher, отменять старый refresh/проверять generation перед публикацией. Ограничить глубину и обработать directory symlinks: не следовать вне workspace, не повторять canonical directories. Само наличие symlink не означает бесконечный обход сейчас — рекурсия зависит от expanded, но пользователь может наращивать цепочку.

**Confidence:** high для Main I/O; величина задержки зависит от дерева. **Effort:** S. **Регрессия:** StrictMode disk checks и большое дерево; старый refresh не отменяет более новое раскрытие.

#### F-017 — пути файлов не проверяются относительно workspace

**Evidence:** `createFile():179` делает `File(workspace, name.trim())`, а затем `mkdirs/writeText` без containment check. `openFile/saveEditing` также доверяют File из дерева, включая symlink.

**Impact:** `../...` позволяет создавать файлы/каталоги вне workspace в пределах прав UID. Например, `../audit-created` создаётся в filesDir, а не в workspace. Symlink из клонированного repo позволяет вывести редактор за ожидаемую область. Это не выход из Android UID sandbox, но нарушение границы редактора.

**Fix:**
```kotlin
// До
val file = File(workspace, name.trim())
// После: guard до любых mkdir/write; повторить перед save.
val root = workspace.canonicalFile.toPath()
val file = File(workspace, name.trim()).canonicalFile
require(file.toPath().startsWith(root) && file.toPath() != root) {
    "Path must stay inside workspace"
}
```
Решить явно, разрешены ли symlinks; для жёсткой защиты от конкурентной подмены компонент пути одного canonical check недостаточно — нужны no-follow операции/контролируемый resolver. Для create использовать CREATE_NEW, чтобы между exists и write не перезаписать файл, созданный агентом.

**Confidence:** high. **Effort:** S для обычного traversal, M для race-safe filesystem boundary. **Регрессия:** `../`, пустой путь, sibling-prefix, symlink outside, конкурентное создание — без выхода/overwrite.

#### F-018 — редактируемая форма уже является применённой конфигурацией

**Evidence:** `SettingsViewModel.updateServer():85–86` сохраняет и вызывает `graph.refreshClient()`; `setHost/setUsername/setPassword` зовут его при каждом символе. `SettingsScreen.kt:84–85,94–95,111–112` привязаны непосредственно к значениям ServerConfig. Lifecycle observer перезапускает LOCAL на credential/port changes.

**Impact:** ввод пароля/порта вызывает серию смен клиента, отмен сетевых задач и стартов сервера, включая промежуточные credentials/ports. Пустое/невалидное значение port не принимается вообще (`:94–96`), поэтому поле нельзя нормально очистить для редактирования. Каждый refresh создаёт новый default OkHttpClient, усиливая churn.

**Fix:**
```kotlin
// До — onValueChange
settings.setServerConfig(updated)
graph.refreshClient()
// После: отдельный новый draft (portText хранится String).
_state.update { it.copy(/* draft-only changes */) }
// В обработчике Apply: validate draft -> persist once -> refreshClient once.
```
Это новая модель формы, не существующий параметр `copy`. Ввести Apply/Cancel и field errors; активное соединение менять атомарно после валидации. Debounce допустим для поиска, но не заменяет явное применение credentials. Переиспользовать общий transport и immutable connection snapshot.

**Confidence:** high. **Effort:** S. **Регрессия:** ввод десяти символов не трогает runtime; Apply меняет конфигурацию один раз; порт можно очистить и заново ввести.

#### F-020 — CI не проверяет несколько критических классов дефектов

**Evidence:** `app/build.gradle.kts:86–87`: `checkReleaseBuilds = false`, `abortOnError = false`; `.github/workflows/ci.yml:31–35` выполняет только test/assembleDebug. В app нет test/androidTest; native `tools/test_proot_seccomp.py` и real API smoke не вызываются workflow. Нет detekt/ktlint gate. Release workflow `:44` делает assembleRelease, но не запускает тесты и не сверяет имя v-tag с versionName.

**Impact:** ошибки Android API availability, UI-состояний, native regression и R8-release могут пройти зелёный debug CI. Особенно важно при minSdk 26 и работе с JVM API/native child processes. Это пробел проверок, а не утверждение, что текущий release заведомо сломан. Project rules/branch protection на GitHub этим аудитом не проверены.

**Fix:**
```kotlin
// До
lint { checkReleaseBuilds = false; abortOnError = false }
// После: точечное исключение осознанного targetSdk, не отключение всех ошибок.
lint {
    checkReleaseBuilds = true
    abortOnError = true
    disable += "ExpiredTargetSdkVersion"
}
```
```yaml
# Дополнение к CI, после настройки lint baseline при необходимости:
- run: ./gradlew test lintDebug assembleDebug --stacktrace
- run: python3 tools/test_proot_seccomp.py
```
Отдельно добавить release build/R8 smoke без production keystore, тесты ViewModel на F-009–F-018, API smoke с pinned CLI; секреты для этих smoke не нужны. Проверять точное соответствие release tag/versionName и монотонность versionCode до публикации. Не повышать targetSdk вслепую, ломая execve.

**Confidence:** high. **Effort:** S для gate; полноценные app/device tests — M/L суммарно, план ниже. **Регрессия:** намеренный lint/native дефект блокирует PR; release config действительно собирается; безсекретные тесты доступны PR.

#### F-005 — распаковщик неправильно проверяет пути и разрешает ссылки

**Evidence:** `RootfsExtractor.kt:40`: `target.canonicalPath.startsWith(destRoot)` — строковый prefix. `resolveWithin():93–99` не проверяет containment, требует `exists()` и разрешает относительный linkName от root, а не родителя ссылки. `createSymlink():77` выбирает результат или произвольный `Path.of(linkName)`.

**Impact:** `/.../rootfs-other/file` проходит guard `/.../rootfs`; entry `../rootfs-other/file` способен записать соседний каталог. Hardlink с `../...` на существующий файл может скопировать данные вне rootfs. Для symlink `usr/lib/link -> .` текущий resolver находит rootfs как существующий `.` и создаёт ссылку на rootfs, не на usr/lib. Порядок записей влияет на результат. Trusted digest-pinned архивы сужают угрозу, но не исправляют алгоритм; это не доказательство злонамеренного текущего rootfs.

**Fix:**
```kotlin
// До
if (!target.canonicalPath.startsWith(destRoot)) continue
// После: хотя бы корректная component-wise граница, с ошибкой, а не silent skip.
val root = destDir.canonicalFile.toPath()
check(target.canonicalFile.toPath().startsWith(root)) { "Archive path escapes rootfs" }
```
Отдельно исправить resolver: relative symlink вычислять от `target.parent`, absolute guest link — от guest root, преобразуя в безопасный rootfs-relative link; hardlink — от archive root. Не использовать exists как условие валидности forward link. Запрещать запись через ссылку наружу и повторно проверять компоненты при merge. Один prefix patch не решает symlink/hardlink и TOCTOU целиком; нужен небольшой единый resolver с тестами.

**Confidence:** high. **Effort:** M. **Регрессия:** sibling-prefix traversal, hardlink outside, dot/relative/absolute/forward symlinks в разном порядке. Существующий symlink test проверяет тип ссылки, но не её содержимое/целевой путь.

#### F-006 — reset/retry параллельны старой установке

**Evidence:** `BootstrapManager.retry():64–67` и `reset():72–77` вызывают cancel без join; reset сразу удаляет baseDir. `SettingsViewModel.reinstallEnvironment():173–174` немедленно запускает start. Download/read/extract циклы не проверяют cancellation, blocking HTTP не связан с Call.cancel.

**Impact:** старая job может продолжить запись в те же archive/rootfs, которые удаляет reset и использует новый запуск. Старые progress/FAILED/READY могут обновить новый UI; mutable opencodeVersion и SimpleDateFormat оказываются разделяемыми между реально пересекающимися запусками. Простого `@Volatile job` недостаточно.

**Fix:**
```kotlin
// До
job?.cancel(); job = null
layout.baseDir.deleteRecursively()
// После: suspend reset, вызываемый вне удаляемой job, через общий lifecycle lock.
job?.cancelAndJoin()
job = null
withContext(ioDispatcher) {
    runtime.stopServer()
    check(layout.baseDir.deleteRecursively()) { "Cannot remove environment" }
}
```
Сериализовать start/retry/reset одним coordinator/Mutex; не удерживать lock, который нужна отменяемой job для finally. Исправить cooperative cancellation во всех download/extract/process путях (F-007), иначе join просто будет ждать до сетевого таймаута. Снимать immutable version snapshot на запуск. Терминал перед удалением rootfs также остановить, не только managed server. Удаление rootfs сейчас происходит на UI-пути — перенос на IO обязателен.

**Confidence:** high. **Effort:** M. **Регрессия:** reset во время download/extract/probe; после возврата reset старый writer/progress отсутствует; workspace сохранён; новая установка получает только свою версию.

#### F-007 — blocking runner не отменяется, stdout ограничен слишком поздно

**Evidence:** `EnvironmentRuntime.exec():112` ждёт `proc.waitFor(timeoutMs, ...)` на IO, reader thread `:103–107` бесконечно добавляет в StringBuilder; `takeLast(20_000)` применяется только после завершения (`:121`). Нет finally для cleanup. `OpenCodeClient.execute():104` и download clients используют synchronous Call.execute без cancellation hook.

**Impact:** `withContext(IO)` освобождает Main, но не делает waitFor/execute автоматически отменяемыми. После ухода со screen команды/сеть продолжают работу до timeout (для Git — до десяти минут), reset сталкивается с ними. Большой stdout может вызвать OOM, даже если итоговый result короткий. Исключение wait/join может пропустить cleanup. Исключение из `onLine` на raw thread не возвращается вызывающей coroutine.

**Fix:**
```kotlin
// До
val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
// После: на IO использовать interruptible wait и cleanup в finally.
try {
    val finished = runInterruptible { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
    // обработка timeout/result
} finally {
    // terminate + bounded reap в NonCancellable/IO, закрыть streams,
    // дождаться reader; не уничтожать уже переданный другому владельцу process.
}
```
Сделать bounded ring buffer непосредственно в reader, включая лимит длины строки/чанка. Для HTTP нужен новый suspend bridge на enqueue/`suspendCancellableCoroutine`, вызывающий Call.cancel при отмене и корректно закрывающий response, если continuation уже отменена. Просто обернуть execute в ещё один withContext недостаточно. В download/extract loops проверять coroutineContext.ensureActive между чанками; у extract можно передать cancellation callback, не привязывая pure extractor к Android.

**Confidence:** high. **Effort:** M. **Регрессия:** отмена sleep/blocked HTTP; child PID и запрос заканчиваются в ограниченное время; многомегабайтный stdout/длинная строка не повышают retained buffer выше лимита.

#### F-009 — SSE не обеспечивает восстановление и не обнаруживает overflow

**Evidence:** `OpenCodeClient.kt:165` игнорирует ChannelResult от trySend; `onClosed():180–181` закрывает flow. `ChatViewModel.subscribeEvents():162–164` молча завершает job с комментарием о восстановлении через serverState. Само закрытие SSE serverState не меняет. Handler `session.*` также выполняет сетевой loadSessionsQuiet, замедляя collector.

**Impact:** обычный обрыв Wi-Fi/прокси или закрытие SSE оставляет CONNECTED при мёртвом канале. UI перестаёт получать busy/permission/completion; это может выглядеть как вечная генерация. При переполнении callbackFlow события просто пропадают. Conflate всего потока недопустим: permission/status — не взаимозаменяемые токены текста.

**Fix:**
```kotlin
// До
trySend(decoded)
// После — отдельно разобрать JSON и проверить результат отправки.
val result = trySend(decoded)
if (result.isFailure && !isClosedForSend) {
    close(IllegalStateException("SSE event buffer overflow"))
}
```
Не помещать overflow close внутрь catch, который тут же поглотит ошибку. В subscription сделать reconnect loop с capped exponential backoff/jitter; normal EOF также переводить в reconnect. CancellationException не retry. На reconnect сверять sessions/messages/status/ожидающие разрешения через поддерживаемые V1 API; только повторная подписка не вернёт уже потерянные события. 401/неверный protocol не повторять бесконечно без изменения конфигурации. Показывать RECONNECTING и метрики overflow/reconnect; не решать buffer проблемой unlimited channel.

**Confidence:** high. **Effort:** M. **Регрессия:** normal EOF, TCP reset, 401, burst выше capacity и медленный collector; после reconnect UI согласован с сервером.

#### F-016 — редактор способен повредить файл

**Evidence:** `openFile():104–106` исключает лишь directory/size и вызывает readText; его декодер не является binary detector. `saveEditing():126` делает writeText на исходный файл, без atomic replace и проверки изменения после открытия.

**Impact:** бинарный файл <2 MiB может открыться с replacement characters и после Save безвозвратно измениться. I/O failure/процесс kill после truncate оставляет неполное содержимое. Параллельная правка OpenCode/терминала затирается старой редакторской копией. Есть и UI race: сохранение A, открытие B, завершение A безусловно сбрасывает editing=null.

**Fix:**
```kotlin
// До
val content = file.readText()
editing.file.writeText(editing.content)
// После: строгая декодировка прежде, чем разрешить редактирование.
val content = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()
```
Дополнительно reject NUL/заведомо binary и дать read-only view вместо Save. В EditingFile сохранить fingerprint исходных bytes; перед записью сравнить и запросить разрешение при конфликте. Записывать в same-directory temp, flush/fsync по политике долговечности, atomic move с понятной обработкой unsupported filesystem. Перед сохранением выполнить F-017. Закрывать editor только если всё ещё открыт тот же файл/редакция. Не выдавать сравнение hash+rename за абсолютную защиту от другого concurrent writer: для гарантии нужен общий протокол владения/lock.

**Confidence:** high. **Effort:** M. **Регрессия:** PNG/NUL/invalid UTF-8 не редактируются как обычный текст; отказ записи сохраняет оригинал; внешняя правка вызывает conflict; завершение save A не закрывает B.

### 🟡 Minor

#### F-021 — чувствительные поля не маскируются

**Evidence:** SettingsScreen OutlinedTextField с `value = state.server.password` (`:110–116`), `state.git.githubToken` (`:204–211`), `apiKey` (`:348–355`) не имеют visualTransformation/secure input. Список уже сохранённых provider keys маскируется — претензия к полям ввода.

**Impact:** shoulder surfing/скриншоты показывают полный credential; клавиатура не получает password semantics. Это не замена F-001/F-002 и не remote exploit.

**Fix:**
```kotlin
OutlinedTextField(
    value = apiKey,
    onValueChange = { apiKey = it },
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    // остальные текущие параметры
)
```
Добавить осознанный show/hide toggle. Если используется secure field новой версии Compose, проверить её контракт/доступность, не менять API по памяти.

**Confidence:** high. **Effort:** XS. **Регрессия:** initial masked, explicit reveal, пароль не попадает в preview/logs.

#### F-022 — жёсткое обрезание доступных моделей

**Evidence:** `ChatScreen.ModelsSheet():431`: `provider.models.values.take(12).forEach`.

**Impact:** 13-ю и последующие модели нельзя выбрать через UI, хотя API передал их. Если новая нужная модель не попала в первые 12, пользователь видит неполный список без объяснения.

**Fix:**
```kotlin
// До
provider.models.values.take(12).forEach { ... }
// После: хотя бы убрать усечение; для большого каталога — lazy rows/search.
provider.models.values.forEach { ... }
```
Предпочтительно flatten provider headers/model rows в один LazyColumn с составными stable keys `(providerId, modelId)` и добавить поиск: просто убрать take сохраняет eager composition всех моделей внутри одного provider item. Не ограничивать backend список ради UI-производительности.

**Confidence:** high. **Effort:** XS для устранения искусственного лимита; S с поиском/lazy flatten. **Регрессия:** выбрать модель №13 и №100, включая совпадающие modelId у разных providers.

## 4. План улучшения

### Быстрые победы — сразу

Порядок доставки по риску важнее суммарного количества изменений. Critical M начать немедленно; XS ниже можно вести параллельно, не откладывая transport/credential fix.

| ID | Действие | Effort | Риск бездействия | Критерий готовности |
|---|---|---|---|---|
| F-001 | HTTPS endpoint + запрет remote cleartext secrets; согласие на передачу ключей | M | Перехват credentials/переписки | Негативные HTTP/TLS тесты; local HTTP сохранён |
| F-002 | Убрать token URL/argv, мигрировать origin URLs, предложить ротацию | M | PAT остаётся в workspace | Offline clone/push scan не находит fake token |
| F-003 | Стабильный случайный local secret + migration | S | Доступ соседнего приложения | Anonymous 401 + authenticated UI/probe; device test вторым APK |
| F-004 | Явная ошибка secure store/recovery вместо plaintext | S | Тихая потеря protection/настроек | Fault injection MasterKey без plaintext |
| F-011 | Rethrow cancellation; ошибки обязательных шагов не скрывать | XS | Ложные состояния/фоновые задачи | Таблица cancel/error тестов connect |
| F-019 | SHA-256 raw manifest до parse | XS | Pin не обеспечивает обещанный trust chain | Подменённый manifest отклонён |
| F-008 | Generation guard launch/stop | S | Сервер жив после stop | Barrier-based deterministic test |
| F-010 | Conflated throttling вместо collectLatest debounce | S | Чат не обновляется во время ответа | Live updates в virtual-time burst |
| F-017 | Containment + CREATE_NEW до filesystem mutation | S | Выход редактора за workspace/overwrite | Traversal/symlink/create-race tests |
| F-021/F-022 | Маскирование и доступ ко всем моделям | XS + XS | Утечки на экране/невозможность выбора | Compose regression на mask/13-ю модель |

### Короткий срок — 2–4 недели

| Зачем / ID | Что делать | Риск бездействия | Критерий готовности |
|---|---|---|---|
| Отмена/жизненный цикл, F-006/F-007 | Сериализовать bootstrap lifecycle; await cancellation; cancellable HTTP/process bridge; bounded output; stop terminal before reset | Записи в удаляемый rootfs, OOM, процессы после ухода | Reset/cancel на каждой фазе; нет поздних writes/живых PID; buffer cap под flood |
| Целостность загрузки, F-005 | Resolver для tar symlink/hardlink + adversarial archive tests | Повреждённый rootfs/выход за каталог | Link-order permutation и outside cases; bootstrap реального pinned rootfs на ARM/x86 |
| Чат, F-009/F-012/F-013 | Reconnect/reconciliation; endpoint/session epoch; credentials change coordinator | Зависшие busy/permissions, неверные сообщения, старые ключи | EOF/reset/burst/session-switch/add-remove tests; UI показывает degraded/reconnecting |
| Git/редактор, F-014–F-017 | Явный selected project; IO refresh с snapshot epoch; strict text/atomic save/conflict UI | Неверный repo, jank, потеря файлов | Два repo, большой tree, внешняя правка, disk failure tests |
| Конфигурация, F-018 | Draft/Apply/Cancel, shared transport, immutable endpoint identity | Перезапуски и torn operations на каждый символ | Один Apply → одна смена runtime/client; ноль при наборе |
| Инфраструктура, F-020 | Blocking lint с точечным target исключением; native gate; app test seams вместо hardcoded AppGraph в тестируемой логике; release/R8 smoke | Те же дефекты повторно проходят merge | Воспроизводимые negative checks; PR без secrets проходит все gates |

Новые seams создавать по необходимости: transport/credentials/process/files repositories, IO dispatcher и clock/backoff source. Не оборачивать каждый класс интерфейсом ради mockability. Для чистых Flow-тестов — kotlinx-coroutines-test и fakes; Turbine опционален. Для реальных native lifecycle — оставить настоящие процессы и watchdog timeout, а не моделировать PID mocks.

### Стратегически — 1–3 месяца

| Зачем / ID | Что делать | Риск бездействия | Критерий готовности |
|---|---|---|---|
| Security contract, F-001–F-005/F-017/F-019 | Документировать доверие к remote endpoint, Linux guest и workspace; секреты по endpoint; ротация/удаление; dependency SBOM/SCA для JVM + Debian/CLI/native | Ложное представление, что proot/loopback изолирует UID/secrets | Threat model reviewed; secret lifecycle/integrity tests; severity policy advisories |
| Android compatibility, F-003/F-006/F-007/F-020 | Device matrix API 26 и актуальные API; ARM64/ARM32 remote-only/x86; process recreation/background/reset/IME; 4/16 KiB page | Green JVM не ловит устройство/OS restrictions | Задокументированный smoke/release checklist с фактическими результатами; minSdk API failures блокируют выпуск |
| Производительность/наблюдаемость, F-007/F-009/F-010/F-015/F-018 | Измерить startup/reconnect, retained logs, reload frequency, frame stalls; затем точечный incremental message reducer вместо полной истории на каждый tick | Батарея/память/сетевой churn растут с диалогом | Baseline и согласованные бюджеты; отсутствие regression на representative history/tree; секреты в telemetry отсутствуют |
| Release discipline, F-020 | Version/tag assertion, R8 and signature checks, зафиксированные trusted CI actions, reproducible native provenance | Неверно маркированные/неработающие APK и трудный rollback | Release доказывает versionCode/name/ABI/signature и наличие всех gates; native asset hashes воспроизводимы |

Нет оснований обещать совместимость с Play при targetSdk 28 или рекомендовать механическое поднятие target. Это отдельное продуктовое/архитектурное ограничение, не задача lint suppression.

## 5. Профилактика

### Конкретные автоматические правила

- **detekt, `SwallowedException`**: ловит пустые/контекстно потерянные catch (F-009/F-011/F-013). **`TooGenericExceptionCaught`** с документированным allowlist на UI boundary/tolerant parser, а не глобальным ban. Для catch в suspend-пути добавить собственное правило/доступный coroutine-rule plugin: требовать rethrow CancellationException; stock detekt не считать полноценным доказательством cancellation safety.
- **`InjectDispatcher`** (версию/наличие правила сверить с выбранным detekt): начать с runtime, files, bootstrap seams (F-006/F-007/F-015). Не требовать dispatcher constructor у каждого простого Composable. **`GlobalCoroutineUsage`** и **`SleepInsteadOfDelay`** — preventative; production нарушений GlobalScope здесь не обнаружено.
- **Дополнительное собственное правило**: запрещать пустой catch и игнорируемый результат `runCatching` в suspend/network-путях без локального обоснования (F-009/F-011/F-013). Названия новых правил здесь не выдаются за встроенные. Silent tolerant parse разрешать локально с причиной и counter неизвестных protocol shapes без содержимого секретов.
- **ktlint**: ввести gate на существующую `.editorconfig`; это consistency, не исправление F-001–F-020. Не смешивать автоформатирование всего проекта с critical patches.
- **Android Lint**: blocking `NewApi`, `InlinedApi`, `UnsafeOptInUsageError` там, где применимо выбранной версии; `ExpiredTargetSdkVersion` исключить точечно с ADR. Для прочих предупреждений baseline и постепенное ужесточение вместо `abortOnError=false`. Проверять Java API availability на minSdk отдельно от JVM target 17.
- **Compiler**: использовать доступное в Kotlin 2.4.10 `compilerOptions.allWarningsAsErrors` после baseline cleanup. Проверять opt-in/coroutine warnings; не включать глобальное подавление предупреждений ради одного экспериментального Flow operator. Explicit API/Dokka для внутренних Android modules пока необязательны; включать при реальной публикации библиотеки.
- **CI**: JVM tests + lint + 7 native tests на каждый PR; real CLI smoke с integrity-pinned download и без provider keys; release/R8 gate; secret scanning и dependency review/SBOM. Coverage использовать для критических ветвей lifecycle/error, а не требовать произвольный общий процент Kover как замену сценарным тестам.

### Чек-лист review

1. Есть ли URL/argv/log/file, куда попал credential? REMOTE требует TLS? Loopback имеет auth? Новому endpoint не отправляются ключи молча?
2. У каждого child/HTTP Call есть владелец, cancel hook и bounded cleanup? Есть finally при throw/cancel? Лимит stdout применяется во время чтения?
3. stop/reset ждёт старую работу и инвалидирует поздний launch/result? start и stop имеют общий протокол сериализации?
4. Suspend catch не превращает cancellation в success? Partial failure не оставляет CONNECTED без данных?
5. Ответ помечен endpoint/session/editor epoch? Старый запрос не обновит новый screen selection?
6. Overflow/reconnect/normal EOF имеют явную политику? Ни permission, ни terminal status не выбрасываются бесконтрольно?
7. Path проверен по компонентам, с symlink policy? Нет check-then-overwrite? Save атомарен и обнаруживает внешнее изменение?
8. На Main нет directory scan/delete, crypto init в hot UI path или process wait? Изменения формы не перезапускают backend до Apply?
9. Тесты включают error/cancel/race, а не только happy path? Для race есть barriers/fakes, а не угадывание sleep duration?
10. Debug и release/R8/минимальный API действительно проверены? TargetSdk exception не отключает все lint ошибки?

### Что мониторить после исправлений

- Время local startup/restart/cancel/reset и число живых child PID после stop, отдельно по ABI/Android; коды выхода и фаза сбоя без env/secrets.
- SSE reconnect count, причины/401, overflow, время с последнего события и до успешной reconciliation; число reload requests в секунду, размер history/retained buffer.
- Main thread slow calls, frame time при раскрытии больших каталогов, память при flood stdout/длинном чате.
- Credentials sync pending/error по endpoint identity без самих URL userinfo/паролей/ключей; число plaintext fallback должно стать нулём по конструкции.
- Save conflict/write failure и факт сохранения исходного файла после отказа; Git active project path без auth URL.

## Приложение: доказательства и ограничения воспроизведения

- `python3 tools/test_proot_seccomp.py`: 7/7 PASS в текущем checkout. Это Linux x86_64 + Android-like syscall traps и ELF-проверки трёх ABI, не выполнение Android ARM binaries на телефоне.
- `./gradlew test --stacktrace`: до Gradle execution завершилось с `JAVA_HOME is not set and no 'java' command could be found`; новых локальных JVM результатов нет.
- Offline Git reproduction с fake `AUDIT_FAKE`: `.git/config remote.origin.url` содержит token URL после clone с URL rewriting на локальный bare repo. Scratch находится в игнорируемом `.work/audit`; реальные credentials и сеть не использовались.
- GHSA API: два прямых Maven artifacts не вернули advisory; полного CVE scan транзитивного/native графа не выполнено.
- Остальные race/cancellation/security выводы — статические доказательства по приведённым цепочкам. Предложенные negative/device tests — критерии будущей верификации, не якобы уже пройденные тесты.
