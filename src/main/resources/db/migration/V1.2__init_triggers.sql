-- создаем функцию для автодобавления
CREATE OR REPLACE FUNCTION log_order_status_change_limited()
    RETURNS TRIGGER AS
$$
BEGIN
    -- Ограничиваем обновление на эти сущности
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        -- Добавляем запись в историю
        INSERT INTO order_status_history (order_id, status, comment, changed_at)
        VALUES (NEW.id, NEW.status, NEW.comment, current_timestamp);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Создаем триггер (при добавлении в таблицу order
CREATE TRIGGER trigger_order_status_change_limited
    AFTER UPDATE OF status
    ON "order"
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status) -- Триггер срабатывает только при изменении статуса
EXECUTE FUNCTION log_order_status_change_limited();

CREATE TRIGGER trigger_order_status_change_limited_insert
    AFTER INSERT
    ON "order"
    FOR EACH ROW
EXECUTE FUNCTION log_order_status_change_limited();

-- Ставим триггер если в течении дня продавец не принял товар

--Если в течении дня товар не взяли в работу и он остался в статусе BOOKED
CREATE OR REPLACE FUNCTION process_expired_booked_orders()
    RETURNS VOID AS $$
BEGIN
    -- Обновляем просроченные заказы
    UPDATE "order"
    SET status = 'FAILED',
        comment = 'Товар отменен потому что продавец не вышел на связь в течение дня'
    WHERE status = 'BOOKED'
      AND created_at <= current_timestamp - interval '1 day';
END;
$$ LANGUAGE plpgsql;

-- расписание для обновления полей в 00 00
-- SELECT * FROM cron.job;
-- UPDATE cron.job SET database = 'model_store' WHERE jobid = 1;
-- SHOW cron.database_name;
-- CREATE EXTENSION IF NOT EXISTS pg_cron;
-- SELECT cron.schedule(
--                'process_expired_orders_job', -- Имя задачи
--                '0 0 * * *', -- Ежедневно в 00:00
--                $$ CALL process_expired_booked_orders(); $$ -- Вызов функции
--        );
