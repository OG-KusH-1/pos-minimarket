package cl.minimarket.pos.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Levanta el contexto completo, pide {@code /v3/api-docs} y escribe el contrato en
 * {@code frontend/openapi/openapi.json}, que consume {@code npm run api:gen} para generar el
 * cliente Angular (ADR-18, ADR-27). Así el contrato versionado en git se regenera con cada
 * {@code mvn verify} y un cambio de API sin regenerar se ve en el diff.
 *
 * <p><b>Acceso a la ruta:</b> {@code /v3/api-docs} está en las rutas globales del filtro de
 * tienda (no exige {@code X-Store-Id}), pero la seguridad por defecto de Boot exige autenticación
 * mientras no exista la cadena de producción (T19): su {@code BasicAuthenticationEntryPoint}
 * responde 401. Se resuelve solo en esta prueba con el post-procesador {@code user("1")} (id
 * numérico, como espera el filtro de tienda); no se toca la configuración de seguridad. Cuando T19
 * permita la ruta de forma anónima, el usuario seguirá siendo inocuo.
 *
 * <p><b>Salida determinista:</b> se escribe con claves ordenadas, saltos de línea LF y un salto final, sin importar
 * el sistema operativo, para que el diff en git no cambie entre Windows y Linux. La ruta es
 * relativa al directorio de trabajo de failsafe ({@code backend/}).
 */
class OpenApiExportIT extends AbstractIntegrationIT {

    /** Comilla doble, para armar JSON de prueba sin escapes. */
    private static final String Q = "\"";
    private static final Path OUTPUT = Path.of("..", "frontend", "openapi", "openapi.json");

    @Autowired
    private MockMvc mockMvc;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT).build();

    @Test
    void exportsOpenApiContract() throws Exception {
        JsonNode doc = MAPPER.readTree(fetchApiDocs());

        assertThat(doc.path("openapi").asString()).startsWith("3.");
        assertThat(doc.path("info").isObject()).isTrue();
        assertThat(doc.path("info").path("title").asString()).isNotBlank();

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, canonicalJson(doc), StandardCharsets.UTF_8);
        assertThat(OUTPUT).exists();
    }

    @Test
    void twoConsecutiveExportsAreByteIdentical() throws Exception {
        String first = canonicalJson(MAPPER.readTree(fetchApiDocs()));
        String second = canonicalJson(MAPPER.readTree(fetchApiDocs()));
        assertThat(second.getBytes(StandardCharsets.UTF_8))
                .isEqualTo(first.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void canonicalJsonSortsObjectKeysRecursivelyButKeepsArrayOrder() throws Exception {
        JsonNode unsorted = MAPPER.readTree("{" + Q + "b" + Q + ":{" + Q + "z" + Q + ":1," + Q + "a" + Q + ":2},"
                + Q + "a" + Q + ":[{" + Q + "y" + Q + ":1," + Q + "x" + Q + ":2},3,1]}");
        String json = canonicalJson(unsorted);
        assertThat(json.indexOf(Q + "a" + Q + " :")).isLessThan(json.indexOf(Q + "b" + Q + " :"));
        assertThat(json.indexOf(Q + "x" + Q)).isLessThan(json.indexOf(Q + "y" + Q));
        assertThat(json.indexOf(Q + "a" + Q + " : 2")).isLessThan(json.indexOf(Q + "z" + Q));
        assertThat(json.indexOf("3")).isLessThan(json.lastIndexOf("1"));
    }

    private String fetchApiDocs() throws Exception {
        return mockMvc.perform(get("/v3/api-docs").with(user("1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Serializa el árbol de forma canónica: propiedades de objeto ordenadas alfabéticamente en todos
     * los niveles (springdoc no garantiza el orden de {@code paths} ni de {@code components.schemas}
     * entre ejecuciones), el orden de los arreglos intacto (es semántico), LF y salto final.
     */
    private static String canonicalJson(JsonNode doc) {
        return MAPPER.writeValueAsString(sortKeys(doc)).replace("\r\n", "\n") + "\n";
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            node.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sorted.set(e.getKey(), sortKeys(e.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            node.forEach(item -> copy.add(sortKeys(item)));
            return copy;
        }
        return node;
    }
}
