ALTER TYPE product_availability RENAME VALUE 'EXTERNAL_ONLY' TO 'EXTERNAL_PRODUCT';

UPDATE dictionary
SET value = 'EXTERNAL_PRODUCT', description = 'Товар из внешнего источника'
WHERE type = 'PRODUCT_AVAILABILITY' AND value = 'EXTERNAL_ONLY';

ALTER TABLE participant ADD COLUMN is_agent boolean NOT NULL DEFAULT false;
UPDATE participant SET is_agent = true WHERE mail IN ('agentFigurkin', 'agentFigovBaron');

CREATE TABLE admin_agent_order_action (
    id bigserial PRIMARY KEY,
    admin_id bigint NOT NULL REFERENCES participant(id),
    agent_id bigint NOT NULL REFERENCES participant(id),
    order_id bigint NOT NULL REFERENCES "order"(id),
    action varchar(40) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX admin_agent_order_action_order_idx ON admin_agent_order_action(order_id);
