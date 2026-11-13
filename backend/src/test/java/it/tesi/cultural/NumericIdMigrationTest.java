package it.tesi.cultural;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_PASSWORD", matches = ".+")
class NumericIdMigrationTest {

    @Test
    void migratesAllTablesPreservingValuesLinksNullsConstraintsAndSequences() throws Exception {
        String schema = "tesi_migration_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv().getOrDefault("TEST_DATABASE_URL",
                "jdbc:postgresql://localhost:5432/cultural_db");
        String user = System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "postgres");
        String password = System.getenv("TEST_DATABASE_PASSWORD");
        String numericSchema = Files.readString(Path.of("..", "schema.sql"));
        String oldSchema = numericSchema.replace(
                "id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY",
                "id UUID PRIMARY KEY DEFAULT gen_random_uuid()")
                .replace(" INTEGER REFERENCES", " UUID REFERENCES")
                .replace(" INTEGER NOT NULL REFERENCES", " UUID NOT NULL REFERENCES");
        String migration = Files.readString(Path.of("..", "migrate_numeric_ids.sql"));
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema + ", public");
                statement.execute(oldSchema);
                statement.execute("""
                        CREATE TABLE visit_logs (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id UUID REFERENCES users(id) ON DELETE SET NULL,
                            item_id UUID REFERENCES catalog_items(id) ON DELETE SET NULL,
                            timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """);
                statement.execute("""
                        INSERT INTO users (username, email, password_hash, created_at)
                        VALUES ('first','first@example.test','preserved-hash','2026-01-01'),
                               ('second','second@example.test','second-hash','2026-01-02');
                        INSERT INTO catalog_items (title) VALUES ('Original item');
                        INSERT INTO events (title, start_datetime, capacity) VALUES ('Original event', now(), 10);
                        INSERT INTO reservations (user_id, event_id, seats)
                        SELECT u.id, e.id, 2 FROM users u CROSS JOIN events e WHERE u.username = 'second';
                        INSERT INTO feedbacks (user_id, item_id, rating, comment)
                        SELECT u.id, c.id, 4, 'Preserved review' FROM users u CROSS JOIN catalog_items c
                        WHERE u.username = 'first';
                        INSERT INTO feedbacks (user_id, event_id, rating)
                        SELECT NULL, id, 3 FROM events;
                        """);
                statement.execute(migration);
                try (var rows = statement.executeQuery("""
                        SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_name = 'visit_logs'
                        """)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
                try (var rows = statement.executeQuery("""
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema = current_schema() AND data_type = 'integer'
                          AND (column_name = 'id' OR column_name LIKE '%\\_id')
                        """)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(10);
                }
                try (var rows = statement.executeQuery("""
                        SELECT u.username, u.password_hash, r.user_id, e.title FROM reservations r
                        JOIN users u ON r.user_id = u.id JOIN events e ON r.event_id = e.id
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("second");
                    assertThat(rows.getString(2)).isEqualTo("second-hash");
                    assertThat(rows.getInt(3)).isEqualTo(2);
                    assertThat(rows.getString(4)).isEqualTo("Original event");
                }
                try (var rows = statement.executeQuery("""
                        SELECT u.username, c.title, f.comment FROM feedbacks f
                        JOIN users u ON f.user_id = u.id JOIN catalog_items c ON f.item_id = c.id
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("first");
                    assertThat(rows.getString(2)).isEqualTo("Original item");
                    assertThat(rows.getString(3)).isEqualTo("Preserved review");
                }
                for (String table : java.util.List.of("users", "catalog_items", "events",
                        "reservations", "feedbacks")) {
                    try (var rows = statement.executeQuery("SELECT nextval(pg_get_serial_sequence('"
                            + table + "', 'id')) = (SELECT MAX(id) + 1 FROM " + table + ")")) {
                        rows.next();
                        assertThat(rows.getBoolean(1)).as(table + " next identifier").isTrue();
                    }
                }
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO reservations(user_id,event_id,seats) VALUES(999999,1,1)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO reservations(user_id,event_id,seats) VALUES(2,1,1)"))
                        .isInstanceOf(SQLException.class);
                statement.execute("DELETE FROM users WHERE username='first'");
                try (var rows = statement.executeQuery(
                        "SELECT user_id FROM feedbacks WHERE comment='Preserved review'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1)).isNull();
                }
                statement.execute(numericSchema);
                assertThatThrownBy(() -> statement.execute(migration)).isInstanceOf(SQLException.class);
                statement.execute("ROLLBACK");
            } finally {
                statement.execute("ROLLBACK");
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
