package cl.minimarket.pos.support;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica la inmutabilidad de {@code audit_log} (REVOKE a {@code pos_app} + trigger que rige
 * incluso para {@code pos_owner}) y la excepción de {@code stored_files} (solo
 * {@code UPDATE (deleted_at)}). Las filas insertadas no pueden borrarse (es lo que se prueba),
 * por eso cada prueba usa valores únicos y no asume la tabla vacía.
 */
class AuditImmutabilityIT extends AbstractIntegrationIT {

    private static final String INSERT_AUDIT = """
            INSERT INTO audit_log (username, action, entity_type, entity_id, hash)
            VALUES ('it-user', 'IT_TEST', 'IT', :entityId, repeat('a', 64))
            """;

    private static String insertAuditRow(JdbcClient jdbc) {
        String entityId = UUID.randomUUID().toString().substring(0, 30);
        int rows = jdbc.sql(INSERT_AUDIT).param("entityId", entityId).update();
        assertThat(rows).isEqualTo(1);
        return entityId;
    }

    @Test
    void appCanInsertButNotUpdateOrDeleteAuditLog() {
        JdbcClient app = appJdbc();
        String entityId = insertAuditRow(app);

        assertThatThrownBy(() -> app.sql("UPDATE audit_log SET reason = 'x' WHERE entity_id = :id")
                .param("id", entityId).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.sql("DELETE FROM audit_log WHERE entity_id = :id")
                .param("id", entityId).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void triggerRejectsUpdateAndDeleteEvenForOwner() {
        JdbcClient owner = ownerJdbc();
        String entityId = insertAuditRow(owner);

        assertThatThrownBy(() -> owner.sql("UPDATE audit_log SET reason = 'x' WHERE entity_id = :id")
                .param("id", entityId).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("audit_log es inmutable");
        assertThatThrownBy(() -> owner.sql("DELETE FROM audit_log WHERE entity_id = :id")
                .param("id", entityId).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("audit_log es inmutable");
    }

    @Test
    void appCanOnlyUpdateDeletedAtOnStoredFiles() {
        JdbcClient owner = ownerJdbc();
        JdbcClient app = appJdbc();
        String key = "it/" + UUID.randomUUID();
        owner.sql("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256, category)
                VALUES (:key, 'a.csv', 'text/csv', 1, repeat('b', 64), 'EXPORT')
                """).param("key", key).update();
        try {
            int updated = app.sql("UPDATE stored_files SET deleted_at = now() WHERE storage_key = :key")
                    .param("key", key).update();
            assertThat(updated).isEqualTo(1);

            assertThatThrownBy(() -> app.sql("UPDATE stored_files SET original_name = 'z' WHERE storage_key = :key")
                    .param("key", key).update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> app.sql("DELETE FROM stored_files WHERE storage_key = :key")
                    .param("key", key).update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("permission denied");
        } finally {
            owner.sql("DELETE FROM stored_files WHERE storage_key = :key").param("key", key).update();
        }
    }
}
