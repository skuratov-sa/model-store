# 29.2 — профиль и реквизиты агента

База: локальная `master` `e92c060c0e863dc176eb6f18ebea0c33c2cd8c4c`.
В legacy все строки ниже обслуживает `AdminAgentController` под `/admin/actions`.
Общая цепочка безопасности в обоих режимах требует ADMIN для этого префикса.

| Метод | Путь после `/admin/actions/agents/{agentId}` | Modern | Owner use case |
| --- | --- | --- | --- |
| GET | `/profile` | да | `AgentProfileCommands.read` |
| PUT | `/profile` | да | `AgentProfileCommands.update` |
| GET, POST | `/addresses` | да | `AddressUseCases` |
| PUT, DELETE | `/addresses/{id}` | да | `AddressUseCases` |
| GET, POST | `/accounts` | да | `AccountUseCases` |
| PUT, DELETE | `/accounts/{id}` | да | `AccountUseCases` |
| GET, POST | `/transfers` | да | `TransferUseCases` |
| PUT, DELETE | `/transfers/{id}` | да | `TransferUseCases` |
| GET, POST | `/social-networks` | да | `SocialNetworkUseCases` |
| PUT, DELETE | `/social-networks/{id}` | да | `SocialNetworkUseCases` |

`GET /admin/actions/agents` относится к отдельной административной инвентаризации,
но не входит в 18 профильных/реквизитных маршрутов пункта 29.2.

Для `PUT /profile` используется принятая команда identity с входом
`agentId, fullName?, phoneNumber?, deadlineSending?, deadlinePayment?, imageId?`
и выходом `id, login, fullName, phoneNumber, imageId, deadlineSending,
deadlinePayment, status`. Она проверяет текущего активного ADMIN, выполняет
изменение скаляров и привязку изображения через принятый ID-порт в общей
JPA-транзакции. Принятая `UpdateParticipant.execute` по-прежнему отвергает
агента через `requireEditable()`. Из пакета 29.2 прямой SQL по `participant`
или `image` не выполняется.

`GET /profile` читает snapshot 04.4 из identity. Администратору доступны
BLOCKED/DELETED целевые агенты, как в legacy `requireAgent`.
GET маршруты также проверяют caller ADMIN и target agent через
`AgentProfileCommands.read(callerId, agentId)` перед обращением к use case
области. Для POST/PUT/DELETE реквизитов `AgentProfileWrites` открывает общую
JPA-транзакцию, вызывает принятый `AgentAccessLock.lockActiveAdminAndAgent`,
затем owner use case. Блокировки caller и target удерживаются до завершения
записи. Пакет 29.2 не получает auth/JPA/domain объект участника.

Существующий ADMIN-only фильтр на `/admin/actions/**` останавливает
`agent_access` до контроллера. Legacy делает то же. Owner-side порт 04.4
проверяет актуальную запись активного ADMIN, поэтому подписанная подмена `role` у USER
не раскрывает профиль и реквизиты. Если будет отдельно согласован owner-доступ
к этому пути, интеграция должна охватывать только точные маршруты 29.2 и
проверять agentId через `VerifyAgentAccess`.

Modern отвечает 404 на отсутствующего либо не-агента. Для ADMIN статус
целевого агента не ограничивается. Пустые списки accounts/transfers/
social-networks возвращают legacy `404` с кодом области, а addresses —
`200 []`. Ошибки некорректных сроков и
слишком длинного телефона из owner-команды нормализуются без деталей БД;
точное сравнение со старым поведением зафиксировать в 32.

## Проверка 20.10.2026

Новая самостоятельная проверка после интеграции 04.5 перечитала все восемь
файлов 29.2, миграционный план, legacy `AdminAgentController` и
`AdminAgentService`, четыре legacy и четыре modern owner use case, DTO,
ошибки и блокировки ordering24. Все 18 маршрутов сопоставлены по методам и
путям. Для GET/PUT профиля остаётся owner-команда 04.4; PUT блокирует caller
и target в одной транзакции. Все 12 POST/PUT/DELETE реквизитов вызывают
публичный `@Transactional("transactionManager")` метод фасада, затем
`AgentAccessLock` (`MANDATORY`), затем owner use case с `REQUIRED`.
Self-invocation и `REQUIRES_NEW` в этом пути нет. Identity фиксирует порядок
participant locks по возрастанию ID; остальные owner блокировки приобретаются
после них, как в ordering24. 29.2 не пишет `participant`/`image` напрямую.

Находки и исправления: исходная проверка через `AgentProfileCommands.read`
перед отдельной owner-транзакцией допускала запись после отзыва ADMIN.
Транзакционный фасад устранил окно. Расширенный тест теперь проверяет все 12
операций при отозванной роли и статусе администратора, а также при удалённом
признаке агента. Два теста проверяют порядок отзыва ADMIN до/после захвата
блокировки, ещё один — изменение `is_agent` после захвата, и один — rollback
вставки адреса при ошибке, возникшей уже после owner insert. Проверены
пустые ответы, чужие ID, BLOCKED/DELETED target, guest/USER/agent_access,
refresh/verify/expired/tampered JWT, подписанная подмена role claim, null patch,
фото и JSON-поля. Код `TRANSFER_NOT_FOUND` при дубликате сохранён точно как
в legacy `TransferServiceImpl`, несмотря на необычное имя.

Итоговый расширенный прогон на отдельных embedded PostgreSQL: **56 тестов в
11 классах, 0 ошибок, 0 пропусков**. Первое выполнение соседних owner-тестов
не подняло контекст из-за отсутствующего пути к тестовому приватному RSA-ключу;
повторный полный прогон с ключами из `src/test/resources/keys` прошёл.
SMTP и S3 заменены mock-компонентами тестовых классов. Точная команда успешного
прогона (PowerShell, из корня репозитория):

```powershell
$env:JAVA_HOME='C:\Users\skura\.jdks\temurin-21.0.11'
$env:GRADLE_USER_HOME='C:\Users\skura\.gradle'
$env:APP_PRIVATE_KEY_PATH='keys/test_private_key.pem'
$env:APP_PUBLIC_KEY_PATH='keys/test_public_key.pem'
.\gradlew.bat --offline --no-daemon test --tests 'com.model_store.modern.seller.agent.profile.*' --tests 'com.model_store.modern.seller.address.AddressIntegrationTest' --tests 'com.model_store.modern.seller.account.AccountUseCasesTest' --tests 'com.model_store.modern.seller.account.AccountJpaHttpTest' --tests 'com.model_store.modern.seller.TransferSocialIntegrationTest' --tests 'com.model_store.modern.identity.participant.AgentProfileCommandsIntegrationTest' --tests 'com.model_store.modern.identity.participant.AgentAccessLockIntegrationTest' --tests 'com.model_store.modern.ordering.create.OrderCreateIntegrationTest' --tests 'com.model_store.modern.ordering.create.OrderCreateLegacyHttpTest'
.\gradlew.bat --offline --no-daemon bootJar
```

Для пункта 32 остаётся точное сравнение некоторых пограничных ошибок с legacy:
04.4 возвращает нормализованную `400 BAD_REQUEST` для срока вне диапазона и
безопасную `409 DUPLICATE_KEY` без текста БД для слишком длинного телефона;
legacy может отдавать низкоуровневую ошибку БД. В 29.2 намеренно сохранено
отсутствие ограничений по статусу целевого агента, соответствующее
`requireAgent` legacy.
