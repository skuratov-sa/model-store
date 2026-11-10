# 30 — планировщики и фоновые действия

**История этапа реализации.** После отдельно запрошенного этапа недоступности
сжатия координатор поручил новый самостоятельный аудит. Его исправления, текущие
source/JAR hashes и окончательная проверка описаны в `MIGRATION_30_AUDIT_REPORT.md`.
Приведённые ниже148 результатов и hashes относятся к исходному завершённому этапу,
а не к последующим audit sources. Полный scope теперь включает также отдельный
audit report:17 code files и2 отчёта, все новые относительно принятой базы.

Этап реализации, 10.11.2026. Локальная ветка `codex/migration-30-scheduling`,
managed worktree `2bc5/model-store`. Исходное дерево было clean, detached
`63e3e51d3d0742303e3386998d7774dd8ed4691e`; новая ветка создана непосредственно от
принятой **local master `5add9e56e8bd8f39236286794bcfc8ed02a05599`**.
Ни master, ни remotes не изменялись. Commit/push/fetch/pull/deploy не выполнялись.
Субагенты не запускались. Самостоятельный последующий аудит и commit ожидают
отдельных поручений координатора; этот отчёт не объявляет их выполненными.

## Фактический inventory до переноса

Прочитаны общий план; отчёты 12.3, 16.2, 22, 25, 26/28 в принятой базе,
29.1; Java schedulers, ImageServiceImpl, ProductRepository, S3ServiceImpl;
runtime filter/config; принятые media command/attachment/evidence,
product command/reservation и ordering creation/lifecycle. Проверена действующая
последовательность Flyway, а не только начальная V1.0.

| Legacy schedule | Действие и действующая схема | Modern owner/порт | Runtime |
| --- | --- | --- | --- |
| `ProductUpdateScheduler.expireProducts`, `0 */5 * * * *` | Один UPDATE product: только ACTIVE, availability <> GIVEAWAY, expiration_date <= CURRENT_TIMESTAMP → TIME_EXPIRED. Не переключает даты/статусы розыгрышей | catalog `ProductMaintenance.expireDueOrdinary` | Только новый в modern, только Java в default/legacy |
| `ImageCleanupScheduler.cleanUpTemporaryImages`, `0 0 0 * * *` | UPDATE image → DELETE для TEMPORARY возрастом >=24h и ACTIVE PRODUCT у DELETED product; SELECT DELETE возрастом >=24h либо любой PRODUCT у DELETED product; S3 original/medium/thumbnail, затем DELETE image только при status DELETE. Ошибка отдельного изображения логируется, остальные продолжаются | media `ImageMaintenance`, scalar catalog `DeletedProductForImageCleanup`, ordering `ImageOrderMaintenance`, identity `MaintenanceParticipantLocks` | То же разделение |
| `ProductCleanupScheduler.cleanUpDeletedProducts`, `0 0 2 * * *` | Batch200 DELETED product без любого PRODUCT image, FOR UPDATE SKIP LOCKED; DELETE product_favorite/product_basket/product_cart, затем guarded DELETE product в одной R2DBC tx на batch; repeat до выбранных <200 | catalog `ProductMaintenance.cleanupDeletedBatch`, read-only media `ImageProductReferences` и ordering `ProductOrderReferences` | То же разделение |

Все три cron оставляют `zone` пустым, как Java: используется зона scheduling/JVM,
**не принудительно Europe/Moscow/UTC**. PG сравнивает timestamptz с transaction
CURRENT_TIMESTAMP, интервал 24 hours сохранён. Зона JVM задаёт момент midnight/02:00;
зона сессии PostgreSQL влияет на отображение, не на сравнение абсолютных дат.
Команды не ждут полуночного cron в тестах, их вызывают напрямую.
Существующая функция `current_utc_timestamp()` возвращает `CURRENT_TIMESTAMP AT
TIME ZONE 'UTC'` как timestamptz, с неявной обратной конверсией в зоне сессии.
Это может влиять на исторический default created_at при не-UTC сессии; функция и
старые значения здесь не исправляются. Очистка использует фактически сохранённый
created_at, как Java; тестовые границы задаются явными timestamptz.

## Схема и намеренные безопасные ограничения для 32

V1.21 снимает `order.product_id` и `review.product_id` FK, оставляя исторические
числовые IDs и снимок товара в заказе. Поэтому первоначальный FK из V1.0 **не
защищает** удаление заказанного товара. Modern явно сохраняет товар при наличии
любой фактической строки order по product_id, включая terminal/cancelled статусы.
Поля order/review/history не записываются. Это согласованное защитное отличие
от legacy physical cleanup; откат в legacy возвращает прежнее поведение.

Modern также сохраняет GIVEAWAY и любой product с giveaway_start_at/end_at,
чтобы не терять историю розыгрышей. Expiry по-прежнему не использует giveaway даты.
В legacy image cleanup мог удалить S3 для TEMPORARY PRODUCT у DELETED товара,
не пометив image DELETE, после чего conditional row delete оставлял запись.
Modern сначала фиксирует DELETE для каждого допустимого кандидата, исправляя эту
небезопасную границу. Активные ORDER evidence никогда не становятся кандидатом
только из-за возраста. Даже повреждённые TEMPORARY/DELETE с actual payment proof
или order_case_image ссылкой сохраняются до S3.

Реальные оставшиеся FK: product_favorite/basket/cart → product (NO ACTION),
product_category → product (CASCADE); product → participant;
image.uploaded_by → participant; order_case_image.image_id → image.
У image.entity_id полиморфной FK нет; order.image_payment_proof_id также без FK.
Триггеры order status/history и review rating не менялись. Product cleanup
явно удаляет только свои зависимости, после свежих положительных проверок.
FK-ошибка откатывает один product целиком, включая favorite/basket/cart/category,
логируется и не останавливает следующий ID. Order/review FK как safeguard не используются.

## Только новые согласованные файлы

Все production paths ниже относительно `src/main/kotlin/com/model_store/modern/`:

- `shared/scheduling/infrastructure/ModernSchedulingConfiguration.kt` — modern-only EnableScheduling.
- `shared/scheduling/infrastructure/MaintenanceSchedulers.kt` — cron, keyset paging, logging/continuation; без business SQL/entities.
- `catalog/product/maintenance/application/ProductMaintenance.kt` — commands и scalar batch cursor/counts.
- `catalog/product/maintenance/infrastructure/JdbcProductMaintenance.kt` — owner product/dependents writes и JPA tx.
- `catalog/product/maintenance/application/DeletedProductForImageCleanup.kt` — scalar owner/deleted snapshot и product lock.
- `catalog/product/maintenance/infrastructure/JdbcDeletedProductForImageCleanup.kt` — отдельный bean без media dependency.
- `media/image/maintenance/application/ImageMaintenance.kt` — candidate IDs, per-ID cleanup outcome, scalar storage port.
- `media/image/maintenance/infrastructure/JdbcImageMaintenance.kt` — image owner writes, two-phase tombstone/purge; отдельный bounded S3 cleanup client в этом же новом файле.
- `media/image/maintenance/application/ImageProductReferences.kt` и `infrastructure/JdbcImageProductReferences.kt` — read-only наличие image любого статуса для product ID.
- `ordering/maintenance/application/ProductOrderReferences.kt` и `infrastructure/JdbcProductOrderReferences.kt` — read-only actual orders, scalar order observation/lock и payment/case evidence references.
- `identity/participant/maintenance/application/MaintenanceParticipantLocks.kt` и `infrastructure/JdbcMaintenanceParticipantLocks.kt` — ID-only locks, без authorization policy.

Новые собственные тесты: `src/test/kotlin/com/model_store/modern/shared/scheduling/`
`MaintenanceIntegrationTest.kt`, `SchedulingRuntimeIsolationTest.kt`,
`SchedulingBootJarTest.kt`. Отчёт: этот файл. Existing owner packages/files,
Java/reactive, LegacyRuntimeConfiguration, общие Gradle/security/Jackson,
Flyway, MIGRATION_PLAN.md, docker-compose.yml, plan_2.md не изменялись.

DI граф не цикличен: catalog maintenance вызывает только отдельный media references
bean; media maintenance вызывает отдельный catalog product-lock bean, который не
зависит от media. Нет передачи foreign domain/JPA aggregates. Все owner контракты
содержат только ID/scalars; application data classes/interfaces без Spring.

## Транзакции, блокировки, сбои

Фоновые commands требуют отсутствие ambient transaction, иначе отказывают до
мутации/S3. Они явно используют qualifier `transactionManager` JPA, без R2DBC
и без REQUIRES_NEW. Межконтекстные locks/reference reads требуют writable actual
transaction, joined EntityManager ожидаемой EMF и bound ожидаемый DataSource;
ошибка границы имеет отдельный application type, не превращается repository
exception translation в неясный SQL failure. Discovery reads не дают разрешения.

Каждый product deletion: discover owner → lock participant → lock product →
сверить owner/status/availability/dates → media references и actual orders →
зависимости → product. Удаление каждого ID — отдельная tx; keyset по исходному
выбранному ID двигается также через skipped/failed, поэтому 200 защищённых строк
не создают бесконечный repeat. Limit <=200. Lock timeout 10s, transaction timeout30s.
Expiry имеет только product writes без последующего participant/order/image lock,
поэтому обратного product→participant edge не вводит.

Image: discover tag/entity/uploader/filename и product owner либо order parties/status.
Полный distinct набор participant IDs (uploader, PARTICIPANT entity, product owner,
ORDER seller/customer) блокируется отдельными statements ASC. Отсутствующий ID →
skip/conflict всего элемента до order/product/image. Затем order либо product,
затем image. Связи/owner/parties/tag/entity/uploader/filename/status перечитываются;
при изменении — typed CONFLICT без расширения набора participant locks. Нет обхода
принятых 12.3/25 или следования новому владельцу после image lock.

1. Короткая JPA tx проверяет eligibility/actual evidence references и фиксирует DELETE.
2. Только после успешного возврата commit начинается новая JPA tx с тем же frozen
   participant/association plan и fresh locks/recheck. Удерживая эти locks, удаляются
   все три S3 варианта и затем строка image. Повторное изменение association → conflict.

S3 client имеет connection2s/socket5s/request10s/execution15s, maxErrorRetry1 на
вызов; варианты последовательные. Другой принятый S3 client не менялся. Image tx
timeout90s, SQL statement30s и lock10s. Малформатный basename не удаляется.
Partial/ambiguous S3 failure оставляет **уже committed DELETE**. Ошибка удаления
строки/commit после S3 также оставляет tombstone при rollback; повторяет удаление
уже отсутствующих keys. Crash после tombstone/между variants/после S3 до DB commit
имеет тот же путь повтора. Crash после DB commit оставляет отсутствующие row/objects.
DB и S3 не атомарны; exactly-once внешних вызовов не обещается. Ручное восстановление
объектов, потерянные до существования DB row uploads и storage retention находятся
за этим контрактом; существующее ограничение upload compensation12 сохраняется.

## Runtime и несколько процессов

ModernModeLegacyFilter уже исключает весь Java scheduler graph в modern.
LegacyRuntimeConfiguration уже включает scheduling только в !modern. Понадобилась
только новая modern-only конфигурация. Внутри одного процесса выбирается ровно один
набор из трёх cron; default legacy сохранён. Несколько modern cleaners могут выбрать
один ID: row/participant/product/order locks и fresh recheck сериализуют допустимые
эффекты; успешное повторное удаление не повторяет бизнес-запись. Ошибки оставляют
данные для следующего запуска. Distributed leader election не добавляется.

Принятое create24 сначала берёт обнаруженных ACTIVE/non-GIVEAWAY seller и customer
participants ASC, затем
ProductReservation16.1 product FOR UPDATE с ACTIVE/non-GIVEAWAY recheck, затем
INSERT order в той же JPA tx. Поддерживаемые media uploads/claims также берут product
перед image insertion/activation и отвергают DELETED/DELETE. Поэтому product lock
закрывает окно новых ссылок этих writers. Проверяются оба порядка с actual booking.

**Смешанные legacy+modern процессы, direct SQL и старые writers без общего lock
protocol не защищены одним @Profile.** Особенно нет FK для polymorphic image и
исторических order/review product IDs. Координатор/оператор обязан обеспечить один
набор schedulers на одном наборе данных и остановить старые workers при переключении.
Без такого deployment coordination задача не обещает межпроцессную эксклюзивность
или защиту от несогласованных legacy/directSQL inserts/reattach. Это согласованная
граница приёмки, не решённая распределённая блокировка.

## Проверки реализации до отдельного аудита

Финальная проверка завершилась **exit0**, непосредственно полученным от процесса
Gradle (не восстановленным по XML), `BUILD SUCCESSFUL in 11m 23s`:
**148 tests / 19 classes / 0 failures / 0 errors / 0 skipped**.
Из них собственные: 21 MaintenanceIntegrationTest + 1 runtime graph test + 1 actual
JAR dual-mode test = **23**. Связанная регрессия — **125**: media12.2/12.3,
ProductImageAttachment, catalog commands/reservation, giveaway22, agent product29.1,
ordering create24 и lifecycle25, включая legacy HTTP contracts.

Команда: `gradlew.bat --offline --no-daemon bootJar test` с фильтрами
`com.model_store.modern.shared.scheduling.*Test`,
`com.model_store.modern.media.image.command.MediaLockOrderIntegrationTest`,
`com.model_store.modern.media.image.command.ProductImageAttachmentTest`,
`com.model_store.modern.media.image.command.OrderImageEvidence*Test`,
`com.model_store.modern.catalog.product.command.ProductCommand*Test`,
`com.model_store.modern.catalog.product.reservation.ProductReservationTest`,
`com.model_store.modern.catalog.giveaway.admin.*Test`,
`com.model_store.modern.ordering.create.*Test`,
`com.model_store.modern.ordering.lifecycle.*HttpTest`,
`com.model_store.modern.catalog.product.agent.AgentProductTransactionsTest`.

JDK `C:/Users/skura/.jdks/temurin-21.0.11`, cache `C:/Users/skura/.gradle`.
JAVA_TOOL_OPTIONS содержал только
`-Dapp.public-key-path=keys/test_public_key.pem -Dapp.private-key-path=keys/test_private_key.pem`;
`RUN_BOOT_JAR_SMOKE=true`. Только приватные embedded PostgreSQL и loopback S3/SMTP,
fixture RSA, без production/Docker/production keys. Доступ к cache Gradle сначала
был запрещён sandbox; разрешённый повтор использовал тот же offline cache.

| Evidence под `build/migration-30/` | Содержимое |
| --- | --- |
| `final-verification.log`, `final-verification.exitcode` | Полный завершённый log и непосредственно наблюдённый exit0 |
| `final-results/*.xml`, `final-summary.json` | XML всех19 классов, скопированные/прочитанные только после завершения процесса |
| `final-source-manifest.json` | SHA-256 всех579 файлов src/main и src/test перед финальным запуском |
| `source-recheck.json` | После завершения проверены579 файлов: **0 mismatches** |
| `dependencies.json` | Все шесть проверок ancestor12/12.3/16/16.1/22/25 дали0 |
| `bootjar-legacy.log`, `bootjar-modern.log` | Actual JAR startup и TRACE registration schedulers в обоих режимах |
| `deliverables-sha256.json`, `verification-metadata.json` | Итоговые собственные hashes и проверенная база/artifact/counters |

Source manifest SHA-256:
`dd1e263b9d783def3cf518ed09aaa24351c70633c86250834f9ff4f01bf1e943`.
Один bootJar SHA-256:
`548cfd06555f9f158db94cfa07adfe7ca360c329de635c0e6a5c98a1da2febb8`.
Actual-JAR тест сделал приватную копию, сверял тот же hash после каждого режима,
очищал inherited environment, использовал конечные network/startup/process waits
и закрывал fixtures. В логах legacy зарегистрировано ровно по одному cron на
трёх Java beans; modern — ровно три cron одного MaintenanceSchedulers, Java beans
не зарегистрированы. TRACE показывает прежние cron и `zone=""`. Оба дочерних JVM
после проверок намеренно остановлены через Process.destroy и окончательно дожданы
(Windows exit1 от остановки сохранён в XML; это не startup/test failure).

Собственные проверки включают:

- absolute expiration с `+03:00`, PG session zone, inclusive SQL predicate,
  ACTIVE/non-GIVEAWAY recheck и повтор без дополнительных expiry writes;
- реальные orders BOOKED/CANCELLED/COMPLETED/FAILED, сохранение product/order/review
  IDs и favorite/basket/cart у защищённых товаров; GIVEAWAY/history/active/image guards;
- keyset через201 защищённый товар; поздний per-product SQL23503 с rollback
  зависимостей и успешным следующим ID;
- old TEMPORARY, young DELETE, ACTIVE PRODUCT/ORDER и DELETED product;
- committed tombstone виден отдельному соединению **до первого S3**;
  late S3 failure, late beforeCommit failure, повтор и отсутствующий row;
- реальный loopback S3: original удалён, medium500 с retry, thumbnail остаётся;
  следующий проход удаляет все keys и только затем DB row;
- scheduler manual invocation продолжает следующие IDs после сбоя и повторяет
  committed DELETE при следующем запуске; никакого ожидания cron;
- конкурирующие cleaners, оба направления uploader/owner ID ASC, смена owner,
  tag/entity/uploader/status и ORDER parties во время ожидания; не следовать новым IDs;
- accepted ProductImageAttachment выигрывает до cleanup, ACTIVE image сохраняется;
  actual CreateOrder.book выигрывает до product cleanup и сохраняет reference;
  actual cleanup выигрывает, ожидающий CreateOrder.book получает PRODUCT_NOT_FOUND
  без orphan/stock effects. Порядок устанавливается PG locks/pg_blocking_pids и
  test-only fixture trigger/advisory barrier, без sleeps для гонок;
- no-tx/read-only/JDBC-only owner guards, normal caller JPA, missing participant,
  запрет ambient tx до tombstone/S3.

Промежуточные отказы не скрыты: initial runtime test неверно приводил Spring7
OutcomeTrackingRunnable к ScheduledMethodRunnable (`initial-runtime/`,1 failure);
own-tests-1/2 — ошибки компиляции собственных тестов; own-tests-3 —16 tests/4 failures
(неполная address/transfer fixture и repository exception translation проверки
границы), XML в `own-tests-3-results/`; own-tests-4 —21 tests/1 failure из-за cursor
фикстуры, которую занимали201 ранее созданный защищённый товар, XML в
`own-tests-4-results/`. Все исправления вошли в окончательный непропущенный148 run.
На границе завершения реализации production/test sources после этого запуска
не менялись; последующие отдельно порученные audit fixes имеют новый manifest.

Полный log также содержит connection-refused/closed-connection сообщения от
кэшированных Spring test contexts, чьи PostgreSQL были уже закрыты их существующим
AfterAll; этот шум сохранён. Процесс нормально завершён exit0, XML без ошибок.
Общие/чужие test fixture lifecycle файлы ради подавления этих сообщений не менялись.
19 классов не означают весь проект: отдельная регрессия reputation28/cases26,
административные27 и общая приёмка32 этим запуском не заявляются.

Команда настоящего сжатия контекста и счётчик его заполнения среди доступных tools
не обнаружены (проверен список tools); сводкой сжатие не имитировалось.

## Отдельный этап после реализации: проверка доступности сжатия

После завершения финального процесса exit0, сохранения XML/manifests/JAR SHA и
окончания отчёта получено отдельное поручение координатора повторить проверку.
Доступный список tools проверен повторно **после реализации**: команды настоящего
сжатия контекста и счётчика заполнения нет. Сжатие выполнить невозможно; сводка
не создавалась вместо него. Это подтверждение недоступности отдельного этапа.
На границе этого отдельного этапа security/plan audit и commit ещё не начинались,
ожидали следующих сообщений. Исходники тогда после148 run не менялись; был дополнен
только отчёт. Последующее отдельное поручение аудита отражено в новом audit report;
commit по-прежнему не выполнялся.
