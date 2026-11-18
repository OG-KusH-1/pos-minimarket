package cl.minimarket.pos.audit.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Utilidad sin estado para construir el payload canónico y calcular el hash SHA-256 encadenado
 * de {@code audit_log} (ADR-18, RN-25).
 *
 * <p><b>Formato del hash:</b> {@code sha256((prev_hash o "") + payload_canonico)} en hexadecimal
 * minúscula (64 caracteres).
 *
 * <p><b>Payload canónico:</b> objeto JSON compacto (sin espacios) con sus 14 claves ordenadas
 * alfabéticamente: {@code action}, {@code authorizationId}, {@code entityId}, {@code entityType},
 * {@code ip}, {@code newValue}, {@code occurredAt}, {@code oldValue}, {@code reason},
 * {@code storeId}, {@code terminalId}, {@code traceId}, {@code userId}, {@code username}.
 *
 * <p>Los nodos JSON anidados ({@code oldValue} y {@code newValue}) también ordenan las claves de
 * sus objetos alfabéticamente de forma recursiva, de modo que el reordenamiento interno de
 * {@code jsonb} en PostgreSQL nunca invalide la verificación. El instante {@code occurredAt} se
 * trunca a microsegundos (precisión de {@code timestamptz}) y se formatea en ISO-8601 UTC.
 */
public final class AuditHasher {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AuditHasher() {
    }

    /**
     * Convierte un objeto arbitrario en {@link JsonNode} usando Jackson 3.
     *
     * @param value valor a convertir; puede ser {@code null}
     * @return nodo JSON resultante, o {@code null} si {@code value} es nulo o {@code NullNode}
     */
    public static JsonNode toJsonNode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            return node.isNull() ? null : node;
        }
        JsonNode tree = MAPPER.valueToTree(value);
        return tree == null || tree.isNull() ? null : tree;
    }

    /**
     * Serializa un {@link JsonNode} a texto JSON para persistirlo en una columna {@code jsonb}.
     *
     * @param node nodo JSON; puede ser {@code null}
     * @return texto JSON, o {@code null} si el nodo es nulo
     */
    public static String toJsonString(JsonNode node) {
        return node == null ? null : MAPPER.writeValueAsString(node);
    }

    /**
     * Parsea una cadena JSON leída desde PostgreSQL ({@code jsonb::text}).
     *
     * @param rawJson texto JSON; puede ser {@code null}
     * @return nodo JSON, o {@code null} si la entrada es nula o en blanco
     */
    public static JsonNode parseJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        JsonNode tree = MAPPER.readTree(rawJson);
        return tree == null || tree.isNull() ? null : tree;
    }

    /**
     * Calcula el hash SHA-256 encadenado de un registro de auditoría.
     *
     * @param prevHash hash del registro anterior, o {@code null} si es el primero de la cadena
     * @param entry datos del registro (los campos {@code id} y {@code hash} no participan)
     * @return hash SHA-256 en hexadecimal minúscula (64 caracteres)
     */
    public static String computeHash(String prevHash, AuditLogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        String payload = canonicalPayload(entry);
        MessageDigest digest = newDigest();
        if (prevHash != null) {
            digest.update(prevHash.getBytes(StandardCharsets.UTF_8));
        }
        digest.update(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Construye el JSON canónico de los campos de contenido de un registro de auditoría.
     *
     * @param entry registro a serializar
     * @return JSON compacto con claves ordenadas alfabéticamente
     */
    public static String canonicalPayload(AuditLogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        appendStringField(out, "action", entry.action(), true);
        appendNumberField(out, "authorizationId", entry.authorizationId(), false);
        appendStringField(out, "entityId", entry.entityId(), false);
        appendStringField(out, "entityType", entry.entityType(), false);
        appendStringField(out, "ip", entry.ip(), false);
        appendJsonField(out, "newValue", entry.newValue(), false);
        appendStringField(out, "occurredAt", formatInstant(entry.occurredAt()), false);
        appendJsonField(out, "oldValue", entry.oldValue(), false);
        appendStringField(out, "reason", entry.reason(), false);
        appendNumberField(out, "storeId", entry.storeId(), false);
        appendNumberField(out, "terminalId", entry.terminalId(), false);
        appendStringField(out, "traceId", entry.traceId(), false);
        appendNumberField(out, "userId", entry.userId(), false);
        appendStringField(out, "username", entry.username(), false);
        out.append('}');
        return out.toString();
    }

    private static String formatInstant(Instant instant) {
        Objects.requireNonNull(instant, "occurredAt");
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MICROS));
    }

    private static void appendStringField(StringBuilder out, String key, String value, boolean first) {
        appendKey(out, key, first);
        out.append(value == null ? "null" : MAPPER.writeValueAsString(value));
    }

    private static void appendNumberField(StringBuilder out, String key, Long value, boolean first) {
        appendKey(out, key, first);
        out.append(value == null ? "null" : value);
    }

    private static void appendJsonField(StringBuilder out, String key, JsonNode value, boolean first) {
        appendKey(out, key, first);
        if (value == null || value.isNull()) {
            out.append("null");
        } else {
            writeCanonicalJson(value, out);
        }
    }

    private static void appendKey(StringBuilder out, String key, boolean first) {
        if (!first) {
            out.append(',');
        }
        out.append(MAPPER.writeValueAsString(key)).append(':');
    }

    private static void writeCanonicalJson(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            entries.sort(Map.Entry.comparingByKey());
            out.append('{');
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(MAPPER.writeValueAsString(entries.get(i).getKey())).append(':');
                writeCanonicalJson(entries.get(i).getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeCanonicalJson(node.get(i), out);
            }
            out.append(']');
        } else {
            out.append(MAPPER.writeValueAsString(node));
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
