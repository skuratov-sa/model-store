-- Preserve essential product details before removing the foreign key.
ALTER TABLE "order"
    ADD COLUMN product_name varchar(200),
    ADD COLUMN product_unit_price float,
    ADD COLUMN product_currency currency,
    ADD COLUMN product_availability product_availability;

UPDATE "order" o
SET product_name = p.name,
    product_unit_price = p.price,
    product_currency = p.currency,
    product_availability = p.availability
FROM product p
WHERE o.product_id = p.id;

ALTER TABLE "order"
    ALTER COLUMN product_unit_price SET NOT NULL,
    ALTER COLUMN product_currency SET NOT NULL,
    ALTER COLUMN product_availability SET NOT NULL;

-- Keep the numeric product id in orders and reviews as historical data.
ALTER TABLE "order" DROP CONSTRAINT order_product_id_fkey;
ALTER TABLE review DROP CONSTRAINT review_product_id_fkey;
