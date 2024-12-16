-- Вставка категорий
INSERT INTO category (name)
VALUES ('Электроника'),
       ('Одежда'),
       ('Продукты питания'),
       ('Книги'),
       ('Техника');

-- Вставка адресов
INSERT INTO address (country, city, street, house_number, apartment_number)
VALUES ('Россия', 'Москва', 'Тверская', '12', '34'),
       ('США', 'Нью-Йорк', '5th Avenue', '22', '10'),
       ('Германия', 'Берлин', 'Unter den Linden', '25', '5'),
       ('Великобритания', 'Лондон', 'Baker Street', '221B', '1');

-- Вставка пользователей
INSERT INTO participant (login, mail, full_name, phone_number, status)
VALUES ('user1', 'user1@mail.com', 'Иван Иванов', '+79990000001', 'ACTIVE'),
       ('user2', 'user2@mail.com', 'Петр Петров', '+79990000002', 'BLOCKED'),
       ('user3', 'user3@mail.com', 'Анна Сидорова', '+79990000003', 'ACTIVE'),
       ('user4', 'user4@mail.com', 'Алексей Смирнов', '+79990000004', 'ACTIVE');

-- Вставка информации о социальных сетях
INSERT INTO social_network (type, login, participant_id)
VALUES ('TELEGRAM', 'user1_telegram', 1),
       ('VK', 'user2_vk', 2),
       ('FACEBOOK', 'user3_facebook', 3),
       ('WHATSAPP', 'user4_whatsapp', 4);

-- Вставка связи пользователя и адреса
INSERT INTO participant_address (participant_id, address_id)
VALUES (1, 1),
       (2, 2),
       (3, 3),
       (4, 4);

-- Вставка товаров
INSERT INTO product (name, description, count, price, currency, originality, participant_id)
VALUES ('Смартфон', 'Современный смартфон с хорошей камерой', 100, 50000, 'RUB', 'Оригинал', 1),
       ('Ноутбук', 'Мощный ноутбук для работы и игр', 50, 70000, 'USD', 'Оригинал', 2),
       ('Хлеб', 'Простой хлеб', 200, 50, 'RUB', 'Не определено', 3),
       ('Книга "Программирование"', 'Книга по программированию для начинающих', 150, 1000, 'RUB', 'Оригинал', 4);

-- Вставка связи товаров и категорий
INSERT INTO product_category (product_id, category_id)
VALUES (1, 1),
       (2, 1),
       (3, 3),
       (4, 4);

-- Вставка товаров в избранное
INSERT INTO product_favorite (participant_id, product_id)
VALUES (1, 1),
       (2, 2),
       (3, 3),
       (4, 4);

-- Вставка товаров в корзину
INSERT INTO product_cart (participant_id, product_id)
VALUES (1, 2),
       (2, 3),
       (3, 4),
       (4, 1);

-- Вставка заказов
INSERT INTO "order" (seller_id, customer_id, amount, status, address_id, booking_price)
VALUES (1, 2, 1, 'NEW', 1, 5000),
       (2, 3, 2, 'BOOKED', 2, 10000),
       (3, 4, 3, 'PAID_FOR', 3, 15000),
       (4, 1, 4, 'ON_THE_WAY', 4, 20000);
