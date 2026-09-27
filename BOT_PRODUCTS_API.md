# Товары ботов и заказы: контракт API

## Товар

- Тип `EXTERNAL_ONLY` заменён на `EXTERNAL_PRODUCT`. Значение в PostgreSQL и словаре меняет миграция V1.16. Клиенту нужно одновременно обновить enum, подпись и форму товара.
- `POST /agent/products` сохраняет прежнее тело запроса, включая `externalUrl`. Товар сразу становится `ACTIVE`, имеет `availability=EXTERNAL_PRODUCT` и `count=null`.
- `EXTERNAL_PRODUCT` любого продавца можно добавить в корзину и оформить обычным заказом. `count=null` означает неограниченное число заказов без автоматического списания остатка.
- Если сотрудник задаёт `prepaymentAmount` внешнему товару, заказ после подтверждения продавцом переходит в `AWAITING_PREPAYMENT`; без предоплаты — в `AWAITING_PAYMENT`. В ответе заказа `totalPrice` означает остаток к оплате, `prepaymentAmount` — предоплату за всё заказанное количество.
- Публичные `ProductDto` и `GetProductResponse` возвращают `externalUrl: null`. Это относится также к корзине, избранному и вложенной карточке в заказе.

## Административный API

Все маршруты ниже требуют токен администратора (`SCOPE_ADMIN`). Бот остаётся владельцем товара и продавцом в заказе.

| Метод и путь | Назначение |
| --- | --- |
| `GET /admin/actions/agents` | ID, логин и статус всех бот-аккаунтов |
| `GET /admin/actions/agent-orders?status=&page=0&size=50` | Общая очередь заказов ботов |
| `GET /admin/actions/agents/{agentId}/orders?status=&page=0&size=50` | Заказы одного бота |
| `GET /admin/actions/agents/{agentId}/products` | Товары бота, включая `externalUrl` |
| `GET /admin/actions/products/{productId}` | Карточка любого товара с `externalUrl` для администратора |
| `PUT /admin/actions/agents/{agentId}/products/{productId}` | Изменение карточки товара бота; `availability` остаётся `EXTERNAL_PRODUCT` |
| `GET/PUT /admin/actions/agents/{agentId}/profile` | Профиль бота: имя, телефон, сроки отправки и оплаты, изображение |
| `GET/POST/PUT/DELETE /admin/actions/agents/{agentId}/social-networks[/{id}]` | Контакты бота |
| `GET/POST/PUT/DELETE /admin/actions/agents/{agentId}/transfers[/{id}]` | Доставка бота |
| `GET/POST/PUT/DELETE /admin/actions/agents/{agentId}/accounts[/{id}]` | Реквизиты получения оплаты |
| `GET/POST/PUT/DELETE /admin/actions/agents/{agentId}/addresses[/{id}]` | Адреса бота |
| `POST /admin/actions/agents/{agentId}/orders/{orderId}/{action}` | Действие по заказу бота |

Для действия по заказу допустимы `CONFIRM`, `CONFIRM_PREPAYMENT`, `SHIP`, `CANCEL`. Необязательный параметр `comment` передаётся в query string; для `SHIP` там же передаётся `deliveryUrl`. Сервис проверяет, что продавец заказа — указанный бот, применяет обычные правила перехода статусов и записывает ID администратора в `admin_agent_order_action`.

Ответы списков заказов содержат `sellerId` и `sellerLogin`, чтобы различать ботов в общей очереди. У каждого заказа есть снимок `prepaymentAmount`. Бот не может выполнять обычные операции через пользовательские маршруты; его доступ с токеном ограничен созданием товара через `POST /agent/products`.

## Клиентская часть

1. Заменить `EXTERNAL_ONLY` на `EXTERNAL_PRODUCT` и разрешить для этого типа кнопки корзины и покупки.
2. Скрыть внешний URL в покупательском интерфейсе. Для административной карточки загружать его отдельным запросом `GET /admin/actions/products/{productId}`.
3. В административном интерфейсе показать общую очередь заказов ботов и выбор бота из `GET /admin/actions/agents`; затем его заказы, товары и профиль.
4. Действия сотрудника по заказу отправлять с его административным токеном через маршруты выше. Не подменять `sellerId` и не использовать токен бота.
5. Для товара бота дать сотруднику настроить реквизиты оплаты перед первой продажей. Миграция не создаёт их автоматически.

Переименование логина `figovBaron` не включено: новое имя пока не согласовано.
