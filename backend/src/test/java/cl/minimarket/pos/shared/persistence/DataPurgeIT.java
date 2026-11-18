package cl.minimarket.pos.shared.persistence;

import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los cuatro jobs de purga contra PostgreSQL real y con los permisos reales de {@code pos_app}:
 * cada uno elimina (o marca) solo lo vencido. Las pruebas usan un instante fijo y filas con
 * identificadores propios, para no interferir con otras pruebas que comparten la BD.
 */
class DataPurgeIT extends AbstractIntegrationIT {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Autowired
    DataPurgeService purge;

    @Autowired
    DataPurgeJobs jobs;

    @Autowired
    JdbcClient jdbc;

    private long userId;

    @BeforeEach
    void createUser() {
        userId = TestData.insertUser(jdbc);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant daysBefore(long days) {
        return NOW.minus(Duration.ofDays(days));
    }

    // ---- login_attempts ----------------------------------------------------------------------

    private String insertAttempt(Instant attemptedAt) {
        String username = "att-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO login_attempts (username, method, success, attempted_at) VALUES (:u, 'PASSWORD', false, :t)")
                .param("u", username).param("t", at(attemptedAt)).update();
        return username;
    }

    private boolean attemptExists(String username) {
        return jdbc.sql("SELECT count(*) FROM login_attempts WHERE username = :u").param("u", username)
                .query(Long.class).single() == 1;
    }

    @Test
    void purgesOnlyLoginAttemptsOlderThan90Days() {
        String old = insertAttempt(daysBefore(91));
        String edge = insertAttempt(daysBefore(89));
        String recent = insertAttempt(daysBefore(1));

        int deleted = purge.purgeLoginAttempts(NOW);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(attemptExists(old)).isFalse();
        assertThat(attemptExists(edge)).isTrue();
        assertThat(attemptExists(recent)).isTrue();
    }

    // ---- refresh_tokens ----------------------------------------------------------------------

    private long insertToken(Instant expiresAt, Instant absoluteExpiresAt, Instant revokedAt) {
        return jdbc.sql("""
                INSERT INTO refresh_tokens (user_id, token_hash, family_id, issued_at, expires_at, absolute_expires_at,
                                            revoked_at, revoked_reason)
                VALUES (:u, :h, :f, :i, :e, :a, :r, :reason) RETURNING id
                """)
                .param("u", userId)
                .param("h", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
                .param("f", UUID.randomUUID())
                .param("i", at(daysBefore(200)))
                .param("e", at(expiresAt))
                .param("a", at(absoluteExpiresAt))
                .param("r", revokedAt == null ? null : at(revokedAt))
                .param("reason", revokedAt == null ? null : "LOGOUT")
                .query(Long.class).single();
    }

    private boolean tokenExists(long id) {
        return jdbc.sql("SELECT count(*) FROM refresh_tokens WHERE id = :id").param("id", id)
                .query(Long.class).single() == 1;
    }

    @Test
    void purgesOnlyDeadRefreshTokensOlderThanTheRetention() {
        Instant future = NOW.plus(Duration.ofDays(10));
        long active = insertToken(future, future, null);
        long expiredLongAgo = insertToken(daysBefore(30), future, null);
        long expiredYesterday = insertToken(daysBefore(1), future, null);
        long absoluteExpiredLongAgo = insertToken(future, daysBefore(20), null);
        long revokedLongAgo = insertToken(future, future, daysBefore(15));
        long revokedYesterday = insertToken(future, future, daysBefore(1));

        purge.purgeRefreshTokens(NOW);

        assertThat(tokenExists(active)).as("activo").isTrue();
        assertThat(tokenExists(expiredLongAgo)).as("vencido hace 30 días").isFalse();
        assertThat(tokenExists(expiredYesterday)).as("vencido ayer: se conserva para detectar reuso").isTrue();
        assertThat(tokenExists(absoluteExpiredLongAgo)).as("tope absoluto vencido hace 20 días").isFalse();
        assertThat(tokenExists(revokedLongAgo)).as("revocado hace 15 días").isFalse();
        assertThat(tokenExists(revokedYesterday)).as("revocado ayer").isTrue();
    }

    @Test
    void purgingAReplacedTokenNullsTheReplacementReference() {
        Instant future = NOW.plus(Duration.ofDays(10));
        long successor = insertToken(future, future, null);
        long old = insertToken(daysBefore(30), future, daysBefore(30));
        jdbc.sql("UPDATE refresh_tokens SET replaced_by_id = :s WHERE id = :o").param("s", successor).param("o", old).update();
        long pointing = insertToken(future, future, null);
        jdbc.sql("UPDATE refresh_tokens SET replaced_by_id = :o WHERE id = :p").param("o", old).param("p", pointing).update();

        purge.purgeRefreshTokens(NOW);

        assertThat(tokenExists(old)).isFalse();
        assertThat(jdbc.sql("SELECT replaced_by_id FROM refresh_tokens WHERE id = :p").param("p", pointing)
                .query((rs, n) -> Optional.ofNullable(rs.getObject("replaced_by_id"))).single()).isEmpty();
    }

    // ---- idempotency_keys --------------------------------------------------------------------

    private UUID insertKey(Instant createdAt, Instant expiresAt) {
        UUID key = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO idempotency_keys (idem_key, user_id, endpoint, request_hash, status, created_at, expires_at)
                VALUES (:k, :u, 'POST /purge', :h, 'COMPLETED', :c, :x)
                """)
                .param("k", key).param("u", userId).param("h", "a".repeat(64))
                .param("c", at(createdAt)).param("x", at(expiresAt)).update();
        return key;
    }

    private boolean keyExists(UUID key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE idem_key = :k").param("k", key)
                .query(Long.class).single() == 1;
    }

    @Test
    void purgesOnlyExpiredIdempotencyKeys() {
        UUID expired = insertKey(NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(6)));
        UUID alive = insertKey(NOW.minus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(22)));

        int deleted = purge.purgeIdempotencyKeys(NOW);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(keyExists(expired)).isFalse();
        assertThat(keyExists(alive)).isTrue();
    }

    // ---- stored_files ------------------------------------------------------------------------

    private long insertFile(String category, Instant createdAt) {
        return jdbc.sql("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256, category, created_at)
                VALUES (:k, 'f.bin', 'application/octet-stream', 10, :h, :c, :t) RETURNING id
                """)
                .param("k", UUID.randomUUID().toString()).param("h", "b".repeat(64))
                .param("c", category).param("t", at(createdAt))
                .query(Long.class).single();
    }

    /** {@code deleted_at} de un archivo; vacío si es NULL. */
    private Optional<Instant> deletedAt(long id) {
        return jdbc.sql("SELECT deleted_at FROM stored_files WHERE id = :id").param("id", id)
                .query((rs, n) -> Optional.ofNullable(rs.getObject("deleted_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant))
                .single();
    }

    @Test
    void marksOnlyOldImportAndExportFilesAndNeverDeletesRows() {
        long oldImport = insertFile("IMPORT", daysBefore(31));
        long oldExport = insertFile("EXPORT", daysBefore(45));
        long recentImport = insertFile("IMPORT", daysBefore(29));
        long oldImage = insertFile("PRODUCT_IMAGE", daysBefore(400));
        long oldLogo = insertFile("COMPANY_LOGO", daysBefore(400));
        long alreadyMarked = insertFile("EXPORT", daysBefore(90));
        jdbc.sql("UPDATE stored_files SET deleted_at = :t WHERE id = :id")
                .param("t", at(daysBefore(60))).param("id", alreadyMarked).update();

        int marked = purge.markOrphanStoredFiles(NOW);

        assertThat(marked).isGreaterThanOrEqualTo(2);
        assertThat(deletedAt(oldImport)).contains(NOW);
        assertThat(deletedAt(oldExport)).contains(NOW);
        assertThat(deletedAt(recentImport)).isEmpty();
        assertThat(deletedAt(oldImage)).as("las imágenes de producto nunca son huérfanas").isEmpty();
        assertThat(deletedAt(oldLogo)).isEmpty();
        assertThat(deletedAt(alreadyMarked)).as("no se re-marca").contains(daysBefore(60));
    }

    // ---- jobs (cableado con el reloj real) -----------------------------------------------------

    @Test
    void scheduledJobsRunAgainstTheRealClockAndSwallowFailures() {
        Instant realNow = Instant.now();
        String old = insertAttempt(realNow.minus(Duration.ofDays(120)));
        String recent = insertAttempt(realNow.minus(Duration.ofDays(1)));
        long oldFile = insertFile("IMPORT", realNow.minus(Duration.ofDays(60)));
        UUID expiredKey = insertKey(realNow.minus(Duration.ofDays(3)), realNow.minus(Duration.ofDays(2)));

        jobs.purgeLoginAttempts();
        jobs.purgeRefreshTokens();
        jobs.purgeIdempotencyKeys();
        jobs.markOrphanStoredFiles();

        assertThat(attemptExists(old)).isFalse();
        assertThat(attemptExists(recent)).isTrue();
        assertThat(deletedAt(oldFile)).isPresent();
        assertThat(keyExists(expiredKey)).isFalse();
    }
}
