package com.model_store.service.impl;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class StoreChannelAgentsMigrationTest {
    @Test
    void upgradePreservesExistingAgentsRejectsIdentityCollisionsAndIsRepeatable() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
            Flyway.configure().dataSource(url, "postgres", "")
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("1.28"))
                    .load().migrate();
            String migration;
            try (var input = getClass().getResourceAsStream("/db/migration/V1.29__add_store_channel_agents.sql")) {
                assertThat(input).isNotNull();
                migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }

            try (Connection connection = DriverManager.getConnection(url, "postgres", "")) {
                long figurkinId = number(connection, "SELECT id FROM participant WHERE mail='agentFigurkin'");
                String figurkin = text(connection, "SELECT row_to_json(p)::text FROM participant p WHERE id=" + figurkinId);
                String baron = text(connection, "SELECT row_to_json(p)::text FROM participant p WHERE mail='agentFigovBaron'");
                // A previously provisioned channel may have custom identifiers and be blocked.
                long miomioId = number(connection, """
                        INSERT INTO participant(login,mail,full_name,password,status,is_agent,deadline_sending,deadline_payment)
                        VALUES ('existing_miomio','customMioMail','MioMio','existing-credential-marker','BLOCKED',true,7,9)
                        RETURNING id
                        """);
                String miomio = text(connection, "SELECT row_to_json(p)::text FROM participant p WHERE id=" + miomioId);

                // Fail after earlier channels would have been inserted; the entire migration must roll back.
                long ordinary = number(connection, """
                        INSERT INTO participant(login,mail,password,status)
                        VALUES ('kitsune_castle','ordinary@example.test','ordinary-password-marker','ACTIVE') RETURNING id
                        """);
                assertMigrationConflict(connection, migration, "non-agent participant");
                assertThat(text(connection, "SELECT is_agent::text FROM participant WHERE id=" + ordinary)).isEqualTo("false");
                execute(connection, "DELETE FROM participant WHERE id=" + ordinary);

                long byMail = number(connection, """
                        INSERT INTO participant(login,mail,password,status,is_agent)
                        VALUES ('bigshelf_by_mail','agentBigShelf','marker','ACTIVE',true) RETURNING id
                        """);
                long byLogin = number(connection, """
                        INSERT INTO participant(login,mail,password,status,is_agent)
                        VALUES ('BIGSHELF','otherShelfMail','marker','ACTIVE',true) RETURNING id
                        """);
                assertMigrationConflict(connection, migration, "Ambiguous channel agent");
                execute(connection, "DELETE FROM participant WHERE id IN (" + byMail + "," + byLogin + ")");

                long before = number(connection, "SELECT count(*) FROM participant");
                Flyway.configure().dataSource(url, "postgres", "")
                        .locations("classpath:db/migration").target(MigrationVersion.fromVersion("1.29"))
                        .load().migrate();
                assertThat(number(connection, "SELECT count(*) FROM participant")).isEqualTo(before + 4);
                assertThat(text(connection, "SELECT row_to_json(p)::text FROM participant p WHERE id=" + figurkinId)).isEqualTo(figurkin);
                assertThat(text(connection, "SELECT row_to_json(p)::text FROM participant p WHERE mail='agentFigovBaron'")).isEqualTo(baron);
                assertThat(text(connection, "SELECT row_to_json(p)::text FROM participant p WHERE id=" + miomioId)).isEqualTo(miomio);
                for (String name : List.of("animestyle", "BIG SHELF", "Агент Фигуркин", "Фигурки Аниме", "Замок Кицунэ", "MioMio")) {
                    assertThat(number(connection, "SELECT count(*) FROM participant WHERE is_agent AND full_name='" + name + "'"))
                            .as(name).isEqualTo(1);
                }
                assertThat(number(connection, """
                        SELECT count(*) FROM participant WHERE login IN ('animestyle','bigshelf','anime_figures','kitsune_castle')
                        AND is_agent AND role='USER' AND status='ACTIVE' AND password='!agent-token-only!'
                        AND created_at IS NOT NULL AND deadline_sending=1 AND deadline_payment=1
                        """)).isEqualTo(4);

                List<String> after = snapshots(connection);
                execute(connection, migration);
                assertThat(snapshots(connection)).containsExactlyElementsOf(after);
            }
        }
    }

    private void assertMigrationConflict(Connection connection, String migration, String message) throws SQLException {
        List<String> before = snapshots(connection);
        connection.setAutoCommit(false);
        try {
            assertThatThrownBy(() -> execute(connection, migration))
                    .isInstanceOf(SQLException.class).hasMessageContaining(message);
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
        assertThat(snapshots(connection)).containsExactlyElementsOf(before);
    }

    private List<String> snapshots(Connection connection) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT row_to_json(p)::text FROM participant p ORDER BY id")) {
            while (result.next()) rows.add(result.getString(1));
        }
        return rows;
    }

    private long number(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
