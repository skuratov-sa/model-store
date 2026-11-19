package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V1_33__record_order_stock_reservation extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                ALTER TABLE "order" ADD COLUMN stock_reserved boolean;

                CREATE OR REPLACE FUNCTION process_expired_booked_orders() RETURNS VOID AS $$
                BEGIN
                    WITH cancelled AS (
                        UPDATE "order" SET status = 'CANCELLED',
                            comment = 'Заказ отменён: продавец не ответил в течение дня'
                        WHERE status = 'BOOKED' AND created_at <= current_timestamp - interval '1 day'
                        RETURNING product_id, count, stock_reserved, product_availability
                    ), restored AS (
                        SELECT c.product_id, SUM(c.count) AS amount
                        FROM cancelled c JOIN product p ON p.id = c.product_id
                        WHERE c.stock_reserved IS TRUE OR (c.stock_reserved IS NULL
                            AND COALESCE(c.product_availability, p.availability) = 'PURCHASABLE')
                        GROUP BY c.product_id
                    )
                    UPDATE product p SET count = p.count + restored.amount
                    FROM restored
                    WHERE p.id = restored.product_id AND p.count IS NOT NULL;
                END;
                $$ LANGUAGE plpgsql;
                """);
        }
    }
}
