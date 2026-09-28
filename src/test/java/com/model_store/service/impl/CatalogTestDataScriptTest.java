package com.model_store.service.impl;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogTestDataScriptTest {

    @Test
    void scriptPopulatesCurrentCatalogAndCanRunTwice() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
            Flyway.configure()
                    .dataSource(url, "postgres", "")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            String script;
            try (InputStream input = getClass().getClassLoader()
                    .getResourceAsStream("testdata/V1.3__init_test_data.sql")) {
                assertThat(input).isNotNull();
                script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }

            try (Connection connection = DriverManager.getConnection(url, "postgres", "")) {
                for (int run = 0; run < 2; run++) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(script);
                    }
                }

                assertThat(count(connection, "SELECT COUNT(*) FROM category")).isEqualTo(113);
                assertThat(count(connection, "SELECT COUNT(*) FROM product p JOIN participant seller "
                        + "ON seller.id = p.participant_id "
                        + "WHERE seller.login LIKE 'test_catalog_seller_%' AND p.status = 'ACTIVE'"))
                        .isEqualTo(68);
                assertThat(count(connection, "SELECT COUNT(*) FROM category c "
                        + "WHERE NOT EXISTS (SELECT 1 FROM category child WHERE child.parent_id = c.id) "
                        + "AND EXISTS (SELECT 1 FROM product_category pc WHERE pc.category_id = c.id)"))
                        .isEqualTo(101);
                assertThat(count(connection, "SELECT COUNT(*) FROM product p "
                        + "JOIN participant seller ON seller.id = p.participant_id "
                        + "WHERE seller.login LIKE 'test_catalog_seller_%' "
                        + "AND p.availability = 'PREORDER' AND p.used"))
                        .isPositive();
                assertThat(count(connection, "SELECT COUNT(*) FROM product_category pc "
                        + "JOIN category c ON c.id = pc.category_id "
                        + "WHERE c.slug = 'nsfw_adult'"))
                        .isPositive();
                assertThat(count(connection, "SELECT COUNT(*) FROM ("
                        + "SELECT product_id, category_id FROM product_category "
                        + "GROUP BY product_id, category_id HAVING COUNT(*) > 1) duplicates"))
                        .isZero();
                assertThat(count(connection, "SELECT COUNT(*) FROM \"order\" "
                        + "WHERE comment = 'Тестовый заказ каталога'")).isEqualTo(1);
                assertThat(count(connection, "SELECT COUNT(*) FROM review "
                        + "WHERE comment = 'Тестовый отзыв каталога'")).isEqualTo(1);
            }
        }
    }

    private long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
