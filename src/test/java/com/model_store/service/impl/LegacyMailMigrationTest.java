package com.model_store.service.impl;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class LegacyMailMigrationTest {
    @Test
    void upgradePreservesAmbiguousAccountsAndBlocksNewDuplicates() throws Exception {
        try (var postgres = EmbeddedPostgres.start()) {
            var source = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("1.31")).load().migrate();
            try (var connection = source.getConnection(); var sql = connection.createStatement()) {
                sql.execute("""
                        INSERT INTO participant(login,mail,password,status) VALUES
                        ('legacy_mail_1','Old-Mail@example.test','first','ACTIVE'),
                        ('legacy_mail_2',' old-mail@example.test ','second','BLOCKED')
                        """);
                Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
                try (var rows = sql.executeQuery("""
                        SELECT mail,password,status,legacy_mail_collision FROM participant
                        WHERE lower(btrim(mail)) = 'old-mail@example.test' ORDER BY login
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("mail")).isEqualTo("Old-Mail@example.test");
                    assertThat(rows.getString("password")).isEqualTo("first");
                    assertThat(rows.getBoolean("legacy_mail_collision")).isTrue();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("mail")).isEqualTo(" old-mail@example.test ");
                    assertThat(rows.getString("status")).isEqualTo("BLOCKED");
                    assertThat(rows.getBoolean("legacy_mail_collision")).isTrue();
                    assertThat(rows.next()).isFalse();
                }
                assertThatThrownBy(() -> sql.execute("""
                        INSERT INTO participant(login,mail,password,status) VALUES
                        ('new_mail_3','old-mail@example.test','third','ACTIVE')
                        """)).isInstanceOf(SQLException.class).hasMessageContaining("uq_participant_normalized_mail");
                sql.execute("UPDATE participant SET mail = 'different@example.test' WHERE login = 'legacy_mail_2'");
            }
        }
    }
}
