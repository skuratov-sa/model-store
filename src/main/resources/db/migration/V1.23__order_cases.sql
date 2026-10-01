
CREATE TABLE order_case (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES "order"(id),
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('DISPUTE', 'PAYMENT_APPEAL', 'CANCELLATION_REQUEST')),
    state VARCHAR(16) NOT NULL CHECK (state IN ('OPEN', 'RESOLVED')),
    opened_by BIGINT REFERENCES participant(id),
    opening_comment TEXT,
    previous_order_status order_status,
    telegram_url VARCHAR(2048),
    resolved_by BIGINT REFERENCES participant(id),
    outcome VARCHAR(16) CHECK (outcome IN ('BUYER', 'SELLER', 'CANCELLED', 'REJECTED', 'LEGACY')),
    resolution_comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CHECK ((state = 'OPEN' AND resolved_at IS NULL AND outcome IS NULL)
        OR (state = 'RESOLVED' AND resolved_at IS NOT NULL AND outcome IS NOT NULL))
);
CREATE UNIQUE INDEX uq_open_order_case ON order_case(order_id) WHERE state = 'OPEN';
CREATE INDEX idx_order_case_admin_queue ON order_case(state, created_at DESC, id DESC);

ALTER TABLE image ADD COLUMN uploaded_by BIGINT REFERENCES participant(id);
CREATE TABLE order_case_image (
    case_id BIGINT NOT NULL REFERENCES order_case(id),
    image_id BIGINT NOT NULL UNIQUE REFERENCES image(id),
    uploaded_by BIGINT REFERENCES participant(id),
    PRIMARY KEY(case_id, image_id)
);

-- Historical FAILED orders were cancellations unless their history contains a dispute.
UPDATE "order" o SET status = 'CANCELLED'
WHERE o.status = 'FAILED'
  AND NOT EXISTS (SELECT 1 FROM order_status_history h
                  WHERE h.order_id = o.id AND h.status = 'DISPUTED');

INSERT INTO order_case(order_id, kind, state, opened_by, opening_comment, previous_order_status)
SELECT o.id, 'DISPUTE', 'OPEN', o.customer_id, o.comment, 'ON_THE_WAY'
FROM "order" o WHERE o.status = 'DISPUTED';

-- Older completed disputes have no trustworthy opener evidence or administrator decision.
INSERT INTO order_case(order_id, kind, state, opened_by, opening_comment,
                       previous_order_status, outcome, resolution_comment, created_at, resolved_at)
SELECT o.id, 'DISPUTE', 'RESOLVED', o.customer_id, opened.comment,
       'ON_THE_WAY', 'LEGACY', o.comment, opened.changed_at, closed.changed_at
FROM "order" o
JOIN LATERAL (SELECT comment, changed_at FROM order_status_history
              WHERE order_id = o.id AND status = 'DISPUTED'
              ORDER BY changed_at DESC LIMIT 1) opened ON true
JOIN LATERAL (SELECT changed_at FROM order_status_history
              WHERE order_id = o.id AND status IN ('COMPLETED', 'FAILED')
                AND changed_at >= opened.changed_at
              ORDER BY changed_at LIMIT 1) closed ON true
WHERE o.status IN ('COMPLETED', 'FAILED');

CREATE OR REPLACE FUNCTION process_expired_booked_orders() RETURNS VOID AS $$
BEGIN
    WITH cancelled AS (
        UPDATE "order" SET status = 'CANCELLED',
            comment = 'Заказ отменён: продавец не ответил в течение дня'
        WHERE status = 'BOOKED' AND created_at <= current_timestamp - interval '1 day'
        RETURNING product_id, count
    ), restored AS (
        SELECT product_id, SUM(count) AS amount FROM cancelled GROUP BY product_id
    )
    UPDATE product p SET count = p.count + restored.amount
    FROM restored
    WHERE p.id = restored.product_id AND p.availability = 'PURCHASABLE'
      AND p.count IS NOT NULL;
END;
$$ LANGUAGE plpgsql;

INSERT INTO dictionary(type, value, description)
VALUES ('ORDER_STATUS', 'CANCELLED', 'Заказ отменён');
