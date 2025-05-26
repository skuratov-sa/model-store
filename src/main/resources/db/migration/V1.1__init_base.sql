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

-- Заполнение словаря по датам за которые пользователь сможет отправить товар 'deadline_payment'
INSERT INTO dictionary (type, value, description)
VALUES
    ('SORT_BY', 'DATE_DESC', 'Сортировка по добавлению'),
    ('SORT_BY', 'PRICE_ASC', 'Сортировка по возрастанию цены'),
    ('SORT_BY', 'PRICE_DESC', 'Сортировка по убыванию');

INSERT INTO dictionary (type, value, description)
VALUES
    ('PRODUCT_AVAILABILITY', 'PURCHASABLE', 'В наличии'),
    ('PRODUCT_AVAILABILITY', 'PREORDER', 'Предзаказ'),
    ('PRODUCT_AVAILABILITY', 'EXTERNAL_ONLY', 'Покупка из другого магазина');

INSERT INTO dictionary (type, value, description)
VALUES
    ('ORDER_STATUS', 'BOOKED', 'Забронирован'),
    ('ORDER_STATUS', 'AWAITING_PREPAYMENT', 'Предоплачен'),
    ('ORDER_STATUS', 'AWAITING_PREPAYMENT_APPROVAL', 'Ожидается согласие продавца на предоплату'),
    ('ORDER_STATUS', 'AWAITING_PAYMENT', 'Ожидает оплаты'),
    ('ORDER_STATUS', 'ASSEMBLING', 'Заказ находится в процессе сборки'),
    ('ORDER_STATUS', 'ON_THE_WAY', 'В пути следования'),
    ('ORDER_STATUS', 'DISPUTED', 'Ведется спор по заказу'),
    ('ORDER_STATUS', 'COMPLETED', 'Заказ успешно завершен'),
    ('ORDER_STATUS', 'FAILED', 'Заказ отклонён');


INSERT INTO image (filename, tag, status, entity_id, created_at)
VALUES
    ('почта_россии.jpg', 'SYSTEM', 'ACTIVE', NULL, current_timestamp),
    ('трансфер.jpg', 'SYSTEM', 'ACTIVE', NULL, current_timestamp),
    ('самовывоз.png', 'SYSTEM', 'ACTIVE', NULL, current_timestamp);

-- Верхний уровень
INSERT INTO category (name, parent_id) VALUES
                                           ('Prize Figures', NULL),
                                           ('Nendroids', NULL),
                                           ('Best Sellers', NULL),
                                           ('Statues', NULL),
                                           ('Complete models', NULL),
                                           ('NSFW (18+)', NULL),
                                           ('Preorder', NULL),
                                           ('Companies', NULL),
                                           ('Figma', NULL),
                                           ('Bunny suites', NULL),
                                           ('Franchises', NULL),
                                           ('3d print', NULL),
                                           ('Handmade', NULL),
                                           ('Other', NULL);

-- Получаем id для вложенных вставок
-- Предположим, мы делаем вручную и знаем ID каждой категории верхнего уровня
-- В реальности можно воспользоваться SELECT и сохранить в переменные, или использовать RETURNING id

-- Пример: допустим id для Companies = 8, Franchises = 11

-- Companies
INSERT INTO category (name, parent_id) VALUES
                                           ('Sega', 8),
                                           ('Taito', 8),
                                           ('FuRyu', 8),
                                           ('Pop up Parade', 8),
                                           ('Max Factory', 8),
                                           ('Good Smile company', 8),
                                           ('Myethos', 8),
                                           ('Bandai', 8),
                                           ('Freeing', 8),
                                           ('Othres', 8);

-- Franchises
INSERT INTO category (name, parent_id) VALUES
                                           ('Rezero', 11),
                                           ('Vocaloides', 11),
                                           ('Titan Attack', 11),
                                           ('Jojo', 11),
                                           ('My hero Academy', 11),
                                           ('Chainsaw man', 11),
                                           ('Genshin Impact', 11);



INSERT INTO participant (login, password, role, mail, full_name, phone_number, status, deadline_sending, deadline_payment, created_at)
VALUES (
           'admin', -- login
           '$2a$10$1rzAkwoon3cS0dXtPakBM.FMJHjBw.r6gFnLfHpdpjm8WuplsAdk6', -- зашифрованный пароль
           'ADMIN', -- роль
           'admin@example.com', -- email
           'Станиславский', -- полное имя
           '+79991234567', -- номер телефона
           'ACTIVE', -- статус
           1, -- deadline_sending
           1, -- deadline_payment
           current_timestamp -- дата создания
       );

