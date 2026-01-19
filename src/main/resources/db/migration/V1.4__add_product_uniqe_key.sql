-- 1) Оставляем один ACTIVE на (participant_id, name), остальные переводим в DELETED
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY participant_id, name ORDER BY id) AS rn
    FROM product
    WHERE status = 'ACTIVE'
)
UPDATE product p
SET status = 'DELETED'
FROM ranked r
WHERE p.id = r.id
  AND r.rn > 1;

-- 2) Создаём уникальный индекс
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_one_client
    ON product (participant_id, name)
    WHERE status = 'ACTIVE';

COMMIT;