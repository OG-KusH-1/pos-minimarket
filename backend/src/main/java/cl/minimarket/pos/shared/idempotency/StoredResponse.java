package cl.minimarket.pos.shared.idempotency;

import java.util.Map;

/**
 * Respuesta HTTP guardada de una request idempotente completada.
 *
 * @param status código HTTP original (siempre 2xx)
 * @param body cuerpo JSON como texto, o {@code null} si la respuesta original no tuvo cuerpo
 * @param headers cabeceras que se reponen en el replay (hoy solo {@code Location}); nunca nulo
 */
public record StoredResponse(int status, String body, Map<String, String> headers) {

    /** Copia defensiva de las cabeceras. */
    public StoredResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
