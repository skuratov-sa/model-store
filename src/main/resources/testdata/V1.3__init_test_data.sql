-- Тестовые данные каталога. Запускать вручную после миграции V1.17 на локальной БД:
-- psql -v ON_ERROR_STOP=1 -U root -d model_store -f src/main/resources/testdata/V1.3__init_test_data.sql
-- Пользователи: test_catalog_seller_1 ... test_catalog_seller_5, пароль: test123.
-- Повторный запуск не создаёт дополнительных пользователей, товаров или связей.

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM category WHERE slug = 'catalog')
       OR NOT EXISTS (SELECT 1 FROM category WHERE slug = 'nsfw_adult')
       OR NOT EXISTS (SELECT 1 FROM category WHERE slug = 'manga_by_publisher') THEN
        RAISE EXCEPTION 'Сначала примените миграции каталога до V1.17';
    END IF;
END
$$;

INSERT INTO participant
    (login, password, role, mail, full_name, phone_number, status,
     deadline_sending, deadline_payment, age)
SELECT 'test_catalog_seller_' || n,
       '$2a$10$/haIqlaHqmqBhS17IxZkjuZT7Zg8FRNJJoonbwSLkegMmFp1PWxPK',
       'USER', 'test_catalog_seller_' || n || '@example.invalid',
       'Тестовый продавец ' || n, '+7999000000' || n, 'ACTIVE',
       3, 7, CASE WHEN n = 1 THEN 17 ELSE 18 + n END
FROM generate_series(1, 5) AS n
ON CONFLICT (login) DO NOTHING;

-- Адрес и способ доставки нужны для ручной проверки корзины и заказов.
DO $$
DECLARE
    n INTEGER;
    seller_id BIGINT;
    address_id BIGINT;
BEGIN
    FOR n IN 1..5 LOOP
        SELECT id INTO STRICT seller_id
        FROM participant WHERE login = 'test_catalog_seller_' || n;

        IF NOT EXISTS (SELECT 1 FROM participant_address WHERE participant_id = seller_id) THEN
            INSERT INTO address (country, city, street, house_number, apartment_number, index)
            VALUES ('Россия', 'Москва', 'Тестовая улица', n::text, '1', 101000)
            RETURNING id INTO address_id;
            INSERT INTO participant_address (participant_id, address_id)
            VALUES (seller_id, address_id);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM transfer WHERE participant_id = seller_id) THEN
            INSERT INTO transfer (sending, price, currency, participant_id)
            VALUES ('RUSSIAN_POST', 350, 'RUB', seller_id);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM account WHERE participant_id = seller_id) THEN
            INSERT INTO account (transfer_money, username, entity_value, comment, participant_id)
            VALUES ('BANK_SBP', 'Тестовый продавец ' || n, '+7999000000' || n,
                    'Тестовые реквизиты', seller_id);
        END IF;
    END LOOP;
END
$$;

CREATE TEMP TABLE seed_catalog_products (
    seed_key TEXT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    price NUMERIC NOT NULL,
    seller_no INTEGER NOT NULL,
    availability product_availability NOT NULL,
    used BOOLEAN NOT NULL,
    first_slug VARCHAR(100) NOT NULL,
    second_slug VARCHAR(100),
    third_slug VARCHAR(100)
) ON COMMIT DROP;

-- Одна фигурка на каждый Title. Типы и производители чередуются:
-- так у каждой листовой категории фигурок будет хотя бы один товар.
WITH titles AS (
    SELECT slug, name, ROW_NUMBER() OVER (ORDER BY display_order, id) AS rn
    FROM category WHERE parent_id = (SELECT id FROM category WHERE slug = 'figures_by_franchise')
), figure_types AS (
    SELECT slug, name, ROW_NUMBER() OVER (ORDER BY display_order, id) AS rn
    FROM category WHERE parent_id = (SELECT id FROM category WHERE slug = 'figures_by_type')
), manufacturers AS (
    SELECT slug, name, ROW_NUMBER() OVER (ORDER BY display_order, id) AS rn
    FROM category WHERE parent_id = (SELECT id FROM category WHERE slug = 'figures_by_manufacturer')
)
INSERT INTO seed_catalog_products
SELECT 'figure:' || title.slug,
       title.name || ' — ' || figure_type.name,
       'Тестовая фигурка: ' || title.name || ', ' || figure_type.name
           || ', производитель ' || manufacturer.name,
       2500 + title.rn * 350,
       (1 + (title.rn - 1) % 5)::INTEGER,
       CASE WHEN title.rn % 4 = 0 THEN 'PREORDER' ELSE 'PURCHASABLE' END::product_availability,
       title.rn % 3 = 0,
       figure_type.slug, manufacturer.slug, title.slug
FROM titles title
JOIN figure_types figure_type
  ON figure_type.rn = 1 + (title.rn - 1) % (SELECT COUNT(*) FROM figure_types)
JOIN manufacturers manufacturer
  ON manufacturer.rn = 1 + (title.rn - 1) % (SELECT COUNT(*) FROM manufacturers);

-- Каждая TCG и каждый тип карточек представлены отдельными товарами.
WITH games AS (
    SELECT slug, name, ROW_NUMBER() OVER (ORDER BY display_order, id) AS rn
    FROM category WHERE parent_id = (SELECT id FROM category WHERE slug = 'cards_by_game')
), card_types AS (
    SELECT slug, name, ROW_NUMBER() OVER (ORDER BY display_order, id) AS rn
    FROM category WHERE parent_id = (SELECT id FROM category WHERE slug = 'cards_by_type')
)
INSERT INTO seed_catalog_products
SELECT 'card:' || game.slug,
       game.name || ' — ' || card_type.name,
       'Тестовые карточки ' || game.name || ', ' || card_type.name,
       600 + game.rn * 240,
       (1 + (game.rn - 1) % 5)::INTEGER,
       CASE WHEN game.rn % 3 = 0 THEN 'PREORDER' ELSE 'PURCHASABLE' END::product_availability,
       game.rn % 2 = 0,
       game.slug, card_type.slug, NULL
FROM games game
JOIN card_types card_type
  ON card_type.rn = 1 + (game.rn - 1) % (SELECT COUNT(*) FROM card_types);

-- Каждый издатель, тип и жанр манги получают товар.
WITH publishers AS (
    SELECT slug, name, ROW_NUMBER() OVER (ORDER BY display_order, id) AS rn
    FROM category WHERE parent_id = (SELECT id FROM category WHERE slug = 'manga_by_publisher')
), manga_types AS (
    SELECT slug, name, ROW_NUMBER() OVER (ORDER BY display_order, id) AS rn
    FROM category WHERE parent_id = (SELECT id FROM category WHERE slug = 'manga_by_type')
), genres AS (
    SELECT slug, name, ROW_NUMBER() OVER (ORDER BY display_order, id) AS rn
    FROM category WHERE parent_id = (SELECT id FROM category WHERE slug = 'manga_by_genre')
)
INSERT INTO seed_catalog_products
SELECT 'manga:' || publisher.slug,
       publisher.name || ' — ' || manga_type.name || ' (' || genre.name || ')',
       'Тестовое издание: ' || publisher.name || ', ' || manga_type.name
           || ', жанр ' || genre.name,
       450 + publisher.rn * 110,
       (1 + (publisher.rn - 1) % 5)::INTEGER,
       CASE WHEN publisher.rn % 4 = 0 THEN 'PREORDER' ELSE 'PURCHASABLE' END::product_availability,
       publisher.rn % 3 = 0,
       publisher.slug, manga_type.slug, genre.slug
FROM publishers publisher
JOIN manga_types manga_type
  ON manga_type.rn = 1 + (publisher.rn - 1) % (SELECT COUNT(*) FROM manga_types)
JOIN genres genre
  ON genre.rn = 1 + (publisher.rn - 1) % (SELECT COUNT(*) FROM genres);

INSERT INTO seed_catalog_products VALUES
    ('other:item', 'Тестовый сувенир', 'Товар из раздела «Другое»',
     750, 5, 'PURCHASABLE', FALSE, 'irrelevant', NULL, NULL),
    ('showcase:miku', 'Hatsune Miku Nendoroid', 'Новая фигурка для проверки фильтров',
     3900, 1, 'PURCHASABLE', FALSE, 'nendoroid', 'gsc', 'vocaloid_miku'),
    ('showcase:genshin-used', 'Genshin Impact Scale Figure (б/у)', 'Фигурка бывшая в употреблении',
     7200, 2, 'PURCHASABLE', TRUE, 'scale_figure', 'kotobukiya', 'genshin_impact'),
    ('showcase:honkai-preorder', 'Honkai: Star Rail Figma (предзаказ)', 'Предзаказ с предоплатой',
     8200, 3, 'PREORDER', FALSE, 'figma', 'max_factory', 'honkai_star_rail'),
    ('showcase:azur-adult', 'Azur Lane NSFW (18+)', 'Товар для проверки возрастного фильтра',
     11000, 4, 'PURCHASABLE', FALSE, 'nsfw_adult', 'alter', 'azur_lane'),
    ('showcase:pokemon', 'Pokémon TCG Booster Box', 'Запечатанный бокс карточек',
     5600, 2, 'PURCHASABLE', FALSE, 'pokemon', 'booster_box', NULL),
    ('showcase:one-piece-used', 'One Piece TCG Singles (б/у)', 'Одиночные карточки бывшие в употреблении',
     950, 3, 'PURCHASABLE', TRUE, 'one_piece_tcg', 'singles', NULL),
    ('showcase:manga', 'One Piece, том 1', 'Манга Shueisha для проверки издателя и жанра',
     890, 4, 'PURCHASABLE', FALSE, 'manga_volume', 'shounen', 'publisher_shueisha');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM seed_catalog_products seed
        CROSS JOIN LATERAL unnest(ARRAY[seed.first_slug, seed.second_slug, seed.third_slug]) AS required(slug)
        LEFT JOIN category c ON c.slug = required.slug
        WHERE required.slug IS NOT NULL AND c.id IS NULL
    ) THEN
        RAISE EXCEPTION 'В каталоге не хватает категории для тестового товара';
    END IF;
END
$$;

INSERT INTO product
    (name, description, count, price, prepayment_amount, currency, originality,
     participant_id, status, availability, used)
SELECT seed.name, seed.description, 10, seed.price,
       CASE WHEN seed.availability = 'PREORDER' THEN seed.price * 0.2 ELSE NULL END,
       'RUB', 'Оригинал', seller.id, 'ACTIVE', seed.availability, seed.used
FROM seed_catalog_products seed
JOIN participant seller ON seller.login = 'test_catalog_seller_' || seed.seller_no
WHERE NOT EXISTS (
    SELECT 1 FROM product existing
    WHERE existing.participant_id = seller.id
      AND existing.name = seed.name
      AND existing.status = 'ACTIVE'
);

INSERT INTO product_category (product_id, category_id)
SELECT DISTINCT product.id, category.id
FROM seed_catalog_products seed
JOIN participant seller ON seller.login = 'test_catalog_seller_' || seed.seller_no
JOIN product ON product.participant_id = seller.id
            AND product.name = seed.name
            AND product.status = 'ACTIVE'
CROSS JOIN LATERAL unnest(ARRAY[seed.first_slug, seed.second_slug, seed.third_slug]) AS required(slug)
JOIN category ON category.slug = required.slug
WHERE NOT EXISTS (
    SELECT 1 FROM product_category existing
    WHERE existing.product_id = product.id AND existing.category_id = category.id
);

-- Тестовые избранное и корзина для первого пользователя.
INSERT INTO product_favorite (participant_id, product_id)
SELECT buyer.id, product.id
FROM participant buyer
JOIN participant seller ON seller.login = 'test_catalog_seller_2'
JOIN product ON product.participant_id = seller.id
            AND product.name = 'Pokémon TCG Booster Box'
            AND product.status = 'ACTIVE'
WHERE buyer.login = 'test_catalog_seller_1'
  AND NOT EXISTS (
      SELECT 1 FROM product_favorite existing
      WHERE existing.participant_id = buyer.id AND existing.product_id = product.id
  );

INSERT INTO product_basket (participant_id, product_id, count)
SELECT buyer.id, product.id, 1
FROM participant buyer
JOIN participant seller ON seller.login = 'test_catalog_seller_2'
JOIN product ON product.participant_id = seller.id
            AND product.name = 'Pokémon TCG Booster Box'
            AND product.status = 'ACTIVE'
WHERE buyer.login = 'test_catalog_seller_1'
  AND NOT EXISTS (
      SELECT 1 FROM product_basket existing
      WHERE existing.participant_id = buyer.id AND existing.product_id = product.id
  );

-- Один завершённый заказ и отзыв дают данные для проверки истории и рейтинга продавца.
INSERT INTO "order"
    (seller_id, customer_id, count, status, product_id, address_id, transfer_id,
     total_price, prepayment_amount, comment)
SELECT seller.id, buyer.id, 1, 'COMPLETED', product.id, address.id, transfer.id,
       product.price, 0, 'Тестовый заказ каталога'
FROM participant seller
JOIN participant buyer ON buyer.login = 'test_catalog_seller_1'
JOIN product ON product.participant_id = seller.id
            AND product.name = 'Pokémon TCG Booster Box'
            AND product.status = 'ACTIVE'
JOIN transfer ON transfer.participant_id = seller.id
JOIN participant_address pa ON pa.participant_id = buyer.id
JOIN address ON address.id = pa.address_id
WHERE seller.login = 'test_catalog_seller_2'
  AND NOT EXISTS (
      SELECT 1 FROM "order" existing
      WHERE existing.comment = 'Тестовый заказ каталога'
        AND existing.seller_id = seller.id
        AND existing.customer_id = buyer.id
        AND existing.product_id = product.id
  );

INSERT INTO review (order_id, product_id, reviewer_id, seller_id, rating, comment)
SELECT purchase.id, purchase.product_id, purchase.customer_id, purchase.seller_id,
       5, 'Тестовый отзыв каталога'
FROM "order" purchase
WHERE purchase.comment = 'Тестовый заказ каталога'
  AND NOT EXISTS (SELECT 1 FROM review existing WHERE existing.order_id = purchase.id);

COMMIT;

-- Проверка: товары должны быть в Фигурках, Карточках, Манге и Другом;
-- среди них есть PREORDER, used и NSFW (18+).
SELECT COUNT(*) AS products,
       COUNT(*) FILTER (WHERE used) AS used_products,
       COUNT(*) FILTER (WHERE availability = 'PREORDER') AS preorder_products
FROM product
WHERE participant_id IN (
    SELECT id FROM participant WHERE login LIKE 'test_catalog_seller_%'
)
  AND status = 'ACTIVE';
