package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V1_31__order_terms extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                ALTER TABLE "order"
                  ALTER COLUMN address_id DROP NOT NULL,
                  ALTER COLUMN transfer_id DROP NOT NULL;
                ALTER TABLE "order"
                  ADD COLUMN delivery_carrier varchar(32),
                  ADD COLUMN delivery_address text,
                  ADD COLUMN goods_amount numeric(19,2),
                  ADD COLUMN planned_prepayment_amount numeric(19,2),
                  ADD COLUMN delivery_amount numeric(19,2),
                  ADD COLUMN confirmed_paid_amount numeric(19,2),
                  ADD COLUMN terms_version bigint NOT NULL DEFAULT 0,
                  ADD COLUMN pricing_known boolean NOT NULL DEFAULT false;
                ALTER TABLE "order" ADD CONSTRAINT order_terms_amounts CHECK (
                  (goods_amount IS NULL OR goods_amount >= 0) AND
                  (delivery_amount IS NULL OR delivery_amount >= 0) AND
                  (confirmed_paid_amount IS NULL OR confirmed_paid_amount >= 0));
                CREATE TABLE order_terms_event (
                  id bigserial PRIMARY KEY,
                  order_id bigint NOT NULL REFERENCES "order"(id) ON DELETE CASCADE,
                  actor_id bigint NOT NULL,
                  kind varchar(40) NOT NULL,
                  payload text NOT NULL,
                  created_at timestamptz NOT NULL DEFAULT now()
                );
                CREATE INDEX order_terms_event_order_idx ON order_terms_event(order_id, id);
                INSERT INTO dictionary(type,value,description)
                SELECT 'ORDER_STATUS','AWAITING_PAYMENT_APPROVAL','Ожидает проверки полной оплаты'
                WHERE NOT EXISTS (SELECT 1 FROM dictionary WHERE type='ORDER_STATUS' AND value='AWAITING_PAYMENT_APPROVAL');
                """);
        }
    }
}
