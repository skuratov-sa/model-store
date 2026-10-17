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
- Только embedded PostgreSQL каждого тестового класса, mock AmazonS3/JavaMailSender, `keys/test_public_key.pem` и `keys/test_private_key.pem`. Test worker напечатал оба `JAVA_TOOL_OPTIONS` параметра. Рабочие ключи, БД, SMTP и S3 не использовались.
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
