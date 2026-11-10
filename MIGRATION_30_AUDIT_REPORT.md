# 30 — отдельный security / plan audit

10.11.2026. Этот аудит начат **отдельным сообщением координатора после** завершения
реализации и повторного подтверждения отсутствия команды настоящего сжатия.
Прежние148 зелёных тестов не считались аудитом. Локальная ветка
`codex/migration-30-scheduling`, HEAD/base `5add9e56e8bd8f39236286794bcfc8ed02a05599`.
Commit/push/fetch/pull/deploy, изменение master и запуск субагентов не выполнялись.
Аудит завершён, findings ниже исправлены в согласованном scope. Окончательная
валидация состоит из125 neighboring checks и31 собственной проверки; происхождение
обоих наборов и промежуточные отказы разделены ниже. Весь156 run зелёным не объявляется.

## Метод и scope

Заново прочитаны все18 исходных deliverables:14 production Kotlin,3 own test Kotlin
и MIGRATION_30_REPORT.md. Начальные hashes сверены с manifest реализации: совпали;
сохранены в `build/migration-30/audit/before-deliverables.json`.
Повторно прослежены Java scheduler → service/repository/S3; effective V1.0–V1.28
(особенно V1.21/V1.23), фактические ProductReservation16.1/CreateOrder24,
ImageCommandRows12.3/ProductImageAttachment/OrderImageEvidence12.2 и lifecycle25;
legacy/modern filter/config, transaction-manager selection, accepted S3 client.

Исправления затронули только **10 своих новых файлов**:

| Путь внутри `src/main/kotlin/com/model_store/modern/`, кроме последнего | Изменение |
| --- | --- |
| `catalog/product/maintenance/application/ProductMaintenance.kt` | Upper-bound/cursor contract и typed transaction error |
| `catalog/product/maintenance/infrastructure/JdbcProductMaintenance.kt` | Собственная строгая JPA boundary, bounded page, SKIP LOCKED |
| `catalog/product/maintenance/infrastructure/JdbcDeletedProductForImageCleanup.kt` | Проверка identity реального JDBC DataSource |
| `identity/participant/maintenance/infrastructure/JdbcMaintenanceParticipantLocks.kt` | То же |
| `media/image/maintenance/application/ImageMaintenance.kt` | Upper-bound/cursor contract и typed transaction error |
| `media/image/maintenance/infrastructure/JdbcImageMaintenance.kt` | Собственная JPA boundary, invalid/shared-key protection, bounded page |
| `media/image/maintenance/infrastructure/JdbcImageProductReferences.kt` | Проверка identity JDBC DataSource |
| `ordering/maintenance/infrastructure/JdbcProductOrderReferences.kt` | То же |
| `shared/scheduling/infrastructure/MaintenanceSchedulers.kt` | Один high-water ID на каждый cleanup run |
| `src/test/kotlin/com/model_store/modern/shared/scheduling/MaintenanceIntegrationTest.kt` | Новые regressions, exact errors/PID/SQL и foreign-EMF checks |

Также актуализирован прежний отчёт, создан этот отдельный отчёт. Согласованный
полный список17 code files сохранён в отчёте реализации; новые production paths
сверх него не появились. Existing owner files, чужие27/26/28, Java/reactive,
Flyway/functions, Gradle/security/Jackson, MIGRATION_PLAN.md, пользовательские
docker-compose.yml/plan_2.md не менялись.

## Findings и исправления

1. **Недостаточно самостоятельная проверка JPA boundary у commands.** Expiry
   полагался на qualifier transactionManager, cleanup — на соседние lock ports.
   Нормальный runtime был JPA, но неправильно сконструированный adapter с JDBC
   manager мог выполнить expiry без обещанной JPA boundary. Дополнительно
   references проверяли bound ожидаемый DS, но не identity DS самого JdbcTemplate.
   Теперь commands до работы отвергают ambient tx, чужие manager/EMF/DS; внутри
   собственной tx до SQL проверяют writable actual/JPA joined/expected resources.
   У всех новых lock/reference adapters проверяется также `jdbc.dataSource === DS`.
   Ошибки отдельные typed application exceptions; нет зависимости от proxy
   self-invocation или REQUIRES_NEW. Проверены JDBC-only manager, manager иной EMF,
   отдельный JdbcTemplate/DS и **реальная** чужая JPA EMF на той же приватной PG,
   а также absent/read-only/normal caller boundaries и outer rollback до S3.

2. **Image key мог принадлежать другой живой строке.** У filename нет UNIQUE;
   валидная схема допускает две строки с одним S3 basename. Удаление старой строки
   могло удалить общий объект активной строки. Перед tombstone и перед S3 теперь
   media проверяет отсутствие любого другого image с этим filename. Проверка
   консервативна также между tags, защищая конфигурации с alias buckets. Общие keys
   остаются до ручного разрешения неоднозначности; exactly-once storage ownership
   без schema constraint не обещается. Nullable/blank/path/control filenames теперь
   дают SKIPPED **до изменения status**, вместо поздней ошибки/NPE после tombstone.
   Все три variant keys валидируются также storage adapter до первого DELETE.

3. **Run мог бесконечно следовать растущему keyset.** Cursor двигался через
   protected/failed IDs, но постоянные новые вставки могли удерживать full pages.
   Scheduler теперь один раз получает scalar max ID у соответствующего owner и
   передаёт inclusive ceiling каждой странице. Новые IDs ждут следующий запуск;
   удалённые/изменившиеся rows не мешают продвижению. Владелец foreign SQL не пишет.
   Product recheck использует прежний по смыслу SKIP LOCKED: занятый product
   пропускается до удаления любых зависимостей. Participant wait остаётся bounded
   lock timeout10s; per-item errors не зацикливают страницу. Проверка busy product
   удерживает реальный FOR UPDATE, независимо доказывает55P03 на NOWAIT probe,
   требует успешный следующий ID до освобождения holder.

4. **Доказательства concurrency/error type были слабее утверждений.** Раньше
   observer принимал любой backend за blocker и concurrent-cleaner test мог
   фактически оказаться последовательным; S3 test принимал любой Exception.
   Теперь test-only spies не меняют результат owner-port calls, а получают actual
   backend PID **в транзакции конкретного worker до блокирующего SQL**. Observer
   сопоставляет точные waiter/holder PID и проверяет SQL; concurrent cleaners
   обязаны реально блокироваться до release. AmazonS3Exception500/InternalError
   проверяется точно; transaction error type проверяется для каждого порта отдельно.
   Добавлены обе стороны реальных ORDER evidence и PRODUCT upload/cleanup races,
   foreign EMF, malformed/shared keys, high-water insertion и фактический socket
   timeout выделенного S3 client с сохранением committed DELETE.

   Первый новый observer выявил две собственные ошибки ожидания: PG transaction
   кешировал pg_stat_activity snapshot, поэтому теперь каждый poll вызывает
   pg_stat_clear_snapshot(); actual booking для уже DELETED товара не обнаруживает
   seller в active-only discovery и ожидает **product FOR UPDATE**, имея customer
   lock. SQL assertion исправлен по фактическому контракту. Product lock и
   последующий ACTIVE recheck всё равно исключают orphan creation после удаления.
   Полный прогон затем выявил краткую несогласованность live pg_blocking_pids с
   query text pg_stat_activity даже после очистки snapshot. Observer теперь ждёт
   **одновременно** exact PID edge и expected SQL до конечного deadline; предыдущий
   SELECT pg_backend_pid не считается ни успехом, ни немедленным отказом.

## Подтверждённые границы и отклонения плана

Shared scheduler знает только application ports/IDs/counts. Product/dependents
пишет только catalog, image пишет media; ordering/identity ports только читают/
блокируют своё. Foreign domain/JPA aggregates, Reactor/R2DBC в новых путях отсутствуют.
DI не цикличен: catalog maintenance → отдельный media references bean;
media maintenance → отдельный catalog product-lock bean без зависимости от media.
Identity background port не читает role/status/is_agent и не добавляет authorization.

Полный набор discovered participants заранее distinct ASC, далее order либо product,
далее image. Ни одна ветка после frontier не добавляет participant. Owner/parties/
tag/entity/uploader/filename/status сравниваются с frozen observation под locks;
eligibility и actual references вычисляются заново. Чужой owner/association →
CONFLICT/skip, без следования новому ID. Product expiry берёт только product locks
и не движется назад к participants. Порядки совместимы с accepted12.3/25.

V1.21 действительно сняла order/review product FK. Product cleanup под product lock
явно вызывает actual-order EXISTS без фильтра статуса и image EXISTS любого статуса;
сохраняет GIVEAWAY и giveaway dates/history. Не пишет order/review/history IDs.
Оставшиеся FK favorite/basket/cart/category не подменяют эту защиту; поздний SQL
failure откатывает dependents одного ID. Booking24/Reservation16.1 и принятые media
writers используют тот же product lock и recheck до INSERT/activation. Оба порядка
booking доказаны actual use case, и обе стороны ORDER evidence — actual media port.

Сначала отдельный успешный commit DELETE; только затем новая writable JPA tx с
fresh locks, reference/key/association checks → original/medium/thumbnail → row delete.
Внешняя tx отвергается до первого изменения/S3, в том числе при outer rollback.
Нет self-invoked @Transactional, скрытого inner join, ручного commit или REQUIRES_NEW.
Ambiguous S3 response/partial variants/timeout/late beforeCommit сохраняют committed
tombstone для replay. DB rollback не восстанавливает S3; S3 exactly-once/atomic DB+S3
не заявляются. Dedicated client сохраняет2s/5s/10s/15s/maxRetry1 settings; accepted
общий client и его consumers не менялись.

Неопределённый ответ prepare commit не запускает S3: строка может остаться исходной
или уже быть DELETE. Неопределённый ответ purge commit оставляет либо DELETE, либо
уже отсутствующий row после удаления всех variants. Повтор безопасен в обоих
случаях; принудительный crash/потеря commit ACK отдельно не инъецировались.

Cron5min/midnight/02:00 и empty zone сохраняются. Default legacy старый; modern
исключает Java schedulers через принятый filter и включает только новый набор.
Один профиль **не гарантирует** exclusive scheduling разных JVM. Mixed legacy/
modern/directSQL writers требуют coordinated deployment, особенно без polymorphic
image FK. Новые locks защищают только согласованный modern protocol. High-water
ID и duplicate-key guards — защитные уточнения, не новая бизнес-авторизация.

Ограничения: invalid/shared-key rows остаются для операционного разбора; новые IDs
после ceiling ждут следующий cron; busy participant может задержать один ID до10s;
долгий S3 вызов удерживает маленький per-image lock set, ограниченный client timeout.
Исторический current_utc_timestamp default и потерянные до DB row upload objects
сохраняют ранее описанные ограничения; схему/старые значения аудит не исправляет.

## Проверки и provenance

Все прогоны: JDK21.0.11, cache C:/Users/skura/.gradle, offline/no-daemon,
JAVA_TOOL_OPTIONS только два test RSA paths, приватный embedded PG и loopback
S3/SMTP; RUN_BOOT_JAR_SMOKE=true только где нужен actual artifact.

| Прогон | Результат | Evidence в `build/migration-30/audit/` |
| --- | --- | --- |
| targeted-1 | Непосредственный exit1;25 tests,2 failures observer SQL assertions | log/exitcode, `targeted-1-results/` |
| targeted-2 | Непосредственный exit0;28 tests,0 failures/errors/skipped | log/exitcode, `targeted-2-results/` |
| regression / final-verification | Непосредственный exit1;156 tests,1 failure только transient-SQL observer | `regression-source-manifest.json`, `regression-source-recheck.json`, log/exitcode, `regression-results/` |
| final-own-verification | Непосредственный exit0;31 tests /3 classes /0 failures/errors/skipped; BUILD SUCCESSFUL3m47s | `final-source-manifest.json`, log/exitcode, `final-own-results/`, `final-own-summary.json` |

Targeted-1 не объявляется проверкой окончательного кода: во время этого
промежуточного запуска была дополнена cross-tag shared-key защита. После targeted-2
добавлены SKIP LOCKED и его регрессия; финальный source manifest снят **после** них.
Все промежуточные XML скопированы только после фактического завершения процессов.
Regression process завершён до XML; из156 все125 neighboring tests прошли,
а собственные31 имели ровно один отказ observer SQL assertion. После исправления
**только собственного test helper** успешно повторены все31 own tests, включая actual
JAR legacy/modern. Полный неуспешный156 run успешным не объявляется. Исходные148
результатов реализации хранятся отдельно и не являются финальными результатами
изменённых audit sources.

Итого **156 уникальных test IDs**:125 соседних (16 классов,0 failures/errors/skips)
из полного regression run и31 собственных (3 класса,0 failures/errors/skips) из
завершающего own run. Final own включает29 MaintenanceIntegrationTest +1 runtime
default/legacy/modern +1 actual JAR legacy/modern. Совпадение установлено по
class+testcase name, `test-identity-counts.json`:240 исполнений на4 audit runs,
156 уникальных IDs,84 повторных исполнения. В84 входят диагностические25 и28
исполнений и31 повтор после broad run; это **не** дополнительные84 уникальных теста.
Всего в audit history3 неуспешных исполнения (2 targeted-1,1 regression observer),
они не скрыты и не включаются в итоговую зелёную оценку156 distinct checks.

После заключительного exit0 повторно проверены **все579 source files**,0 mismatches
с финальным manifest. Между broad regression и own verification изменился ровно
один source path — `MaintenanceIntegrationTest.kt`, только private observer helper.
Ни runtime sources, ни125 neighboring test sources не изменились; `bootJar` был
UP-TO-DATE. Поэтому повтор соседей не маскирует изменения кода: их успешные XML
соответствуют тому же production artifact, а изменённый тест проверен заново.

- Final source manifest SHA-256:
  `03976e768ab4a073aeb7b2e5f3b977d34171d6088cbfdd67d51979e6195b2672`.
- Regression source manifest SHA-256:
  `a93015b1c9b7c722e73cb411b330ecfc559f40e8d09552ab9624660984177a23`.
- Неизменный между regression и final own **bootJar SHA-256**:
  `c977c1ed14e2bb0d5e70724da9ce420b8c779ef89740f709980614308711512c`.

JAR в обоих прогонах фактически стартовал legacy/modern из одной приватной frozen
копии с проверкой SHA. Точные3 старых либо3 новых scheduler registrations,
прежние cron и empty zone, HTTP readiness и изолированные S3/SMTP подтверждены.
Дочерние JVM завершены/дожданы (Windows destroy exit1 ожидаем и сохранён в XML).
Final own XML содержит17 exact waiter/holder/SQL checkpoints. Failures не подменены
любым exception; spies передают вызов реальному owner adapter без изменения результата.

Все окончательные evidence в `build/migration-30/audit/`:
`verification-metadata.json`, `final-source-recheck.json`,
`final-source-manifest.json`, `regression-source-manifest.json`,
`regression-source-recheck.json`, `regression-summary.json`,
`final-own-summary.json`, `test-identity-counts.json`,
`final-deliverables-sha256.json`, перечисленные logs/exitcode/XML directories.
Actual JAR logs broad run сохранены как `regression-bootjar-{legacy,modern}.log`;
заключительные — `../bootjar-{legacy,modern}.log`. Прежние implementation logs
перенесены копиями в `../implementation/`. Шум cached-context PostgreSQL shutdown
connection-refused сохранён в полном broad log, не выдаётся за business failure;
сам broad failure1 точно установлен по completed XML. XML копировались только
после непосредственно наблюдённого завершения каждого процесса.

Commit не создан; master/remotes неизменны. Следующий этап ожидает отдельного
поручения координатора. Команды настоящего сжатия/счётчика в доступных tools нет;
ранее отдельно подтверждённая недоступность не подменялась сводкой.

## Точечный ремонт fixture после root reproduction

Отдельное поручение координатора после локального commit
`b6ad22bc020ffa72eaba1d88a9a613a9d7fe5e69`, 10.11.2026. Координатор сообщил:
combined root run на `42ab0aa`, сессия17187, обнаружил IOException →
FileNotFoundException в SchedulingBootJarTest на ProcessBuilder.start.
Относительный `build/migration-30` существовал после worker-проверок, но отсутствовал
в чистой root-сборке. Это предоставленное root reproduction; root-процесс здесь
не останавливался/не дублировался, root-дерево не изменялось.

Изменены ровно два файла: собственный SchedulingBootJarTest.kt и этот audit report.
Единственная code delta: `Files.createDirectories(log.parent)` сразу после
вычисления log path и до redirect/start. Оба actual profiles, frozen JAR SHA,
readiness/registration assertions и cleanup сохранены, skip/обхода проверки нет.
Production/runtime/Gradle/Flyway/прочие sources и отчёт реализации не менялись.

Standalone regression выполнена только для SchedulingBootJarTest: приватная новая
рабочая папка содержит копию того же JAR и только test RSA fixtures. До запуска
designated `fixture-.../build/migration-30` проверен как отсутствующий; тест сам
создал его, сохранил legacy/modern logs и прошёл оба реальных запуска. Для этой
проверки локальный ignored init script задаёт test workingDir и JVM user.dir;
репозиторная конфигурация не менялась. Первый вызов с одним workingDir также дал
exit0/1test, но JVM разрешила пути от исходного каталога; этот вызов **не считается**
доказательством missing-parent regression. Его log/XML сохранены отдельно.

Окончательный узкий запуск: непосредственно наблюдённый **exit0**, BUILD SUCCESSFUL
1m3s, **1 test /0 failures/errors/skipped**. В чистой output-папке получены три
старых registration lines для legacy и одна line с тремя modern methods. Это
повтор того же test ID, не добавочный уникальный тест. Прежние125 neighboring и31
final-own проверка сохраняют свой ранее описанный provenance; новый156 run не заявляется.

Evidence: `build/migration-30/output-parent-fix/verification.log`, `.exitcode`,
`completed-results/*.xml`, `fixture-before.json`, `verification-metadata.json`,
`source-manifest.json`, `initial-working-dir.log`/`.exitcode`, `initial-results/`.
Actual logs находятся в указанном fixtureWorkingDirectory/build/migration-30.
XML скопирован только после завершения процесса. Старые evidence directories
не удалялись, production keys/services/Docker не использовались.

После запуска проверены579 sources:0 mismatches с новым manifest; относительно
предыдущего audited manifest отличается только SchedulingBootJarTest.kt.
Новый source manifest SHA-256:
`575c1e5f3ebf3882e24a4aad7a893f0f7a4b60f3296e8980a423e2deb00d359d`.
Тот же JAR и его private copy имеют SHA-256:
`c977c1ed14e2bb0d5e70724da9ce420b8c779ef89740f709980614308711512c`.
Test source SHA-256:
`91c5d524c5fd222b40d94d5a04c2bfa0feabcaca3ad18443f7e80e0aa44a6a69`.

Самостоятельная проверка delta: путь literal и принадлежит test output; создание
каталога идемпотентно, не удаляет/не перемещает данные и не добавляет пользовательский
input или runtime side effects. Assertions/error propagation не ослаблены.
Дополнительный commit не создавался, ожидается отдельное поручение.
