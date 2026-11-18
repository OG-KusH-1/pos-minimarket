package cl.minimarket.pos.shared.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Calcula la huella (SHA-256, 64 caracteres hexadecimales) de un request para detectar si dos
 * usos de la misma {@code Idempotency-Key} corresponden a la misma solicitud (ADR-14).
 *
 * <p>La huella cubre: método, ruta, query, usuario y cuerpo. El usuario se incluye aunque la clave
 * ya es por usuario, como defensa en profundidad.
 *
 * <p><b>Cuerpo canónico:</b> si el cuerpo es JSON válido se normaliza (propiedades de objeto
 * ordenadas alfabéticamente, sin espacios) para que dos serializaciones equivalentes del mismo
 * contenido den la misma huella: un cliente que reintenta tras reconstruir el JSON con otro orden
 * de campos no debe recibir un falso conflicto. El orden de los arreglos se respeta porque es
 * semántico (p. ej. líneas de venta). Si no es JSON (o está vacío) se usan los bytes tal cual.
 *
 * <p>Es una clase de utilidad sin estado y segura entre hilos.
 */
public final class RequestFingerprint {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final char SEPARATOR = '\n';

    private RequestFingerprint() {
    }

    /**
     * Calcula la huella del request.
     *
     * @param method método HTTP (p. ej. {@code POST})
     * @param path ruta sin query
     * @param query query string sin el {@code ?}; {@code null} o vacío si no hay
     * @param userId id del usuario autenticado
     * @param body cuerpo crudo; {@code null} equivale a vacío
     * @return SHA-256 en hexadecimal minúscula (64 caracteres)
     * @throws NullPointerException si {@code method}, {@code path} o {@code userId} son nulos
     */
    public static String of(String method, String path, String query, Long userId, byte[] body) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(userId, "userId");
        MessageDigest digest = newDigest();
        update(digest, method.toUpperCase(Locale.ROOT) + SEPARATOR + path + SEPARATOR
                + (query == null ? "" : query) + SEPARATOR + userId + SEPARATOR);
        digest.update(canonicalBody(body));
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Cuerpo en forma canónica (ver la descripción de la clase). */
    static byte[] canonicalBody(byte[] body) {
        if (body == null || body.length == 0) {
            return new byte[0];
        }
        try {
            JsonNode tree = MAPPER.readTree(body);
            if (tree == null || tree.isMissingNode()) {
                return body;
            }
            StringBuilder out = new StringBuilder(body.length);
            write(tree, out);
            return out.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException e) {
            return body;
        }
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            entries.sort(Map.Entry.comparingByKey());
            out.append('{');
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(MAPPER.writeValueAsString(entries.get(i).getKey())).append(':');
                write(entries.get(i).getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else {
            out.append(MAPPER.writeValueAsString(node));
        }
    }

    private static void update(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 es obligatorio en toda JVM (JCA); no puede ocurrir.
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
