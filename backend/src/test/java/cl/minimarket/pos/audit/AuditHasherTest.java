package cl.minimarket.pos.audit;

import cl.minimarket.pos.audit.domain.AuditHasher;
import cl.minimarket.pos.audit.domain.AuditLogEntry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de {@link AuditEvent} y {@link AuditHasher}.
 */
class AuditHasherTest {

    @Test
    void canonicalPayloadIsIndependentOfNestedJsonKeyOrder() {
        Map<String, Object> mapA = new LinkedHashMap<>();
        mapA.put("z", 2);
        mapA.put("a", 1);

        Map<String, Object> mapB = new LinkedHashMap<>();
        mapB.put("a", 1);
        mapB.put("z", 2);

        Instant now = Instant.parse("2026-11-13T06:49:42.123456789Z");
        AuditLogEntry entryA = sampleEntry(now, AuditHasher.toJsonNode(mapA));
        AuditLogEntry entryB = sampleEntry(now, AuditHasher.toJsonNode(mapB));

        assertThat(AuditHasher.canonicalPayload(entryA))
                .isEqualTo(AuditHasher.canonicalPayload(entryB));
        assertThat(AuditHasher.computeHash("prev123", entryA))
                .isEqualTo(AuditHasher.computeHash("prev123", entryB))
                .hasSize(64);
    }

    @Test
    void occurredAtTruncatesNanosecondsToMicroseconds() {
        Instant withNanos = Instant.parse("2026-11-13T06:49:42.123456789Z");
        Instant withMicros = Instant.parse("2026-11-13T06:49:42.123456Z");

        AuditLogEntry entryNanos = sampleEntry(withNanos, null);
        AuditLogEntry entryMicros = sampleEntry(withMicros, null);

        assertThat(AuditHasher.computeHash(null, entryNanos))
                .isEqualTo(AuditHasher.computeHash(null, entryMicros));
    }

    @Test
    void hashChangesWhenPrevHashOrContentChanges() {
        Instant now = Instant.parse("2026-11-13T06:49:42.123456Z");
        AuditLogEntry entry = sampleEntry(now, null);

        String genesisHash = AuditHasher.computeHash(null, entry);
        String chainedHash = AuditHasher.computeHash("0".repeat(64), entry);
        AuditLogEntry modified = new AuditLogEntry(
                null, now, 1L, "admin", 1L, null, "127.0.0.1",
                "OTHER_ACTION", "product", "10", null, null, "motivo", null, "trace-1", null, null);

        assertThat(genesisHash).isNotEqualTo(chainedHash);
        assertThat(AuditHasher.computeHash(null, modified)).isNotEqualTo(genesisHash);
    }

    @Test
    void auditEventRejectsNullOrBlankRequiredFields() {
        assertThatThrownBy(() -> new AuditEvent(null, "product", "1", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditEvent("  ", "product", "1", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent("ACTION", "", "1", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent("ACTION", "product", " ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditEventValidatesMaxFieldLengths() {
        AuditEvent exactMax = new AuditEvent(
                "A".repeat(AuditEvent.MAX_ACTION_LENGTH),
                "E".repeat(AuditEvent.MAX_ENTITY_TYPE_LENGTH),
                "I".repeat(AuditEvent.MAX_ENTITY_ID_LENGTH),
                null, null, null, null);
        assertThat(exactMax.action()).hasSize(AuditEvent.MAX_ACTION_LENGTH);

        assertThatThrownBy(() -> new AuditEvent(
                "A".repeat(AuditEvent.MAX_ACTION_LENGTH + 1), "product", "1", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(
                "ACTION", "E".repeat(AuditEvent.MAX_ENTITY_TYPE_LENGTH + 1), "1", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(
                "ACTION", "product", "I".repeat(AuditEvent.MAX_ENTITY_ID_LENGTH + 1), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AuditLogEntry sampleEntry(Instant occurredAt, JsonNode newValue) {
        return new AuditLogEntry(
                null,
                occurredAt,
                1L,
                "admin",
                1L,
                null,
                "127.0.0.1",
                "PRODUCT_PRICE_CHANGED",
                "product",
                "10",
                null,
                newValue,
                "motivo",
                null,
                "trace-1",
                null,
                null
        );
    }
}
