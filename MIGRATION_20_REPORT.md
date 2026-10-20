# 20 — общий MVC HTTP-контракт (этап 1)

## База и границы

Ветка `codex/migration-20-mvc-http-contract`, текущий `HEAD`: `11de9b3a02d29983c1c93eaf7251a74b7d613fb0`. При создании дерева был `14ff6b58afb4173a4bed5f25f2d731bab04f501a`; работа началась с фактической локальной `master` `19eea32802e7de3f81aeb8aad5bc03d7d12a92b2`, затем ветка fast-forward обновлена через `e6fe744` до `11de9b3`. Локальная `master` позднее продвинулась до `cd52fa6b9094bfefa92a7dc06a0b7c5f34204416` только новой записью плана; ветка 20 её не включала, чтобы не менять координаторский план. Принятая зависимость 19 — `eab32bfe300caf5537d66703a1db2af929098572`, 35 совместных тестов и `bootJar`; база также содержит принятые 16.1 и 04.4. `origin/master` не использовалась. `MIGRATION_PLAN.md`, Java/reactive, Docker, Flyway, build/Gradle и пакеты 04.4/29.2/24 не менялись.

## Проверенная матрица

| Часть | Legacy/reactive до 20 | Modern/MVC после 20 | Проверка и предел |
| --- | --- | --- | --- |
| Гость и роли | `WebSecurityConfig`: публичные auth, категории, справочник, media read, поиск и публичные карточки/розыгрыши; `/admin/actions/**` требует `SCOPE_ADMIN`. | Существующие точные пары метод/путь и один сегмент сохранены; остальные запросы требуют JWT, admin-префикс требует ADMIN. `GET` OpenAPI/Swagger добавлены как публичные, остальные методы этих путей закрыты. | Полная RSA chain: guest/USER/ADMIN, `refresh`/`verify`/forged/expired/невалидные claims, границы путей, context path. Legacy слабее к некоторым токенам; это принятое security-отличие 32 и обратно не переносится. |
| Optional JWT поиска | Старый `POST /products/find` принимает гостя и использует bearer при возможности. | Отдельная цепочка только для точного `POST /products/find`; неподходящий optional bearer даёт гостевой поиск. У остальных маршрутов токен обязателен и декодируется один раз в filter chain. `Actor` и проверенный `Jwt` передаются дальше. | `ModernMvcSecurityTest`, `ProductSearchHttpTest`; invalid token на соседних маршрутах даёт 401. |
| CORS | `CorsConfig`: `app.allowed-origins`, методы GET/POST/PUT/DELETE/PATCH/OPTIONS, заголовки Authorization/Content-Type/X-Requested-With, credentials=true. Рабочий default — `localhost:3000` и `localhost:5173`. При явном `app.cors-allow-all=true` используется origin pattern `*`, Spring отражает конкретный Origin вместе с credentials. | Servlet source использует те же origin patterns/флаг и методы/заголовки в обеих цепочках security; default=false не расширяется. | Preflight и обычный ответ отражают разрешённый origin; чужой origin при default — 403 без `Allow-Origin`. Отдельный тест явного allow-all подтвердил отражённый origin, а не literal `Access-Control-Allow-Origin: *`, и credentials=true. |
| Ошибки и validation | Глобальный Java `GlobalExceptionHandler` для WebFlux: `ApiException`, validation, DB, `ResponseStatusException`, catch-all. В нём ещё есть `dbMessage`, но legacy не менялся. Проверенные ошибки тела, enum, request part/query и числового path дают пяти-польный `BAD_REQUEST`/400 с `details:null`. | Legacy advice не сканируется modern. Локальные modern advice сохраняют свои коды/статусы. Совпадающая пяти-польная форма собирается через `MvcApiError`; image остаётся четырёхпольным, agent-account denied — пустым 403, verify-code invalid — собственным `{error: ...}`. Узкий `ModernMvcInputErrorHandler` обрабатывает только четыре framework исключения чтения тела, отсутствия request part/query и приведения path/query типа. Локальный verification catch-all требует двух точных handlers для плохого тела и отсутствующего `email`. У category/product DB errors убраны SQL `dbMessage`. Внутренний servlet `ERROR` dispatch разрешён security, прямой `/error` закрыт. | Реальные отрицательные запросы в обоих профилях совпали по полям/code/message/status/null details; timestamp остаётся текущей датой. Прямые JSON assertions покрывают другие локальные различия и редактирование SQL. Нового глобального catch-all нет. |
| JSON/Jackson | Принятые DTO и `spring-boot-jackson2` для Java/reactive; старый `WebFluxJacksonConfig` остаётся только legacy. | Jackson/Gradle версии и ObjectMapper не менялись. Пять полей и порядок у одинаковых error DTO сохранены, `details:null` сохраняется; route-specific success DTO, enum, Instant/LocalDateTime, Float и nullable значения не перенастраивались глобально. | Существующие auth, catalog, basket, favorite, seller, media HTTP тесты; поиск сравнивает поля с Java `ProductDto` и проверяет неизвестный enum/nullable pageable. |
| Multipart | `POST /images` принимает WebFlux `Flux<FilePart>`, `DELETE /images` — ids/tag. | MVC принимает `List<MultipartFile>` через `files`, параметры `tag`/`entityId`; `spring.servlet.multipart` 10 MiB остаётся. Контроллер сохраняет лимит файлов/пикселей и компенсацию S3. | Настоящий HTTP: гость 401, отсутствие `files` 400, запрос >10 MiB 413; standalone MVC: PNG upload, delete, плохой файл 400, слишком много файлов 413. Legacy `FilePart` в servlet не подключался. |
| OpenAPI | Java `OpenApiConfig`: `bearerAuth` HTTP JWT, docs и Swagger публичны через WebFlux security. | Отдельный modern bean с теми же title/version/bearer scheme; `GET /v3/api-docs`, swagger config/UI публичны. | Настоящий `GET /v3/api-docs` — 200 с `components.securitySchemes.bearerAuth.type=http`; `POST` закрыт. |
| Пагинация | `FindProductRequest`/`FindMyProductRequest`: вложенный `pageable` с `size`, `lastCreatedAt`, `lastPrice`, `lastId`, `sortBy`; `@Min(0)` на Java `size`, но `@Valid` зависит от контроллера. | Search/favorite/basket/detail сохраняют собственные request DTO и cursor SQL. Отсутствующий `pageable` даёт default 50, присутствующий null/отсутствующий `size` — legacy primitive 0 там, где это уже принято. Общий pager не введён, чтобы не менять nullable/default/validation различия. | Product search HTTP: пустой/null pageable, unknown enum, JSON поля; real HTTP `size:0` 200. Полная сравнительная матрица отрицательных size/price/date для всех ещё не перенесённых маршрутов остаётся задачей owner/32. |
| Режимы | `legacy` по умолчанию, старые scheduler и dictionary opt-in; `category=modern` в legacy отвергается guard19. | Shared MVC конфигурация профилирована `modern`, в legacy не сканируется. | Выборочные HTTP regression обоих профилей; один `bootJar` запускался последовательно как `legacy` и `modern` на одной собственной embedded PostgreSQL, обе схемы API docs ответили 200. |

## Переданные локальные advice и точный предел унификации

Координатор передал только части advice/error DTO 13 принятых файлов: basket, category, favorite, product command, agent token, auth, participant, verification, image command, account, address, social, transfer. Двенадцать пяти-польных DTO заменены Kotlin `typealias` на `MvcApiError`, включая nullable `message` category; каждый handler по-прежнему самостоятельно назначает код, сообщение и статус. `ImageCommandErrorHandler` оставлен отдельным, потому что его JSON не содержит `details`. `AccountErrorHandler.agentDenied` и `VerifyCodeController` оставлены без изменения ответа. Категория и товар больше не возвращают `dbMessage` в modern. В legacy этот риск уже известен и не затронут.

Отдельная передача также разрешила исправить положительные mock JWT fixture в `TransferSocialIntegrationTest` и `AccountJpaHttpTest`: до 20 они не имели обязательного `type` и падали внутри принятого `Actor` converter; теперь имеют `type=access`. Проверки недопустимых подписанных токенов остаются в `ModernMvcSecurityTest`. Production security ради фикстур не ослаблялась.

## Проверки этапа 1

- JDK `C:\Users\skura\.jdks\temurin-21.0.11`; Gradle `--offline --no-daemon`, `GRADLE_USER_HOME=C:\Users\skura\.gradle`.
- HTTP/contract классы используют embedded PostgreSQL, mock AmazonS3/JavaMailSender, `keys/test_public_key.pem` и `keys/test_private_key.pem`. Test worker напечатал оба `JAVA_TOOL_OPTIONS` параметра. Первоначальный отдельный bootJar smoke не переопределял S3/SMTP: прежнее утверждение о полной изоляции этого smoke было неверным. Дефект подтверждён при приёмке 29.2 и исправлен в разделе от 20.10.2026 ниже.
- `compileTestKotlin` и `bootJar` — успешно. Smoke-тест из собранного `model-store-0.0.1-SNAPSHOT.jar` запустил оба профиля и получил публичный OpenAPI 200; modern check 200 только в modern, 401 в legacy. Для malformed JSON поиска и verify-code, unknown enum, missing `files`, missing query `ids`/`email` (resend/reset) и неверного numeric path каждый режим вернул пять полей `code/message/status/timestamp/details`, `BAD_REQUEST`, 400, точное legacy-сообщение и `details:null`.
- На базе `e6fe744` итоговая выборочная регрессия **18 HTTP/contract классов, 51 тест, 0 failures/errors/skipped**; проверены guest/USER/ADMIN, RSA, auth, категории, поиск/карточка/команды товара, favorite, basket, image, seller, dictionary, giveaway и оба режима. Первый прогон: 51 тест, 49 прошли, 2 старых seller-теста упали из-за положительных mock JWT без `type`; после исправления фикстур тот же список прошёл целиком.
- На итоговой базе `11de9b3` целевая перепроверка 04.4/participant/security/shared: **4 класса, 19 тестов, 0 ошибок**. После этого `bootJar` пересобран, и повторный dual-mode smoke: **1 тест, 0 ошибок**. Новые реальные HTTP-запросы ограничены 3 секундами на соединение и 10 секундами на ответ, bootJar smoke — 2 секундами на запрос и 90 секундами на старт режима.
- После согласования CORS allow-all и узкого input advice: `bootJar` и **4 класса, 12 тестов, 0 ошибок** (реальные JSON/CORS/RSA и dual-mode bootJar). После расширения точных input cases на query/path тот же набор и `bootJar` прошли; итоговая регрессия локальных advice приведена ниже.
- Прогон после этапа 1 на базе `11de9b3`: **19 классов, 53 теста, 0 failures/errors/skipped**. После самостоятельного аудита focused `bootJar`/оба профиля, RSA/CORS/input — **4 класса, 13 тестов, 0 ошибок**; соседние auth/participant/agent/image и DTO — **5 классов, 12 тестов, 0 ошибок**. `git diff --check` — без ошибок.
- Отдельный opt-in `BootJarDualModeSmokeTest`: **1 тест, 0 ошибок**. Он запускает один собранный JAR последовательно в `legacy` и `modern` на собственной embedded PostgreSQL, проверяет публичный OpenAPI 200 в обоих режимах и отсутствие modern check в legacy. Обычно не входит в общий `test`: нужен заранее собранный `bootJar` и `RUN_BOOT_JAR_SMOKE=true`.

## Ограничения

Modern пока не покрывает ещё не перенесённые заказы/администрирование, поэтому этот этап не объявляет полноту API. Общая форма ошибок используется только там, где JSON уже совпадал; локальные различия и validation сохранены. Legacy `GlobalExceptionHandler` по-прежнему может включать `dbMessage` и требует отдельного решения без изменения старого поведения в этом пункте. Полную сравнительную таблицу всех негативных JSON/validation/pagination комбинаций для будущих областей надо закончить при их переносе и в 32.

Самостоятельный аудит выполнен отдельно от этапа 1. Файлы оставлены незакоммиченными, push не выполнялся; commit/push — только по отдельной следующей команде.

## Самостоятельный аудит 17.10.2026

- Публичный matcher `GET /product/{id}` ранее отвергал явный `/` и `%2f`/`%5c`, но сам пропускал `;` и иные percent-encoded варианты, полагаясь на servlet firewall. Теперь, как уже принятый matcher giveaway, он отвергает любой `%` и `;`. Тест проверяет raw matcher без firewall, context path, один сегмент и метод; real security chain проверяет blocked варианты.
- Локальный `VerificationErrorHandler` уже имел catch-all `Exception`. Он скрывал общий input advice и возвращал 500 для malformed `/auth/verify-code` и отсутствующего `email` у resend/reset. Добавлены ровно два framework-specific handlers в переданный advice; доменные handlers, `{error: ...}` для неверного кода и прежний catch-all сохранены. Оба профиля из одного bootJar проверены по статусу, всем пяти полям JSON, сообщению и `details:null`.
- Повторно просмотрены обе security chains, optional JWT только точного `POST /products/find`, сохранение проверенного `Actor`/`Jwt`, guest/USER/ADMIN, invalid tokens, `ERROR` dispatch против прямого `/error`, ADMIN prefix, соседние и OpenAPI GET пути. CORS default и явный allow-all проверены по preflight и обычному ответу, включая отражённый Origin и credentials. Нового глобального catch-all, SQL details и чужих пакетов в diff нет.

## Точный список файлов этапа 1

Изменённые файлы (16):

```text
src/main/kotlin/com/model_store/modern/catalog/basket/api/BasketErrorHandler.kt
src/main/kotlin/com/model_store/modern/catalog/category/api/CategoryController.kt
src/main/kotlin/com/model_store/modern/catalog/favorite/api/FavoriteErrorHandler.kt
src/main/kotlin/com/model_store/modern/catalog/product/command/api/ProductCommandErrorHandler.kt
src/main/kotlin/com/model_store/modern/identity/agent/api/AgentTokenErrorHandler.kt
src/main/kotlin/com/model_store/modern/identity/auth/api/AuthErrorHandler.kt
src/main/kotlin/com/model_store/modern/identity/participant/api/ParticipantErrorHandler.kt
src/main/kotlin/com/model_store/modern/identity/verification/api/VerificationController.kt
src/main/kotlin/com/model_store/modern/seller/account/api/AccountErrorHandler.kt
src/main/kotlin/com/model_store/modern/seller/address/api/AddressErrorHandler.kt
src/main/kotlin/com/model_store/modern/seller/social/api/SocialNetworkErrorHandler.kt
src/main/kotlin/com/model_store/modern/seller/transfer/api/TransferErrorHandler.kt
src/main/kotlin/com/model_store/modern/shared/config/ModernPlatformConfiguration.kt
src/test/kotlin/com/model_store/modern/seller/TransferSocialIntegrationTest.kt
src/test/kotlin/com/model_store/modern/seller/account/AccountJpaHttpTest.kt
src/test/kotlin/com/model_store/modern/shared/config/ModernMvcSecurityTest.kt
```

Новые файлы (8):

```text
MIGRATION_20_REPORT.md
src/main/kotlin/com/model_store/modern/shared/api/ModernMvcInputErrorHandler.kt
src/main/kotlin/com/model_store/modern/shared/api/MvcApiError.kt
src/main/kotlin/com/model_store/modern/shared/config/ModernOpenApiConfiguration.kt
src/test/kotlin/com/model_store/modern/shared/api/BootJarDualModeSmokeTest.kt
src/test/kotlin/com/model_store/modern/shared/api/ModernSharedHttpIntegrationTest.kt
src/test/kotlin/com/model_store/modern/shared/api/MvcApiErrorContractTest.kt
src/test/kotlin/com/model_store/modern/shared/config/ModernCorsConfigurationTest.kt
```

## Ремонт изоляции bootJar smoke — этап 1, 20.10.2026

### База и scope

По отдельному поручению координатора опубликованная ветка `codex/migration-20-mvc-http-contract` объединена обычным merge с **локальной** `master` `f1f7b66bbd2a86f9da52c7ec20882cff0ec65093`. Merge без конфликтов: `7cb8c9a603818c1520b9bfaf78b1b7d90fa644bd`, родители `6b6acfcfcaa2e7177be9ca0ad722b2742c588c3e` и `f1f7b66bbd2a86f9da52c7ec20882cff0ec65093`. История опубликованного пункта 20 сохранена; rebase/force и origin/master не использовались. Файлы, пришедшие с master, не редактировались в ремонте.

Собственные изменения ремонта ограничены тремя файлами:

- `src/test/kotlin/com/model_store/modern/shared/api/BootJarDualModeSmokeTest.kt`;
- новый тестовый helper `src/test/kotlin/com/model_store/modern/shared/api/BootJarSmokeServices.kt`;
- этот отчёт `MIGRATION_20_REPORT.md`.

Production код/config, S3Service, Java/reactive, Flyway, Gradle, план, owner тесты и production код 29.2, Docker и plan_2 не менялись. Коммит самого ремонта и push на этом этапе не выполнялись; синхронизационный merge указан выше отдельно.

### Дефект и исправление

В корневой приёмке 29.2 на `f1f7b66` единственный упавший тест из 67 — `BootJarDualModeSmokeTest`: реальный JAR наследовал S3 defaults и пытался подключиться к `localhost:9000` при `S3Config.createBuckets()`. Stacktrace проверен в корневых `build/migration-29-2-acceptance.log` и XML этого теста. Предыдущий smoke мог зависеть от локальных сервисов; сам факт прежнего успешного старта не доказывал его изоляцию.

Теперь тест поднимает IPv4 loopback S3 HTTP stub и SMTP mock на **случайных удерживаемых портах**. S3 отдаёт XML ACL четырёх собственных тестовых бакетов, принимает их public policy и отвечает XML на `listBuckets()`. Stub проверяет точные метод/путь/query, тестовый access key и регион в AWS Signature V4, отвергает прочий трафик. Учтён фактический протокол SDK v1: `doesBucketExistV2()` выполняет `GET /bucket/?acl`, а не HEAD. Наличие запросов ACL/policy проверяется для legacy, `GET /` — для modern. Фикстура не имитирует object storage целиком: текущие отрицательные HTTP cases не загружают файлы.

Дочерние JVM получают явные фиктивные S3 endpoint/region/access-key/secret-key, все имена бакетов, CDN URL и параметр path style. Оба существующих production builders уже жёстко включают path style; его фактическое применение проверяется путями запросов к loopback stub. Для mail явно заданы loopback host/port, фиктивные username/password и адреса `.test`, SMTP без auth/TLS, все сетевые таймауты 2 секунды. `spring.mail.test-connection=true` требует реального handshake с mock SMTP отдельно в каждом режиме.

Окружение дочернего процесса очищается целиком, сохраняются только Windows `SystemRoot/WINDIR/TEMP/TMP`. Родительские credentials, Spring/AWS/mail variables, proxy и JVM options не наследуются. Используются свежая временная рабочая папка, только classpath `application.yml` с профильным файлом и пустой `spring.config.import`; локальный `.env` не читается. RSA пути явно указывают на настоящие **тестовые** fixtures. Значения унаследованных секретов не читались и не печатались.

Один и тот же JAR запускается последовательно в legacy и modern на одной собственной embedded PostgreSQL. Сохранены все прежние реальные HTTP/security/JSON assertions: OpenAPI 200, modern check 200 только в modern и 401 в legacy; malformed search/verify-code, неверный enum, отсутствие multipart files, неверный числовой path, отсутствие ids и email для resend/reset. Все восемь отрицательных случаев в каждом режиме проверяют прежние пять JSON полей, code/status, точное message и null details.

JVM закрываются в `finally`: ожидание 10 секунд, затем destroyForcibly и ещё максимум 10 секунд с assertion. PostgreSQL и обе сетевые фикстуры закрываются через `use`; PG startup ограничен 30 секундами, библиотечный `pg_ctl stop -m fast -t 5 -w` ограничивает остановку. S3 server останавливается немедленно; SMTP listener и активный socket закрываются, executor threads ожидаются максимум по 5 секунд. SMTP socket read timeout — 5 секунд. Старт каждого режима ограничен 90 секундами, HTTP GET — 2 секунды, POST — 10 секунд, ожидание фактических S3/SMTP startup вызовов — 5 секунд.

### Фактическая проверка

- JDK `C:\Users\skura\.jdks\temurin-21.0.11`, `GRADLE_USER_HOME=C:\Users\skura\.gradle`, `RUN_BOOT_JAR_SMOKE=true`; `JAVA_TOOL_OPTIONS` родителя содержал только пути к двум test RSA fixtures. Docker, внешние S3/SMTP и рабочая БД не запускались.
- Команда: `gradlew.bat --offline --no-daemon bootJar test --tests com.model_store.modern.shared.api.BootJarDualModeSmokeTest`.
- `bootJar` собран на объединённой базе; финальный запуск использовал этот артефакт без пересборки production кода. SHA-256 одного JAR для обоих режимов: `9bdfd93413cdb5d142d30b7272bc0b1872e8a28ede8b0090f1cfab7e71ae62f5`.
- Финальный прогон: **BUILD SUCCESSFUL**, **1 тест, 0 skipped, 0 failures, 0 errors**, 38.573 секунды по XML. XML содержит отдельные строки `bootJar legacy: ... all eight negative JSON cases passed` и `bootJar modern: ... all eight negative JSON cases passed` и штатное завершение embedded PostgreSQL за 0.255 секунды.
- При разработке два прогона выявили несовпадения протокола самой заглушки: сначала ACL вместо HEAD, затем слеш перед `?acl`. Они исправлены в helper; production код и прежние HTTP проверки не ослаблялись.
- Логи в этом worktree: `build/migration-20-smoke-repair.log`, `build/migration-20-bootjar-legacy.log`, `build/migration-20-bootjar-modern.log`; результат — `build/test-results/test/TEST-com.model_store.modern.shared.api.BootJarDualModeSmokeTest.xml`.
- Финальная проверка diff: без whitespace ошибок; в рабочем дереве только три перечисленных файла ремонта. После теста собственных JVM с данным bootJar не осталось, PID embedded PostgreSQL и её listening socket больше не существуют. Проверка процессов была только читающей.

Этап 1 ремонта завершён. Самостоятельный аудит приведён ниже; коммит исправления и push ожидают отдельных поручений. Пункт 29.2 и его приёмочный статус этим отчётом не изменяются.

## Самостоятельный аудит ремонта — 20.10.2026

Аудит выполнен отдельным этапом по поручению координатора. Собственный diff по-прежнему ограничен `BootJarDualModeSmokeTest.kt`, `BootJarSmokeServices.kt` и этим отчётом; production/config/соседние тесты/план не редактировались. HEAD остаётся синхронизационным merge `7cb8c9a603818c1520b9bfaf78b1b7d90fa644bd`; новых коммитов и push нет.

### Найдено и исправлено

1. **Очистка при частичном запуске.** Обычный внешний `use` не закрывает объект, конструктор которого упал. Раньше успешный bind S3 и последующий сбой SMTP могли оставить S3 listener/executor. Теперь частичный запуск fixture и каждого loopback listener защищён собственным try/catch с закрытием уже полученных ресурсов; ошибка cleanup добавляется как suppressed к исходной.
2. **Закрытие всех ресурсов при ошибке одного.** Раньше исключение из `s3.stop`, `smtp.close` или первого await могло прервать оставшуюся очистку. Теперь `closeAll` выполняет все операции, собирает исходную и suppressed ошибки, затем сообщает их. Listener, активный socket и worker защищены от гонки accept/close через один lock. Завершение worker проверяется и через bounded await, и через `Future.get`, чтобы исключение фонового потока не потерялось. При прерывании cleanup продолжает освобождать остальные ресурсы.
3. **Ограничения сетевого ввода/вывода.** У `HttpExchange.requestBody.readAllBytes()` не было собственного лимита и гарантированного read deadline. Общий HTTP server заменён двумя узкими socket listeners. Accept timeout — 1 секунда, socket read — 2 секунды, watchdog принудительно закрывает каждое соединение через 5 секунд, ограничивая также write/общую длительность соединения. HTTP request line ограничена 1 KiB, строка заголовка 4 KiB, сумма заголовков 16 KiB, тело policy 8 KiB, SMTP line 512 байт. Неизвестный HTTP framing/метод/путь/credential scope отвергается. Каждый listener/активный socket закрывается; каждый worker и watchdog имеют await до 5 секунд, проверка результата worker — до 1 секунды.
4. **Точное содержимое S3/SMTP протокола.** Удалён неиспользуемый HEAD. Разрешены только `GET /`, ACL четырёх test buckets и policy трёх public test buckets. Сырые пути сравниваются без URL decoding, допускается лишь фактический SDK trailing slash. Authorization проверяется целым AWS4 header по test access key, date/region/service scope, обязательным signed host/date headers, формату signature и точному loopback Host; подписи/секреты не журналируются. Policy JSON сравнивается с ожидаемым read policy конкретного бакета; ответ policy — 204. ACL содержит корректный FULL_CONTROL grant владельцу, listBuckets возвращает четыре fixture buckets. SMTP принимает только EHLO/HELO и QUIT: счётчик растёт после завершённого handshake, а не при одном greeting. Startup assertions проверяют только трафик после checkpoint соответствующего режима.
5. **Один неизменный артефакт.** Общий путь `build/libs` мог быть заменён другой сборкой между профилями. Теперь JAR один раз копируется в private `@TempDir`; оба режима запускают эту же копию. SHA-256 фиксируется до запусков и сверяется перед и после каждого режима. Оба режима и все прежние реальные HTTP/JSON/security assertions сохранены.
6. **Случайный порт приложения без reserve/release.** Раньше свободный порт выбирался через socket, закрывался, а затем повторно занимался дочерней JVM. Теперь `server.port=0`, приложение само удерживает случайный loopback порт. Smoke получает фактический порт из startup сообщения Netty/Tomcat; 90-секундный startup deadline включает ожидание этого сообщения и успешного OpenAPI ответа. HTTP client один, connect timeout 2 секунды, GET timeout 2 секунды, POST timeout 10 секунд, proxy явно запрещён; client закрывается в `@AfterEach` с await до 5 секунд.
7. **Metadata и окружение.** В этапе 1 был задан `aws.disableEc2Metadata`, который не является ключом используемого AWS SDK v1. По константам установленной библиотеки проверен и задан `com.amazonaws.sdk.disableEc2Metadata=true`, дополнительно явный `AWS_EC2_METADATA_DISABLED=true`, `aws.region=us-east-1` и `com.amazonaws.regions.RegionUtils.disableRemote=true`. Из родительского окружения сохраняются только Windows SystemRoot/WINDIR. TEMP/TMP, java.io.tmpdir, user.home и рабочая папка задаются собственным временным каталогом режима. JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/SPRING_CONFIG/proxy/AWS/mail/credentials не наследуются. Classpath config location, пустой config import, явные PG/RSA/S3/SMTP свойства сохраняются. Ни `.env`, ни home AWS profiles не требуются.
8. **JVM cleanup и первичная ошибка.** Дочерний процесс теперь обёрнут в `AutoCloseable`/`use`: ошибка остановки сохраняется как suppressed к исходной HTTP assertion, вместо потери причины. Destroy/ожидание 10 секунд всегда сопровождается force destroy и ещё максимум 10 секундами, если процесс жив. Interrupted ожидание не отменяет cleanup; флаг восстанавливается после закрытия PG/fixtures/HTTP client. PG по-прежнему закрывается через use, startup 30 секунд, библиотечная остановка `pg_ctl -m fast -t 5 -w`.

### Повторная фактическая проверка после исправлений

В PowerShell заданы только следующие тестовые параметры запуска, затем выполнена точная команда:

```powershell
$env:JAVA_HOME='C:\Users\skura\.jdks\temurin-21.0.11'
$env:GRADLE_USER_HOME='C:\Users\skura\.gradle'
$env:JAVA_TOOL_OPTIONS='-Dapp.public-key-path=C:/Users/skura/.codex/worktrees/b778/model-store/src/test/resources/keys/test_public_key.pem -Dapp.private-key-path=C:/Users/skura/.codex/worktrees/b778/model-store/src/test/resources/keys/test_private_key.pem'
$env:RUN_BOOT_JAR_SMOKE='true'
& .\gradlew.bat --offline --no-daemon bootJar test --tests com.model_store.modern.shared.api.BootJarDualModeSmokeTest *> build/migration-20-smoke-selfreview.log
```

Результат: **exit 0, BUILD SUCCESSFUL in 53s; 1 тест, 0 skipped, 0 failures, 0 errors**, 33.608 секунды по XML. `bootJar` актуален на той же объединённой базе; production код ремонта не менялся, задача bootJar подтвердила артефакт UP-TO-DATE. SHA-256 source JAR и private snapshot совпали: `9bdfd93413cdb5d142d30b7272bc0b1872e8a28ede8b0090f1cfab7e71ae62f5`. Все четыре проверки hash до/после legacy/modern прошли.

XML содержит обе строки `bootJar legacy/modern: S3/SMTP startup, OpenAPI/security and all eight negative JSON cases passed` и подтверждение завершения обоих JVM. Legacy получил port 59513, modern — 59535; embedded PostgreSQL — 59483. JVM PID 34976/38420 и PG PID 36284 завершились; финальная читающая проверка этих PID и listening sockets вернула по 0. PG штатно остановлена за 0.258 секунды. Fixture close также подтвердил завершение обоих listener workers и watchdog; HTTP client termination assertion прошёл.

Артефакты: `build/migration-20-smoke-selfreview.log`, `build/migration-20-bootjar-legacy.log`, `build/migration-20-bootjar-modern.log`, `build/test-results/test/TEST-com.model_store.modern.shared.api.BootJarDualModeSmokeTest.xml`. `RUN_BOOT_JAR_SMOKE=true` проверен фактическим выполнением и XML `skipped=0`; прежние JSON assertions не удалялись и не отключались. `git diff --check` без ошибок. Рабочие значения credentials/environment не выводились; Docker, внешние S3/SMTP и production БД не использовались.

### Предел проверки

Это проверка запуска JAR и прежних HTTP контрактов. S3 fixture проверяет форму подписи и test credential scope, но не вычисляет AWS HMAC и не заменяет S3 compatibility suite; object upload/download здесь намеренно не эмулируются. SMTP проверяет транспортный handshake, а не доставку писем. Определение случайного application port привязано к проверенным startup сообщениям Netty/Tomcat этой версии Boot. Очистка аварийных путей проверена по структуре владения и bounded cleanup; отдельные fault-injection сценарии не запускались. Общая root приёмка 22/29.2 остаётся за координатором. Коммит и push ожидают отдельного поручения.

## Финальная синхронизация и проверка перед fix-коммитом — 20.10.2026

По отдельному финальному поручению локальная master `313dc520de0ab2c0ae2ce7c48b706411e9b03f7d` безопасно объединена в эту же опубликованную ветку обычным merge. Integration merge — `b73a2d555262254d77db5438786e339869a8aa9b`, родители `7cb8c9a603818c1520b9bfaf78b1b7d90fa644bd` и `313dc520de0ab2c0ae2ce7c48b706411e9b03f7d`. Merge не включал незакоммиченный ремонт; SHA-256 каждого из трёх его файлов до/после merge совпали. Добавленные из master production файлы пункта 22 и план отдельно не редактировались.

На этой базе заново выполнены настоящая сборка `bootJar` и непропущенный smoke обоих режимов. Тестовые переменные окружения и команда те же, что в разделе selfreview; единственное отличие — файл лога:

```powershell
& .\gradlew.bat --offline --no-daemon bootJar test --tests com.model_store.modern.shared.api.BootJarDualModeSmokeTest *> build/migration-20-smoke-final.log
```

Фактический результат: **exit 0, BUILD SUCCESSFUL in 1m 14s; 1 тест, 0 skipped, 0 failures, 0 errors**, 39.690 секунды по XML. `compileKotlin`, `bootJar` и `compileTestKotlin` фактически выполнены. Один новый JAR для обоих режимов имеет SHA-256 `b2831068cd0aeb76359aa24d3c97360c2cbd23207211095396e671445d666df8`; hash private snapshot совпал с source JAR и выдержал все проверки до/после каждого режима. XML содержит отдельные успешные строки legacy/modern для S3/SMTP, OpenAPI/security и всех восьми отрицательных JSON случаев.

JVM 30744/23324 штатно завершены, PG 35912 остановлена за 0.253 секунды. Финальная читающая проверка этих PID и listening ports 64867/62198/62248 вернула по 0. `git diff --check` без ошибок. Логи: `build/migration-20-smoke-final.log`, `build/migration-20-bootjar-legacy.log`, `build/migration-20-bootjar-modern.log`; XML — `build/test-results/test/TEST-com.model_store.modern.shared.api.BootJarDualModeSmokeTest.xml`.

Отдельный fix-коммит должен иметь единственным parent этот integration merge и содержать **ровно**:

```text
MIGRATION_20_REPORT.md
src/test/kotlin/com/model_store/modern/shared/api/BootJarDualModeSmokeTest.kt
src/test/kotlin/com/model_store/modern/shared/api/BootJarSmokeServices.kt
```

Перед отправкой origin URL проверен: `https://github.com/skuratov-sa/model-store.git`. Читающая сверка показала исходный remote SHA `6b6acfcfcaa2e7177be9ca0ad722b2742c588c3e` для `refs/heads/codex/migration-20-mvc-http-contract`; он сохранён в ancestry integration merge. Разрешена только отправка `HEAD:refs/heads/codex/migration-20-mvc-http-contract` без force. Точный fix SHA, результат push, parent/base и финальный remote SHA фиксируются после создания коммита в финальном сообщении и локальном `build/migration-20-delivery.md`, чтобы не помещать SHA коммита внутрь его собственного содержимого.
