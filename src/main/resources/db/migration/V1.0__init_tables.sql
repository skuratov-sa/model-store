-- TIME ZONE
CREATE OR REPLACE FUNCTION current_utc_timestamp()
    RETURNS TIMESTAMP WITH TIME ZONE AS
$$
BEGIN
    RETURN CURRENT_TIMESTAMP AT TIME ZONE 'UTC';
END;
$$ LANGUAGE plpgsql;


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
    'BLOCKED',
    'WAITING_VERIFY',
    'DELETED'
    );
CREATE CAST (character varying AS participant_status) WITH INOUT AS ASSIGNMENT;

CREATE TYPE participant_role AS enum (
    'USER',
    'ADMIN'
    );
CREATE CAST (character varying AS participant_role) WITH INOUT AS ASSIGNMENT;

CREATE TYPE product_status AS enum (
    'ACTIVE',
    'BLOCKED',
    'DELETED'
    );
CREATE CAST (character varying AS product_status) WITH INOUT AS ASSIGNMENT;


CREATE TYPE order_status AS ENUM (
    'BOOKED',                        -- Забронирован
    'AWAITING_PREPAYMENT',          -- Предоплачен (ожидается оплата предоплаты)
    'AWAITING_PREPAYMENT_APPROVAL', -- Ожидается согласие продавца на предоплату
    'AWAITING_PAYMENT',             -- Ожидает полной оплаты
    'ASSEMBLING',                   -- Заказ находится в процессе сборки
    'ON_THE_WAY',                   -- В пути следования
    'DISPUTED',                     -- Ведется спор по заказу
    'COMPLETED',                    -- Заказ успешно завершен
    'FAILED'                        -- Заказ отклонён
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
    'PRODUCT',
    'ORDER',
    'SYSTEM'
    );
CREATE CAST (character varying AS image_tag) WITH INOUT AS ASSIGNMENT;

CREATE TYPE image_status AS enum (
    'ACTIVE',
    'TEMPORARY',
    'DELETE'
    );
CREATE CAST (character varying AS image_status) WITH INOUT AS ASSIGNMENT;

CREATE TYPE shipping_methods_type AS enum (
    'PRODUCT_PICKUP',
    'TRANSPORT_COMPANY',
    'RUSSIAN_POST',
    'FREE_POST'
    );
CREATE CAST (character varying AS shipping_methods_type) WITH INOUT AS ASSIGNMENT;

CREATE TYPE dictionary_type AS enum (
    'SOCIAL_NETWORK',
    'CURRENCY',
    'SHOPPING_METHODS',
    'TRANSFER_MONEY',
    'DEADLINE_SENDING',
    'DEADLINE_PAYMENT',
    'SORT_BY',
    'PRODUCT_AVAILABILITY',
    'ORDER_STATUS'
    );
CREATE CAST (character varying AS dictionary_type) WITH INOUT AS ASSIGNMENT;

CREATE TYPE transfer_money_type AS enum (
    'BANK_CARD',
    'BANK_SBP',
    'CASH'
    );
CREATE CAST (character varying AS transfer_money_type) WITH INOUT AS ASSIGNMENT;

CREATE TYPE product_availability AS ENUM (
    'PURCHASABLE',
    'PREORDER',
    'EXTERNAL_ONLY'
    );
CREATE CAST (character varying AS product_availability) WITH INOUT AS ASSIGNMENT;

CREATE TABLE dictionary
(
    type        dictionary_type NOT NULL,
    value       varchar(200),
    description varchar(500)
);
comment on table dictionary is 'Словарь основных enum сущностей';
comment on column dictionary.type is 'Категория слова';
comment on column dictionary.value is 'Значение';
comment on column dictionary.description is 'Описание';

CREATE TABLE image
(
    id         bigserial PRIMARY KEY,
    filename   varchar(500),
    tag        image_tag                NOT NULL,
    status     image_status             NOT NULL DEFAULT 'TEMPORARY',
    entity_id  bigint                   NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_utc_timestamp()
);
CREATE INDEX idx_image_entity ON image (entity_id, tag);

comment on table image is 'Изображения в карточках товаров';
comment on column image.id is 'Идентификатор изображения';
comment on column image.filename is 'Название файла';
comment on column image.tag is 'Тег отношение к сущности';
comment on column image.entity_id is 'Идентификатор сущности';
comment on column image.created_at is 'Дата создания записи';


CREATE TABLE category
(
    id        bigserial PRIMARY KEY,
    name      varchar(255),
    parent_id bigint REFERENCES category (id)
);
comment on table category is 'Виды категорий';
comment on column category.id is 'Идентификатор категории';
comment on column category.name is 'Название категории';
comment on column category.parent_id is 'Идентификатор родительской категории';


CREATE TABLE address
(
    id               bigserial PRIMARY KEY,
    country          varchar(255),
    city             varchar(255),
    street           varchar(255),
    house_number     varchar(20),
    apartment_number varchar(20),
    index            integer
);
comment on table address is 'Адрес участника';
comment on column address.id is 'Идентификатор адреса';
comment on column address.country is 'Страна проживания';
comment on column address.city is 'Город проживания';
comment on column address.street is 'Улица проживания';
comment on column address.house_number is 'Номер дома';
comment on column address.apartment_number is 'Номер квартиры';
comment on column address.index is 'Почтовый индекс';

-- PARTICIPANT
CREATE TABLE participant
(
    id               bigserial PRIMARY KEY,
    login            varchar(255) UNIQUE,
    password         varchar(500)             NOT NULL,
    role             participant_role         NOT NULL DEFAULT 'USER',
    mail             varchar(255) UNIQUE,
    full_name        varchar(255),
    phone_number     varchar(40),
    status           participant_status       NOT NULL,
    deadline_sending smallint                 NOT NULL DEFAULT 1,
    deadline_payment smallint                 NOT NULL DEFAULT 1,
    seller_status    VARCHAR(10)                       DEFAULT 'DEFAULT', -- Статус продавца (DEFAULT, VIP, PRO)
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_utc_timestamp()
);
comment on table participant is 'Пользователь';
comment on column participant.id is 'Идентификатор пользователя';
comment on column participant.login is 'Уникальный ник пользователя';
comment on column participant.mail is 'Почта email';
comment on column participant.full_name is 'Полное имя пользователя через пробел';
comment on column participant.phone_number is 'Номер телефона ';
comment on column participant.status is 'Статус пользователя';
comment on column participant.deadline_sending is 'Крайний срок отправки товара';
comment on column participant.deadline_payment is 'Крайний срок оплаты товара';
comment on column participant.seller_status is 'Статус продавца: DEFAULT, VIP или PRO';
comment on column participant.created_at is 'Дата создания профиля';

CREATE TABLE transfer
(
    id             bigserial PRIMARY KEY,
    sending        shipping_methods_type NOT NULL,
    price          int                   NOT NULL DEFAULT 0,
    currency       currency              NOT NULL DEFAULT 'RUB',
    participant_id bigint                NOT NULL REFERENCES participant (id)
);
comment on table transfer is 'Предпочитаемые способы отправки товаров';
comment on column transfer.id is 'Идентификатор записи';
comment on column transfer.sending is 'Тип отправки';
comment on column transfer.price is 'Цена для отправки';
comment on column transfer.currency is 'Валюта';
comment on column transfer.participant_id is 'Идентификатор пользователя';

CREATE TABLE account
(
    id             bigserial PRIMARY KEY,
    transfer_money transfer_money_type NOT NULL,
    username       varchar(100),
    entity_value   varchar(100), -- Если номер то +7..., если карта то пишем ее номер
    comment        varchar(255),
    participant_id bigint              NOT NULL REFERENCES participant (id)
);
comment on table account is 'Типы оплаты пользователя';
comment on column account.id is 'Идентификатор счета';
comment on column account.transfer_money is 'Способ оплаты';
comment on column account.username is 'Имя получателя';
comment on column account.entity_value is 'Значение способа в зависимости от способа оплаты';
comment on column account.comment is 'Комментарий пользователя';
comment on column account.participant_id is 'Идентификатор пользователя';


CREATE TABLE social_network
(
    id             bigserial PRIMARY KEY,
    type           social_network_type NOT NULL,
    login          varchar(255),
    participant_id bigint              NOT NULL REFERENCES participant (id)
);
comment on table social_network is 'Информация о социальных сетях';
comment on column social_network.id is 'Идентификатор записи';
comment on column social_network.type is 'Тип социальной сети';
comment on column social_network.login is 'Ник в социальной сети';
comment on column social_network.participant_id is 'Идентификатор пользователя';

CREATE TABLE participant_address
(
    id             bigserial PRIMARY KEY,
    participant_id bigint NOT NULL REFERENCES participant (id),
    address_id     bigint NOT NULL REFERENCES address (id)
);
comment on table participant_address is 'Связь пользователя и адресов';
comment on column participant_address.id is 'Уникальный идентификатор';
comment on column participant_address.participant_id is 'Идентификатор пользователя';
comment on column participant_address.address_id is 'Идентификатор адреса';

CREATE INDEX idx_participant_address_pid_aid ON participant_address (participant_id, address_id);


------------------------------------------ PRODUCT

CREATE TABLE product
(
    id                bigserial PRIMARY KEY,
    name              varchar(200)                         NULL,
    description       varchar(2000)                        NULL,
    count             integer                              NULL,
    price             float                                NOT NULL,
    prepayment_amount float CHECK (prepayment_amount >= 0) NULL,
    currency          currency                             NOT NULL,
    originality       varchar                              NULL,
    participant_id    bigint                               NOT NULL REFERENCES participant (id),
    status            product_status                       NOT NULL DEFAULT 'ACTIVE',
    availability      product_availability                 NOT NULL DEFAULT 'PURCHASABLE',
    external_url      varchar(1000)                        NULL,
    category_id       bigint                               NOT NULL REFERENCES category (id),
    created_at        TIMESTAMP WITH TIME ZONE             NOT NULL DEFAULT current_utc_timestamp()
);
comment on table product is 'Товары по которым осуществляются сделки';
comment on column product.id is 'Идентификатор товара';
comment on column product.name is 'Название товара';
comment on column product.description is 'Описание товара';
comment on column product.count is 'Количество единиц';
comment on column product.price is 'Цена товара поштучно';
comment on column product.prepayment_amount is 'Предоплата товара';
comment on column product.currency is 'Валюта для цены товара';
comment on column product.originality is 'Показатель оригинальности товара';
comment on column product.participant_id is 'Идентификатор пользователя';
comment on column product.status is 'Статус (ACTIVE, BLOCKED, DELETED)';
comment on column product.availability is 'Тип покупки (PURCHASABLE, PREORDER, EXTERNAL_ONLY)';
comment on column product.external_url is 'URL товара из внешнего сайта';
comment on column product.category_id is 'Категория товара';
comment on column product.created_at is 'Дата создания товара';

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


CREATE TABLE product_basket
(
    id             bigserial PRIMARY KEY,
    participant_id bigint NOT NULL REFERENCES participant (id),
    product_id     bigint NOT NULL REFERENCES product (id)
);
comment on table product_basket is 'Корзина товары';
comment on column product_basket.id is 'Идентификатор записи';
comment on column product_basket.participant_id is 'Идентификатор пользователя';
comment on column product_basket.product_id is 'Идентификатор продукта';


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

----------------------------------- ORDER
CREATE TABLE "order"
(
    id                     bigserial PRIMARY KEY,
    seller_id              bigint                   NOT NULL REFERENCES participant (id),
    customer_id            bigint                   NOT NULL REFERENCES participant (id),
    count                  integer                  NOT NULL,
    status                 order_status             NOT NULL,
    product_id             bigint                   NOT NULL REFERENCES product (id),
    account_id             bigint REFERENCES account (id),
    address_id             bigint                   NOT NULL REFERENCES address (id),
    transfer_id            bigint                   NOT NULL REFERENCES transfer (id),
    total_price            float                    NOT NULL DEFAULT 0,
    prepayment_amount      float                    NOT NULL DEFAULT 0,
    image_payment_proof_id bigint,
    delivery_url           varchar(1000),
    comment                varchar(500),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_utc_timestamp()
);

comment on table "order" is 'Заказ товара';
comment on column "order".id is 'Идентификатор заказа';
comment on column "order".seller_id is 'Идентификатор продавца';
comment on column "order".customer_id is 'Идентификатор покупателя';
comment on column "order".count is 'Количество приобретаемого товара';
comment on column "order".status is 'Статус исполнения заказа';
comment on column "order".product_id is 'Идентификатор продукта';
comment on column "order".account_id is 'Идентификатор счета';
comment on column "order".address_id is 'Идентификатор адреса доставки';
comment on column "order".transfer_id is 'Идентификатор способа отправки';
comment on column "order".total_price is 'Общая цена за товары';
comment on column "order".image_payment_proof_id is 'Id картинки подтверждающей оплату';
comment on column "order".delivery_url is 'URL отслеживания перемещения товара';
comment on column "order".comment is 'Последний комментарий товара';
comment on column "order".created_at is 'Дата создания заказа';


CREATE TABLE order_status_history
(
    id         bigserial PRIMARY KEY,
    order_id   bigint                   NOT NULL REFERENCES "order" (id) ON DELETE CASCADE,
    status     order_status             NOT NULL,
    comment    varchar(500)             NULL,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_timestamp
);


CREATE TABLE review
(
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT    NOT NULL REFERENCES "order" (id) ON DELETE CASCADE,
    product_id  BIGINT    NOT NULL REFERENCES "product" (id) ON DELETE CASCADE,
    reviewer_id BIGINT    NOT NULL REFERENCES participant (id) ON DELETE CASCADE, -- кто оставил отзыв
    seller_id   BIGINT    NOT NULL REFERENCES participant (id) ON DELETE CASCADE, -- кому отзыв
    rating      SMALLINT CHECK (rating BETWEEN 1 AND 5),
    comment     varchar(500),
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP
);

comment on column review.id is 'Идентификатор отзыва';
comment on column review.order_id is 'Идентификатор связанного товара в заказе';
comment on column review.product_id is 'Идентификатор товара';
comment on column review.reviewer_id is 'Идентификатор участника, оставившего отзыв (покупатель)';
comment on column review.seller_id is 'Идентификатор продавца, получившего отзыв';
comment on column review.rating is 'Оценка продавцу (от 1 до 5)';
comment on column review.comment is 'Текстовый комментарий к отзыву';
comment on column review.created_at is 'Дата и время создания отзыва';
comment on column review.updated_at is 'Дата и время последнего обновления отзыва';


CREATE TABLE seller_rating
(
    seller_id      BIGINT PRIMARY KEY REFERENCES participant (id) ON DELETE CASCADE,
    average_rating NUMERIC(3, 2) DEFAULT 0.0,
    total_reviews  INTEGER       DEFAULT 0,
    CONSTRAINT unique_seller_status UNIQUE (seller_id)
);

