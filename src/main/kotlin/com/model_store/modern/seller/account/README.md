# Пункт 08 — реквизиты `/accounts`

Сохранены методы POST и GET `/accounts`, GET `/accounts/participant/{participantId}`,
PUT и DELETE `/accounts/{id}`. Тело POST и DELETE пустое, PUT и GET возвращают
`id`, `transferMoney`, `username`, `entityValue`, `comment`, `participantId`.
Пустой список даёт `404 ACCOUNT_NOT_FOUND`. При создании повторный `entityValue`
одного участника, включая `null`, даёт `409 ACCOUNT_ALREADY_EXISTS`.
Обновление и удаление чужого ID дают `404 ACCOUNT_NOT_FOUND`.

**Осознанное отличие для пункта 20/32:** старый GET
`/accounts/participant/{participantId}` позволял любому вошедшему пользователю
читать чужие реквизиты. Modern возвращает `403 ACCESS_DENIED`, если ID в пути
не совпадает с владельцем токена. Сам агент также получает пустой `403` на обычных
`/accounts`, как в старом `AgentAccountAccessFilter`. Административные
`/admin/actions/agents/{agentId}/accounts` относятся к пункту 29.2.

Создание в modern сериализовано по ID владельца транзакционной блокировкой
PostgreSQL перед проверкой дубля. Legacy-путь остаётся без этой блокировки;
при одновременной записи через оба режима уникальность `entityValue` схемой
не гарантирована. Адаптеры переключения относятся к пункту 19.
