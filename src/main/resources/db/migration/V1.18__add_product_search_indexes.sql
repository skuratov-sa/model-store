-- Category filtering and the adult-content exclusion use opposite lookup directions.
CREATE INDEX idx_product_category_category_product
    ON product_category (category_id, product_id);

CREATE INDEX idx_product_category_product_category
    ON product_category (product_id, category_id);

-- Seller searches always constrain participant_id.
CREATE INDEX idx_product_participant_created_id
    ON product (participant_id, created_at DESC, id DESC);
