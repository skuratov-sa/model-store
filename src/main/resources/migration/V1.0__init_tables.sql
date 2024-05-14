-- CREATE DATABASE  model_store;
CREATE TYPE currency AS enum (
    'USD',
    'EUR',
    'GBP',
    'JPY',
    'CNY',
    'RUB'
    );
CREATE CAST (character varying AS currency) WITH INOUT AS ASSIGNMENT;

CREATE TYPE participant_status AS enum (
    'ACTIVE',
    'BLOCKED'
    );
CREATE CAST (character varying AS participant_status) WITH INOUT AS ASSIGNMENT;

CREATE TYPE order_status AS enum (
    'NEW',
    'BOOKED',
    'PAID_FOR',
    'ON_THE_WAY',
    'DELIVERED',
    'RECEIVED'
    );
CREATE CAST (character varying AS order_status) WITH INOUT AS ASSIGNMENT;

CREATE TYPE social_network_type AS enum (
    'TELEGRAM',
    'VK',
    'FACEBOOK',
    'WHATSAPP'
    );
CREATE CAST (character varying AS social_network_type) WITH INOUT AS ASSIGNMENT;

CREATE TYPE image_tag AS enum (
    'PARTICIPANT',
    'PRODUCT'
    );
CREATE CAST (character varying AS image_tag) WITH INOUT AS ASSIGNMENT;


CREATE TABLE image
(
    id   bigserial PRIMARY KEY,
    path varchar(2000),
    tag image_tag NOT NULL,
    entity_id bigint
);
CREATE INDEX idx_image_entity ON image (entity_id);
CREATE INDEX idx_image_tag ON image (tag);

comment on table image is 'Изображения в карточках товаров';
comment on column image.id is 'Идентификатор изображения';
comment on column image.path is 'Путь к файлу';
comment on column image.tag is 'Тег отношение к сущности';
comment on column image.entity_id is 'Идентификатор сущности';


CREATE TABLE category
(
    id   bigserial PRIMARY KEY,
    name varchar(255)
);
comment on table category is 'Виды категорий';
comment on column category.id is 'Идентификатор категории';
comment on column category.name is 'Название категории';


CREATE TABLE address
(
    id               bigserial PRIMARY KEY,
    country          varchar(255),
    city             varchar(255),
    street           varchar(255),
    house_number     varchar(20),
    apartment_number varchar(20)
);
comment on table address is 'Адрес участника';
comment on column address.id is 'Идентификатор адреса';
comment on column address.country is 'Страна проживания';
comment on column address.city is 'Город проживания';
comment on column address.street is 'Улица проживания';
comment on column address.house_number is 'Номер дома';
comment on column address.apartment_number is 'Номер квартиры';

-- PARTICIPANT

CREATE TABLE participant
(
    id                     bigserial PRIMARY KEY,
    login                  varchar(255) UNIQUE NOT NULL,
    mail                   varchar(255) UNIQUE,
    full_name               varchar(255),
    phone_number           varchar(40),
    state                  participant_status,
    createdAt              timestamp
);
comment on table participant is 'Пользователь';
comment on column participant.id is 'Идентификатор пользователя';
comment on column participant.login is 'Уникальный ник пользователя';
comment on column participant.mail is 'Почта email';
comment on column participant.full_name is 'Полное имя пользователя через пробел';
comment on column participant.phone_number is 'Номер телефона ';
comment on column participant.state is 'Статус пользователя';
comment on column participant.createdAt is 'Дата создания профиля';


CREATE TABLE social_network
(
    id             bigserial PRIMARY KEY,
    type           social_network_type,
    login          varchar(255),
    participant_id bigint NOT NULL REFERENCES participant (id)
);
comment on table social_network is 'Информация о социальных сетях';
comment on column social_network.id is 'Идентификатор записи';
comment on column social_network.type is 'Тип социальной сети';
comment on column social_network.login is 'Ник в социальной сети';
comment on column social_network.participant_id is 'Идентификатор пользователя';


CREATE TABLE participant_address
(
    id         bigserial PRIMARY KEY,
    participant_id bigint NOT NULL REFERENCES participant (id),
    address_id bigint NOT NULL REFERENCES address (id)
);
comment on table participant_address is 'Связь пользователя и адресов';
comment on column participant_address.id is 'Уникальный идентификатор';
comment on column participant_address.participant_id is 'Идентификатор пользователя';
comment on column participant_address.address_id is 'Идентификатор адреса';


CREATE TABLE "order"
(
    id            bigserial PRIMARY KEY,
    seller_id     bigint       NOT NULL REFERENCES participant (id),
    customer_id   bigint       NOT NULL REFERENCES participant (id),
    amount        integer      NOT NULL,
    status        order_status NOT NULL,
    address_id    bigint       NOT NULL REFERENCES address (id),
    booking_price integer,
    createdAt     timestamp    NOT NULL
);

comment on table "order" is 'Заказ товара';
comment on column "order".id is 'Идентификатор заказа';
comment on column "order".seller_id is 'Идентификатор продавца';
comment on column "order".customer_id is 'Идентификатор покупателя';
comment on column "order".amount is 'Количество приобретаемого товара';
comment on column "order".status is 'Статус исполнения заказа';
comment on column "order".address_id is 'Идентификатор адреса доставки';
comment on column "order".booking_price is 'Цена бронирования';
comment on column "order".createdAt is 'Дата создания заказа';

-- PRODUCT
CREATE TABLE product
(
    id          bigserial PRIMARY KEY,
    name        varchar(200)  NULL,
    description varchar(2000) NULL,
    count       integer       NOT NULL,
    price       integer       NOT NULL,
    currency    currency      NOT NULL,
    originality varchar       NULL,
    createdAt   timestamp     NOT NULL
);
comment on table product is 'Товары по которым осуществляются сделки';
comment on column product.id is 'Идентификатор товара';
comment on column product.name is 'Название товара';
comment on column product.description is 'Описание товара';
comment on column product.count is 'Количество единиц';
comment on column product.price is 'Цена товара поштучно';
comment on column product.currency is 'Валюта для цены товара';
comment on column product.originality is 'Показатель оригинальности товара';
comment on column product.createdAt is 'Дата создания товара';


CREATE TABLE product_category
(
    id          bigserial PRIMARY KEY,
    product_id  bigint NOT NULL REFERENCES product (id),
    category_id bigint NOT NULL REFERENCES category (id)
);
comment on table product_category is 'Связь категории и товара';
comment on column product_category.id is 'Идентификатор товара';
comment on column product_category.category_id is 'Идентификатор категории';


CREATE TABLE product_favorite
(
    id             bigserial PRIMARY KEY,
    participant_id bigint NOT NULL REFERENCES participant (id),
    product_id     bigint NOT NULL REFERENCES product (id)
);
comment on table product_favorite is 'Избранные товары';
comment on column product_favorite.id is 'Идентификатор записи';
comment on column product_favorite.participant_id is 'Идентификатор пользователя';
comment on column product_favorite.product_id is 'Идентификатор продукта';


CREATE TABLE product_cart
(
    id             bigserial PRIMARY KEY,
    participant_id bigint NOT NULL REFERENCES participant (id),
    product_id     bigint NOT NULL REFERENCES product (id)
);
comment on table product_cart is 'Корзина товаров';
comment on column product_cart.id is 'Идентификатор записи';
comment on column product_cart.participant_id is 'Идентификатор пользователя';
comment on column product_cart.product_id is 'Идентификатор продукта';