package com.model_store.service.impl;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class OrderProductSnapshotMigrationTest {

    @Test
    void upgradeBackfillsExistingOrderAndPreservesReviewAfterProductDeletion() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
            Flyway.configure()
                    .dataSource(url, "postgres", "")
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("1.20"))
                    .load().migrate();

            long productId;
            long orderId;
            try (Connection connection = DriverManager.getConnection(url, "postgres", "")) {
                long sellerId = queryLong(connection, "INSERT INTO participant (login, password, status) "
                        + "VALUES ('snapshot-migration-seller', 'password', 'ACTIVE') RETURNING id");
                long addressId = queryLong(connection, "INSERT INTO address DEFAULT VALUES RETURNING id");
                long transferId = queryLong(connection, "INSERT INTO transfer (sending, participant_id) "
                        + "VALUES ('PRODUCT_PICKUP', " + sellerId + ") RETURNING id");
                productId = queryLong(connection, "INSERT INTO product "
                        + "(name, price, currency, participant_id, status, availability) VALUES "
                        + "('Historical Product', 100, 'RUB', " + sellerId + ", 'DELETED', 'PURCHASABLE') "
                        + "RETURNING id");
                orderId = queryLong(connection, "INSERT INTO \"order\" "
                        + "(seller_id, customer_id, count, status, product_id, address_id, transfer_id) VALUES "
                        + "(" + sellerId + ", " + sellerId + ", 1, 'COMPLETED', " + productId + ", "
                        + addressId + ", " + transferId + ") RETURNING id");
                execute(connection, "INSERT INTO review (order_id, product_id, reviewer_id, seller_id, rating) "
                        + "VALUES (" + orderId + ", " + productId + ", " + sellerId + ", " + sellerId + ", 5)");
            }

            Flyway.configure()
                    .dataSource(url, "postgres", "")
                    .locations("classpath:db/migration")
                    .load().migrate();

            try (Connection connection = DriverManager.getConnection(url, "postgres", "")) {
                assertThat(queryString(connection, "SELECT product_name FROM \"order\" "
                        + "WHERE id = " + orderId)).isEqualTo("Historical Product");
                assertThat(queryString(connection, "SELECT product_unit_price::text FROM \"order\" "
                        + "WHERE id = " + orderId)).isEqualTo("100");
                assertThat(queryString(connection, "SELECT product_currency::text FROM \"order\" "
                        + "WHERE id = " + orderId)).isEqualTo("RUB");
                assertThat(queryString(connection, "SELECT product_availability::text FROM \"order\" "
                        + "WHERE id = " + orderId)).isEqualTo("PURCHASABLE");
                execute(connection, "DELETE FROM product WHERE id = " + productId);
                assertThat(queryLong(connection, "SELECT count(*) FROM \"order\" WHERE product_id = " + productId))
                        .isEqualTo(1);
                assertThat(queryLong(connection, "SELECT count(*) FROM review WHERE product_id = " + productId))
                        .isEqualTo(1);
            }
        }
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
