# 29.3 — заказы агента: реализация и самостоятельный аудит

Реализация:10.11.2026. Отдельно порученный самостоятельный аудит завершён11.11.2026.
Актуальные findings, исправления и161-run приведены в последнем разделе.
Commit ожидает отдельного поручения. Начальная инвентаризация и прежние результаты
ниже сохранены как история стадий, а не как evidence нынешнего audit snapshot.

## Передача scope и начало реализации

Координатор отдельным сообщением согласовал восемь новых owner/test файлов из
предложения ниже и ровно один existing JdbcLifecycleRead.kt (только extraction
mapper + bounded SelectedOrderRead). Реализация начинается после этого согласования.
Точный дополнительный собственный seller список до кода:
`application/AgentOrderActions.kt`, `api/AgentOrderController.kt`,
`api/AgentOrderErrors.kt` под `src/main/kotlin/com/model_store/modern/seller/agent/order/`;
`src/test/kotlin/com/model_store/modern/seller/agent/order/AgentOrderHttpTest.kt`.
Никаких других existing файлов не передано. Исторические «до реализации/не выполнено»
ниже относятся к предыдущей стадии; текущие проверки будут записаны отдельно.

## База, состояние и владение

- Managed worktree: `C:/Users/skura/.codex/worktrees/migration-29-3-agent-orders/model-store`.
- Ветка: `codex/migration-29-3-agent-orders`.
- HEAD и проверенный merge-base с local master: `9d745cb75e148bda471ba5846b4152851fac6223`.
- Дерево до создания ветки и при возобновлении после usage-limit чистое. Частичных source/test изменений нет.
- Репозиторных AGENTS.md поиск не нашёл; `C:/Users/skura/.codex/AGENTS.md` существует и пуст.
- Прочитаны план, относящиеся к границам/контрактам/проверкам разделы отчётов12.3/16.2/25/27/29.1 и README29.2. Выводы сверены с фактическими Java/Kotlin/SQL.
- Начальное владение: только новые `modern/seller/agent/order/{domain,application,infrastructure,api}/**`, собственные новые тесты и этот отчёт.
- Сейчас добавлен только этот отчёт. Реализация, тесты и сборка29.3 ещё не выполнялись. Старые результаты соседей не выдаются за проверку29.3.
- Java/reactive/Flyway, shared/config, maintenance30, принятые owner-файлы, пользовательские compose/plan_2 и MIGRATION_PLAN не менялись.
- Commit/staging/push/fetch/pull/публикация/deploy не выполнялись. Подагенты/новые чаты не создавались.
- Настоящая команда сжатия и счётчик использования контекста в доступных инструментах отсутствуют. Процент неизвестен; этот отчёт не является сжатием.

Предыдущая попытка сохранения отчёта не исполнилась: automatic approval review остановился по usage-limit, без заключения о небезопасности. После возобновления рабочее дерево уже явно входит в writable roots; отчёт сохранён обычной разрешённой записью. В той неисполненной попытке файл не создавался.

## Actual legacy: маршруты

Все три маршрута обслуживает смешанный `AdminAgentController`, который не меняется. Общий префикс `/admin/actions` требует `SCOPE_ADMIN` в legacy и modern security.

| HTTP и путь | Параметры и результат |
| --- | --- |
| GET `/admin/actions/agent-orders` | nullable `OrderStatus status`, `page=0`, `size=50`; список `FindOrderResponse` |
| GET `/admin/actions/agents/{agentId}/orders` | те же параметры; сначала существующий target agent; список `FindOrderResponse` |
| POST `/admin/actions/agents/{agentId}/orders/{orderId}/{action}` | `action` — String с точным регистром; необязательные query `comment`, `deliveryUrl`; тела нет; ответ Long orderId |

**Отдельного GET деталей заказа агента нет.** Полные детали, фото и история входят в ответ очереди. Нет `/agent/orders`, agent-specific point detail или GET audit. Новые пути не изобретаются.

Принятые25 GET `/order/seller` и `/order/customer` не переданы29.3. В actual legacy `AgentAccountAccessFilter` запрещает их любому caller с `is_agent=true`. Исключения этого фильтра только для ACTIVE агента: POST `/agent/products` и POST `/images?tag=PRODUCT` без entityId. Поэтому29.3 не открывает новые чтения `agent_access`. Если они будут отдельно согласованы, потребуется `VerifyAgentAccess`, а не административный `AgentAccessLock`.

## Очередь, DTO и SQL чтений

Call graph: `AdminAgentService.orders` → `OrderServiceImpl.getAgentOrders` → `OrderRepository.findAgentOrders` → seller mapper → enrichOrders → sellerLogin.

- `page<0` или `size` вне1..100 →400 `INVALID_REQUEST`, «Некорректная страница или размер страницы». Эта проверка предшествует requireAgent. Offset `(long)page*size` без int overflow.
- requireAgent: `findByIdAndIsAgentTrue`, без ограничения статуса. Иначе404 `PARTICIPANT_NOT_FOUND`, «Бот не найден». Target ACTIVE/BLOCKED/DELETED допустим.
- SQL JOIN seller participant с `is_agent=true`, без status фильтра; nullable agentId/status; `ORDER BY o.created_at DESC,o.id DESC LIMIT/OFFSET`. Пустая очередь200[], конкретный отсутствующий/не-agent target404.
- `FindOrderResponse`: orderId, actualStatus, totalPrice, prepaymentAmount, sellerId, sellerLogin, createdAt, userInfo, product, transfer, images, paymentProofImageId, deliveryUrl, histories. Это полный DTO, не summary.
- UserInfo — customer: id/imageId/login/phoneNumber/mail. `findShortInfo` только ACTIVE; иначе остаётся исходный id с null-полями. Пароль не нужен. SellerLogin отдельно читается по ID без фильтра ACTIVE.
- Product: name/count/price/currency/availability из order snapshot; остальные текущие поля, категории/главное фото/рейтинг/отзывы/эффективный giveaway status — принятый enrichment25. При физически отсутствующем product fallback DELETED + snapshot/sellerId.
- Transfer: transferId/addressId/imageId/address/price/currency; текущие transfer/address и actual legacy формат полного адреса; sending→image1..4 уже реализованы25.
- Images: ACTIVE ORDER IDs по created_at. Histories: status/comment/changedAt по changed_at, без придуманного ID tie-break; HTTP время `dd.MM.yyyy HH:mm:ss`, Europe/Moscow. Order.createdAt — строка.
- Null product/customer/transfer/address ссылки дают actual404 `PRODUCT_NOT_FOUND` / `PARTICIPANT_NOT_FOUND` / `TRANSFER_NOT_FOUND`; не заменять silently пустым DTO.
- `OrderResponse25` уже содержит sellerLogin с default null; API29.3 может reuse `from(view).copy(sellerLogin=...)`, не меняя accepted API-файл.

## Действия, ошибки и запись

Call graph: `AdminAgentService.actOnOrder` → requireAgent → order.findById → sellerId==agentId → dispatch → audit.save. Отсутствующий/чужой order404 `INVALID_REQUEST`, «Заказ бота не найден». Неизвестный action проверяется **после** target/order:400 `INVALID_REQUEST`, «Неизвестное действие с заказом». Не заменять String enum HTTP binding: это поменяет приоритет/форму ошибки.

| Legacy action | Переиспользуемый owner action25 | Actual переход |
| --- | --- | --- |
| CONFIRM | AGREE | BOOKED → AWAITING_PREPAYMENT при prepaymentAmount>0, иначе AWAITING_PAYMENT |
| CONFIRM_PREPAYMENT | APPROVE_PREPAYMENT | AWAITING_PREPAYMENT_APPROVAL → AWAITING_PAYMENT |
| SHIP | SHIP | ASSEMBLING → ON_THE_WAY |
| CANCEL | CANCEL | только BOOKED → CANCELLED; возврат ограниченного текущего PURCHASABLE stock |

Таблица — инвентаризация Java, а не новая реализация матрицы. Повторный/недопустимый переход400 `BAD_REQUEST`, «Нельзя выполнить операцию с данными условиями»; проигранный CAS400 `BAD_REQUEST`, «Статус заказа изменился. Повторите операцию».

Административный SHIP допускает отсутствующий deliveryUrl, в отличие от ordinary route25. Не добавлять новые URL/blank проверки. Comment order/history varchar(500): превышение даёт actual500 `INTERNAL_ERROR`, не новый400. Error envelope: code/message/status/timestamp/details. Integrity/reference mapping сверять с actual `GlobalExceptionHandler`,25/27 advice; malformed path/query binding отдельно сверить HTTP-тестом.

Эффективные migrations: V1.0 order/history; V1.2 status trigger — единственный writer истории; V1.16 is_agent + `admin_agent_order_action`; V1.21 snapshot и удаление product FK; V1.22 CANCELLED; V1.23 case/payment/expiry/stock; V1.24–28 giveaway данные. Новые миграции не нужны.

`admin_agent_order_action`: id bigserial PK; admin_id/agent_id/order_id NOT NULL FK; action varchar(40) NOT NULL; created_at timestamptz NOT NULL default now(); index(order_id). Audit хранит **реального** adminId отдельно от agentId. SellerId заказа не меняется. Audit, переход, trigger history и stock должны откатываться вместе. Legacy stock increment при quantity<=0 — no-op; положительное количество отдавать принятому catalog `ProductReservation`, без копии stock SQL.

## Блокирующая owner-зависимость: предложение координатору

`OrderLifecycle.execute(Actor?,...)` требует ACCESS/USER|ADMIN и запрещает current bot actor для AGREE/APPROVE_PREPAYMENT/SHIP/CANCEL. Подстановка Actor/JWT агента недопустима. `AdminOrderCases27` также принимает Actor и разрешает обращения, а не эти действия. Без расширения owner scope законного command entry point нет. Seller не должен писать order/audit/participant/catalog/media либо копировать матрицу/stock/history.

### Командный контракт: только новые owner-файлы

Предлагается передать29.3 следующие **НОВЫЕ** пути:

1. `src/main/kotlin/com/model_store/modern/ordering/agent/application/AgentOrderCommands.kt`
2. `src/main/kotlin/com/model_store/modern/ordering/agent/application/AgentOrderAudit.kt`
3. `src/main/kotlin/com/model_store/modern/ordering/agent/infrastructure/JdbcAgentOrderAudit.kt`
4. `src/test/kotlin/com/model_store/modern/ordering/agent/AgentOrderCommandIntegrationTest.kt`

Публичный ID/scalar-only contract:

`AgentOrderCommands.execute(callerAdminId: Long, agentId: Long, orderId: Long, action: String, comment: String?, deliveryUrl: String?): Long`.

Внутренний `AgentOrderAudit.record(adminId,agentId,orderId,action): Unit`. На seller→ordering границе нет Actor/JWT/JPA/domain entities; typed errors для missing/foreign order/unknown action, accepted identity/lifecycle failures сохраняются.

Owner algorithm в одной writable application JPA transaction:

1. `LifecycleTransaction.requireWritableJpa`; scalar discovery order, без блокировки order.
2. Полный DISTINCT ASC participant set caller+target agent+observed seller+customer через `LifecycleStore.lockParticipant`. **Не** вызывать AgentAccessLock пары раньше customer с меньшим ID.
3. После полного набора accepted `AgentAccessLock.lockActiveAdminAndAgent`: повторные pair locks уже удерживаются; fresh current ACTIVE nonagent ADMIN и target.is_agent; target status не ужесточается.
4. Order FOR UPDATE; fresh parties должны совпасть с observation, seller==agentId; не следовать новой ассоциации/не расширять set за lock frontier.
5. Dispatch четырёх действий, reuse `LifecycleOrder.transition` + `LifecycleStore.transition`; preserve observed status/CAS loser semantics. Не вызывать ordinary lifecycle под поддельным actor.
6. CANCEL stock только `ProductReservation.returnLimitedStock`, положительный quantity. Не копировать stock/history, не активировать images и не выполнять платежи/case resolution.
7. Audit insert после успешных перехода/stock в той же caller tx. Ошибка audit/trigger/stock или внешний rollback откатывает всё. Никаких REQUIRES_NEW/retry/manual commit.

При missing discovery current caller/target всё равно проверяются до возвращения404. Прямые mutation adapters требуют действующий writable application EMF+DataSource guard, а не только annotation.

### Ограниченное чтение: узкая зависимость от projection25

`LifecycleRead.orders(participantId,seller)` читает **все** заказы участника. Его enrichment mapper private внутри `JdbcLifecycleRead`. Selected-ID/queue API отсутствует. Загрузка всех seller orders и пагинация в памяти недопустимы: unbounded работа и ошибки от строк вне выбранной страницы.

Предлагаемые **НОВЫЕ** пути:

5. `src/main/kotlin/com/model_store/modern/ordering/agent/application/AgentOrderQueries.kt`
6. `src/main/kotlin/com/model_store/modern/ordering/agent/infrastructure/JdbcAgentOrderQueue.kt`
7. `src/main/kotlin/com/model_store/modern/ordering/lifecycle/application/SelectedOrderRead.kt`
8. `src/test/kotlin/com/model_store/modern/ordering/agent/AgentOrderQueueIntegrationTest.kt`

Контракты:

- `AgentOrderQueries.queue(callerAdminId:Long,agentId:Long?,status:String?,page:Int,size:Int):List<AgentOrderView>`.
- `AgentOrderView(order:OrderView,sellerLogin:String?)` — immutable application scalar read model.
- `SelectedOrderRead.ordersByIds(ids:List<Long>,seller:Boolean):List<OrderView>`.

Queue adapter сначала actual bounded SQL с LIMIT/OFFSET, затем enrichment **только** selected IDs; порядок восстанавливается по ordered ID list. Общая очередь проверяет caller через CurrentAdminLock; конкретная — через AgentAccessLock, в writable tx. READ_COMMITTED snapshot semantics остаются прежними.

Для reuse projection без копирования history/enrichment требуется отдельно передать **один существующий файл**, который пока менять запрещено:

`src/main/kotlin/com/model_store/modern/ordering/lifecycle/infrastructure/JdbcLifecycleRead.kt`.

Минимальное изменение: implement новый SelectedOrderRead; выделить существующий ResultSet mapper в private функцию; использовать её из old orders и нового bounded ordersByIds. Не менять old public LifecycleRead/OrderLifecycle/domain/API, DTO или правила enrichment. **Это изменение не сделано и не считается разрешённым.** Если существующий файл передавать нельзя, нужен отдельно поставленный owner-side bounded reader; копия25 enrichment или unbounded обход не предлагаются.

### Файлы в уже разрешённом seller scope после согласования

Будущие AgentOrderActions/application, AgentOrderController и AgentOrderErrors/api; собственный HTTP contract и legacy/modern тесты в seller/agent/order. Verified ACCESS+ADMIN caller ID берётся из principal; JWT не декодируется повторно. Agent_access, включая signed ADMIN claim, не допускается на admin действия. Без shared/config edits и SQL записи чужих таблиц. Новых agent-access routes не будет.

## Проверки после разрешения контракта: пока НЕ выполнены

- Actual legacy/modern HTTP DTO/nulls/errors/params/page/status, sellerLogin и history; отсутствующие/чужие orders; nullable SHIP URL; неизвестный action и error precedence.
- Реальные issued13 tokens, role claim types, access/agent_access/refresh/expired/forged, current USER/ADMIN/bot, self-agent admin denial; ACTIVE/BLOCKED/DELETED target.
- Stale ADMIN role/status/is_agent revoke-first/action-first, оба порядка caller/agent/customer IDs, exact SQL/PID/blocker; fresh associations.
- Repeated/concurrent четыре перехода,25 payments,26 openings,27 resolve; stock/history/audit ровно раз; quantity<=0/overflow/deleted product; поздний audit/trigger failure и внешний rollback.
- Guards no tx/read-only/JDBC-only/actual foreign EMF/wrong DataSource. Lock-order12.3 и совместимость30 на принятой интеграции; не менять чужие файлы ради теста.
- Offline JDK `C:/Users/skura/.jdks/temurin-21.0.11`, cache `C:/Users/skura/.gradle`, fixture keys; изолированные PostgreSQL/mock S3/SMTP. Один новый JAR в legacy+modern, законченные XML/exit/log/source hashes; без production.

Инвентаризация завершена. Следующий шаг — решение координатора по названному owner scope, включая минимальный projection25 refactor или отдельно поставленный reader. Зависимый код остановлен согласно прямому ограничению задания. Это не implementation completion и не самостоятельный аудит. После реализации отдельные context step → новый аудит → local commit остаются отдельными поручениями; сейчас commit запрещён.

## Реализация после отдельного согласования owner scope

Историческая остановка выше снята следующим сообщением координатора. Все названные
новые owner-файлы, три seller-файла, три test-файла и единственный существующий
JdbcLifecycleRead.kt реализованы. Публичный command contract ID/scalar-only;
обычный Actor агента не создаётся. Seller получает проверенный principal, допускает
только ACCESS/ADMIN и отвергает bot caller до binding; актуальные caller/target
проверяются ещё раз через identity owner после полного sorted participant set.
Queries используют writable caller JPA locks, команды — один application JPA tx.
Stock только через catalog owner; история только trigger; audit INSERT последний.

JdbcLifecycleRead изменён только extraction: тело прежнего ResultSet mapper
перенесено в private project с явным return. Все прежние SQL/enrichers/nullable
поля и сортировки остались прежними. Новый SelectedOrderRead ограничен100 входными
ID, empty→empty. Queue SQL LIMIT/OFFSET выполняется до enrichment; результат
восстанавливает порядок ordered ID list. Неограниченного чтения seller orders нет.

### Промежуточные проверки и исправления, без сокрытия неуспеха

- compileKotlin: exit0, BUILD SUCCESSFUL51s. Предшествующий sandbox запуск не смог
  открыть Gradle cache lock; разрешённый offline запуск завершился успешно.
- Первый собственный прогон: exit1,14tests/5failures. Production дефект: nullable
  JDBC query parameter без явного SQL-типа давал PostgreSQL unknown parameter;
  исправлен CAST(:agent AS bigint)/CAST(:status AS text). Остальные failures:
  fixture пыталась нарушить NOT NULL; global page включала строки предыдущего
  теста; диагностические pg_stat_activity snapshots были закэшированы транзакцией.
  Исправлены изоляция queue target, временное снятие/обязательное восстановление
  собственного test-DB NOT NULL для malformed historical fixture, и вызов
  pg_stat_clear_snapshot перед каждым диагностическим опросом.
- Второй собственный прогон: exit1,14tests/1failure. Ожидался raw IllegalArgumentException,
  но @Repository переводит oversized-ID argument failure в InvalidDataAccessApiUsageException.
  Исправлено точное ожидаемое исключение, production contract не менялся.
- Третий собственный прогон: непосредственно наблюдён exit0, BUILD SUCCESSFUL1m3s,
  **21tests/4classes,0failures/errors/skips**. Законченные XML скопированы после
  завершения в build/migration-29-3-own3-results; счётчики own3-summary.json.
  Этот прогон включает actual legacy/modern HTTP, реальный issue13+refresh,
  malformed role/type claims, current admin отказы, legacy matrix, direct guards,
  actual foreign EMF, late audit CHECK/outer rollback/stock overflow/nonpositive
  count, reassociation refusal, конкурентные25 payment/26 opening/27 resolution.
- После третьего прогона расширена собственная revoke race matrix на все4actions
  и добавлена гонка media12.3 vs delegated command. Поэтому21 — промежуточное
  evidence, не окончательная проверка изменённых test sources.

Заключительная выбранная регрессия и bootJar запущены с RUN_BOOT_JAR_SMOKE=true,
JDK21.0.11, offline/cache и test keys. До запуска сохранён полный src manifest
build/migration-29-3-final-source.json. Лог build/migration-29-3-final.log; exit
записывается только после завершения процесса. Пока выполнение не окончено,
успех или XML counters этого запуска не заявляются.

## Заключительная проверка реализации и остановка

Непосредственно наблюдён **exit0, BUILD SUCCESSFUL3m27s**. Завершённые XML разобраны
и скопированы только после выхода процесса. Один invocation: **155 выполнений в16
классах,0failures/errors/skips**. Это executions, не заявление155 уникальных
сценариев: admin-case классы намеренно наследуют часть user-case проверок26.
Собственные29.3 классы не наследуют чужие тесты: **22 собственных выполнения**.

| Класс | Выполнений |
| --- | ---: |
| AgentOrderCommandIntegrationTest | 11 |
| AgentOrderQueueIntegrationTest | 1 |
| AgentOrderLegacyHttpTest | 4 |
| AgentOrderModernHttpTest | 6 |
| OrderLifecycleLegacyHttpTest / ModernHttpTest | 9 / 21 |
| AdminOrderCaseLegacyHttpTest / ModernHttpTest | 16 / 26 |
| UserOrderCaseLegacyHttpTest / ModernHttpTest | 6 / 14 |
| AgentTokenHttpTest / AgentTokenLegacyHttpTest | 5 / 1 |
| AgentAccessLockIntegrationTest | 11 |
| MediaLockOrderIntegrationTest | 15 |
| ModernMvcSecurityTest | 8 |
| BootJarDualModeSmokeTest | 1 |

Артефакты:

- `build/migration-29-3-final.log`, `build/migration-29-3-final.exit`.
- `build/migration-29-3-final-results/`, `build/migration-29-3-final-summary.json`.
- `build/migration-29-3-final-source.json`:582 src/resource/test files,
  повторная проверка после процесса **0mismatches**. SHA256 manifest:
  `bab2829384670c17496a5aefa37ad3bd6e65c151189fc3bea438bc5b7c257af4`.
- `build/migration-29-3-final-hashes.json` сохраняет эти hash/counters.
- JAR SHA256:
  `28182ee96f837c7670a159d1af8909183c2717f2fc2d7572fa924c3c9aa34645`.
- Actual smoke заморозил копию одного JAR, напечатанный SHA совпадает с итоговым
  артефактом; оба legacy/modern прошли S3/SMTP startup, security/OpenAPI и8negative
  JSON checks. Оба дочерних JVM остановлены и дожданы. Логи скопированы в
  `build/migration-29-3-bootjar-legacy.log` и `...-modern.log`.
- `build/migration-29-3-projection-equivalence.json`: старое тело mapper совпадает
  после удаления только отступов/нового return; прежние enrichers после mapper
  побайтово совпадают после нормализации окончания строки. Полная25 HTTP регрессия
  дополнительно проверила прежний JSON/nulls/history/error contract.
- Неуспешные initial/own2 и успешные промежуточные own3 XML/logs сохранены.
  Собственные root logs перемещены в build с прежними именами `build-29-3-*`;
  неуспехи не перезаписаны финальным запуском. Generated `.kotlin` удалён только
  после проверки точного абсолютного пути внутри своего рабочего дерева.

Тесты подтверждают все4action × role/status/is_agent revocation × обе очередности
operation/revocation × caller ниже/выше agent; CAS-конкуренцию с единственным audit,
history/stock effect; реальные owner calls25/26/27 в документированных допустимых
состояниях; отказ новой party после frontier; no-tx/read-only/JDBC/actual foreign
EMF/wrong DataSource audit guards; full rollback при late CHECK и outer exception.
Media12.3 против delegated CANCEL проверен реальными ImageCommandRows и owner
командой при agentId<adminId, не mock-блокировками и без catch deadlock/retry.

В логах сохранены прежние compiler/deprecation warnings и shutdown connection
reset/refused от cached test contexts при остановке embedded PostgreSQL. Они не
скрыты; процесс exit0 и XML без errors. Production services/keys не использованы.

### Сопоставление с30 и пределы доказательств

После завершения тестов **read-only** просмотрен принятый local42ab0aa, без merge/
fetch/base смены: MaintenanceParticipantLocks, JdbcProductMaintenance,
JdbcImageMaintenance, JdbcProductOrderReferences и owner contracts.30 блокирует
полный discovered participant set DISTINCT ASC до product/order/image и отказывает
сменившимся ассоциациям. Product cleanup после product lock делает только MVCC
EXISTS order references, без order FOR UPDATE; expiry UPDATE product не получает
поздних participant/order locks. Image cleanup PRODUCT идёт participant→product→image,
ORDER — participant→order→image.29.3 идёт participant→order→product, audit FK rows
уже удерживаются, поздних participant/image locks не добавлено. В этом lock graph
нет обратного product→order/participant edge, который замкнул бы цикл с29.3.

Это статическая проверка конкретного протокола30 плюс фактическая гонка12.3;
**совместного runtime29.3+30 на этой ветке не было**: по прямому assignment база
осталась9d745cb. Интеграционную гонку на объединённом дереве должен проверить31;
отсутствие всех возможных глобальных deadlock/races и смешанный legacy+modern rollout
не сертифицируются. Полный repository suite и load testing не выполнялись.

Дополнительные явные границы совместимости: current caller ACTIVE/nonagent ADMIN
строже legacy JWT-only чтений; изменившиеся parties отказываются, а не следуются
за новым lock set. Read snapshots остаются READ_COMMITTED. Исторический productId<=0
не входит в положительный ID contract catalog owner и может отказать/откатиться
строже legacy; stock owner не ослаблялся. Никаких новых agent_access paths.

### Итоговое владение — ровно14 файлов

Новые production:

1. `modern/ordering/agent/application/AgentOrderCommands.kt`
2. `modern/ordering/agent/application/AgentOrderAudit.kt`
3. `modern/ordering/agent/application/AgentOrderQueries.kt`
4. `modern/ordering/agent/infrastructure/JdbcAgentOrderAudit.kt`
5. `modern/ordering/agent/infrastructure/JdbcAgentOrderQueue.kt`
6. `modern/ordering/lifecycle/application/SelectedOrderRead.kt`
7. `modern/seller/agent/order/application/AgentOrderActions.kt`
8. `modern/seller/agent/order/api/AgentOrderController.kt`
9. `modern/seller/agent/order/api/AgentOrderErrors.kt`

Один existing production:

10. `modern/ordering/lifecycle/infrastructure/JdbcLifecycleRead.kt`

Три новых test файла и отчёт:

11. `src/test/kotlin/com/model_store/modern/ordering/agent/AgentOrderCommandIntegrationTest.kt`
12. `src/test/kotlin/com/model_store/modern/ordering/agent/AgentOrderQueueIntegrationTest.kt`
13. `src/test/kotlin/com/model_store/modern/seller/agent/order/AgentOrderHttpTest.kt`
14. `MIGRATION_29_3_REPORT.md`

Production paths выше относительно `src/main/kotlin/com/model_store/`.
Source после успешного final invocation не изменялся. Дополнен только этот отчёт.
git diff --check без ошибок; единственный tracked modified файл — согласованный
JdbcLifecycleRead; остальные13новые, ничего не staged. HEAD9d745cb и ветка прежние.
Никакого commit/remote/deploy/публикации, чужих файлов/плана/shared/maintenance30 edits.

Реализация остановлена. Следующие отдельные шаги context availability → новый
самостоятельный аудит → local commit требуют отдельных сообщений координатора.
Настоящее сжатие/счётчик по-прежнему недоступны; сводка за сжатие не выдаётся.

## Последняя правка оформления и актуальное evidence реализации

После155-run проверка новых файлов нашла одну лишнюю пустую строку EOF в
AgentOrderHttpTest.kt. Первоначальная shell-обёртка также ошибочно воспринимала
обычный exit1 git no-index для нового файла как whitespace failure без диагностики;
исправленная обёртка различает exit-code и реальный whitespace diagnostic.
Удалён ровно **один LF в конце test-файла**, production не менялся.

Точность правки доказана SHA: исходный test восстановлен как current+один LF,
его SHA совпал с manifest155-run. Копия before test и доказательство сохранены:
`build/migration-29-3-format-before-AgentOrderHttpTest.kt`,
`build/migration-29-3-format-proof.json`, `...-format-delta.json`.
Из582 файлов manifest изменился ровно этот один test; production и остальные581
файл совпадают. После правки запущены оба затронутых HTTP-класса и тот же actual
JAR smoke. Непосредственно наблюдён **exit0, BUILD SUCCESSFUL1m34s**:
**11выполнений/3класса,0failures/errors/skips** (legacy4,modern6,smoke1).

Текущие артефакты этого отдельного invocation:

- `build/migration-29-3-format-final.log`, `.exit`, `...-results/`, `...-summary.json`.
- `build/migration-29-3-format-final-source.json`:582files, повторный hash-check
  **0mismatches** после завершения. SHA256:
  `10f8cc66d707422fcedbdd21cd8031f06d34d06766a3e6a17b3068cfa4567184`.
- `build/migration-29-3-current-hashes.json`, `...-current-combined-summary.json`.
- `build/migration-29-3-format-bootjar-legacy.log`, `...-modern.log`: оба режима
  снова реально выполнены и закрыты. Snapshot JAR hash и текущий artifact hash
  совпадают с прежним `28182ee96f837c7670a159d1af8909183c2717f2fc2d7572fa924c3c9aa34645`.

**Актуальное совокупное покрытие155выполнений/16классов — ДВА invocation**:
13неизменённых классов/144выполнения из155-run плюс3класса/11выполнений последнего
прогона вместо их старых результатов. Это не новый единый155-run и не166тестов;
перекрывающиеся11 не суммируются дважды. Собственные22 проверки актуальны.
Предыдущие155-log/XML/source manifest сохранены отдельно и не переименованы
в final для изменённого test snapshot.

После последнего процесса source не менялся; текущие source/hash проверки чистые.
Новые файлы и единственный согласованный existing прошли whitespace check.
Принятая30 в общей master a268372 не переносилась: read-only diff relevant Kotlin
owner-кода42ab0aa→a268372 пуст, статический вывод выше сохраняется. **Совместного
runtime29.3+30 нет**, он остаётся последующей интеграционной проверкой после переноса.

Итог реализации готов для координатора: тот же14file scope, никакого staging/
commit/remote/deploy, HEAD9d745cb. Самостоятельный аудит не начат. Теперь возможно
отдельно порученное последним сообщением подтверждение доступности context tools;
аудит и local commit всё ещё требуют новых отдельных сообщений.

## Отдельный context step после завершения реализации

Последнее отдельное сообщение координатора поручило этот шаг после окончания
текущего прогона и окончательного отчёта. Прогон завершён exit0,11/3 XML сохранены,
итоговый implementation report выше закончен **до** этой проверки tools.

После реализации заново проверены доступные tool metadata: команды настоящего
сжатия контекста **нет**, счётчика использования/заполнения контекста **нет**.
Совпадения поиска относятся к компактным snapshots чатов/презентаций и другим
предметным инструментам, а не к сжатию этого контекста. Выполнить сжатие или
сообщить измеренный процент невозможно. Сводка вместо сжатия не создавалась.

Самостоятельный новый аудит и commit не начинались. Source не менялся; добавлен
только этот раздел отчёта. Работа остановлена до следующего отдельного сообщения.

## Отдельный самостоятельный аудит — 10–11.11.2026

Аудит начат только после следующего отдельного поручения координатора, после
завершённой реализации и отдельного context step. Прежние155/16 в двух invocation
не объявлялись аудитом. Заново проверены все14owned файлов и фактические Java
AdminAgentController/AdminAgentService, OrderServiceImpl/OrderRepository/mapper/DTO,
GlobalExceptionHandler, AgentAccountAccessFilter/security; effective SQL V1.0/1.2/
1.16/1.21/1.22/1.23 и последующие giveaway constraints; принятые ordering25/26/27,
catalog reservation, identity locks/JPA refresh и shared verified Actor/decoder.
План и direct assignment определяют scope; любые прежние указания плана про push
не применяются из-за прямого запрета пользователя. Новых маршрутов agent_access нет.

До исправлений все14owned файлов скопированы в `build/migration-29-3-audit-before/`,
сохранены `...-audit-before-owned.json` и полный `...-audit-before-source.json`.
Два новых regression test добавлены **до** изменения production. Repro:
непосредственно наблюдён **exit1, BUILD FAILED46s,2tests/2failures**.
`build/migration-29-3-audit-repro.log`, `.exit`, `...-repro-results/` сохраняют
завершённые XML; source manifest `...-audit-repro-source.json` снят во время этого
процесса без source edits между запуском и завершением. Это доказательство
дефектов, не успешная проверка. Production тогда совпадал с implementation snapshot.

### Findings и исправления

1. **Miswired JdbcTemplate мог записать audit вне caller transaction.**
   Guard LifecycleTransaction подтверждал настоящую application JPA-транзакцию,
   но не идентичность DataSource самого audit template. Repro использовал второй
   реальный DriverManagerDataSource к той же приватной PostgreSQL при действующей
   caller JPA tx: исключение не возникло, после caller rollback осталась **1audit
   строка**; это напечатано в XML до обязательной очистки fixture. Удалённой БД или
   production в тесте нет. Это недостаток защиты при внутренней ошибке wiring,
   не заявление о возможности выбирать DataSource через HTTP.

   JdbcAgentOrderAudit теперь непосредственно проверяет actual writable tx,
   joined EntityManager именно application EMF, bound application DataSource и
   `jdbc.jdbcTemplate.dataSource === dataSource`, дополнительно к принятому guard.
   Отказ LifecycleTransactionRequired происходит **до SQL**. Adapter не начинает
   транзакцию, не commit/suspend/retry, не использует REQUIRES_NEW. Тесты также
   проверяют no-tx/read-only/JDBC-only/actual foreign EMF, actual wrong manager
   с application EMF и другим DS, unproxied command и прямой audit с no-op внешним
   guard. Application execute не владеет JdbcTemplate: его SQL делегирован
   принятым owner LifecycleStore/catalog, а caller resources проверяет accepted
   LifecycleTransaction; никакого downcast или вмешательства в owner25 не добавлено.

2. **Global queue могла вернуть статус вне фильтра и смешать старого seller с
   новым order snapshot.** Между bounded candidate IDs и SelectedOrderRead другой
   действующий admin может выполнить переход в своей JPA tx. Repro через реальный
   AgentOrderCommands.CANCEL вернул CANCELLED в очереди с фильтром BOOKED. Candidate
   login также читался до enrichment, хотя legacy читает sellerLogin после него.

   Новый JdbcAgentOrderQueue сохраняет selected seller ID, после bounded projection
   отбрасывает изменившегося seller и статус вне requested filter; не следует
   новым participant IDs и не добирает следующую страницу. Затем bounded scalar
   SQL читает актуальный login только retained seller IDs с is_agent=true. Статус
   target agent по-прежнему не ограничивается, BLOCKED/DELETED разрешены. Проверка
   покрывает реальный competing owner CANCEL, seller reassociation и login change
   между selection/projection. Stable-data порядок, фильтры/DTO/pagination прежние;
   IDs/SQL параметры bound, nullable CAST bigint/text сохранены. Ни SELECT всех
   seller orders, ни копии mapper/history/enrichment не добавлено.

   **Явный предел совместимости:** при concurrent membership/status change modern
   пропускает stale candidate и может вернуть меньше size; legacy мог вернуть
   ранее отобранный raw snapshot. Это намеренное сохранение корректного фильтра
   и отказ следовать новому owner, а не repeatable-read гарантия и не молчаливое
   обещание прежнего concurrent snapshot. READ_COMMITTED сохраняется; состояние
   после последнего SELECT всё равно может измениться. Учитывать при32.

3. **Ряд concurrency/token assertions допускал неверную причину успеха.**
   Прежняя проверка двух ожидающих сессий не связывала их с конкретными workers;
   часть одиночных проверок сравнивала лишь SQL fragment. Теперь каждый worker
   начинает ограниченную caller JPA tx, сообщает свой backend PID до операции,
   получает LOCAL statement_timeout15s/lock_timeout12s. Проверки сравнивают exact
   worker PID, полный normalized SQL, root holder PID; два CAS workers проверяются
   по их PID и цепочке blockers до конкретного root, не по постороннему count.
   pg_stat_clear_snapshot,8s wait и12s Future bounds сохранены; sleep/retry/deadlock
   swallowing нет. CAS loser STATUS_CHANGED теперь проверяется для всех4actions
   в обоих ID-порядках, а не только CANCEL.

   Seller/customer reassociation проверяется в обоих caller/agent ID-порядках;
   replacement participant имеет меньший ID и удерживается отдельным FOR NO KEY
   UPDATE, совместимым с FK, но блокирующим новый FOR UPDATE. Отказ завершается,
   пока эта блокировка ещё удерживается: новый ID за frontier не захватывается.
   Добавлены fresh target.is_agent revoke и BLOCKED/DELETED target после wait.
   HTTP401/403 теперь exact по типу credentials, включая real issue13+refresh,
   signed malformed role/type/id, expired и другой RSA key; stale current caller
   проверяет точный JSON403/пустой bot403. Human binding, invalid-page-before-target,
   nullable SHIP, raw URL/comment и SQL длина URL проверены в обоих профилях.

Исправлен также неточный test comment: suspended address NOT NULL создаёт
**намеренно malformed fixture**, а не legal historical SQL row. Ограничение
восстанавливается в finally в собственной test DB; это не Flyway/schema change.

### Повторная проверка архитектуры и существующего projection

- Все3HTTP маршрута совпадают с actual legacy; отдельного detail route нет.
  Verified principal проходит ACCESS+ADMIN gate; текущий ACTIVE nonagent caller
  и target.is_agent проверяются owner locks в той же tx. Caller/agent/customer/
  seller DISTINCT ASC полностью захвачены до повторного pair AgentAccessLock,
  затем order lock и fresh parties/ownership/status. Missing order всё равно
  авторизует caller/target; unknown String action проверяется после target/order.
- ID/scalar boundary сохраняется. Seller не пишет foreign tables; domain matrix
  LifecycleOrder.transition, guarded LifecycleStore.transition и stock owner
  переиспользованы. Audit FK admin/agent/order уже входят в удерживаемый set.
  Nonpositive quantity пропускает positive stock port, как legacy; история только
  existing trigger. Late named SQL23514, overflow и outer rollback проверяют весь
  order/stock/history/audit эффект, не лишь HTTP ошибку. Ни PAY/case матрицы, ни
  SQL stock/history writer в seller/новом command не скопированы.
- Единственный existing JdbcLifecycleRead **не изменялся в аудите**: SHA совпал
  с before snapshot. Новая независимая проверка mapper против HEAD подтверждает
  только extraction/return/отступы, прежние enrichers идентичны. Old orders SQL,
  DTO/null/errors/history ORDER BY и photo/product fallback неизменны. Bounded
  SelectedOrderRead empty→empty, max100; вне-page malformed row не обрабатывается.
  Offset вычисляется Long после page>=0 и size1..100; overflow Int умножения нет.
  Доказательство: `build/migration-29-3-audit-projection-equivalence.json`.
-30 на a268372 прочитан снова только read-only: participant DISTINCT ASC перед
  product/order/image, отсутствие поздних participant locks/следования ассоциациям;
  product cleanup order-reference EXISTS не блокирует order, expiry не получает
  order/participant locks. Протокол не добавляет обратного ребра к29.3. Это **static
  analysis**, не совместный runtime. База29.3 по заданию осталась9d745cb; runtime30
  ждёт переноса/интеграции.12.3 проверен actual ImageCommandRows vs owner command.

### Текущие результаты самостоятельного аудита

Собственный промежуточный audit run: непосредственно наблюдён exit0,
BUILD SUCCESSFUL1m3s, **28executions/4classes,0failures/errors/skips**.
Законченные XML в `build/migration-29-3-audit-own-results/`, счётчики own-summary.
Затем выполнена требуемая регрессия с новым bootJar и RUN_BOOT_JAR_SMOKE=true.
Первая попытка wrapper не исполнилась из-за PowerShell parse error; это не test
failure или начатый Gradle run. Исправлен только wrapper, sources не менялись.

Заключительный audit invocation: непосредственно наблюдён **exit0,
BUILD SUCCESSFUL2m55s,161executions/16classes,0failures/errors/skips**.
Это **один нынешний161-run**, без суммирования старых155/own28/repro2. Executions
не объявляются161 уникальным сценарием: admin-case классы наследуют часть26 checks.

| Нынешние классы | Executions |
| --- | ---: |
| AgentOrderCommandIntegrationTest | 14 |
| AgentOrderQueueIntegrationTest | 2 |
| AgentOrderLegacyHttpTest / ModernHttpTest | 5 / 7 |
| OrderLifecycleLegacyHttpTest / ModernHttpTest | 9 / 21 |
| AdminOrderCaseLegacyHttpTest / ModernHttpTest | 16 / 26 |
| UserOrderCaseLegacyHttpTest / ModernHttpTest | 6 / 14 |
| AgentTokenHttpTest / AgentTokenLegacyHttpTest | 5 / 1 |
| AgentAccessLockIntegrationTest / MediaLockOrderIntegrationTest | 11 / 15 |
| ModernMvcSecurityTest / BootJarDualModeSmokeTest | 8 / 1 |

`build/migration-29-3-audit-final.log` / `.exit`: log и наблюдённый completed exit.
Завершённые XML скопированы только после выхода процесса в `...-audit-final-results/`;
счётчики `...-audit-final-summary.json`, итог `...-audit-final-hashes.json`.
Current manifest `...-audit-final-source.json`: **582files,0mismatches** после run.
SHA256 manifest: `b7a58e17821d2c5eceea412f4734c02eaf6aab00f50b0b9603e9ef4f86160d2f`.

Пересобранный JAR SHA256:
`aeba413fe87342bc78aee9ee4d2011ae9894311e6a5039c09b0cca61f367401f`.
Actual smoke заморозил один artifact, напечатал тот же SHA, выполнил оба
legacy/modern с isolated PostgreSQL/loopback S3/SMTP, проверил startup/OpenAPI/
security и8negative JSON checks в каждом режиме, остановил и дождался обоих JVM.
Логи: `build/migration-29-3-audit-bootjar-legacy.log` / `...-modern.log`.
JDK21.0.11, offline Gradle cache и test RSA keys прежние; production не использован.
Compiler/deprecation и cached-context PostgreSQL shutdown warnings сохранены,
не скрыты и не превращены в тестовые skips.

### Окончательный scope, риски и остановка аудита

Тот же полный14file scope из списка выше. Audit delta production:
**только** JdbcAgentOrderAudit.kt и JdbcAgentOrderQueue.kt; также изменены все3
собственных test-файла и этот отчёт. Ни одного нового пути сверх14 нет.
`build/migration-29-3-audit-delta.json` перечисляет5изменённых src files;
остальные577src/resource/test файлов совпадают с before. В частности,
**все569src/resource/test файлов вне13owned code files** неизменны. Собственный
JdbcLifecycleRead, остальные application/domain/API production файлы тоже unchanged.
Все production/test formatting checks закончены до final run; после него
добавлен только отчёт, текущие source manifests повторно сверены.

Остаются явно описанные границы: static-only30, полная интеграция31/сопоставление32,
READ_COMMITTED concurrent queue omissions, исторический nonpositive productId вне
accepted catalog ID contract и stricter current ACTIVE/nonagent ADMIN/reassociation
refusal против legacy. Полный repository suite, load и mixed legacy/modern rollout
не заявляются. Новых известных actionable дефектов в поддерживаемом own scope
после исправлений и161-run не осталось; это не доказательство отсутствия всех
возможных глобальных races/уязвимостей приложения.

Самостоятельный аудит завершён и остановлен. Ничего не staged/committed;
HEAD9d745cb, ветка codex/migration-29-3-agent-orders. Чужие existing source,
Java/reactive/Flyway/shared/maintenance30/user compose/plan_2/MIGRATION_PLAN не
менялись. Push/fetch/pull/публикация/deploy, новые чаты и подагенты отсутствуют.
Настоящие compaction/counter по-прежнему недоступны; сводка не выдаётся за сжатие.
Local commit возможен только после следующего отдельного сообщения координатора.
