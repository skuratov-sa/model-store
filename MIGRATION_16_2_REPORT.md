# 16.2 — текущие права ADMIN при смене статуса товара

Этап 1, реализация и необходимые проверки: 09.11.2026.
Ветка `codex/migration-16-2-status-authorization`, managed worktree `0c59/model-store`.
Исходный и текущий HEAD: `55919e4dd564e855986813434bc71652940208c2`, актуальная локальная master
на момент начала. Ветка создана непосредственно от этого HEAD. Кодовая база включает
`d8e2982e174796ad0a7fbfae762e85d3f88dcbc3`; origin/master не использовалась.
Проверены принятые зависимости через `git merge-base --is-ancestor`: 16 `14f0744`,
04.5 `f788303`, 12.3 `44e0c7e` — все три завершились с кодом 0.

Коммитов, push, приёмки в master и правок MIGRATION_PLAN.md не было.
Отдельный последующий аудит по поручению координатора выполнен09.11.2026; результаты ниже.
Субагенты не запускались. Команда настоящего сжатия контекста в доступных инструментах
не обнаружена; сводкой сжатие не имитировалось.

## Самостоятельное воспроизведение на общей базе

До правки production-кода создан новый `ProductStatusAuthorizationTest`: настоящий сервер
Spring Boot на случайном локальном порту, настоящий HTTP PUT, ACCESS JWT с подписью
тестового RSA-ключа, собственный embedded PostgreSQL и Flyway общей базы.
Caller был ACTIVE nonagent ADMIN, owner — отдельный агент. JWT выпущен до SQL-отзыва
роли до USER. `PUT /admin/actions/product/{id}?productStatus=BLOCKED` вернул 200,
товар изменился с ACTIVE на BLOCKED. Исходная проверка успешно подтвердила именно дефект.
XML сохранён локально в `build/status162-base-repro.xml` (1 тест, 0 ошибок/пропусков).
После исправления тот же сценарий ожидает 403/ACCESS_DENIED и неизменный ACTIVE.

Отчёт 29.1 прочитан только как внешний контекст. Незавершённые файлы 29.1 не копировались
и не используются: ни ActiveAgentLock, ни AgentDirectory, ни catalog/product/agent в базе не нужны.

## Изменённые файлы

| Файл | Изменение |
| --- | --- |
| `src/main/kotlin/com/model_store/modern/catalog/product/command/api/ProductCommandController.kt` | Только AdminProductStatusController: передать caller ID из проверенного Actor в команду |
| `src/main/kotlin/com/model_store/modern/catalog/product/command/application/ProductCommands.kt` | Только внедрение owner-порта и changeStatus: writable JPA transaction, текущая блокирующая авторизация, перевод отказа в прежний ProductCommandFailure |
| `src/main/kotlin/com/model_store/modern/identity/participant/application/CurrentAdminLock.kt` | Новый ID-only owner-порт и typed ошибки forbidden/transaction-required |
| `src/main/kotlin/com/model_store/modern/identity/participant/infrastructure/JdbcCurrentAdminLock.kt` | Новая реализация проверки ACTIVE/nonagent/ADMIN с FOR UPDATE в транзакции вызывающего сценария |
| `src/test/kotlin/com/model_store/modern/catalog/product/command/ProductStatusAuthorizationTest.kt` | 10 новых интеграционных проверок после отдельного аудита, включая настоящий HTTP и конкурентный SQL-отзыв |
| `src/test/kotlin/com/model_store/modern/catalog/product/command/ProductStatusBootJarTest.kt` | Новый реальный dual-mode JAR-тест операции статуса и отзыва трёх прав |
| `src/test/kotlin/com/model_store/modern/catalog/product/command/ProductCommandHttpTest.kt` | Два существующих прямых вызова changeStatus получили ID администратора |
| `src/test/kotlin/com/model_store/modern/catalog/giveaway/admin/AdminGiveawayHttpTest.kt` | Один существующий прямой вызов changeStatus получил ID администратора |
| `MIGRATION_16_2_REPORT.md` | Этот отчёт |

Необходимость трёх изменений вызовов в принятых тестах сообщена до их правки:
прежняя двухаргументная сигнатура удалена, поэтому прежние вызовы перестали бы компилироваться.
Другие сценарии и ожидания этих тестов сохранены. Существующие identity/agent, shared,
media, Java/reactive, Flyway, конфигурация и файлы ordering не менялись.

## Авторизация и порядок блокировок

Контроллер по-прежнему отвергает JWT без ADMIN claims, agent_access и отсутствующий ID.
Caller ID приходит только из `@AuthenticationPrincipal Actor`, не из body/параметра запроса.
Refresh JWT отвергает общий настоящий decoder. Application не получает Actor, AuthAccounts,
participant SQL, identity domain/JPA-сущности или секреты.

`ProductCommands.changeStatus(id, status, callerId)` открывает/присоединяет
`transactionManager` JPA. Небезопасного overload без caller ID больше нет, что также проверено
рефлексией. Новый identity owner-порт использует MANDATORY, без собственного commit или
REQUIRES_NEW. Проверяет actual transaction, writable mode, joined EntityManager ожидаемой
EMF и привязанный ожидаемый DataSource. JDBC читает role/status/is_agent из заблокированной
tuple, поэтому старый managed ParticipantEntity не подменяет актуальную проверку после отзыва.
Нет транзакции, JDBC-only/чужой manager, read-only и чужие EMF/DataSource отвергаются.

Строка caller блокируется до проверки и удерживается до commit/rollback общей операции.
Только после неё берётся product FOR UPDATE и вызывается прежняя запись ProductCommandStore.
Отказ owner-порта превращается в 403/ACCESS_DENIED; нормальные ошибки товара сохраняются.

**Target owner для этой операции не участвует в авторизации.** Legacy и принятая команда16
позволяют администратору менять статус товара любого владельца, обычного или агента,
независимо от состояния владельца. Смена статуса не записывает participant_id, категории или
image и не вызывает owner/media-порт. Поэтому participant lock set содержит только caller:
порядок `participant IDs ASC (один ID) → product`, без движения назад по lock frontier.
Не нужны discovery owner, новый scalar lookup, повторная проверка owner под product lock
или AgentAccessLock04.5. Нельзя выдавать обычного владельца за агента — здесь этого нет.
Смена владельца не меняет административного разрешения и не заставляет следовать новому ID.

Это также проверено настоящим HTTP: ordinary/agent owners с ID меньше и больше caller,
в том числе когда отдельное соединение держит FOR UPDATE на owner — status завершается,
не ждёт owner и не образует обратного порядка participant locks. Проверен и caller=owner.

## Проверки

Среда: JDK `C:/Users/skura/.jdks/temurin-21.0.11`, Gradle cache `C:/Users/skura/.gradle`,
все запуски `--offline`. Только embedded PostgreSQL, тестовые RSA-ключи и mock S3/mail
в Spring-тестах. В JAR-тестах — отдельный embedded PostgreSQL и локальные S3/SMTP fixtures
принятого BootJarSmokeServices, реальные дочерние JVM. Production keys, Docker и общая БД
не использовались. JAVA_TOOL_OPTIONS JAR-запуска содержал только два пути тестовых ключей;
дочерние JAR-процессы очищают наследуемое окружение, fixture keys передаются явно.

На этапе1 новый `ProductStatusAuthorizationTest` содержал9 тестов:

- Реальный stale ACCESS ADMIN после ADMIN→USER: 403/ACCESS_DENIED, товар ACTIVE.
- Отзыв ACTIVE→BLOCKED/DELETED/WAITING_VERIFY, is_agent=true, отсутствующий caller:
  403 без записи. agent_access и USER claims — 403; refresh — 401.
- Разрешённый normal ACCESS ADMIN, ordinary/agent owners в обоих ID-порядках,
  DELETED owners и caller=owner; owner ID товара сохранён.
- Удерживаемый сторонний owner lock не нужен команде и не блокирует её при обоих ID-порядках.
- No-tx, JDBC-only, read-only, чужая EMF и чужой DataSource, положительная caller-JPA transaction.
  Единственная changeStatus-сигнатура требует три аргумента.
- Ранее загруженный managed ADMIN не подменяет свежую tuple после SQL-отзыва.
  Внешняя ошибка после разрешённого changeStatus откатывает статус; следующий вызов работает.
- Revoke-first для role/status/is_agent: fixture держит participant lock, HTTP ждёт именно
  эту сессию (подтверждено pg_blocking_pids), после commit отзыва получает 403 без записи.
- Status-first для role/status/is_agent: fixture держит product lock, HTTP уже держит caller;
  отзыв ждёт именно HTTP-транзакцию (pg_blocking_pids). После release товара HTTP получает200,
  затем завершается отзыв, следующий HTTP получает403. Итоговый статус остаётся BLOCKED.
- Expiry сохраняется при блокировке, продлевается при активации просроченного товара,
  будущий срок не меняется; image/category не меняются; AWAITING_GIVEAWAY —400/INVALID_REQUEST,
  missing/deleted product —404/PRODUCT_NOT_FOUND; giveaway отключается без потери настроек.

Связанная регрессия и bootJar: **78 тестов в9 классах, 0 failures/errors/skips**, BUILD SUCCESSFUL,
3m32s. XML сохранены в `build/status162-regression/`:

| Класс | Тестов |
| --- | ---: |
| ProductStatusAuthorizationTest | 9 |
| ProductCommandHttpTest | 7 |
| ProductCommandLegacyHttpTest | 1 |
| AdminGiveawayHttpTest | 13 |
| ProductReservationTest | 10 |
| AgentAccessLockIntegrationTest | 11 |
| MediaLockOrderIntegrationTest | 15 |
| ProductImageAttachmentTest | 4 |
| ModernMvcSecurityTest | 8 |

Команда: `./gradlew.bat --offline test --tests '*ProductStatusAuthorizationTest'
--tests '*ProductCommandHttpTest' --tests '*ProductCommandLegacyHttpTest'
--tests '*AdminGiveawayHttpTest' --tests '*MediaLockOrderIntegrationTest'
--tests '*ProductImageAttachmentTest' --tests '*ProductReservationTest'
--tests '*ModernMvcSecurityTest' --tests '*AgentAccessLockIntegrationTest' bootJar --console=plain`.

После bootJar отдельно выполнено с `RUN_BOOT_JAR_SMOKE=true`:
`./gradlew.bat --offline test --tests '*BootJarDualModeSmokeTest'
--tests '*ProductStatusBootJarTest' --console=plain`.
**2 теста в2 классах, 0 failures/errors/skips**, BUILD SUCCESSFUL, 1m03s.
Итог проверок этапа1: **80 тестов в11 классах**, без ошибок и пропусков.

Оба JAR-теста используют замороженную копию одного собранного артефакта SHA-256
`efd2fd9ebfc9cedca13fd1e7e306ebb9f81e06b02a08df7175ac214dea66b580`.
Один тест проверяет общий startup/API/security/S3/SMTP в legacy и modern;
новый тест отдельно запускает тот же JAR в обоих режимах и проверяет настоящий PUT:

| Условие | Legacy HTTP / товар | Modern HTTP / товар |
| --- | --- | --- |
| Текущий ACTIVE nonagent ADMIN | 200 / BLOCKED | 200 / BLOCKED |
| Старый ADMIN ACCESS, текущая role USER | 200 / BLOCKED | 403 ACCESS_DENIED / исходный ACTIVE |
| Старый ADMIN ACCESS, текущий status BLOCKED | 200 / BLOCKED | 403 ACCESS_DENIED / исходный ACTIVE |
| Старый ADMIN ACCESS, текущий is_agent true | 403 / исходный ACTIVE | 403 ACCESS_DENIED / исходный ACTIVE |

Логи: `build/status162-bootjar-legacy.log`, `build/status162-bootjar-modern.log`,
общий smoke — `build/migration-20-bootjar-{legacy,modern}.log`.

Промежуточные неуспешные запуски не скрыты: первый compile нового теста выявил nullable Int
в helper pg_blocking_pids, исправлен requireNotNull. Первый новый JAR-тест ошибочно ожидал
legacy200 при is_agent=true; фактический JAR дал403, ожидание исправлено по реальному контракту.
В том запуске существующий BootJarDualModeSmokeTest прошёл. Первый JAR XML сохранён в
`build/status162-first-jar/`. Исправление production-кода из-за этой ошибки ожидания не менялось.

## Отличие для32 и пределы этапа

**Намеренно строже legacy:** modern singular status PUT больше не доверяет устаревшей ADMIN
роли и ACTIVE-состоянию в момент выпуска токена. После их отзыва возвращает403/ACCESS_DENIED
и не меняет товар, хотя фактический legacy JAR ещё возвращает200. is_agent проверяется заново
в той же удерживаемой авторизации; legacy уже отказывает нормальному токену агента раньше.
Нормальные status/expiry/giveawayEnabled, пустой успешный ответ и ошибки товара сохранены.

Это узкое исправление status16. Остальные команды16 и claims-based проверки иных принятых
сценариев не переписывались; незавершённая29.1 не объявлена проверенной интеграцией.
Scope25 ordering/lifecycle не менялся. Проверки использовали PostgreSQL fixtures, не production.
Недоступность настоящего сжатия подтверждена отдельной проверкой доступных инструментов;
координатор принял подтверждение и отдельно поручил аудит. Разрешение commit/push остаётся
следующим этапом координатора. Изменения оставлены незакоммиченными.

## Отдельный самостоятельный аудит09.11.2026

Проверен полный diff относительно `55919e4dd564e855986813434bc71652940208c2`, включая
все четыре новых source/test-файла, отчёт и четыре изменённых принятых файла.
База и ветка не изменились: HEAD всё ещё `55919e4dd564e855986813434bc71652940208c2`,
`codex/migration-16-2-status-authorization`. Полный окончательный список девяти файлов
приведён в таблице «Изменённые файлы» выше; новых файлов на этапе аудита не добавлено.

**Production-дефектов и отклонений в diff не выявлено; production-код при аудите не менялся.**
Проверено по всем call sites: единственный production-вызов changeStatus находится в
AdminProductStatusController и передаёт verified Actor ID. Остальные вызовы — тестовые
fixtures. Нет overload без caller, роли из request или application-параметра роли;
public CurrentAdminLock принимает только caller ID и всегда читает текущие права в БД.

Проверены selected transaction source, явный JPA qualifier команды, MANDATORY порта,
ресурсы EMF/DataSource, READ_ONLY guard и JDBC tuple read. Между проверкой прав и записью
товара нет завершения транзакции или повторного захвата participant. Self-invocation нет:
контроллер вызывает managed ProductCommands, который вызывает отдельный managed owner-порт.
Даже прямое создание реализации порта не обходит её собственный transaction-resource guard.
В тесте дополнительно подтверждено, что команды и порт действительно являются AOP proxy.

Порядок блокировок остаётся caller → product, с одним participant ID; owner и image не
добавляются после product frontier. Обычный owner не передаётся в AgentAccessLock. Код
patch/create/delete/extend16, ProductCommandStore, image/giveaway owner-порты и Java/reactive
не изменены. Поля expiry/giveawayEnabled и ветви нормальных ошибок/успешного ответа после
новой авторизации побайтно соответствуют исходной реализации; для stale ADMIN намеренно
меняется отказ, включая проверку прав перед domain-validation.

**Найдены и устранены пробелы в доказательности собственных тестов:**

- Добавлен десятый реальный HTTP-тест именно status-маршрута: отсутствующий токен,
  malformed JWT, повреждённая RSA-подпись, истёкший JWT с учётом clock skew, отсутствие id,
  отсутствие role и agent_access без issuedBy. Все дают401; полная строка product до/после
  совпадает. Существующий тест сохраняет agent_access403, refresh401 и USER403.
- Stale managed ParticipantEntity теперь проверяется вызовом настоящего
  `commands.changeStatus` через оба production-прокси. Наружу выходит именно
  ProductCommandFailure/FORBIDDEN/ACCESS_DENIED, транзакция откатывается, товар не меняется.
  Прежний прямой вызов вручную созданного порта в этом сценарии заменён.
- Добавлены вызовы самой команды внутри read-only JPA и JDBC-only транзакции: отказ до
  записи, товар остаётся ACTIVE. Отдельно проверен guard вручную созданного порта без tx.
  Foreign EMF/DataSource проверяются как несовпадающие ключи ресурсов при реальной JPA tx;
  foreign EMF здесь Mockito-объект, foreign DataSource — отдельный объект без соединения.
  Настоящая JDBC-only транзакция проверяется через DataSourceTransactionManager общей fixture.
- В revoke-first ожидание теперь фильтрует не только точный PID fixture-blocker, но и SQL
  `FROM participant`. В status-first первый blocker — точный PID fixture с product lock,
  ожидающий запрос — `FROM product`; дополнительно проверяется незавершённость HTTP до release.
  Отзывающая сессия теперь открывается явно, сообщает собственный pg_backend_pid через очередь,
  и тест сравнивает этот PID с сессией, заблокированной именно HTTP status-транзакцией на
  `UPDATE participant`. После release проверяются HTTP-результат, изменённое поле caller,
  конечный product status и повторный запрещённый HTTP. Все три отзыва role/status/is_agent
  проверены в обоих порядках.

В конкурентных тестах нет catch-all, retries, условных skip, поглощения исключений futures
или обработки40P01. Future failures доходят до теста; неожиданные HTTP500/409 не подходят
ни под одно ожидаемое200/403. `runCatching` в JAR helper относится только к startup readiness
GET до запуска сценария; сам status PUT и все SQL/assertions не перехватываются.
JAR-тесты сохраняют явный opt-in RUN_BOOT_JAR_SMOKE; на этапе1 оба реально выполнены с true,
XML подтверждает0 skipped. На этом аудите JAR и прежняя регрессия не повторялись.

После этих изменений выполнен только затронутый класс:
`./gradlew.bat --offline test --tests '*ProductStatusAuthorizationTest' --console=plain`.
Те же JDK/cache/fixture keys; **10 тестов, 0 failures/errors/skips**, BUILD SUCCESSFUL,30s.
XML сохранён в `build/status162-audit/`. `git diff --check` чистый.

С учётом неизменных69 тестов регрессии и2 actual-JAR тестов этапа1, актуальное совокупное
покрытие — **81 тест в11 классах**. Это совокупность прогонов: после аудита повторялся
только изменённый класс, единого нового прогона81 теста не заявляется. BootJar production-код
не менялся, SHA-256 проверен повторно и совпадает:
`efd2fd9ebfc9cedca13fd1e7e306ebb9f81e06b02a08df7175ac214dea66b580`.

Пределы: самопроверка не заменяет приёмку координатора; чужая незавершённая29.1 не
интегрировалась, другие claims-based сценарии и ordering25 не расширялись. Изоляция fixtures
сохранена; общая БД, production keys и Docker не использованы. На завершении отдельного
аудита commit/push не выполнялись, изменения оставлены для проверки координатором.
