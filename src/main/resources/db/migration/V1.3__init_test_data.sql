-- Скрипт заполнения данных для таблиц
-- Таблицу dictionary не заполняем, используем её значения

-- Таблица category
INSERT INTO category (name, parent_id)
VALUES
    ('Electronics', NULL),
    ('Smartphones', 1),
    ('Laptops', 1),
    ('Accessories', 1),
    ('Home Appliances', NULL);

-- Таблица address
INSERT INTO address (country, city, street, house_number, apartment_number, index)
VALUES
    ('Russia', 'Moscow', 'Tverskaya', '12', '34', 123456),
    ('USA', 'New York', '5th Avenue', '7', NULL, 10001),
    ('Germany', 'Berlin', 'Kurfürstendamm', '15', '8A', 10719),
    ('France', 'Paris', 'Champs-Élysées', '22', '14B', 75008);

-- Таблица participant
INSERT INTO participant (login, mail, full_name, phone_number, status, deadline_sending, deadline_payment, created_at)
VALUES
    ('user1', 'user1@example.com', 'Ivan Ivanov', '+71234567890', 'ACTIVE', 1, 2,current_timestamp),
    ('user2', 'user2@example.com', 'Petr Petrov', '+79876543210', 'BLOCKED', 2, 2, current_timestamp),
    ('user3', 'user3@example.com', 'Sidor Sidorov', '+70123456789', 'ACTIVE', 1, 2, current_timestamp);


-- Таблица participant_transfer
INSERT INTO transfer (sending, price, currency, participant_id)
VALUES
    ('TRANSPORT_COMPANY', 500, 'RUB', 1),
    ('PRODUCT_PICKUP', 0, 'RUB', 2),
    ('RUSSIAN_POST', 1000, 'USD', 3);

-- Таблица participant_accounts
INSERT INTO account (transfer_money, username, entity_value, comment, participant_id)
VALUES
    ('BANK_CARD', 'user1_bank', '40817810099910004312', 'Main account', 1),
    ('BANK_SBP', 'user2_card', '4276380045612345', 'Visa card', 1),
    ('BANK_CARD', 'user3_paypal', 'user3@example.com', 'PayPal account', 2);

-- Таблица social_network
INSERT INTO social_network (type, login, participant_id)
VALUES
    ('VK', 'vk_user1', 1),
    ('WHATSAPP', 'fb_user2', 2),
    ('TELEGRAM', 'insta_user3', 3);

-- Таблица participant_address
INSERT INTO participant_address (participant_id, address_id)
VALUES
    (1, 1),
    (2, 2),
    (3, 3);

-- Таблица product
INSERT INTO product (name, description, count, price, currency, originality, participant_id, status, category_id, created_at)
VALUES
    ('Smartphone X', 'Latest model smartphone', 50, 799.99, 'USD', 'Original', 1, 'ACTIVE', 1,current_timestamp),
    ('Laptop Pro', 'High-performance laptop', 30, 1299.99, 'USD', 'Original', 2, 'ACTIVE',  2, current_timestamp),
    ('Wireless Earbuds', 'Noise-cancelling earbuds', 100, 199.99, 'USD', 'Original', 3, 'ACTIVE',  3, current_timestamp);


-- Таблица product_favorite
INSERT INTO product_favorite (participant_id, product_id)
VALUES
    (1, 2),
    (2, 3),
    (3, 1);

-- Таблица product_cart
INSERT INTO product_cart (participant_id, product_id)
VALUES
    (1, 3),
    (2, 1),
    (3, 2);

-- Таблица image
INSERT INTO image (filename, tag, status, entity_id, created_at)
VALUES
    ('image1.jpg', 'PARTICIPANT', 'TEMPORARY', 1, current_timestamp),
    ('image2.jpg', 'PARTICIPANT', 'ACTIVE', 2, current_timestamp),
    ('image3.png', 'PRODUCT', 'ACTIVE', 3, current_timestamp),
    ('image4.bmp', 'PRODUCT', 'TEMPORARY', 4, current_timestamp);
