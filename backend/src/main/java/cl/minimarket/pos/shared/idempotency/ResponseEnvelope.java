package cl.minimarket.pos.shared.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Envoltura JSON con la que se guarda la respuesta en {@code idempotency_keys.response_body}
 * (columna {@code jsonb}), de modo que se pueda reponer algo más que el cuerpo (cabeceras como
 * {@code Location}) sin cambiar el esquema.
 *
 * <p>Formato (versión 1):
 * <pre>{"envelopeVersion":1,"headers":{"Location":"/x/1"},"body":{...}}</pre>
 * {@code body} es el cuerpo original (JSON) o {@code null}; {@code headers} puede estar vacío.
 *
 * <p>Compatibilidad: si el valor guardado no es un objeto con {@code envelopeVersion} (filas
 * anteriores a la envoltura o escritas por otra vía) se interpreta como el cuerpo tal cual y sin
 * cabeceras. Si cambia el formato, subir la versión y mantener la lectura de la anterior.
 */
final class ResponseEnvelope {

    static final String VERSION_FIELD = "envelopeVersion";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ResponseEnvelope() {
    }

    /**
     * Construye el JSON a guardar.
     *
     * @param body cuerpo JSON como texto, o {@code null}
     * @param headers cabeceras a reponer en el replay (no nulo, puede estar vacío)
     * @return texto JSON de la envoltura
     * @throws IllegalArgumentException si {@code body} no es JSON válido
     */
    static String wrap(String body, Map<String, String> headers) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put(VERSION_FIELD, 1);
        ObjectNode headerNode = envelope.putObject("headers");
        headers.forEach(headerNode::put);
        try {
            if (body == null) {
                envelope.putNull("body");
            } else {
                envelope.set("body", MAPPER.readTree(body));
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("El cuerpo de la respuesta no es JSON válido", e);
        }
        return MAPPER.writeValueAsString(envelope);
    }

    /**
     * Interpreta lo guardado.
     *
     * @param status código HTTP guardado
     * @param stored contenido de {@code response_body}; puede ser {@code null}
     * @return respuesta reconstruida
     */
    static StoredResponse unwrap(int status, String stored) {
        if (stored == null) {
            return new StoredResponse(status, null, Map.of());
        }
        JsonNode node = MAPPER.readTree(stored);
        if (!node.isObject() || !node.has(VERSION_FIELD)) {
            return new StoredResponse(status, stored, Map.of());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headerNode = node.get("headers");
        if (headerNode != null && headerNode.isObject()) {
            headerNode.properties().forEach(entry -> headers.put(entry.getKey(), entry.getValue().asString()));
        }
        JsonNode body = node.get("body");
        String bodyText = body == null || body.isNull() ? null : MAPPER.writeValueAsString(body);
        return new StoredResponse(status, bodyText, Map.copyOf(headers));
    }
}
