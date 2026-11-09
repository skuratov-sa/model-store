# 29.1 — агентские товары: реализация и интеграция12.3/16.2/25

Реализация: 20.10.2026. Отдельный аудит: 21.10.2026.
Ветка `codex/migration-29-1-agent-products`, выделенное managed worktree `5936/model-store`.
Точный исходный HEAD и merge-base: локальная master `783770f16691286a1e8f3016b7dc5dd0f0dab76d`.
Ветка создана от этого SHA, не от origin/master. Новых деревьев, чатов или субагентов не создавалось.
Предыдущая интеграционная база после отдельно порученного fast-forward21.10.2026:
`d8e2982e174796ad0a7fbfae762e85d3f88dcbc3` из локальной master, с принятым12.3 (`44e0c7e`).
Текущая база после отдельно порученного fast-forward09.11.2026:
`68247f3df439923a431300e7c39c175357e8d225` из локальной master, с принятыми16.2 и25.
Пользователь изменил порядок публикации: только будущие локальные коммиты по отдельной команде,
никаких push. На текущем этапе commit также не выполняется.
Текущая адаптация завершена:53 собственных и141 связанных проверок,0 ошибок/пропусков,
два фактически выполненных dual-mode smoke одного нового JAR. Свежий отдельно назначенный аудит
на68247f3 также завершён: уточнены два собственных test файла, целевые13+7 проверок успешны.
Результаты приведены в последнем разделе; локальный commit требует отдельного поручения.

## Область и зависимости

В истории базы подтверждены через `merge-base --is-ancestor`: 13 `121bd9c`, 16 `14f0744`,
04.5 `f788303`, 16.1 `19eea32`, 12.1 `a148f54`, 22 `313dc52`, 12.2 `99977fa`.
29.2 и исправление dual-mode smoke также входят в исходную базу. Последующее уточнение плана
координатором на `e3f61f8` не меняет исходную базу этой ветки.

Основные новые файлы лежат только в `modern/seller/agent/product/**`,
`modern/catalog/product/agent/**` и соответствующих тестах.
Координатор отдельно согласовал новые `identity/agent/application/ActiveAgentLock.kt`,
его инфраструктурную реализацию/тест и новые `identity/participant/application/AgentDirectoryQuery.kt`,
инфраструктуру чтения и `seller/agent/profile/api/AgentDirectoryController.kt`.
Список агентов закреплён за этим же 29.1; существующие 18 профильных маршрутов 29.2 не менялись.

Существующие Java/reactive, Flyway, общие config/API/security, Gradle/Docker, MIGRATION_PLAN,
docker-compose.yml, plan_2.md, принятые product/command/giveaway, media и ordering файлы не редактировались.
Новых миграций нет. Изменения только новые файлы; commit/push не выполнялись.

## Инвентаризация actual legacy и маршрутов

| HTTP | Legacy call graph | Modern |
| --- | --- | --- |
| POST `/agent/products` | AgentController → JwtService.isAgentToken/getIdByAccessToken → ProductServiceImpl.createAgentProduct → ParticipantRepository.findByIdAndIsAgentTrue + ACTIVE → ProductRepository.save → ImageService.updateImagesStatus → CategoryService.addLinkProductAndCategories | AgentProductController → AgentProductActions → VerifyAgentAccess13 → catalog AgentProducts.create → identity ActiveAgentLock → ProductCommandStore16 → ProductImageLink12.1 → categories |
| GET `/admin/actions/agents/{agentId}/products` | AdminAgentController → AdminAgentService.products → requireAgent → ProductRepository.findByParticipantId | catalog AgentProducts.products → identity AgentProfileCommands.read → scalar product read |
| GET `/admin/actions/products/{productId}` | AdminAgentController → AdminAgentService.product → ProductRepository.findById; 404 PRODUCT_NOT_FOUND | catalog AgentProducts.product → identity current-admin check → scalar product read |
| PUT `/admin/actions/agents/{agentId}/products/{productId}` | AdminAgentService.requireAgent → ProductServiceImpl.updateAgentProduct → updateOwnedProduct(includeInactive=true) → giveaway dictionary lock → product FOR UPDATE + owner/non-DELETED → ProductMapper.updateProduct → settings/orders/overlap → save → images → replace categories | catalog AgentProducts.update → AgentAccessLock04.5 → dictionary22 → product + exact owner → same patch/settings rules22 → store16 → media12.1 → categories |
| POST `/admin/actions/agents/{agentId}/products/{productId}/extend` | requireAgent → ProductServiceImpl.extendExpirationDate → findProductForExtendForUpdate + owner → ACTIVE/new expiration | catalog AgentProducts.extend → AgentAccessLock04.5 → EXTENDABLE16 → same status/expiration |
| GET `/admin/actions/agents` | AdminAgentController → AdminAgentService.agents → ParticipantRepository.findByIsAgentTrueOrderByIdAsc → AgentSummaryDto(id,login,status) | new AgentDirectoryController → identity AgentDirectoryQuery(current ACTIVE nonagent ADMIN) → scalar entries ORDER BY id ASC |

`/admin/actions/products/{productId}` — plural GET, без alias.
Принятый singular `PUT /admin/actions/product/{id}?productStatus=…` остаётся единственным
путём изменения статуса товара; он проверяется совместно с новыми маршрутами. Дополнительный
status CRUD или административное создание не изобретались. Modern запускается со всеми
контроллерами вместе; конфликт mapping вызывает отказ старта тестового приложения.
Новые сценарии подключены только в полном modern MVC. Legacy использует прежние контроллеры/сервисы.
Работу bridge23 и ordering25 эта реализация не меняет.

### DTO, mapper, чтение и ограничения

* CreateAgentProductRequest: name/description/price/currency/originality/externalUrl/categoryIds/used/imageIds.
  Всегда EXTERNAL_PRODUCT, ACTIVE, count/prepayment=null, used по умолчанию false.
  Никаких transfer/social prerequisites; generic ProductCommands.create не вызывается.
* CreateOrUpdateProductRequest: patch non-null полей, categoryIds=null сохраняет связи, [] удаляет,
  imageIds=null/[] не удаляет фотографии. Входные id/participantId/status/createdAt не участвуют в записи.
* Явный availability при agent update допускает только EXTERNAL_PRODUCT/GIVEAWAY.
  Исторические PURCHASABLE/PREORDER при availability=null редактируются по старому mapper:
  count остаётся только у PURCHASABLE, prepayment у него очищается; новый запрет исторических типов не введён.
* Внешняя ссылка проверяется только на blank; scheme/host не ужесточены. Отрицательная конечная цена
  допускается как в legacy; prepayment проверяется по прежнему диапазону. Nonfinite значения отклоняются безопасно.
* Legacy administrative Product JSON содержит 22 поля, включая явные null и createdAt.
  Его чтение не вычисляет effective giveaway status, не добавляет фото/категории и не фильтрует status.
  Список товаров без пагинации и без гарантии сортировки, как derived legacy query.
  Plural details читает также товар обычного продавца и DELETED товар, как actual legacy.
* BLOCKED/DELETED целевой агент разрешён администратору; проверяется is_agent, статус не ужесточён.
  Update исключает DELETED product, extend — только ACTIVE/TIME_EXPIRED и не GIVEAWAY.
* Розыгрыш: reuse AdminGiveaways.applySettings22, dictionary lock/overlap/active-order queries22,
  HTTPS t.me/telegram.me без userinfo, complete dates/rules/homeText/winners, enabled/end-in-future,
  запрет activation BLOCKED/DELETED. Разрешены соседние непересекающиеся расписания, Instant/timezone сохранены.
  Leaving giveaway сохраняет исторические поля, выключает enabled, требует price>0,
  возвращает TIME_EXPIRED/AWAITING_GIVEAWAY в ACTIVE и продлевает уже истёкший срок.
  Legacy игнорирует присланные giveaway settings при выходе из типа; это поведение сохранено.

Учтены Flyway: V1.0 product/image/product_category (varchar lengths, NOT NULL, FK), V1.4 active
unique `(participant_id,name)`, V1.17 used NOT NULL/default false, V1.23 uploaded_by,
V1.24–V1.26 giveaway enum/fields/checks/status, V1.27 scheduling и V1.28 удаление последнего
глобального unique enabled/active giveaway. Ограничения категорий глобальные: participant ownership
у category нет, existing IDs допустимы; отсутствующий ID — INVALID_REFERENCE. Повторные category IDs
не дедуплицируются без старого основания: на паре нет unique constraint.

## Границы и транзакции

Seller принимает Actor из shared security, повторно JWT не разбирает. AgentProductActions.create
обязательно вызывает VerifyAgentAccess.requireActiveAgent13. Каталог получает только verified caller
attributes/ID и application patch DTO; ProductRecord и AdminGiveaway остаются внутри catalog.
Seller не пишет product/image/participant и не получает auth models/password/mail.
API DTO отделены от application snapshots. Domain AgentProductPolicy без Spring/JPA/HTTP;
его ошибки переводятся в application typed failures до выхода за catalog.

На границе команды используется `transactionManager` (JPA), вложенные owner-порты присоединяются
к ней. Сохраняются product, settings, categories и media claim; поздняя ошибка категории и внешняя
ошибка вызывающего кода откатывают всё. S3 не участвует в DB-транзакции, собственных S3-вызовов нет.
Ни caller, ни target product owner при update не подменяется; точный owner проверяется под product lock.
Generic ProductCommands.update по-прежнему запрещает агента.

ActiveAgentLock требует MANDATORY writable JPA transaction и реальные EMF/DataSource resources;
возвращает Unit. Owner JDBC `SELECT is_agent,status … FOR UPDATE` читает свежую tuple после ожидания,
обходя устаревший JPA identity map. Запрет при no/wrong/readOnly/foreign EMF/DataSource проверяется.
Детерминированно закреплён старый read→write gap: результат Verify13 остаётся скалярным ID после
успешного параллельного отзыва ACTIVE/is_agent; новый lock отвергает его до записи.
После успешного lock отзыв ждёт commit/rollback внешней транзакции.

Порядок новой записи: participant IDs ASC → giveaway dictionary при update → product → image12.1 NOWAIT.
Создание имеет одного participant, новый product и image. Extend не запрашивает dictionary.
Совместимость с reservation16.1 и настройками22 подтверждается ожиданием PostgreSQL locks,
а не предположением по таймеру. HTTP timeout 15с, connect 3с; future/latch/lock ожидания конечные.

## Историческое воспроизведение дефекта media до12.3

**На исходной базе это было ограничением приёмки конкурентности29.1.** Координатор принял
доказательство и выделил отдельный12.3 в локальной master `efbda16`. Исправление12.3 позже принято
как `44e0c7e`; текущая общая база — `d8e2982`. Его интеграционные проверки описаны в последнем разделе.
`modern/media/image/command/infrastructure/JdbcImageCommandRows.kt`:
`insertBatch` вызывает authorizeUpload; `markDeleted` сначала авторизует caller, затем image,
затем authorizeUpload PRODUCT. `authorizeUpload` около строки78 блокирует actor participant,
а около строки96 захватывает product и owner. В результате при `agentId < adminId`:

1. T1/JPA держит agent (первый lock04.5 в возрастающем порядке).
2. T2/JPA media ADMIN upload/delete держит admin и ждёт agent при PRODUCT authorization.
3. T1 продолжает настоящий AgentProducts.update → AgentAccessLock04.5 и ждёт admin.
4. PostgreSQL детектор выдаёт `40P01`, одна транзакция откатывается, другая завершается.

Прежний тест `AgentProductTransactionsTest.REPRO accepted media reverses admin agent participant lock order on upload and delete`
детерминированно проверял оба пути: backend PID/pg_blocking_pids, finite gates,
ровно одна 40P01 и один успех. Это **успешный repro дефекта**, не доказательство отсутствия deadlock.
Исходный media-файл не изменён. Предложение владельцу media/интеграции: до product/image брать
caller и product owner participant в общем ASC порядке, повторно проверяя текущие права/owner
после ожидания; не сохранять caller-first и затем owner при обратных ID.
Исправление принадлежит12.3. Его независимый repro может вызывать непосредственно принятый
AgentAccessLock04.5 после первой participant блокировки; новый порт29.1 для воспроизведения не нужен.
После12.3 тот же порядок gates должен позволять T1 завершить lock04.5/запись и освободить agent,
после чего media завершается. Критерий смены ожидания repro: убрать assert одной40P01, требовать
отсутствия40P01/таймаута и успешного последовательного завершения обеих разрешённых операций,
с проверкой owner, media association/status и отсутствия частичного отката. До согласованной
интеграции12.3 assert40P01 оставался доказательством известного дефекта, не зелёной гарантией.
После принятия12.3 исходный тест заменён собственными проверками исправленного порядка;
отрицательные доказательства остаются историческими в этом отчёте и сохранённых логах.

## Подтверждённые безопасные отличия для 32

* Legacy shared decoder запрещает только type=refresh; signed verify/agent_refresh с normal ADMIN
  проходят административные маршруты. Modern требует normal access ADMIN и отклоняет их401.
  Agent access, в том числе с заявленным ADMIN, не даёт административных полномочий.
* Для административных чтений/записи новый identity owner проверяет текущего ACTIVE nonagent ADMIN.
  Stale/ложные ADMIN claims, отозванная роль/статус/is_agent теперь отвергаются403.
* Inactive agent create legacy filter возвращает403 без тела, Verify13/modern — typed JSON403.
* Принятый media12.1 запрещает чужого uploaded_by, неполный пакет, неверные теги/статусы,
  дубли/лимит и повторный захват другого товара. Actual legacy updateStatusByIds не проверяет
  uploaded_by и считает любой ненулевой updated count успехом. Парный реальный HTTP-тест
  закрепляет legacy200 против modern403/404 при foreign uploader/mixed missing IDs.
* Currency.CREATE есть в Java API enum, но отсутствует в PostgreSQL: legacy500/INTERNAL_ERROR,
  modern409/DUPLICATE_KEY без раскрытия SQL. Переполнение varchar также безопасно переводится modern.
* Legacy integrity response может раскрывать dbMessage в details; modern возвращает details=null,
  сохраняя codes/messages для обычных NOT NULL/duplicate/FK ошибок.

## Проверки этапа1 — 20.10.2026

Среда: JDK `C:/Users/skura/.jdks/temurin-21.0.11`, GRADLE_USER_HOME `C:/Users/skura/.gradle`,
`gradlew --offline --no-daemon`, JAVA_TOOL_OPTIONS с keys/test_public_key.pem и test_private_key.pem.
Каждый новый test class имеет собственную embedded PostgreSQL, mock SMTP/S3. HTTP — настоящий
сервер/RSA JWT, отдельные WebFlux/R2DBC legacy и MVC/JPA modern приложения.

Первый транзакционный прогон exit0: 13 тестов (9+4), без failures/errors/skipped,
включая ожидаемый media repro. Лог `build/migration-29-1-transactions.log`.
Следующий собственный прогон exit0: 37 тестов в4 классах (10+5+11+11), без ошибок/пропусков,
лог `build/migration-29-1-own.log`. XML считался только после exit0.
После него добавлены ещё HTTP-сравнения active-order/media и foreign-DataSource case,
перевод domain ошибки на application границе; финальная регрессия запущена отдельно.

Расширенная регрессия + bootJar: exit0, **133 теста в23 классах**, failures/errors/skipped=0.
Лог `build/migration-29-1-regression.log`, сохранённый разбор XML
`build/migration-29-1-regression-summary.json` (сделан после exit0, до следующего test task).
Включены собственные41 теста и соседние13/04.4/04.5/29.2/16/16.1/22/12.1/12.2/media upload/delete.
В их числе есть намеренный успешный assert40P01; это не устранение ограничения12.3.
После старта этого прогона добавлен только тест ожидания обычной status-команды16 против agent patch,
production-код не менялся. Он запущен в отдельном заключительном прогоне всех11 транзакционных тестов
вместе с фактическим BootJarDualModeSmokeTest при RUN_BOOT_JAR_SMOKE=true.
Заключительный прогон exit0: **12 тестов в2 классах**, failures/errors/skipped=0:
все11 `AgentProductTransactionsTest` (включая новую гонку обычного статуса) и один
**непропущенный** `BootJarDualModeSmokeTest` с фактическим запуском одного JAR в legacy и modern,
изолированными loopback SMTP/S3 и PostgreSQL. Лог `build/migration-29-1-smoke.log`, сводка
`build/migration-29-1-smoke-summary.json`; overlap с регрессией не суммируется как новые тесты.
JAR snapshot SHA-256 из успешного smoke совпал с артефактом:
`42e908e3c152c4075f6126516fac2b834dcb44be81695fa7147673ff7c5dc42a`.

Для повторения заключительного прогона после bootJar:
`RUN_BOOT_JAR_SMOKE=true; gradlew --offline --no-daemon test --tests '*AgentProductTransactionsTest' --tests '*BootJarDualModeSmokeTest'`
(в PowerShell переменную задавать через `$env:RUN_BOOT_JAR_SMOKE='true'`).
Полный список классов и результаты расширенной регрессии сохранены в regression-summary.json.

Этап1 независимой реализации и проверок завершён. Финальная приёмка конкурентности29.1
остаётся за координатором после12.3 и обновления repro на безопасное завершение.
На момент завершения этапа1 самостоятельный аудит ожидал отдельного назначения; его результат
за21.10 приведён ниже. Commit/push не выполнялись. Галочку ставит координатор после своей приёмки.
Команда настоящего сжатия и счётчики last_token_usage.total_tokens/model_context_window
в доступных инструментах не найдены; сводка не называется сжатием.

## Отдельный самостоятельный аудит — 21.10.2026

Назначен отдельным сообщением после завершения реализации и отдельного подтверждения отсутствия
команды настоящего сжатия. Исходная база остаётся `783770f`; ожидаемая интеграция12.3 не подменена
чужим checkout или временной правкой media. Commit/push по-прежнему не выполнялись.

### Подтверждённые находки и исправления

1. **P2 — проверка JDBC DataSource в ActiveAgentLock была неполной.** Проверялись ресурсы объявленных
   EMF/DataSource, но не участие DataSource самого NamedParameterJdbcTemplate. В штатной конфигурации
   они совпадали; однако miswired template мог выполнить FOR UPDATE через отдельное autocommit
   соединение и сразу отпустить lock. Это дефект гарантии порта при неправильной компоновке, не
   обнаруженный способ передать DataSource через HTTP. Детерминированный тест
   `ActiveAgentLockIntegrationTest.rejects a JDBC template whose datasource is not enlisted in caller JPA`
   до исправления не получал ожидаемый отказ. Исправлен только новый `JdbcActiveAgentLock.kt`:
   теперь требуется настоящий ConnectionHolder объявленного DataSource, а resource JDBC template
   должен быть тем же holder. No/wrong/readOnly/foreign EMF/foreign DS проверки сохранены;
   lock по-прежнему MANDATORY, writable JPA, без собственного commit.

2. **P3 — внешний URL молча проверялся строже Java legacy.** Kotlin isNullOrBlank относит U+00A0,
   U+2007 и U+202F к blank, Java String.isBlank — нет. Парный настоящий HTTP-тест
   `external URL follows Java legacy blank semantics for nonbreaking spaces` показал legacy200 и
   modern400 до исправления. В новом `AgentProductPolicy.externalUrl` используется codePoints с
   Character.isWhitespace, совпадающий с Java правилом. Исправление действует при создании и
   редактировании EXTERNAL_PRODUCT, без новых требований к scheme/host. Прежние null/ASCII blank
   отказы сохраняются. Переписывание принятых generic product/giveaway правил не потребовалось.

3. **Недостаток достоверности теста удержания lock исправлен.** Старый тест после owner lock
   делал UPDATE того же participant; этот UPDATE сам блокировал отзыв и мог замаскировать
   отсутствующую блокировку порта. Теперь для outer rollback записывается несвязанная category
   fixture, а отзыв is_agent обязан ждать именно ActiveAgentLock. Проверяется откат этой fixture.
   Дополнительно `real seller creation holds agent authorization until product commit` вызывает
   настоящий AgentProductActions/Verify13/catalog owner и подтверждает: отзыв ждёт product commit,
   после него завершается; товар сохраняет настоящий owner. Проверка stale JPA после ожидания
   и отдельное воспроизведение read→write gap Verify13 остаются в наборе.

4. **Расширена HTTP-матрица создания.** Новый парный тест
   `agent creation verifies JWT type issuer signature and expiry before writing` отдельно проверяет
   refresh/verify/agent_refresh, отсутствующий/чужой issuedBy, повреждённую RSA подпись и истечение
   срока на POST `/agent/products`, а также отсутствие записи после отказов. Normal USER/ADMIN
   access, normal ACCESS самого агента, guest, inactive/non-agent ранее уже проверялись. Для
   административных пяти маршрутов сохранены отдельные проверки нормального ADMIN ACCESS,
   текущих DB role/status/is_agent и недопустимых токенов.

Repro до исправлений завершился exit1 с ожидаемыми дефектами; это диагностическое доказательство,
не успешный результат приёмки. Лог `build/migration-29-1-audit-repro.log`, исходные failure diagnostics
сохранены в `build/migration-29-1-audit-repro-results/` до следующего test task.

### Повторная проверка границ и переноса

* Все6 маршрутов остались точными legacy mappings; plural GET товара не превращён в alias
  singular PUT статуса. Полный modern context стартует с принятыми контроллерами вместе.
* Actor приходит из security; JWT в новых use cases/controllers повторно не разбирается. Verified
  attributes/ID публичного catalog caller конструируются seller-фасадом, не из request body.
  Agent create требует Verify13 и затем свежий ACTIVE/is_agent под owner lock. ADMIN write проверяет
  caller и target через04.5 в той же JPA-транзакции; target BLOCKED/DELETED не запрещён дополнительно.
* В новых seller/catalog файлах нет прямых SQL writes image/participant, AuthAccounts, password/mail
  чтения или передачи identity/JPA сущностей. Catalog ProductRecord/AdminGiveaway используются
  только внутри catalog. Через границу seller проходят application scalar DTO; domain ошибки
  новой политики переводятся в application failure. Принятый typed AgentTokenFailure13 — ошибка
  без account/JPA payload, не передача auth сущности.
* Agent create не вызывает generic create с подставленным caller; generic update по-прежнему
  запрещает agent owner. Административный patch не содержит изменения owner: после product lock
  проверяется exact `ownerId == agentId`, несмотря на широкие legacy giveaway admin чтения.
* Порядок новой записи остаётся participant IDs ASC → dictionary → product → image; create имеет
  одного participant и новый product, extend не берёт dictionary и исключает GIVEAWAY. Обратные
  admin/agent ID обрабатывает принятый04.5, media reversal остаётся известным12.3, не исправленным здесь.
* Проверены entering/leaving GIVEAWAY, active orders, exclusive endpoint overlap (соседние окна
  разрешены), disabled/blocked/deleted состояния, timezone/Instant, raw stored status на agent reads.
  Проверка реального владельца идёт до settings/images/categories writes. Product/settings/media
  claim/categories участвуют в общей JPA-транзакции и откатываются при поздней FK/внешней ошибке.
* Partial patch, null/[] categories и images, historical PURCHASABLE/PREORDER stock, null prepayment,
  finite отрицательная цена и arbitrary nonblank external URL сохранены. Nonfinite запрет и safe DB
  errors — ранее записанные отличия для32. DDD domain новой политики остаётся без Spring/JPA/HTTP.
* HTTP-тесты используют настоящие WebFlux/R2DBC и MVC/JPA серверы/RSA tokens на отдельных PostgreSQL,
  mock SMTP/S3. Проверки ожидания опираются на pg_stat_activity/pg_blocking_pids и конечные gates,
  а не только на sleep. Expected40P01 media тест по-прежнему положительно воспроизводит дефект.

### Ещё два подтверждённых унаследованных отличия22 для32

Парный тест `accepted giveaway policy Unicode normalization differences stay explicit for migration32`
подтвердил: NBSP вокруг Telegram URL legacy не удаляет Java trim и отклоняет400, принятый22
Kotlin trim удаляет его и возвращает200 с по-прежнему проверенным HTTPS Telegram URL. Напротив,
giveaway rules из одного NBSP legacy принимает200, принятый22 считает blank и отклоняет400.
Точное место: `catalog/giveaway/admin/application/AdminGiveaways.kt`, `applySettings` (trim и
isNullOrBlank). Эти безопасные различия наследуются reuse22; его существующий файл не передан
и не менялся. Они подлежат общему сопоставлению32, не скрываются как полная идентичность Unicode.

### Проверки финального состояния аудита

После исправлений: exit0, **50 собственных тестов в4 классах**, failures/errors/skipped=0, bootJar.
`build/migration-29-1-audit-own.log`, `build/migration-29-1-audit-own-summary.json`.
Это16 real HTTP tests на каждый режим,7 ActiveAgentLock tests и11 catalog transaction tests;
expected40P01 включён как доказательство всё ещё существующего ограничения12.3.
XML разобран только после exit0. Production-код после этого прогона не менялся.

Проверки соседних контрактов и актуального JAR: exit0, **63 теста в12 классах**,
failures/errors/skipped=0. Это13/04.5/04.4/16/16.1/22/12.1 и один непропущенный
BootJarDualModeSmokeTest, фактически запускающий один новый JAR в legacy и modern с изолированными
loopback SMTP/S3/PostgreSQL. Лог `build/migration-29-1-audit-regression.log`, разбор XML после exit0
`build/migration-29-1-audit-regression-summary.json`. SHA-256 smoke snapshot и собранного JAR совпал:
`b871d4d190e60bee053128f512999c56606d392689a544b8b4f6460b9e879a57`.

После аудита остаются18 новых согласованных файлов; старые tracked файлы не изменены,
HEAD/ветка и исходная база сохранены, diff/whitespace checks без ошибок. Production-код после
успешных50 собственных тестов не менялся. Других подтверждённых дефектов в переданном scope,
помимо исправленных и известных ограничений выше, аудит не выявил; это не утверждение
полного доказательства безопасности всех немигрированных/будущих интеграционных сценариев.

Аудит завершён, работа остановлена с этим отчётом без commit/push.
Команда настоящего сжатия/требуемые context counters в этой сессии по-прежнему недоступны;
ручное сжатие не выполнялось. Этот аудит не заменяет финальную общую регрессию после12.3.
На момент завершения аудита остаточной блокировкой приёмки был12.3 и последующая смена expected40P01
на проверку безопасного завершения в согласованной общей базе. До принятия исправления ни media-файл,
ни этот repro не менялись. Результат последующего этапа приведён ниже.

## Финальная интеграция на принятом12.3 — 21.10.2026

По отдельному поручению координатора ветка fast-forward от исходного `783770f` до точного
`d8e2982e174796ad0a7fbfae762e85d3f88dcbc3` из **локальной master**. В ней подтверждён ancestor
12.3 `44e0c7ef33209c43f7d5bce33981e4f51afdc6cc` и принятый23. Origin/master не использовался.
До merge сравнен список11 входящих путей с18 собственными untracked файлами: пересечений0.
SHA-256 всех18 файлов сохранены в `build/migration-29-1-before-integration-files.json` и после
merge повторно сверены — все совпали. Merge был строго `--ff-only`, без конфликтов, stash,
reset, удаления или потери собственных изменений. Новых worktrees/чатов не создавалось.

После merge изменены только собственные `AgentProductTransactionsTest.kt` и этот отчёт.
Production-код29.1 остался в проверенном аудитом состоянии; принятые чужие media/config/plan
получены из общей базы и самостоятельно не редактируются.

### Замена исторического expected40P01

Прежний тест с именем `REPRO accepted media reverses admin agent participant lock order on upload and delete`
заменён тремя собственными тестами:

* `agent lock then admin product update serializes media upload and delete without deadlock` —
  прежнее межтранзакционное чередование при ownerId<adminId, но первый lock теперь берёт настоящий
  ActiveAgentLock, затем настоящий AgentProducts.update/AgentAccessLock04.5. Второй поток вызывает
  настоящие media insertBatch/markDeleted. PostgreSQL должен показать, что media ждёт конкретный
  PID первой транзакции; после открытия gate **обе операции обязаны завершиться успешно**.
* `catalog updates serialize media for both administrator owner ID orders` — тот же реальный
  catalog owner update/04.5 против media upload/delete, отдельно adminId<ownerId и ownerId<adminId.
  Проверяются сохранённый patch и owner, точные association/uploader/status изображений, реальный
  результат upload singleton и DELETE существующего изображения. Разрешённых операций no-op нет.
* `waiting media rechecks revoked administrator role and target agent flag` — после успешного
  owner patch первая транзакция отзывает role/is_agent тестовой fixture и удерживает locks.
  Media обязан сначала действительно ждать, затем получить **ровно ImageCommandDenied**, а не
  произвольную ошибку, deadlock или timeout. Catalog patch остаётся committed, owner не меняется,
  запрещённые upload/delete не оставляют новых rows или DELETE status. Проверяется и факт отзыва.

Самопроверка против ложноположительных результатов: gates конечные; ожидание подтверждается
`pg_blocking_pids(waiter)` с конкретным blocker PID до разрешения первой транзакции. Fail-fast/no-op
не пройдут эту проверку. В двух разрешённых сценариях исключение любого вида проваливает тест;
future.get проверяет успешное окончание обеих сторон. В отказном сценарии допускается только
точный штатный тип ImageCommandDenied плюс неизменённое media-состояние. Широкого catch с
игнорированием ошибок, assumptions/skips или ожидания одной40P01 больше нет. При старом caller-first
media порядок первого сценария вновь образовал бы доказанный исторический цикл, и assertNull
результатов его отверг бы. Отрицательные исходные доказательства выше сохранены как история.

Первый предварительный прогон на общей базе: exit0, **20 тестов в2 классах** (13 catalog transaction
и7 ActiveAgentLock), failures/errors/skipped=0. Лог `build/migration-29-1-integration-locks.log`,
сводка после exit0 `build/migration-29-1-integration-locks-summary.json`. После него добавлены только
дополнительные assert association/uploader/status и факта отзыва; они вошли в общий финальный прогон.

### Итоговые проверки

Общий финальный прогон на точном d8e2982 успешно завершился21.10.2026; сохранённый итог проверен
после возобновления09.11.2026 без повторного запуска неизменённых успешных тестов.
Охват: все собственные HTTP/JWT6 маршрутов,
новые identity-порты, partial/URL/rollback, GIVEAWAY/reservation/image claims, соседние13/04.4/04.5/
29.2/16/16.1/21/22, весь пакет media.image (включая12.1/12.2/12.3), safety guard23 и фактический
BootJarDualModeSmokeTest с RUN_BOOT_JAR_SMOKE=true.

**177 тестов в30 классах, failures=0, errors=0, skipped=0**, bootJar и фактический запуск одного JAR
в legacy и modern. Собственные52 проверки (16+16 HTTP,7 ActiveAgentLock,13 catalog transactions)
входят в177; предварительные20 повторно к итогу не прибавляются. Ожидаемой успешной40P01
в текущем source state нет: все три новых теста сериализации присутствуют в успешном XML.

Доказательства сохранены:

* `build/migration-29-1-integration-final.log` — терминальный `BUILD SUCCESSFUL in 10m 3s`.
* `build/migration-29-1-integration-final-summary.json` —30 классов с результатами, точная база,
  hash артефакта и происхождение подтверждения завершения.
* `build/migration-29-1-integration-final-files.json` — SHA-256 собственных файлов; после завершения
  этого отчёта manifest обновлён. Все17 Kotlin sources имеют время записи до финальных XML;
  относительно pre-merge manifest отличаются только разрешённые test и report.
* `build/test-results/test/TEST-com.model_store.modern.shared.api.BootJarDualModeSmokeTest.xml` —
  ровно один выполненный smoke без skip; `migration-20-bootjar-legacy.log` и
  `migration-20-bootjar-modern.log` — реальные дочерние процессы с loopback SMTP/S3/PostgreSQL.

SHA-256 smoke snapshot совпадает с существующим собранным артефактом:
`bcc82542d0faf8ef1a6e18d22a2113683b8e17ad4c0fb1c5dee83202f581c7db`.
После паузы исходный PTY handle уже недоступен; нулевой Gradle exit восстановлен из сохранённого
успешного терминального результата, а не получен повторным wait. XML подсчитан после проверки
окончательного BUILD SUCCESSFUL, не во время исполнения. Активных Java-процессов при возобновлении
не обнаружено. Эта особенность происхождения результата явно сохранена в summary; новый прогон
для получения свежего handle не запускался, поскольку исходники и успешный итог не изменились.

Блокировка29.1 по12.3 снята в проверенных сценариях: разрешённые upload/delete сериализуются,
отзыв role/is_agent приводит к точному штатному отказу без частичной записи и без deadlock.
Безопасные отличия legacy/modern, включая унаследованную Unicode-политику22, остаются записанными
для32. Полный переход всего приложения на modern и соседние задачи здесь не объявляются готовыми.

Финальная интеграция29.1 завершена. Собственные18 файлов сохранены; tracked media/identity existing/
shared/Java/Flyway/план не редактировались относительно принятой базы d8e2982. Commit/push не
выполнялись. Работа остановлена с этим отчётом перед отдельно назначаемым следующим аудитом/публикацией.
Команды настоящего сжатия и требуемых context counters в текущей сессии нет; сжатие не выполнялось,
этот отчёт не называется сжатием.

## Отдельное ревью полного текущего diff после12.3 — 09.11.2026

База/HEAD: `d8e2982e174796ad0a7fbfae762e85d3f88dcbc3`, ветка
`codex/migration-29-1-agent-products`, дерево5936. Повторно прочитаны все новые production файлы,
собственные HTTP/lock/transaction tests и передача scope в MIGRATION_PLAN29.1/12.3; сопоставлены
вызовы принятых13/04.5/16/22/12.1/12.3 и actual legacy. Новых production-исправлений в переданных
файлах не потребовалось. Исправления прежнего аудита (реальный JDBC resource и Java blank URL)
сохранены. Найдено отдельное унаследованное ограничение существующего маршрута16 ниже.

### Историческая находка на d8e2982: singular status16 и отзыв ADMIN, до16.2

Следующие доказательства и предложение относятся к состоянию **до** принятия16.2.
Координатор выделил исправление отдельному исполнителю; закрытие риска на текущей базе68247f3
и замена собственного repro на403 описаны в последнем разделе. Положительный repro200 больше
не является текущим ожиданием тестов29.1.

**Подтверждённый риск отзыва полномочий:** текущие команды29.1 проверяют ADMIN в БД, однако
принятый `PUT /admin/actions/product/{id}?productStatus=…` из16 допускает действующий, правильно
подписанный ADMIN access JWT после изменения роли его владельца на USER. Через него меняется
и статус agent-owned product. Это поведение уже есть в базе d8e2982, его не вводит новый29.1.
Это не случай подделанной подписи: токен выдан до отзыва, срок ещё не истёк.

Точные существующие места:

* `src/main/kotlin/com/model_store/modern/catalog/product/command/api/ProductCommandController.kt`,
  AdminProductStatusController.status, строки106–110: проверка Actor claims без current DB role.
* `src/main/kotlin/com/model_store/modern/catalog/product/command/application/ProductCommands.kt`,
  changeStatus, строка123: команда не получает caller ID, берёт product lock и записывает статус.

Новый собственный real HTTP repro
`AgentProductModernHttpTest.inherited singular status route trusts stale admin JWT unlike agent commands`
делает ADMIN fixture/token → agent-owned product → role USER в БД → проверяет403/ACCESS_DENIED
у новых update/extend29.1 → тот же token на singular status16 получает200, product становится BLOCKED.
Repro стабилен, без гонки и без искусственного mock авторизации. Это **успешное доказательство
унаследованного ограничения**, а не его устранение и не зелёная гарантия общей политики отзыва.

Существующие файлы16 не переданы и не изменены. Координатору нужно отдельно решить политику:
явно принять JWT-until-expiry совместимость старого status-route для32 либо передать точечное
исправление этих двух файлов вместе с owner-side identity lock текущего ADMIN. Для исправления
статус-команда должна получать verified caller ID, авторизоваться в той же writable JPA-транзакции,
брать caller/реального owner participant в согласованном ASC порядке до product/image, повторно
проверять owner под product lock. AgentAccessLock04.5 подходит только для agent target; обычного
owner нельзя выдавать за агента или обходить проверку. Новый identity owner-порт при необходимости
также требует отдельной передачи. SQL participant из catalog и чтение AuthAccounts не предлагаются.
До такого решения **нельзя утверждать, что все существующие status-entrypoints агентского товара
защищены текущей DB ролью**, хотя шесть новых маршрутов29.1 обеспечивают заявленную свою политику.

### Остальные результаты ревью

* В новых шести маршрутах обхода ADMIN ACCESS/Verify13 не найдено. Agent access отвергается на
  admin endpoints даже с ADMIN claim; normal ACCESS самого агента не подходит для agent create.
  Current ACTIVE/is_agent после ожидания проверяет ActiveAgentLock; текущего nonagent ADMIN
  и exact target-agent проверяет04.5, включая refresh устаревшего JPA snapshot у identity owner.
* JdbcActiveAgentLock требует MANDATORY writable JPA и совпадения JDBC ConnectionHolder с
  ресурсом объявленного DataSource при joined EMF. Его JDBC row read не полагается на identity map.
  Caller/target status правила не перепутаны: blocked/deleted target agent допускается admin,
  blocked/deleted caller и agent create — нет.
* IDOR новых update/extend закрыт exact `product.ownerId == path agentId` после product lock.
  Body id/owner/role/status отсутствуют в patch или игнорируются API DTO. Plural details читает
  любой product только для текущего ADMIN — это сохранённый legacy контракт, не публичный IDOR.
* Runtime SQL в новых reader/identity файлах статичен и параметризован. Записи product/categories
  выполняет принятый catalog store, image — media12.1; participant SQL writes из seller/catalog нет.
  AuthAccounts/password/mail, JPA entities и чужие domain aggregate не передаются наружу.
  Typed AgentTokenFailure13 не содержит auth/entity payload; API error не раскрывает SQL/dbMessage.
* Domain не зависит от Spring/JPA/HTTP. Публичный owner-порт принимает скалярные application DTO,
  существующий generic create/update не вызывается с подставленным caller для обхода eligibility.
  Каталог сохраняет собственное право записи и не меняет product owner.
* После12.3 lock hierarchy совместим: participant IDs ASC → dictionary при subtype update → product
  → images; create имеет одного participant/новый product, extend только nongiveaway product.
  Три новые проверки используют настоящие owner/media порты, наблюдают конкретный blocker PID,
  требуют успеха обеих разрешённых операций или точного ImageCommandDenied после revoke. Нет
  старого положительного assert40P01, skip или catch с игнорированием результата. DB assertions
  проверяют owner/association/uploader/status и отсутствие частичных запрещённых media writes.
* Atomic rollback product/settings/categories/media сохраняется: одна JPA boundary, joined media,
  без собственного commit/S3. Проверены late FK и outer rollback, конкурирующие patch/status,
  claims, reservation и schedule settings; переход в GIVEAWAY использует общий dictionary lock,
  actual-orders и exclusive-endpoint overlap22. Target/owner не меняются между проверкой и записью.
* Сверены create/update/extend/directory JSON/null/error формы, отсутствие transfer/social условия
  у agent create, historical stock/partial fields, Java Unicode blank внешнего URL. Для32 остаются
  ранее перечисленные безопасные token/DB/media/nonfinite и унаследованные Unicode22 различия.

### Изменения, проверки и остановка

На этом ревью изменены только собственный `AgentProductModernHttpTest.kt` (добавлен один repro)
и отчёт. Production, test harness и остальные16 собственных файлов совпадают с manifest
проверенного интеграционного состояния; артефакт JAR имеет тот же SHA
`bcc82542d0faf8ef1a6e18d22a2113683b8e17ad4c0fb1c5dee83202f581c7db`.

Новый целевой прогон09.11: `test --tests '*AgentProductModernHttpTest.inherited singular status*'`
с test RSA/mock SMTP/S3/отдельной PG, exit0 **наблюдён непосредственно**,1 тест,0 failures/errors/skipped.
Лог `build/migration-29-1-post-integration-review-repro.log`, summary
`build/migration-29-1-post-integration-review-summary.json`, XML отдельно сохранён в
`build/migration-29-1-post-integration-review-results/`. Неизменный успешный прогон177/30 и actual
dual-mode JAR не перезапускался. Это177 ранее выполненных проверок и один новый отдельный repro,
а не утверждение единого прогона178. Предыдущий integration manifest сохранён историческим;
текущие hashes — `build/migration-29-1-post-integration-review-files.json`.

Полный список18 собственных новых файлов текущего diff (от корня репозитория):

1. `MIGRATION_29_1_REPORT.md`
2. `src/main/kotlin/com/model_store/modern/catalog/product/agent/application/AgentProducts.kt`
3. `src/main/kotlin/com/model_store/modern/catalog/product/agent/application/CatalogAgentProducts.kt`
4. `src/main/kotlin/com/model_store/modern/catalog/product/agent/domain/AgentProductPolicy.kt`
5. `src/main/kotlin/com/model_store/modern/catalog/product/agent/infrastructure/JdbcAgentProductReadStore.kt`
6. `src/main/kotlin/com/model_store/modern/identity/agent/application/ActiveAgentLock.kt`
7. `src/main/kotlin/com/model_store/modern/identity/agent/infrastructure/JdbcActiveAgentLock.kt`
8. `src/main/kotlin/com/model_store/modern/identity/participant/application/AgentDirectoryQuery.kt`
9. `src/main/kotlin/com/model_store/modern/identity/participant/infrastructure/JdbcAgentDirectoryQuery.kt`
10. `src/main/kotlin/com/model_store/modern/seller/agent/product/api/AgentProductController.kt`
11. `src/main/kotlin/com/model_store/modern/seller/agent/product/api/AgentProductErrorHandler.kt`
12. `src/main/kotlin/com/model_store/modern/seller/agent/product/application/AgentProductActions.kt`
13. `src/main/kotlin/com/model_store/modern/seller/agent/profile/api/AgentDirectoryController.kt`
14. `src/test/kotlin/com/model_store/modern/catalog/product/agent/AgentProductTransactionsTest.kt`
15. `src/test/kotlin/com/model_store/modern/identity/agent/ActiveAgentLockIntegrationTest.kt`
16. `src/test/kotlin/com/model_store/modern/seller/agent/product/AgentProductHttpContract.kt`
17. `src/test/kotlin/com/model_store/modern/seller/agent/product/AgentProductLegacyHttpTest.kt`
18. `src/test/kotlin/com/model_store/modern/seller/agent/product/AgentProductModernHttpTest.kt`

Итог: новых требующих исправления дефектов в переданных production-файлах не найдено; внешний
status16/revocation риск подтверждён и оставлен координатору с конкретным repro/предложением.
Существующие13/media/shared/Java/reactive/Flyway/план сохранены. Ревью завершено с этим ограничением,
работа остановлена; commit/push не выполнялись.

## Адаптация к принятым16.2 и25 — 09.11.2026

По отдельному поручению ветка безопасно fast-forward обновлена с d8e2982 до точной local master
`68247f3df439923a431300e7c39c175357e8d225`. В истории этой базы проверены ancestors:
16.2 `8a989e588da981d85b6823274b6a5916cdb35f42` и25 `e24ac52424bf3be801849b0db2a3cd87ac52b647`.
Origin/master не использовалась. До merge сохранены SHA-256 всех18 собственных файлов в
`build/migration-29-1-before-16-2-files.json`, проверено отсутствие пересечений с21 входящим путём.
После `--ff-only` все18 hashes совпали. Конфликтов, stash/reset, удаления или потери работы не было.

В переданном scope изменены только два собственных теста и этот отчёт:

* `AgentProductModernHttpTest`: исторический positive repro заменён на
  `singular status route rejects stale admin JWT without changing agent product`. С тем же
  настоящим ADMIN access JWT после role→USER update/extend29.1 и singular status16.2 обязаны
  вернуть403/ACCESS_DENIED. Полная строка product сравнивается до/после всех трёх отказов,
  проверка не ограничивается только status.
* `AgentProductTransactionsTest`: вызов принятого changeStatus адаптирован к новой сигнатуре
  `(productId, status, callerId)`, передаётся настоящий ACTIVE nonagent ADMIN fixture ID.
  Тест сохраняет смысл: разрешённая status-команда и agent patch сериализуются, committed
  BLOCKED status и второе изменённое поле не теряются. Подстановка target agent вместо caller
  не используется.

Production29.1 после предыдущего аудита не менялся. Принятые production16.2/25, существующие
identity/shared/media/Java/reactive/Flyway/план не редактировались относительно новой базы.
CurrentAdminLock16.2 авторизует caller для статуса любого product и не выдаёт обычного owner за
агента; новые update/extend29.1 по-прежнему отдельно используют AgentAccessLock04.5 для target.

Собственные проверки завершились наблюдённым exit0: **53 теста в4 классах**,0 failures/errors/skipped.
Это17 modern HTTP tests,16 legacy HTTP tests,13 catalog transaction tests и7 ActiveAgentLock tests.
Лог `build/migration-29-1-16-2-own.log`, summary
`build/migration-29-1-16-2-own-summary.json`; завершённые XML отдельно сохранены в
`build/migration-29-1-16-2-own-results/`. Компиляция всего test Kotlin также прошла с новой сигнатурой.
В частности подтверждены исправленный stale ADMIN403, directory/current authorization,
Unicode external URL, eligibility/null/partial update, GIVEAWAY/overlap, reservation/claims,
поздний и внешний rollback, media serialization и отказ после revoke без40P01.

Связанный отдельный прогон на этой базе успешно завершился:16.2 authorization и packaged-JAR тест,
каталог16/16.1/22, identity13/04.5/04.4, media command12.1/12.2/12.3, lifecycle25 legacy/modern,
общий actual dual-mode JAR. Собственный успешный набор53 повторно не запускается.
RUN_BOOT_JAR_SMOKE=true, новый bootJar собран перед тестами; оба smoke использовали только
изолированные PG и loopback SMTP/S3 с fixture RSA.

**141 тест в22 классах,0 failures/errors/skipped**, `BUILD SUCCESSFUL in 9m 7s`.
С собственным непересекающимся набором53/4 это194 проверки в26 классах **в двух отдельных прогонах**,
не один общий запуск194. После успешного own-прогона код не менялся,53 теста повторно не запускались.
Связанный процесс закончился во время usage-limit остановки. После возобновления восстановлен
успех по окончательному Gradle log и полностью записанным XML. Старый PTY handle уже истёк,
поэтому exit0 связанного Gradle восстановлен по BUILD SUCCESSFUL, а не получен повторным wait;
происхождение явно отражено в summary. Наблюдённый own exit0 сохранён отдельно. Повторный прогон
неизменённого успешного кода для получения нового handle не выполнялся.

Сохранённые доказательства текущего состояния:

* own log/summary: `build/migration-29-1-16-2-own.log`, `build/migration-29-1-16-2-own-summary.json`;
  завершённые XML — `build/migration-29-1-16-2-own-results/`.
* adjacent log/summary: `build/migration-29-1-16-2-adjacent.log`,
  `build/migration-29-1-16-2-adjacent-summary.json`; завершённые XML —
  `build/migration-29-1-16-2-adjacent-results/`.
* В adjacent XML ровно по одному выполненному case у `BootJarDualModeSmokeTest` и
  `ProductStatusBootJarTest`, без skip. Каждый реально запускает frozen JAR в legacy и modern;
  оба вывели SHA-256, совпадающий друг с другом и с сохранённым артефактом.
* `build/migration-29-1-16-2-source.json` — проверенная база, branch и hashes всех17 собственных
  Kotlin файлов. Aggregate source SHA вычислен из отсортированных repository paths + TAB +
  lowercase file SHA, соединённых LF без финального LF; report исключён из aggregate.
  Полный snapshot18 собственных файлов после завершения отчёта —
  `build/migration-29-1-16-2-files.json`.

Точные hashes:

* own source aggregate SHA-256:
  `8afa123a7bd955be424cba2ba5176665b3cfdd49406c0298dcc191ba8a883687`;
* текущий bootJar / оба smoke snapshots SHA-256:
  `cb90bfae0030cb5e5fe29a0e09f039c0f30b1f9c86381e0b5bc99ed9964f4b2c`.

Относительно pre-16.2 manifest изменились ровно собственные ModernHttpTest, TransactionsTest и
этот report. Все12 собственных production файлов и остальные test файлы сохранены без изменений.
Полный список18 файлов остаётся тем, что приведён в разделе отдельного ревью09.11 выше; новых
файлов scope не добавлено. На момент восстановления local master `2b008072726462b6a93cf6f165bc2e69bd86de24`
отличалась от проверенной68247f3 только MIGRATION_PLAN.md; ветка29.1 остаётся на точной проверенной
runtime-базе68247f3. Непринятые26/28 в неё не включались, чужие worktrees не затрагивались.

В modern подтверждён закрытый stale ADMIN риск16.2:403/ACCESS_DENIED и неизменность product.
Legacy Java/reactive сохраняет прежний rollback-контракт; packaged status-тест явно проверяет
legacy200 против modern403 после revoke. Это не заявление об исправлении старого Java API.
Для32 остаются ранее записанные безопасные JWT/DB/media/nonfinite/Unicode22 отличия. Бывшие
успешные40P01 и status200 repro теперь только исторические доказательства в отчёте/старых логах;
текущие тесты29.1 требуют исправленной сериализации и modern403, а не повторения дефекта.

На этом этапе никаких commit/push: после отчёта ожидаются отдельно подтверждение сжатия/
недоступности, свежий самостоятельный аудит и отдельная команда локального commit.
Реализация/адаптация на68247f3 завершена, работа остановлена с этим отчётом. Запрет пользователя
на любые push, включая master и codex branches, действует. План/пользовательские файлы не редактировались.

## Свежий самостоятельный аудит на68247f3 — 09.11.2026

Отдельный этап после адаптации16.2/25, по новому поручению. HEAD остаётся
`68247f3df439923a431300e7c39c175357e8d225`, ветка `codex/migration-29-1-agent-products`.
Заново прочитаны все18 собственных файлов, actual legacy controllers/services/mapper/DTO,
принятые identity13/04.5/16.2, product store16, giveaway22 и media12.1/12.3 контракты.
Это самостоятельная сверка исходников, не только повторение предыдущего отчёта.

### Находки и исправления

Новых подтверждённых дефектов production29.1 не найдено. Все12 собственных production файлов
остались побайтно прежними. Найдены и исправлены два пробела достоверности собственных тестов:

* `AgentProductTransactionsTest`: foreign-owner image claim, проигравший concurrent claim и
  ordinary-agent/foreign-target update принимали любой ProductCommandFailure. Теперь требуют
  соответственно FORBIDDEN/ACCESS_DENIED, CONFLICT/INVALID_REQUEST, FORBIDDEN/ACCESS_DENIED и
  NOT_FOUND/PRODUCT_NOT_FOUND. Посторонняя typed ошибка больше не маскирует ожидаемый исход.
* Там же шесть прежних гонок claims/partial/revoke/dictionary/reservation/status, а также три
  сценария удержания/ожидания в `ActiveAgentLockIntegrationTest` наблюдали лишь Lock wait.
  Теперь каждый передаёт PID конкретного блокирующего backend и требует
  `blocker = ANY(pg_blocking_pids(waiter))`. Необязательного blocker/fallback по wait_event_type
  в этих helpers больше нет. Три media-теста после12.3 уже проверяли точный PID и сохранены.

Ожидаемые исходы media остаются строгими: обе разрешённые операции обязаны успешно завершиться;
после отзыва допускается только ImageCommandDenied с отсутствием запрещённых записей. Нет
положительного assert40P01, skip, assumptions или проглатывания ошибки. Futures/gates конечны.

### Сверка прав, границ и данных

* Шесть mappings и DTO сверены с Java. Create получает только agent-access principal через Verify13,
  затем текущий ACTIVE/is_agent под identity lock; normal ACCESS агента не подходит. Admin routes
  требуют normal ACCESS ADMIN и текущего ACTIVE nonagent ADMIN. BLOCKED/DELETED target-agent
  по-прежнему допускается; caller с такими статусами — нет. Body не задаёт owner/role/status.
* Update/extend проверяют exact owner после product lock; SQL update не меняет participant_id.
  Административный plural GET любого product, включая ordinary/DELETED, остаётся legacy контрактом.
  Read authorization проверяется при чтении; удержание прав до конца операции доказывается для
  команд, а не объявляется отдельной гарантией для read-only ответов.
* ActiveAgentLock по-прежнему MANDATORY writable caller-JPA, joined EMF и реальный ConnectionHolder
  того же DataSource, что JDBC template. No-tx/wrong/readOnly/foreign EMF/foreign DS/miswired JDBC
  проверки сохранены. Fresh JDBC tuple после ожидания исключает stale JPA authorization.
* Порядок команд: participant IDs ASC → dictionary при update → product → image NOWAIT.
  Media12.3 собирает участников до lock frontier, блокирует ASC и повторно проверяет owner/права.
  Status16.2 берёт только текущего caller до product, не требует agent-статус обычного owner и
  не берёт дополнительных participant locks после product. Real HTTP29.1 требует403/ACCESS_DENIED
  после ADMIN→USER и неизменность всей product row; старое status200 — только исторический repro.
* Seller не пишет SQL, не читает AuthAccounts и не передаёт JPA/domain aggregate через границу.
  Catalog использует scalar application DTO и owner-порты; SQL чтений параметризован. Domain новой
  политики не зависит от Spring/JPA/HTTP. Ошибки не раскрывают SQL/dbMessage.
* Product/settings/media/categories сохраняют общую JPA boundary и rollback при поздней FK либо
  outer failure. GIVEAWAY переиспользует22: dictionary, active orders, overlap с допустимой
  смежностью, enable/disable/enter/leave и timezone. Partial/null/[] stock/image/category правила,
  22 raw response поля, Java blank external URL и отсутствие transfer/social eligibility сохранены.

### Проверки и происхождение доказательств

Повторно разобраны завершённые архивные XML:53 own/4 класса и141 adjacent/22 класса,
failures/errors/skipped=0. Неизменённые успешные HTTP/adjacent/JAR наборы не перезапускались.
После уточнения тестов выполнены только два целевых прогона с fixture RSA, mock SMTP/S3 и
собственной embedded PostgreSQL:

* `test --tests '*AgentProductTransactionsTest'`:13 тестов,0 failures/errors/skipped,
  **exit0 наблюдён непосредственно** в завершённой сессии87034; BUILD SUCCESSFUL in45s.
  Log/summary — `build/migration-29-1-fresh-audit-transactions.log` и
  `build/migration-29-1-fresh-audit-transactions-summary.json`; XML — одноимённый `-results/`.
* `test --tests '*ActiveAgentLockIntegrationTest'`:7 тестов,0 failures/errors/skipped,
  **exit0 наблюдён непосредственно** в завершённой сессии65167; BUILD SUCCESSFUL in36s.
  Log/summary — `build/migration-29-1-fresh-audit-active-lock.log` и
  `build/migration-29-1-fresh-audit-active-lock-summary.json`; XML — одноимённый `-results/`.

Эти20 проверок повторяют соответствующие20 из53; к194 они не прибавляются как новые тесты.
XML каждого нового прогона архивирован после наблюдённого exit0, до следующего test task.
У прежнего adjacent141 exit0 по-прежнему **восстановлен** по сохранённому BUILD SUCCESSFUL in9m7s:
исходный PTY истёк после interruption. Это не новый наблюдённый exit, provenance не переписан.

Проверен aggregate исторического manifest17 Kotlin sources:
`8afa123a7bd955be424cba2ba5176665b3cfdd49406c0298dcc191ba8a883687`.
Текущий manifest `build/migration-29-1-fresh-audit-source.json` фиксирует те же17 paths и новый
aggregate после двух test-only изменений:
`334b650e7f10099ed236cf104a58c99d1acfad916b8c0a0b67c13a1ce03f23c1`.
Алгоритм прежний: sorted repository path + TAB + lowercase SHA, LF join без final LF, UTF-8.
Report не входит в source aggregate. Snapshot18 файлов после отчёта —
`build/migration-29-1-fresh-audit-files.json`; исторические manifests сохранены.

SHA существующего bootJar повторно вычислен и совпал с SHA в каждом из двух архивных actual
smoke XML (`BootJarDualModeSmokeTest`, `ProductStatusBootJarTest`, по1 case,0 skip):
`cb90bfae0030cb5e5fe29a0e09f039c0f30b1f9c86381e0b5bc99ed9964f4b2c`.
Оба теста действительно запускают frozen JAR в legacy и modern; заново просмотрен их механизм.
Этот JAR соответствует прежним production sources, которые не менялись. Новый aggregate включает
только уточнения тестов; новый build/actual smoke на этом этапе не заявляется.

### Полный состав18 собственных файлов

1. `MIGRATION_29_1_REPORT.md`
2. `src/main/kotlin/com/model_store/modern/catalog/product/agent/application/AgentProducts.kt`
3. `src/main/kotlin/com/model_store/modern/catalog/product/agent/application/CatalogAgentProducts.kt`
4. `src/main/kotlin/com/model_store/modern/catalog/product/agent/domain/AgentProductPolicy.kt`
5. `src/main/kotlin/com/model_store/modern/catalog/product/agent/infrastructure/JdbcAgentProductReadStore.kt`
6. `src/main/kotlin/com/model_store/modern/identity/agent/application/ActiveAgentLock.kt`
7. `src/main/kotlin/com/model_store/modern/identity/agent/infrastructure/JdbcActiveAgentLock.kt`
8. `src/main/kotlin/com/model_store/modern/identity/participant/application/AgentDirectoryQuery.kt`
9. `src/main/kotlin/com/model_store/modern/identity/participant/infrastructure/JdbcAgentDirectoryQuery.kt`
10. `src/main/kotlin/com/model_store/modern/seller/agent/product/api/AgentProductController.kt`
11. `src/main/kotlin/com/model_store/modern/seller/agent/product/api/AgentProductErrorHandler.kt`
12. `src/main/kotlin/com/model_store/modern/seller/agent/product/application/AgentProductActions.kt`
13. `src/main/kotlin/com/model_store/modern/seller/agent/profile/api/AgentDirectoryController.kt`
14. `src/test/kotlin/com/model_store/modern/catalog/product/agent/AgentProductTransactionsTest.kt`
15. `src/test/kotlin/com/model_store/modern/identity/agent/ActiveAgentLockIntegrationTest.kt`
16. `src/test/kotlin/com/model_store/modern/seller/agent/product/AgentProductHttpContract.kt`
17. `src/test/kotlin/com/model_store/modern/seller/agent/product/AgentProductLegacyHttpTest.kt`
18. `src/test/kotlin/com/model_store/modern/seller/agent/product/AgentProductModernHttpTest.kt`

Ограничения: legacy status200 после отзыва сохранён как rollback-контракт; безопасные JWT/DB/media/
nonfinite/Unicode22 различия требуют общего сопоставления32. Проверенные гонки не доказывают
отсутствие всех возможных гонок всего приложения. Задачи26/28/30/31/32 и общая приёмка не входят
в этот аудит. Существующие identity/media/shared/Java/Flyway/план не редактировались.

Свежий аудит завершён. Новых файлов сверх согласованных18 нет, tracked diff пуст.
Изменения этапа — только два собственных test файла и этот отчёт. Commit/staging/push не выполнялись.
Работа остановлена; локальный commit возможен только по отдельной команде, любые push запрещены.
