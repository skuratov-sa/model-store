-- Вставка категорий
-- Вставка данных для типа 'SOCIAL_NETWORK'
INSERT INTO dictionary (type, value, description)
VALUES
    ('SOCIAL_NETWORK', 'TELEGRAM', 'Телеграмм аккаунт'),
    ('SOCIAL_NETWORK', 'VK', 'ВКонтакте аккаунт'),
    ('SOCIAL_NETWORK', 'FACEBOOK', 'Facebook аккаунт'),
    ('SOCIAL_NETWORK', 'WHATSAPP', 'WhatsApp номер');

-- Вставка данных для типа 'CURRENCY'
INSERT INTO dictionary (type, value, description)
VALUES
    ('CURRENCY', 'USD', 'Доллар США'),
    ('CURRENCY', 'EUR', 'Евро'),
    ('CURRENCY', 'GBP', 'Фунт стерлингов'),
    ('CURRENCY', 'JPY', 'Японская йена'),
    ('CURRENCY', 'CNY', 'Китайский юань'),
    ('CURRENCY', 'RUB', 'Российский рубль');


-- Вставка данных для типа 'shipping_methods_type'
INSERT INTO dictionary (type, value, description)
VALUES
    ('SHOPPING_METHODS', 'PRODUCT_PICKUP', 'Самовывоз товара'),
    ('SHOPPING_METHODS', 'TRANSPORT_COMPANY', 'Транспортная компания'),
    ('SHOPPING_METHODS', 'RUSSIAN_POST', 'Почта России'),
    ('SHOPPING_METHODS', 'FREE_POST', 'Бесплатная отправка при определенной стоимости товара');

-- Вставка данных для типа 'transfer_money_type'
INSERT INTO dictionary (type, value, description)
VALUES
    ('TRANSFER_MONEY', 'BANK_CARD', 'Перевод на банковскую карту'),
    ('TRANSFER_MONEY', 'BANK_SBP', 'Перевод по СБП'),
    ('TRANSFER_MONEY', 'CASH', 'Оплата наличными');

-- Заполнение словаря по датам за которые пользователь сможет отправить товар 'deadline_sending'
INSERT INTO dictionary (type, value, description)
VALUES
    ('DEADLINE_SENDING', '1', '1 день'),
    ('DEADLINE_SENDING', '3', '3 дня'),
    ('DEADLINE_SENDING', '5', '5 дней'),
    ('DEADLINE_SENDING', '10', '10 дней'),
    ('DEADLINE_SENDING', '30', '30 дней'),
    ('DEADLINE_SENDING', '40', '40 дней'),
    ('DEADLINE_SENDING', '60', '60 дней');

-- Заполнение словаря по датам за которые пользователь сможет отправить товар 'deadline_payment'
INSERT INTO dictionary (type, value, description)
VALUES
    ('DEADLINE_PAYMENT', '1', '1 день'),
    ('DEADLINE_PAYMENT', '3', '3 дня'),
    ('DEADLINE_PAYMENT', '5', '5 дней');


INSERT INTO image (filename, tag, status, entity_id, created_at)
VALUES
    ('почта_россии.jpg', 'SYSTEM', 'ACTIVE', NULL, current_timestamp),
    ('трансфер.jpg', 'SYSTEM', 'ACTIVE', NULL, current_timestamp),
    ('самовывоз.png', 'SYSTEM', 'ACTIVE', NULL, current_timestamp);

-- TODO Добавить категории от Анрэ
-- INSERT INTO category (name, parent_id) VALUES ()


