# 23 — подключение товара и розыгрышей

## База и решение

20.10.2026. Выданное managed worktree: `C:/Users/skura/.codex/worktrees/0f41/model-store`.
Ветка `codex/migration-23-product-giveaway-bridge` создана от локальной `master`
`783770f16691286a1e8f3016b7dc5dd0f0dab76d`; HEAD и master сверены до создания ветки.
В `MIGRATION_PLAN.md` зависимости 16, 20 и 22 отмечены `[x]`. Прочитаны отчёты
19, 20 и `modern/catalog/giveaway/admin/MIGRATION_22_REPORT.md`, оба существующих bridge
и guard. Предметные файлы 25/29.1, Java/reactive, план, Flyway, Gradle и Docker не меняются.

**Переходный product/giveaway bridge не включён.** В legacy отсутствует изолированная
граница для общей подмены `ProductService`: транзакционные операции заказа/каталога
используют одну R2DBC connection, а modern команды — JPA. Guard явно отклоняет
`app.migration.scenarios.product=modern` и `app.migration.scenarios.giveaway=modern`
в legacy, вместо молчаливого игнорирования. Полный профиль modern остаётся доступен.
Это отказ от неподдерживаемого opt-in, а не новый переключатель реализации.

## Фактические маршруты и вызовы

Обозначения: **R** = `connectionFactoryTransactionManager`, **J** = JPA
`transactionManager`. `TransactionSelectionConfiguration` выбирает R для
аннотированных Publisher-методов, J для синхронных; явный qualifier сохраняется.
«Без общей tx» означает, что чтение не имеет собственной сервисной `@Transactional`,
но R2DBC repository присоединяется к внешней R tx, если она есть. Наличие JDBC/JPA
beans в legacy (`JpaPersistenceConfiguration`) не объединяет эти две транзакции.

| Маршрут/потребитель | Controller → интерфейс/конкретный сервис → фактические вызовы | Зависимости и tx | Возможная точка переключения и ограничение |
| --- | --- | --- | --- |
| `POST /products/find` | `ProductController → ProductService.findByParams → ProductServiceImpl`; затем private `buildProductDtos(Flux)` → public `buildProductDtos(List)` на том же объекте | `ParticipantService.findAgeById`, `ProductRepository.findByParams`; пакетные `ProductCategoryRepository`, `ImageRepository`, `ParticipantRepository`, `SellerRatingRepository`, `ProductMapper`. Без общей tx | Точечный HTTP hook мог бы изолировать query, но старый controller не передан. Общий `@Primary` затронет все строки таблицы; самостоятельный вызов builder обойдёт wrapper |
| `POST /products/names/find` | `ProductController → ProductService.findNamesBySearch → ProductServiceImpl → ProductRepository.findNamesBySearch` | R2DBC read, без общей tx | Только отдельный HTTP hook либо полный wrapper интерфейса. Новый универсальный ProductService не создаётся ради одного чтения |
| `GET /product/{id}` | `ProductController → ProductService.getProductById → ProductServiceImpl` | `ProductRepository.findActualProduct`, `ImageService`, `ReviewService`, `ParticipantService`, `SellerRatingService`, `CategoryService`, mapper; без общей tx. Этот controller не ставит `X-Robots-Tag` | Отдельный HTTP hook; `CategoryServiceBridge` остаётся legacy. JPA query внутри старой цепочки не даёт общей snapshot/lock границы |
| `POST /products/my` | `ProductController → ProductService.findMyByParams(request,id,admin) → ProductServiceImpl` → private/public builders | `ProductRepository.findMyByParams` и те же batch repositories; без общей tx; ADMIN включает giveaway | Интерфейс общий с внутренними builders и agent read; отдельный HTTP hook не передан |
| `POST /products` | `ProductController → ProductService.createProduct(request,id,role) → ProductServiceImpl` → private overload `createProduct(request,id)` | R; `TransferService`, `SocialNetworksService`, mapper, `ApplicationProperties`, `ProductRepository.save/lockGiveawayActivation/existsOverlappingGiveaway`, `ImageService.updateImagesStatus`, `CategoryService.addLinkProductAndCategories` | Только вся команда с её owner dependencies в одной J tx. J category/image commit из R команды небезопасен; старый product не переписывается |
| `PUT /product/{id}` | `ProductController → ProductService.updateProduct(...,role) → ProductServiceImpl` → private `updateOwnedProduct` | R; participant-agent check, product row + giveaway singleton lock, `OrderRepository.existsActiveOrderForProduct`, save, image activation, category replacement | Требует единой product/media/category/order-check tx. Переключить лишь часть без изменения старой логики нельзя |
| `DELETE /product/{id}` | `ProductController → ProductService.deleteProduct → ProductServiceImpl` | R; actual product FOR UPDATE, save DELETED, `ImageService.deleteImagesByEntityId` | Отдельная J команда возможна в полном modern; общий wrapper меняет также внутренние product зависимости |
| `POST /products/extend/{id}` | `ProductController → ProductService.extendExpirationDate → ProductServiceImpl` | R; `ProductRepository.findProductForExtendForUpdate/save`, properties expiry | Тот же метод вызывает `AdminAgentService`; глобальная подмена непредсказуемо меняет agent сценарий |
| `PUT /admin/actions/product/{id}?productStatus=…` | `AdminController → ProductService.updateProductStatus → ProductServiceImpl` | R; giveaway singleton lock, product FOR UPDATE, save | Статус влияет на giveaway window/booking; modern команда только в полном modern |
| `GET /giveaways/active`, `GET /giveaways/products/{productId}` | `GiveawayController → ProductService.findActiveGiveaway/findPublicGiveawayById → ProductServiceImpl` → private `toGiveawayResponse` | `ProductRepository`, `ImageService.findActualImages`; без общей tx; controller ставит `X-Robots-Tag` только на успешном ответе | Отдельного GiveawayService нет. Изоляция через HTTP hook; общая интерфейсная подмена product без него не принята |
| `GET /admin/actions/giveaways/history` | `AdminGiveawayController → ProductService.findGiveawayHistory → ProductServiceImpl`; requireAdminId остаётся controller | `ProductRepository.findGiveawayHistory`, private `withEffectiveGiveawayStatus`; без общей tx | Только отдельный admin HTTP hook; DTO — старый Product, а не современная domain/JPA сущность |
| `GET /admin/actions/giveaways/{productId}` | `AdminGiveawayController → ProductService.findAdminGiveawayDetails → ProductServiceImpl` → public `findAdminGiveaway` на том же объекте | product/agent ownership repositories, `ImageService`, `CategoryService`; без общей tx | Wrapper метода `findAdminGiveaway` не перехватит self-invocation. Полный modern вызывает `AdminGiveaways.details` |
| `PUT /admin/actions/giveaways/{productId}` | `AdminGiveawayController → ProductService.updateAdminGiveaway → ProductServiceImpl` → `findAdminGiveaway` → private `updateOwnedProduct` | R; product/participant/order/category/image repositories и giveaway lock | Подмена generic update не переключит этот вызов; нужен единый giveaway owner command J, уже доступный в полном modern |
| `GET /order?productId=…`, `POST /order/BOOKED` | `OrderController → OrderService → OrderServiceImpl`; prepare → `ProductService.findActualProduct`; createOrders → protected `createOrder` → `findActualProductForUpdate` → protected `createOrderAndUpdateProduct` → `decrementCountIfSufficient`, `BasketService.removeFromBasket`, order save | Prepare без общей tx. BOOKED — R: `OrderRepository`, `ParticipantService`, `AddressService`, `TransferService`, mapper, basket. Row lock/stock/order/basket в одной R tx | Нельзя подменять product lookup/lock/decrement на JPA внутри старого заказа. `boundedElastic` не переносит connection/lock и не объединяет commit |
| Заказные списки/детали и закрытие | `OrderController/AdminAgentController → OrderService/конкретный AdminAgentService → OrderServiceImpl`; private `findOrderByProduct` → `ProductService.shortInfoById`; closure → private restock → `findByIdForUpdate/incrementCountIfLimited` | Read без общей tx; closure — R; `OrderStatusHistoryService`, `ImageService`, `OrderCaseRepository` и прочие order dependencies | `shortInfoById` внутри ProductServiceImpl вызывает `findById/buildProductDto` самостоятельно. Возврат остатка обязан остаться с order/history в R |
| Разрешение обращения заказа | `AdminController → конкретный OrderCaseService`; resolve → `ProductService.findByIdForUpdate/incrementCountIfLimited` | R; `OrderRepository`, `OrderCaseRepository`, `ImageRepository`, `ImageService`, `ParticipantRepository`, `DatabaseClient` | Подмена stock write расщепит возврат и решение обращения. Пункт 23 это не переключает |
| `POST /basket/find`, `POST,PUT,DELETE /basket` | `BasketController → BasketService → BasketServiceImpl`; list → `ProductRepository.findBasketByParams` → `ProductService.buildProductDtos`; add/update → `findActualProduct`; delete → basket repo (также из заказа) | `ParticipantService`, `ProductRepository`, `ProductBasketRepository`; add/update R, list/delete без собственной общей tx | Builders и product lookup являются внутренними зависимостями корзины; общий bridge меняет их тоже. Modern basket использует собственные блокировки |
| `POST /favorites/find`, `POST,DELETE /favorites` | `FavoriteController → FavoriteService → FavoriteServiceImpl`; list → `ProductRepository.findByParams` → `ProductService.buildProductDtos`; add/delete напрямую repositories | `ParticipantService`, `ProductRepository`, `ProductFavoriteRepository`; без общей сервисной tx | Замена ProductService не переключит выборку продукта в favorite, но изменит сборку DTO; частичная подмена не объявляется |
| `POST /agent/products` | `AgentController → ProductService.createAgentProduct → ProductServiceImpl` | R; active is_agent check, mapper вручную, product save, image/category links; `JwtService` в controller проверяет agent token | Eligibility отличается от обычного createProduct. Generic ProductCommands не заменяет agent owner command; новый owner port относится к 29.1 |
| `GET /admin/actions/agents/{agentId}/products`, `GET /admin/actions/products/{productId}` | `AdminAgentController → конкретный AdminAgentService.products/product → ProductRepository` напрямую | `ParticipantRepository` requireAgent, `ProductRepository`; без общей tx | `@Primary ProductService` вообще не переключит эти read paths. Нельзя объявлять все agent товары переключёнными |
| `PUT /admin/actions/agents/{agentId}/products/{productId}`, `POST …/extend` | `AdminAgentController → конкретный AdminAgentService.updateProduct/extendProduct → ProductService.updateAgentProduct/extendExpirationDate → ProductServiceImpl` | Agent check без собственной общей tx, затем R на product proxy; private `updateOwnedProduct`, product/category/image/order repositories | Read останется R, write превратится в J при общей подмене. Административные agent сценарии оставлены 29.1 |
| Agent orders/action | `AdminAgentController → конкретный AdminAgentService.orders/actOnOrder → OrderService → OrderServiceImpl` → product info/restock | actOnOrder R, audit `AdminAgentOrderActionRepository` + order transition; read `OrderRepository`, participants, images | Product J write нельзя независимо commit до audit/order rollback |

Сопутствующие прямые потребители: `ProductUpdateScheduler` и `ProductCleanupScheduler`
используют **ProductRepository напрямую**, не ProductService. ProductService `save` и
`findExpiredActiveProductIds` существуют в интерфейсе, но production вызовов снаружи
ProductServiceImpl не найдено. Это тоже не основание предполагать глобальное переключение.

Все инъекции ProductService в production: четыре controller (Product, Admin,
Giveaway, AdminGiveaway), AgentController и четыре сервиса (OrderServiceImpl,
OrderCaseService, BasketServiceImpl, FavoriteServiceImpl), плюс AdminAgentService.
Прямых инъекций **конкретного ProductServiceImpl** в production не найдено, однако
внутренние вызовы, private overloads и прямые repository обходы остаются.

Проверенные self-invocation, которые не перехватывает новый `@Primary`-wrapper:

- `ProductServiceImpl.shortInfoById → findById().flatMap(this::buildProductDto)`;
  `buildProductDto → buildProductDtos(List)`;
  `findByParams/findMyByParams → private buildProductDtos(Flux) → buildProductDtos(List)`;
  двухаргументный `findMyByParams → findMyByParams(...,false)`.
- Трёхаргументный `updateProduct → updateProduct(...,USER)`, затем private
  `updateOwnedProduct`; `createProduct(...,role) → private createProduct`;
  `updateAdminGiveaway → findAdminGiveaway → updateOwnedProduct`;
  `findAdminGiveawayDetails → findAdminGiveaway`;
  `updateAgentProduct → updateOwnedProduct`. Внешний аннотированный Publisher-метод
  открывает R tx, private/internal вызовы продолжают её; самостоятельной proxy tx у них нет.
- `OrderServiceImpl.createOrders → protected createOrder → protected
  createOrderAndUpdateProduct` остаётся внутри внешней R tx. Вызовы product proxy
  lock/decrement сами не открывают JPA tx. `closureOrder → restoreStockIfNeeded`
  вызывает product lock/increment в той же внешней R tx.
- `AdminAgentService.updateProduct/extendProduct` проверяют агента, затем вызывают
  product proxy. `products/product` обходят его полностью через свой ProductRepository.
  `actOnOrder` объединяет order transition и audit в R tx; делегирование product
  в JPA разрушило бы этот rollback даже при неизменном ADMIN HTTP controller.

## Modern соответствия и матрица конфигурации

| Legacy путь | Полный modern путь / tx |
| --- | --- |
| Поиск и имена | `ProductSearchController → ProductSearchQuery → ProductSearchReadPort/JdbcProductSearchReadPort`; J readOnly |
| Карточка / мои | `ProductDetailController → ProductDetailQuery → ProductDetailReadPort/JdbcProductDetailReadPort`; J readOnly |
| Обычная запись, delete/status/extend | `ProductCommandController → ProductCommands → ProductCommandStore/JdbcProductCommandStore`, seller eligibility, `ProductImageAttachment`, category links; J |
| ADMIN создание/редактирование GIVEAWAY через товар | `ProductCommandController → CreateGiveaway/UpdateGiveawayProduct → ProductCommandStore + AdminGiveawayStore`, media links; J |
| Public giveaways | `PublicGiveawayController → PublicGiveawayQuery → PublicGiveawayReadPort`; J readOnly |
| Admin history/detail/update | `AdminGiveawayController → AdminGiveaways → AdminGiveawayStore/JdbcAdminGiveawayStore`; history/details — JDBC без общей сервисной tx, update — J |
| Заказ / reservation | `OrderCreateController → CreateOrder → ProductReservation`; единая J, reservation требует уже открытую owner tx, без отдельного commit |
| Агентские товары | modern 29.1 ещё не в этой базе; наличие полного modern runtime не означает готовность всех agent routes |

| Режим | Настройка | Результат |
| --- | --- | --- |
| default/legacy (`!modern`) | product/giveaway отсутствуют или `legacy` | Прежний ProductServiceImpl; ни один его метод не делегирует modern |
| default/legacy | `product=modern` или `giveaway=modern` | Явный startup failure с именем свойства, причиной и направлением на full modern; HTTP ready не достигается |
| default/legacy | `category=modern` | Прежний запрет сохранён: общая R2DBC/JPA запись не доказана |
| default/legacy | `dictionary=legacy/modern` | Принятый bridge 19 сохранён; modern JDBC выполняется deferred на boundedElastic в J readOnly |
| modern | product/giveaway/category с любыми значениями или без них | Guard отсутствует. Реализацию выбирает профиль modern и его MVC graph; per-scenario map не является переключателем внутри modern |

Новых granular flags `product-search`, `product-detail`, `giveaway-public` не вводится.
Не заявляется поддержка произвольных ключей свободной scenario map. Для безопасного
изолированного read bridge нужен переданный controller hook или отдельно согласованная
маршрутизация, чтобы не создавать универсальный wrapper всех 30 методов ProductService.
Переход на JPA внутри Reactor даже на boundedElastic не решает atomicity/row locks.
Modern MVC controllers имеют `@Profile("modern")`; legacy graph не получает их.

## Проверки

Общее окружение всех запусков:

```powershell
$env:JAVA_HOME='C:/Users/skura/.jdks/temurin-21.0.11'
$env:GRADLE_USER_HOME='C:/Users/skura/.gradle'
$env:JAVA_TOOL_OPTIONS='-Dapp.public-key-path=keys/test_public_key.pem -Dapp.private-key-path=keys/test_private_key.pem'
```

Первый собственный запуск завершился exit 1 только из-за ошибочного ожидания 409
в двух новых проверках поздней ошибки категории. Фактический принятый legacy
`GlobalExceptionHandler` уже возвращает `400 INVALID_REFERENCE` для FK; ожидание
исправлено, production логика не менялась. Лог: `build/migration-23-first.log`.
XML не использовался для подсчёта неуспешного запуска.

```powershell
.\gradlew.bat --offline --no-daemon test --tests 'com.model_store.bridge.config.*' *> migration-23-own.log
```

**Завершённый повтор: exit 0, BUILD SUCCESSFUL in 1m 27s; 21 тест в 5 классах,
0 failures/errors/skipped.** После exit 0 лог перемещён в
`build/migration-23-own.log`, XML сохранены в `build/migration-23-own-results/`:
guard — 7, default legacy HTTP — 4, explicit legacy HTTP — 4, full modern HTTP — 4,
real unsafe startup — 2. Каждая HTTP-конфигурация имеет собственную embedded
PostgreSQL и mock S3/SMTP, test RSA; HTTP connect/request timeouts — 2/10 секунд.
У отказов реального startup JUnit timeout — 90 секунд; сравнивается содержимое
product/order/basket/favorite/category-links/image/participant до/после отказа.

В обоих legacy вариантах и полном modern выполнены настоящие HTTP public query,
names/card/my, создание и edit/status/extend, public giveaway с точными JSON полями,
Content-Type/X-Robots-Tag/404, ADMIN history/detail/update, ошибки и RSA
USER/ADMIN/agent_access/refresh/verify/forged. Зафиксированы прежние различия
verify/agent_access из отчёта22; поисковый optional bearer остаётся гостевым при
непригодном токене. Откат поздней FK ошибки сохраняет product/image; отказ второй
строки BOOKED откатывает первую запись заказа, stock и basket, затем успешная
покупка меняет их атомарно. Избранное/корзина возвращают прежний product DTO.
В двух legacy вариантах дополнительно выполнены agent create/list/detail/edit/extend;
в modern проверено отсутствие старого ProductService и наличие новых MVC controllers.
Маршруты modern agent 29.1 не объявлены поддержанными этой базой.

Команда общей регрессии и BootJar:

```powershell
$env:RUN_BOOT_JAR_SMOKE='true'
.\gradlew.bat --offline --no-daemon bootJar test --tests 'com.model_store.bridge.*' --tests 'com.model_store.modern.catalog.*' --tests 'com.model_store.modern.ordering.create.*' --tests 'transaction_selection.*' --tests 'com.model_store.modern.shared.config.*' --tests 'com.model_store.modern.shared.api.BootJarDualModeSmokeTest' *> build/migration-23-regression.log
```

**Общая команда: exit 0, BUILD SUCCESSFUL in 13m 22s; 144 теста в 40 классах,
0 failures/errors/skipped, bootJar собран.** XML прочитаны только после exit 0 и
сохранены в `build/migration-23-regression-results/`; полный список классов/чисел —
`build/migration-23-regression-summary.json`. 21 собственная проверка включена в 144,
это не 165 уникальных тестов.

| Группа | Классов | Завершённых тестов |
| --- | ---: | ---: |
| Bridge/guard/собственные real HTTP/startup + dictionary bridge | 7 | 28 |
| Product search/detail/command/reservation | 9 | 36 |
| Public/admin giveaway | 5 | 18 |
| Basket/favorite/category/dictionary | 10 | 25 |
| Order create legacy/modern | 2 | 16 |
| Общие security/CORS/giveaway parity + dual-mode JAR | 5 | 15 |
| Выбор R2DBC/JPA менеджера | 2 | 6 |
| **Всего** | **40** | **144** |

В том числе завершены все 13 AdminGiveawayHttpTest (пересечения/конкуренция,
rollback изображения/категории/настроек, конверсия и reservation), 10 reservation,
7 ProductCommandHttpTest, 12 OrderCreateIntegrationTest, 4 legacy order и
реальные legacy/modern public/admin giveaway parity. В существующих parity
проверках сохранены точные intentional differences: подписанный `verify` на
admin history — legacy 200 / modern 401, подписанный ADMIN `agent_access` с
issuedBy=admin — legacy 200 / modern 403. Они не исправлялись пунктом 23.

`BootJarDualModeSmokeTest`: **1 выполненный тест, skipped=0, 56.286 с**.
Единый исходный JAR и его private snapshot имеют SHA-256
`55dad6d367edcf764817881cc24eee18a743fef07864821172a4af02630e4a22`.
Hash исходного артефакта сверён перед smoke и после общей команды; тест проверил hash
snapshot до/после каждого режима. XML содержит обе отдельные строки
`bootJar legacy/modern: S3/SMTP startup, OpenAPI/security and all eight negative JSON cases passed`.
Тот же XML подтвердил остановку JVM legacy 39312 и modern 24732; embedded PG
получила fast shutdown и завершилась. Использованы встроенные loopback S3/SMTP,
очищенное окружение и test RSA, Docker/localhost:9000 не требовались. Исходники
исправленного smoke из принятой базы не менялись. Логи режимов создаются
существующим тестом как `build/migration-20-bootjar-legacy.log` и
`build/migration-20-bootjar-modern.log`; их копии этого запуска сохранены с
префиксом `migration-23-bootjar-`.

Финальная читающая проверка: порты приложения 64079/64299 больше не слушаются;
PG smoke PID 29628/34940 и legacy JVM 39312 отсутствуют. PID modern JVM 24732
после её подтверждённой тестом остановки переиспользован другим postgres.exe,
созданным в 21:23:05 (smoke завершён в 21:21:27); этот чужой процесс не останавливался.

Длительность полной команды включает многочисленные новые embedded PG и закрытие
Spring context cache. Во время teardown есть предупреждения Hikari о соединениях
с уже остановленными тестовыми PG; ошибок/пропусков тестов нет. Диагностические
снимки потоков собственного worker подтверждали продвижение detail → reservation →
legacy order, без остановки или изменения чужих процессов.

## Состояние этапа 1 и ограничения

Production diff — только `bridge/config/LegacyScenarioSafetyGuard.kt`.
Собственные проверки: расширение `LegacyScenarioSafetyGuardTest.kt` и новые
`ProductGiveawayRuntimeHttpTest.kt`, `ProductGiveawayStartupFailureTest.kt`.
Пятый файл — этот отчёт. Старый Java/reactive, modern предметные пакеты,
MigrationScenarioProperties, Flyway, Gradle, Docker, MIGRATION_PLAN,
пользовательские docker-compose.yml/plan_2.md не редактировались. Новые product/
giveaway bridges и флаги частичного переключения не создавались.

Полный modern runtime доказан, готовность ещё отсутствующих в базе modern agent
routes 29.1 и order lifecycle 25 не заявляется. Startup guard проверен в настоящем
SpringBootTest-приложении; отдельный отрицательный запуск packaged JAR с опасным
opt-in не добавлялся. Сам артефакт реально запущен в двух поддерживаемых режимах.

Этап 1 завершён **без commit/push**, HEAD остаётся исходным `783770f`.
`git diff --check` проходит; галочку плана ставит координатор. Это зафиксированное
состояние этапа 1; отдельный аудит и его повторные проверки описаны ниже.
В доступных инструментах этого чата отсутствуют команда настоящего сжатия и
счётчик `last_token_usage.total_tokens/model_context_window`; сжатие не выполнялось,
этот отчёт им не является.

## Самостоятельный аудит — отдельное поручение 20.10.2026

### Найдено и исправлено

1. **Обход guard глобальной lazy initialization.** Обычный `@Component` без
   обязательного потребителя при `spring.main.lazy-initialization=true` не создавался
   до готовности приложения. У product/giveaway это означало молча принятый
   неподдерживаемый opt-in; у существующего category bridge — возможность обойти
   запрет смешанной R2DBC/JPA записи. Добавлены настоящие startup-варианты
   lazy-product/lazy-giveaway/lazy-category. До исправления команда
   `test --tests com.model_store.bridge.config.ProductGiveawayStartupFailureTest`
   завершилась exit 1: **5 выполнено, 3 ожидаемо упали**, потому что IllegalStateException
   не возникло; `build/migration-23-audit-repro.log`, BUILD FAILED in 1m 3s.
   В единственном production файле guard добавлено **`@Lazy(false)`**.
   Проверки category и profile `!modern` сохранены, разрешение dictionary не менялось.
2. **Неполное доказательство readiness/отсутствия частичных записей.** Ранний тест
   проверял только причину startup failure и снимок преимущественно пустых таблиц.
   Теперь initializer наблюдает `ApplicationReadyEvent` и
   `AvailabilityChangeEvent(ReadinessState.ACCEPTING_TRAFFIC)` до запуска контекста:
   все пять запрещённых вариантов должны иметь оба счётчика 0. Положительный контроль
   safe lazy legacy с product/giveaway/category=legacy и dictionary=modern проверяет
   оба счётчика равными 1. Контроль не позволяет «успешно» пройти отрицательным
   assertions при неработающем наблюдателе. Перед каждым отказом создаётся непустой
   контрольный граф participant/product/image/category-links/basket/favorite/order;
   сравниваются полные строки 11 бизнес-таблиц, включая order history/address/transfer.
3. **Default и изоляция тестов.** Раньше «default» означал отсутствующие scenario flags
   с явно активным legacy profile. Теперь первый HTTP-контекст не задаёт профиль,
   имеет `spring.profiles.active=` и явно проверяет пустой activeProfiles и legacy
   ProductService graph. Отдельный explicit legacy контекст сохранён. Собственные
   HTTP/startup серверы привязаны к `127.0.0.1`, импорт `.env` отключён; HTTP proxy
   запрещён, connect/request deadlines 2/10 секунд сохранены. Все DS/Flyway/R2DBC
   параметры указывают на embedded PG, ключи тестовые, S3/SMTP — mock beans.
4. **Порядок cleanup собственных HTTP-fixtures.** PG раньше закрывалась в JUnit
   AfterAll, а Spring context оставался в cache. Теперь AFTER_CLASS DirtiesContext
   закрывает web server/bean graph/pools, после него собственный TestExecutionListener
   останавливает PG; порядок задан относительно DirtiesContext listener с учётом
   обратного порядка afterTestClass callbacks. Каждая PG зарегистрирована по классу
   и удаляется при закрытии. Нельзя закрывать ApplicationContext вручную из JUnit
   AfterAll до оставшихся Spring listeners: промежуточная попытка это показала и
   была исправлена. В том же промежуточном запуске sentinel order не содержал
   обязательные snapshot-поля V1.21; fixture исправлена. Лог неуспешной проверки
   только тестовых setup/cleanup — `build/migration-23-audit-own.log`, exit 1;
   его XML не использовался для положительных чисел.
5. **Точность таблицы.** Убрано неверное утверждение о X-Robots-Tag на
   `/product/{id}`: его ставит public GiveawayController. Self-invocation
   shortInfo уточнён как `findById().flatMap(this::buildProductDto)`;
   `findById` сам DTO builder не вызывает. Остальные строки повторно сверены
   с конкретными ProductServiceImpl/AdminAgentService/OrderServiceImpl/OrderCaseService,
   basket/favorite и `TransactionSelectionConfiguration`.

### Границы, безопасность и предел утверждений

Новой замены ProductService, JPA-вызовов внутри старого order/agent, product/giveaway
адаптеров и отдельных commit не появилось. Предметные классы и owner ports только
читались. Guard зависит лишь от config properties и не исполняет SQL/HTTP/SMTP/S3;
force-eager проверка не меняет выбор transaction managers и не переносит бизнес-логику
в инфраструктуру. Dictionary остаётся deferred boundedElastic + J readOnly;
CategoryServiceBridge не менялся, его opt-in в legacy по-прежнему запрещён, теперь
также при global lazy init. Старые controllers/services/Flyway и конфигурация 25/29.1
не переписывались. Разрешённый планом отказ от частичного переключения сохранён.

«Нет частичных записей» в этом отчёте означает неизменность проверенных бизнес-строк,
а не отсутствие любых SQL statement: штатный Flyway/schema bootstrap может быть
раньше создания guard. Тесты предварительно применяют миграции к отдельной PG.
Negative startup проверяется настоящим Spring application context, не отдельным
отрицательным запуском JAR. Default/full modern поддержаны; полного modern покрытия
ещё не принятых 25/29.1 нет. Свободная scenario map не получила проверки произвольных
неизвестных ключей; задокументированы ровно product/giveaway/category/dictionary.

JWT/security code и JSON DTO не менялись. Более слабое legacy принятие подписанных
verify/ADMIN agent_access на admin giveaway read остаётся известной границей для 32,
а не объявляется совпадением режимов или новым исправлением 23. Смешанная
R2DBC/JPA атомарность по-прежнему не доказана; ни один переключатель её не обещает.
После исправления lazy guard новых подтверждённых production находок в diff 23 нет.

### Проверки после исправлений

Общее JDK/Gradle/test RSA окружение — как выше. Завершена команда:

```powershell
.\gradlew.bat --offline --no-daemon test --tests 'com.model_store.bridge.config.*' *> build/migration-23-audit-own-final.log
```

**Exit 0, BUILD SUCCESSFUL in 1m 47s; 25 тестов в 5 классах,
0 failures/errors/skipped.** Guard — 7, три HTTP runtime контекста — по 4,
startup — 6 (пять отказов и один положительный readiness control). XML сохранены
только после exit 0 в `build/migration-23-audit-own-results/`.

Финальная команда на исправленном состоянии:

```powershell
$env:RUN_BOOT_JAR_SMOKE='true'
.\gradlew.bat --offline --no-daemon bootJar test --tests 'com.model_store.bridge.*' --tests 'com.model_store.modern.catalog.category.CategoryLegacyBridgeTest' --tests 'com.model_store.modern.catalog.category.CategoryOptInBridgeTest' --tests 'transaction_selection.*' --tests 'com.model_store.modern.shared.config.*' --tests 'com.model_store.modern.shared.api.BootJarDualModeSmokeTest' --tests 'com.model_store.modern.catalog.product.command.*' --tests 'com.model_store.modern.catalog.giveaway.admin.*RealHttpTest' *> build/migration-23-audit-regression.log
```

**Exit 0, BUILD SUCCESSFUL in 5m 11s; 66 тестов в 20 классах,
0 failures/errors/skipped, bootJar пересобран.** Включены все 25 собственных
проверок, 7 dictionary bridge, 3 category guard/bridge, 6 выбора tx, 8 product command,
2 admin giveaway real HTTP, 14 общих security/CORS/public giveaway parity и 1 smoke.
XML прочитаны после exit 0 и сохранены в `build/migration-23-audit-regression-results/`;
полный список — `build/migration-23-audit-regression-summary.json`.

Повторный `BootJarDualModeSmokeTest`: **1 выполнен, skipped=0, 43.217 с**,
новый артефакт/private snapshot SHA-256
`29e1e2ebc9e452e3ba53efa554eb11b5b62fc4867b79e0926504d83015ea52d9`.
Исходный hash сверён во время smoke и после завершения команды; snapshot проверен
до/после каждого режима. Обе строки успеха S3/SMTP/OpenAPI/security/восьми
отрицательных JSON и остановки JVM **7568 / 33444** присутствуют в XML.
Логи сохранены как `build/migration-23-audit-bootjar-legacy.log` и
`build/migration-23-audit-bootjar-modern.log`. Порты packaged приложения —
56881/61709; собственных трёх HTTP-контекстов — 55680/55754/50798.
По XML каждого из трёх собственных HTTP-контекстов отдельно проверено: закрытие
JPA/Spring предшествует PG `server stopped`, dead-pool предупреждений в их output нет.
Финальная TCP-проверка не нашла listeners на всех пяти портах. PID 33444 уже
отсутствует, 7568 переиспользован browser-процессом с временем создания 21:44:04
после завершения smoke; чужой процесс не останавливался.

Число 144/40 выше относится к исторической полной регрессии этапа 1 до
`@Lazy(false)`; все 144 повторно на исправленном diff не запускались. Повторный
набор выбран для изменения startup metadata и уточнённых test fixtures; domain
search/detail/reservation/basket/order production код после той полной проверки
не менялся, а связанные обычные HTTP/basket/order rollback повторились в собственных
трёх runtime-контекстах. 25 и 66 не суммируются как уникальные тесты.

Аудит завершён. Обход lazy initialization исправлен; новых незакрытых находок в
production diff 23 не осталось. Остаточные ограничения перечислены выше.
Изменённые файлы остаются теми же пятью: guard, три собственных тестовых файла и
отчёт. Java/reactive, предметные пакеты, миграции, build/deploy файлы и план
не редактировались. На момент завершения аудита HEAD оставался
`783770f16691286a1e8f3016b7dc5dd0f0dab76d`, commit/push не выполнялись.

## Проверка перед публикацией — отдельное поручение 20.10.2026

Получена отдельная команда commit/push. Ветка проверена:
`codex/migration-23-product-giveaway-bridge`, единственный исходный parent/base —
`783770f16691286a1e8f3016b7dc5dd0f0dab76d`. Зависимости 16/20/22 в базовом плане
отмечены принятыми. Scope — ровно пять перечисленных собственных файлов;
`git diff --check` проходит, общие предметные файлы/план/master не изменяются.
Исходники guard и трёх тестовых файлов после успешной финальной команды 66/20
не редактировались; после неё менялся только отчёт. Push URL проверен как
`https://github.com/skuratov-sa/model-store.git`; разрешён единственный refspec
`HEAD:refs/heads/codex/migration-23-product-giveaway-bridge`, без force.
Точные commit/parent/remote SHA и результат операции фиксируются после публикации
в финальном сообщении и локальном `build/migration-23-delivery.md`, без добавления
SHA коммита внутрь его собственного содержимого.

### Возобновление публикации 21.10.2026

Предыдущая дополнительная проверка актуальности Gradle не запускалась: automatic
approval review вернула `Automatic approval review failed: You've hit your usage limit`
и указала, что операция не выполнена из-за невозможности завершить review, а не
признана небезопасной. На тот момент HEAD был `783770f`, commit/push не начинались;
отказ не обходился. После отдельного сообщения о восстановлении доступа
21.10.2026 проверка повторена обычным разрешённым способом.

Точная финальная команда с прежними selectors и RUN_BOOT_JAR_SMOKE=true:
exit 0, `BUILD SUCCESSFUL in 16s`, **все 10 задач UP-TO-DATE**, включая compile,
bootJar и test. Это подтверждает неизменность входов завершённого прогона 66/20,
а не новый запуск 66 тестов. Лог — `build/migration-23-precommit-inputs.log`.
Hash JAR остался `29e1e2ebc9e452e3ba53efa554eb11b5b62fc4867b79e0926504d83015ea52d9`.
