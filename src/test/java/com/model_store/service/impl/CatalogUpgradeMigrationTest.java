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

class CatalogUpgradeMigrationTest {

    @Test
    void upgradeTransfersLinksAndMarksOnlyOrphanedProductsDeleted() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
            Flyway.configure()
                    .dataSource(url, "postgres", "")
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("1.16"))
                    .load()
                    .migrate();

            long keptProduct;
            long deletedProduct;
            long aliasedProduct;
            long adultId;
            try (Connection connection = DriverManager.getConnection(url, "postgres", "")) {
                long figmaId = queryLong(connection, "SELECT id FROM category WHERE slug = 'figma'");
                adultId = queryLong(connection, "SELECT id FROM category WHERE slug = 'nsfw_adult'");
                long usedId = queryLong(connection, "SELECT id FROM category WHERE slug = 'used'");
                long gradedId = queryLong(connection, "SELECT id FROM category WHERE slug = 'graded'");
                long legacyId = queryLong(connection,
                        "INSERT INTO category (name, parent_id) VALUES ('Nendroids', NULL) RETURNING id");

                keptProduct = createProduct(connection, "Kept product");
                deletedProduct = createProduct(connection, "Deleted product");
                aliasedProduct = createProduct(connection, "Aliased product");
                execute(connection, "INSERT INTO product_category (product_id, category_id) VALUES "
                        + "(" + keptProduct + ", " + figmaId + "), "
                        + "(" + keptProduct + ", " + usedId + "), "
                        + "(" + keptProduct + ", " + adultId + "), "
                        + "(" + deletedProduct + ", " + gradedId + "), "
                        + "(" + aliasedProduct + ", " + legacyId + ")");
            }

            Flyway.configure()
                    .dataSource(url, "postgres", "")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(url, "postgres", "")) {
                assertThat(queryLong(connection, "SELECT count(*) FROM category")).isEqualTo(113);
                assertThat(queryLong(connection, "SELECT id FROM category WHERE slug = 'nsfw_adult'"))
                        .isEqualTo(adultId);
                assertThat(queryString(connection, "SELECT status::text FROM product WHERE id = " + keptProduct))
                        .isEqualTo("ACTIVE");
                assertThat(queryString(connection, "SELECT used::text FROM product WHERE id = " + keptProduct))
                        .isEqualTo("true");
                assertThat(queryLong(connection, "SELECT count(*) FROM product_category pc "
                        + "JOIN category c ON c.id = pc.category_id WHERE pc.product_id = " + keptProduct
                        + " AND c.slug IN ('figma', 'nsfw_adult')")).isEqualTo(2);
                assertThat(queryString(connection, "SELECT status::text FROM product WHERE id = " + deletedProduct))
                        .isEqualTo("DELETED");
                assertThat(queryLong(connection, "SELECT count(*) FROM product_category WHERE product_id = "
                        + deletedProduct)).isZero();
                assertThat(queryString(connection, "SELECT status::text FROM product WHERE id = " + aliasedProduct))
                        .isEqualTo("ACTIVE");
                assertThat(queryLong(connection, "SELECT count(*) FROM product_category pc "
                        + "JOIN category c ON c.id = pc.category_id WHERE pc.product_id = " + aliasedProduct
                        + " AND c.slug = 'nendoroid'")).isEqualTo(1);
            }
        }
    }

    private long createProduct(Connection connection, String name) throws SQLException {
        return queryLong(connection, "INSERT INTO product "
                + "(name, price, currency, participant_id, status, availability) VALUES "
                + "('" + name + "', 100, 'RUB', (SELECT id FROM participant LIMIT 1), "
                + "'ACTIVE', 'PURCHASABLE') RETURNING id");
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
