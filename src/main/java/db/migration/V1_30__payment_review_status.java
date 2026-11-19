package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V1_30__payment_review_status extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'AWAITING_PAYMENT_APPROVAL'");
        }
    }
}
