package cl.minimarket.pos.replenishment.web;

import cl.minimarket.pos.shared.context.support.ConfigurableStoreAccessValidator;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de los endpoints de parámetros de reabastecimiento
 * ({@code API.md} F.2): upsert por alcance con UQ, listado con filtro por alcance, borrado y la
 * regla de que el parámetro GLOBAL (default del sistema) no se puede borrar (PI de T80).
 */
@Import(ReplenishmentParamsIT.StoreValidatorConfig.class)
class ReplenishmentParamsIT extends AbstractIntegrationIT {

    @TestConfiguration
    static class StoreValidatorConfig {
        @Bean
        ConfigurableStoreAccessValidator storeAccessValidator() {
            return new ConfigurableStoreAccessValidator();
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ConfigurableStoreAccessValidator validator;

    private long storeId;

    @BeforeEach
    void setUp() {
        validator.reset();
        // Solo este módulo escribe en replenishment_params: limpiar deja determinista el estado
        // (la ventana máxima de las corridas depende de todas las filas).
        ownerJdbc().sql("DELETE FROM replenishment_params").update();
        storeId = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
    }

    private static String params(String scope, Long storeId, Long categoryId, Long productId, String extras) {
        StringBuilder body = new StringBuilder("{\"scope\":\"" + scope + "\"");
        if (storeId != null) {
            body.append(",\"storeId\":").append(storeId);
        }
        if (categoryId != null) {
            body.append(",\"categoryId\":").append(categoryId);
        }
        if (productId != null) {
            body.append(",\"productId\":").append(productId);
        }
        if (extras != null) {
            body.append(",").append(extras);
        }
        return body.append("}").toString();
    }

    /** PUT y GET: alta de la fila GLOBAL y lectura con filtro por alcance. */
    @Test
    void upsertsAndListsParamsByScope() throws Exception {
        MvcResult created = mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("GLOBAL", null, null, null,
                                "\"windowDays\":21,\"reviewPeriodDays\":5,\"enabled\":true,"
                                        + "\"serviceLevelZ\":1.8,\"safetyMinDays\":1.5,"
                                        + "\"minHistoryDays\":4,\"outlierK\":2.5,\"shelfLifeFactor\":0.8")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.scope").value("GLOBAL"))
                .andExpect(jsonPath("$.storeId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.windowDays").value(21))
                .andExpect(jsonPath("$.reviewPeriodDays").value(5))
                .andExpect(jsonPath("$.serviceLevelZ").value(1.8))
                .andReturn();

        mvc.perform(get("/api/v1/replenishment/params?scope=GLOBAL")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_RUN_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(extractId(created)))
                .andExpect(jsonPath("$[0].windowDays").value(21))
                .andExpect(jsonPath("$[0].outlierK").value(2.5))
                .andExpect(jsonPath("$[0].shelfLifeFactor").value(0.8))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    /** El PUT es upsert por alcance: repetirlo reemplaza la fila en lugar de duplicarla. */
    @Test
    void repeatedUpsertReplacesInsteadOfDuplicating() throws Exception {
        mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("STORE", storeId, null, null, "\"reviewPeriodDays\":2,\"windowDays\":30")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("STORE", storeId, null, null, "\"reviewPeriodDays\":4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewPeriodDays").value(4))
                .andExpect(jsonPath("$.windowDays").value(org.hamcrest.Matchers.nullValue()));

        long rows = jdbc.sql("SELECT COUNT(*) FROM replenishment_params WHERE scope = 'STORE' "
                        + "AND store_id = :storeId")
                .param("storeId", storeId)
                .query(Long.class)
                .single();
        assertThat(rows).isEqualTo(1);
    }

    /** La coherencia alcance ↔ claves (matriz del CHECK de B.11) se valida con VALIDATION_ERROR. */
    @Test
    void rejectsIncoherentScopeWithValidationError() throws Exception {
        mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("STORE", null, null, null, "\"windowDays\":21")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("storeId"));

        mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("GLOBAL", storeId, null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("PRODUCT", null, 55L, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** Fuera de rango según F.3 → VALIDATION_ERROR; referencia inexistente → NOT_FOUND. */
    @Test
    void rejectsOutOfRangeValuesAndMissingReferences() throws Exception {
        mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("STORE", storeId, null, null, "\"windowDays\":3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("STORE", 9_999_999L, null, null, "\"windowDays\":21")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** PI: la fila GLOBAL (default del sistema) no se puede borrar; las demás sí. */
    @Test
    void globalParamCannotBeDeletedButScopedOnesCan() throws Exception {
        MvcResult global = mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("GLOBAL", null, null, null, "\"windowDays\":21")))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult scoped = mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("STORE", storeId, null, null, "\"reviewPeriodDays\":2")))
                .andExpect(status().isOk())
                .andReturn();

        long globalId = extractId(global);
        long scopedId = extractId(scoped);

        mvc.perform(delete("/api/v1/replenishment/params/" + globalId)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));

        mvc.perform(delete("/api/v1/replenishment/params/" + scopedId)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_RUN_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(globalId));

        // La fila borrada ya no existe: segundo DELETE → 404.
        mvc.perform(delete("/api/v1/replenishment/params/" + scopedId)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE))))
                .andExpect(status().isNotFound());
    }

    /** Sin el permiso de gestión, el PUT responde 403 ACCESS_DENIED. */
    @Test
    void putWithoutManagePermissionIsForbidden() throws Exception {
        mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_RUN_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(params("GLOBAL", null, null, null, "\"windowDays\":21")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /** Extrae el id numérico del cuerpo de una respuesta. */
    private static long extractId(MvcResult result) throws java.io.UnsupportedEncodingException {
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
