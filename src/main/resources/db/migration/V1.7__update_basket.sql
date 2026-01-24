ALTER TABLE product_basket ADD COLUMN count INTEGER NOT NULL DEFAULT 1;
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_basket_unique ON product_basket (participant_id, product_id);