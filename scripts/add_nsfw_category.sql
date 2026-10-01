-- Однократное добавление категории 18+ к товарам.
-- Скрипт идемпотентный: повторный запуск не создаёт дубликаты связей.

BEGIN;

CREATE TEMP TABLE nsfw_product_ids (product_id BIGINT PRIMARY KEY);

INSERT INTO nsfw_product_ids (product_id)
VALUES
    (346), (347), (348), (356), (359), (362), (378), (379),
    (385), (387), (389), (391), (411), (426), (433), (435),
    (453), (470), (481), (482), (487), (495), (503), (506),
    (511), (523), (566), (567), (573), (589), (612), (632);

-- Проверяем, что категория существует именно в каноническом варианте.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM category WHERE slug = 'nsfw_adult') THEN
        RAISE EXCEPTION 'Категория с slug nsfw_adult не найдена';
    END IF;
END
$$;

-- Отчёт по указанным ID, которых нет в таблице товаров.
SELECT ids.product_id AS missing_product_id
FROM nsfw_product_ids ids
LEFT JOIN product p ON p.id = ids.product_id
WHERE p.id IS NULL
ORDER BY ids.product_id;

-- Добавляем только отсутствующие связи.
INSERT INTO product_category (product_id, category_id)
SELECT ids.product_id, c.id
FROM nsfw_product_ids ids
CROSS JOIN (SELECT id FROM category WHERE slug = 'nsfw_adult') c
JOIN product p ON p.id = ids.product_id
WHERE NOT EXISTS (
    SELECT 1
    FROM product_category pc
    WHERE pc.product_id = ids.product_id
      AND pc.category_id = c.id
);

-- Контрольный список добавленных/уже существующих связей.
SELECT p.id AS product_id,
       p.name,
       c.id AS category_id,
       c.name AS category_name
FROM product p
JOIN nsfw_product_ids ids ON ids.product_id = p.id
JOIN product_category pc ON pc.product_id = p.id
JOIN category c ON c.id = pc.category_id
WHERE c.slug = 'nsfw_adult'
ORDER BY p.id;

-- Для применения изменений оставьте COMMIT.
-- Для предварительной проверки замените COMMIT на ROLLBACK.
COMMIT;
