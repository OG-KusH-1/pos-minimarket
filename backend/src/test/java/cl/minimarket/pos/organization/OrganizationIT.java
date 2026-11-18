package cl.minimarket.pos.organization;

import cl.minimarket.pos.shared.context.support.ConfigurableStoreAccessValidator;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del módulo {@code organization}: empresa, locales, terminales,
 * parámetros con precedencia {@code STORE} sobre {@code GLOBAL}, y APIs públicas.
 */
@Import(OrganizationIT.StoreValidatorConfig.class)
class OrganizationIT extends AbstractIntegrationIT {

    @TestConfiguration
    static class StoreValidatorConfig {
        @Bean
        ConfigurableStoreAccessValidator storeAccessValidator() {
            return new ConfigurableStoreAccessValidator();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    StoreApi storeApi;

    @Autowired
    TerminalApi terminalApi;

    @Autowired
    SettingsApi settingsApi;

    @Autowired
    ConfigurableStoreAccessValidator validator;

    private long baseStoreId;
    private Map<String, Object> originalCompany;

    @BeforeEach
    void setup() {
        validator.reset();
        baseStoreId = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        originalCompany = ownerJdbc()
                .sql("SELECT id, rut, legal_name, trade_name, business_activity, address, commune, city, phone, email, logo_file_id, timezone, currency FROM company ORDER BY id LIMIT 1")
                .query()
                .singleRow();
    }

    @AfterEach
    void restoreCompany() {
        if (originalCompany != null) {
            ownerJdbc().sql("""
                    UPDATE company
                    SET rut = :rut,
                        legal_name = :legal_name,
                        trade_name = :trade_name,
                        business_activity = :business_activity,
                        address = :address,
                        commune = :commune,
                        city = :city,
                        phone = :phone,
                        email = :email,
                        logo_file_id = :logo_file_id,
                        timezone = :timezone,
                        currency = :currency
                    WHERE id = :id
                    """)
                    .params(originalCompany)
                    .update();
        }
    }

    @Test
    void companyUpdateValidatesRutAndPersistsChanges() throws Exception {
        String invalidRutBody = """
                {
                  "rut": "11111111-9",
                  "legalName": "Minimarket Prueba SpA",
                  "tradeName": "Minimarket Prueba",
                  "businessActivity": "Venta al por menor",
                  "address": "Av. Providencia 100",
                  "commune": "Providencia",
                  "city": "Santiago",
                  "phone": "+56912345678",
                  "email": "contacto@minimarket.cl",
                  "timezone": "America/Santiago"
                }
                """;

        mvc.perform(put("/api/v1/company")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRutBody)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_COMPANY_UPDATE))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RUT_INVALID"));

        String validBody = """
                {
                  "rut": "76.123.456-0",
                  "legalName": "Minimarket Central SpA",
                  "tradeName": "Minimarket Central",
                  "businessActivity": "Comercio al por menor",
                  "address": "Av. Libertador 500",
                  "commune": "Santiago",
                  "city": "Santiago",
                  "phone": "+56223334444",
                  "email": "admin@central.cl",
                  "timezone": "America/Santiago"
                }
                """;

        mvc.perform(put("/api/v1/company")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_COMPANY_UPDATE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rut").value("76123456-0"))
                .andExpect(jsonPath("$.tradeName").value("Minimarket Central"));

        mvc.perform(get("/api/v1/company").with(user("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rut").value("76123456-0"));
    }

    @Test
    void storesCrudDeactivateActivateAndAccessValidation() throws Exception {
        String code = TestData.uniqueStoreCode().toUpperCase();
        String createBody = """
                {
                  "code": "%s",
                  "name": "Sucursal Norte",
                  "address": "Calle Norte 123",
                  "commune": "Independencia",
                  "city": "Santiago",
                  "phone": "+56999998888",
                  "siiBranchCode": "101"
                }
                """.formatted(code);

        String createdJson = mvc.perform(post("/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CONFIG_STORE_MANAGE),
                                new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();

        long createdId = jsonMapper.readTree(createdJson).get("id").asLong();
        assertThat(storeApi.activeStoreIds()).contains(createdId);

        // Con el StoreUsageGuard por defecto, desactivar y reactivar funciona
        mvc.perform(post("/api/v1/stores/{id}/deactivate", createdId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_STORE_MANAGE))))
                .andExpect(status().isNoContent());
        assertThat(storeApi.get(createdId).active()).isFalse();
        assertThat(storeApi.activeStoreIds()).doesNotContain(createdId);

        mvc.perform(post("/api/v1/stores/{id}/activate", createdId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_STORE_MANAGE))))
                .andExpect(status().isNoContent());
        assertThat(storeApi.get(createdId).active()).isTrue();

        // Sin all-access, GET /stores/{id} valida acceso contra StoreAccessValidator
        validator.denyStore(createdId);
        mvc.perform(get("/api/v1/stores/{id}", createdId).with(user("1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));
    }

    @Test
    void terminalsCrudAndTerminalApiValidation() throws Exception {
        String termBody = """
                {
                  "code": "CAJA-01",
                  "name": "Caja Principal"
                }
                """;

        String createdJson = mvc.perform(post("/api/v1/stores/{storeId}/terminals", baseStoreId)
                        .header("X-Store-Id", String.valueOf(baseStoreId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(termBody)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_TERMINAL_MANAGE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CAJA-01"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();

        long terminalId = jsonMapper.readTree(createdJson).get("id").asLong();
        assertThat(terminalApi.requireActive(terminalId, baseStoreId).code()).isEqualTo("CAJA-01");

        // GET /stores/{storeId}/terminals es global y valida acceso al local
        mvc.perform(get("/api/v1/stores/{storeId}/terminals", baseStoreId).with(user("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("CAJA-01"));

        validator.denyStore(baseStoreId);
        mvc.perform(get("/api/v1/stores/{storeId}/terminals", baseStoreId).with(user("1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));
        validator.reset();

        // Desactivar terminal con TerminalUsageGuard por defecto
        mvc.perform(post("/api/v1/terminals/{id}/deactivate", terminalId)
                        .header("X-Store-Id", String.valueOf(baseStoreId))
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_TERMINAL_MANAGE))))
                .andExpect(status().isNoContent());

        assertThatThrownBy(() -> terminalApi.requireActive(terminalId, baseStoreId))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TERMINAL_INVALID);
    }

    @Test
    void settingsPrecedenceStoreOverGlobalAndValidation() throws Exception {
        long otherStoreId = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());

        // Valor fuera de rango -> 400 VALIDATION_ERROR
        mvc.perform(put("/api/v1/settings/pos.return.max_days")
                        .header("X-Store-Id", String.valueOf(baseStoreId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"STORE\",\"value\":999}")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_SETTINGS_UPDATE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Clave solo GLOBAL con scope STORE -> 400 VALIDATION_ERROR
        mvc.perform(put("/api/v1/settings/security.password.min_length")
                        .header("X-Store-Id", String.valueOf(baseStoreId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"STORE\",\"value\":12}")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_SETTINGS_UPDATE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Gerente (sin security:store:all-access) intentando cambiar GLOBAL -> 403 ACCESS_DENIED
        mvc.perform(put("/api/v1/settings/pos.return.max_days")
                        .header("X-Store-Id", String.valueOf(baseStoreId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"GLOBAL\",\"value\":45}")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_SETTINGS_UPDATE))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        int globalMaxDays = settingsApi.get("pos.return.max_days", null, Integer.class);
        int overrideDays = globalMaxDays == 15 ? 16 : 15;

        // Override STORE en baseStoreId
        mvc.perform(put("/api/v1/settings/pos.return.max_days")
                        .header("X-Store-Id", String.valueOf(baseStoreId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"STORE\",\"value\":" + overrideDays + "}")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_SETTINGS_UPDATE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("STORE"))
                .andExpect(jsonPath("$.storeId").value(baseStoreId))
                .andExpect(jsonPath("$.value").value(overrideDays));

        // SettingsApi respeta precedencia: baseStoreId=overrideDays, otherStoreId=globalMaxDays
        assertThat(settingsApi.get("pos.return.max_days", baseStoreId, Integer.class)).isEqualTo(overrideDays);
        assertThat(settingsApi.get("pos.return.max_days", otherStoreId, Integer.class)).isEqualTo(globalMaxDays);

        // /settings/effective refleja el override y excluye security.*
        String effectiveJson = mvc.perform(get("/api/v1/settings/effective")
                        .header("X-Store-Id", String.valueOf(baseStoreId))
                        .with(user("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values['pos.return.max_days']").value(overrideDays))
                .andReturn().getResponse().getContentAsString();

        JsonNode values = jsonMapper.readTree(effectiveJson).get("values");
        assertThat(values.has("security.password.min_length")).isFalse();
    }

    @Test
    void auditLogRecordsOrganizationChanges() throws Exception {
        long companyAuditBefore = countAuditRows("COMPANY_UPDATED");
        String companyBody = """
                {
                  "rut": "76.123.456-0",
                  "legalName": "Minimarket Audit SpA",
                  "tradeName": "Minimarket Audit",
                  "businessActivity": "Comercio al por menor",
                  "address": "Av. Audit 123",
                  "commune": "Santiago",
                  "city": "Santiago",
                  "phone": "+56223334444",
                  "email": "audit@central.cl",
                  "timezone": "America/Santiago"
                }
                """;
        mvc.perform(put("/api/v1/company")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyBody)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_COMPANY_UPDATE))))
                .andExpect(status().isOk());
        assertThat(countAuditRows("COMPANY_UPDATED")).isEqualTo(companyAuditBefore + 1);

        String storeCode = TestData.uniqueStoreCode().toUpperCase();
        String storeBody = """
                {
                  "code": "%s",
                  "name": "Sucursal Audit",
                  "address": "Calle Audit 45",
                  "commune": "Providencia",
                  "city": "Santiago",
                  "phone": "+56911112222",
                  "siiBranchCode": "202"
                }
                """.formatted(storeCode);
        String createdStoreJson = mvc.perform(post("/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeBody)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CONFIG_STORE_MANAGE),
                                new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String storeId = jsonMapper.readTree(createdStoreJson).get("id").asString();

        Integer storeAuditCount = appJdbc()
                .sql("SELECT COUNT(*) FROM audit_log WHERE action = 'STORE_CHANGED' AND entity_type = 'store' AND entity_id = :entityId AND reason = 'CREATED'")
                .param("entityId", storeId)
                .query(Integer.class)
                .single();
        assertThat(storeAuditCount).isEqualTo(1);

        long settingAuditBefore = countAuditRows("SETTING_CHANGED");
        mvc.perform(put("/api/v1/settings/pos.return.max_days")
                        .header("X-Store-Id", String.valueOf(baseStoreId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"STORE\",\"value\":20}")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_SETTINGS_UPDATE))))
                .andExpect(status().isOk());
        assertThat(countAuditRows("SETTING_CHANGED")).isEqualTo(settingAuditBefore + 1);
    }

    private long countAuditRows(String action) {
        return appJdbc()
                .sql("SELECT COUNT(*) FROM audit_log WHERE action = :action")
                .param("action", action)
                .query(Long.class)
                .single();
    }
}
