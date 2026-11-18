package cl.minimarket.pos.security.override;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Utilidad sin estado para serializar un contexto de override en JSON canónico (claves de objeto
 * ordenadas alfabéticamente de forma recursiva, números normalizados y sin espacios) y calcular su
 * hash SHA-256 hexadecimal de 64 caracteres ({@code ROLES_PERMISOS.md} D.4, {@code API.md} F.3).
 */
public final class CanonicalJsonHasher {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CanonicalJsonHasher() {
    }

    /**
     * Convierte un objeto arbitrario o {@link JsonNode} a su representación JSON canónica.
     */
    public static String toCanonicalJson(Object value) {
        if (value == null) {
            return "null";
        }
        JsonNode root = value instanceof JsonNode node ? node : MAPPER.valueToTree(value);
        if (root == null || root.isNull()) {
            return "null";
        }
        JsonNode normalized = canonicalize(root);
        return MAPPER.writeValueAsString(normalized);
    }

    /**
     * Calcula el SHA-256 hexadecimal en minúsculas del JSON canónico de {@code value}.
     */
    public static String hash(Object value) {
        return sha256Hex(toCanonicalJson(value));
    }

    /**
     * Calcula el SHA-256 hexadecimal en minúsculas de una cadena UTF-8.
     */
    public static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Algoritmo SHA-256 no disponible", ex);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.propertyNames().forEach(keys::add);
            Collections.sort(keys);
            for (String key : keys) {
                sorted.set(key, canonicalize(node.get(key)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            for (JsonNode element : node) {
                array.add(canonicalize(element));
            }
            return array;
        }
        if (node.isNumber()) {
            BigDecimal bd = node.decimalValue().stripTrailingZeros();
            if (bd.compareTo(BigDecimal.ZERO) == 0) {
                bd = BigDecimal.ZERO;
            }
            if (bd.scale() <= 0) {
                return MAPPER.getNodeFactory().numberNode(bd.toBigIntegerExact());
            }
            return DecimalNode.valueOf(bd);
        }
        return node;
    }
}
