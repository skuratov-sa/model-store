-- Оставляем корневую категорию 18+ со slug nsfw_adult.
-- Остальные варианты 18+ появились в V1.1 и V1.8 и должны быть объединены с ней.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM category
        WHERE slug = 'nsfw_adult'
    ) THEN
        RAISE EXCEPTION 'Canonical adult category with slug nsfw_adult was not found';
    END IF;
END
$$;

CREATE TEMP TABLE adult_category_canonical ON COMMIT DROP AS
SELECT id
FROM category
WHERE slug = 'nsfw_adult';

CREATE TEMP TABLE adult_category_duplicates ON COMMIT DROP AS
SELECT id
FROM category
WHERE slug = 'nsfw_18plus'
   OR (slug IS NULL AND name IN ('NSFW (18+)', '18+'));

-- Сохраняем дерево категорий, если у старых категорий появились дочерние записи.
UPDATE category child
SET parent_id = canonical.id
FROM adult_category_canonical canonical
, adult_category_duplicates duplicate
WHERE child.parent_id = duplicate.id
  AND child.id <> canonical.id;

-- Если товар уже связан с канонической категорией, удаляем только дублирующую связь.
DELETE FROM product_category duplicate_link
USING adult_category_canonical canonical,
      adult_category_duplicates duplicate
WHERE duplicate_link.category_id = duplicate.id
  AND EXISTS (
      SELECT 1
      FROM product_category canonical_link
      WHERE canonical_link.product_id = duplicate_link.product_id
        AND canonical_link.category_id = canonical.id
  );

-- Остальные связи товаров переносим на каноническую категорию.
UPDATE product_category product_link
SET category_id = canonical.id
FROM adult_category_canonical canonical,
     adult_category_duplicates duplicate
WHERE product_link.category_id = duplicate.id;

-- В product_category нет уникального ограничения, поэтому устраняем возможные
-- повторные связи товара с одной и той же канонической категорией.
WITH ranked_links AS (
    SELECT product_link.id,
           ROW_NUMBER() OVER (
               PARTITION BY product_link.product_id, product_link.category_id
               ORDER BY product_link.id
           ) AS row_number
    FROM product_category product_link
    JOIN adult_category_canonical canonical ON product_link.category_id = canonical.id
)
DELETE FROM product_category product_link
USING ranked_links ranked
WHERE product_link.id = ranked.id
  AND ranked.row_number > 1;

DELETE FROM category duplicate
USING adult_category_duplicates duplicate_category
WHERE duplicate.id = duplicate_category.id;
