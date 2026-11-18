package cl.minimarket.pos.purchasing.supplier.web;

import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del CRUD de proveedores ({@code /api/v1/suppliers}, {@code T57},
 * {@code HU-PRV-01}).
 */
class SupplierIT extends AbstractIntegrationIT {

    private static final AtomicLong RUT_SEQ = new AtomicLong(
            71_000_000L + (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 7_000_000L));

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void crudValidationDuplicateRutAndPermissions() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String validRut = nextValidRut();
        String formattedRut = formatWithDots(validRut);
        String invalidRut = corruptCheckDigit(validRut);

        // Sin autenticación -> 401 UNAUTHENTICATED
        mvc.perform(get("/api/v1/suppliers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        // Sin permiso purchasing:supplier:read -> 403 ACCESS_DENIED
        mvc.perform(get("/api/v1/suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Crear sin permiso purchasing:supplier:manage -> 403 ACCESS_DENIED
        mvc.perform(post("/api/v1/suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson(validRut, "Proveedor Sin Permiso " + suffix, "Fantasia",
                                "CREDIT", 30, 2, List.of(1, 4), null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // RUT con dígito verificador inválido -> 422 RUT_INVALID
        mvc.perform(post("/api/v1/suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson(invalidRut, "Proveedor Invalido " + suffix, null,
                                "CASH_ON_DELIVERY", 0, 1, List.of(1), null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RUT_INVALID"));

        // Días de despacho inválidos (repetidos / fuera de 1..7) -> 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson(validRut, "Proveedor Dias " + suffix, null,
                                "CASH_ON_DELIVERY", 0, 1, List.of(2, 2), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Creación válida con RUT con puntos y días desordenados [4, 1] -> endpoint global [G] sin X-Store-Id
        MvcResult createdRes = mvc.perform(post("/api/v1/suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson(formattedRut, "Distribuidora León " + suffix, "León Sur",
                                "CREDIT", 30, 3, List.of(4, 1), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rut").value(validRut))
                .andExpect(jsonPath("$.legalName").value("Distribuidora León " + suffix))
                .andExpect(jsonPath("$.tradeName").value("León Sur"))
                .andExpect(jsonPath("$.paymentTermsType").value("CREDIT"))
                .andExpect(jsonPath("$.paymentTermsDays").value(30))
                .andExpect(jsonPath("$.leadTimeDays").value(3))
                .andExpect(jsonPath("$.deliveryWeekdays[0]").value(1))
                .andExpect(jsonPath("$.deliveryWeekdays[1]").value(4))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        JsonNode createdJson = jsonMapper.readTree(createdRes.getResponse().getContentAsString());
        long supplierId = createdJson.get("id").asLong();
        long version0 = createdJson.get("version").asLong();

        // RUT duplicado (aunque venga sin puntos) -> 409 DUPLICATED_VALUE
        mvc.perform(post("/api/v1/suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson(validRut, "Otro Proveedor " + suffix, null,
                                "CASH_ON_DELIVERY", 0, 1, List.of(), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"));

        // GET /suppliers/{id}
        mvc.perform(get("/api/v1/suppliers/{id}", supplierId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(supplierId))
                .andExpect(jsonPath("$.rut").value(validRut));

        // GET /suppliers/{id} inexistente -> 404 NOT_FOUND
        mvc.perform(get("/api/v1/suppliers/{id}", 999_999_999L)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // PUT /suppliers/{id} con versión obsoleta -> 409 CONCURRENT_MODIFICATION
        mvc.perform(put("/api/v1/suppliers/{id}", supplierId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson(validRut, "Editado Obsoleto " + suffix, null,
                                "CREDIT", 45, 5, List.of(2, 5), 99L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        // PUT /suppliers/{id} con versión vigente -> 200 OK e incrementa versión
        mvc.perform(put("/api/v1/suppliers/{id}", supplierId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson(validRut, "Distribuidora León Editada " + suffix, "León Pro",
                                "CREDIT", 45, 5, List.of(2, 5), version0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Distribuidora León Editada " + suffix))
                .andExpect(jsonPath("$.paymentTermsDays").value(45))
                .andExpect(jsonPath("$.leadTimeDays").value(5))
                .andExpect(jsonPath("$.deliveryWeekdays[0]").value(2))
                .andExpect(jsonPath("$.deliveryWeekdays[1]").value(5))
                .andExpect(jsonPath("$.version").value(1));

        // Desactivar y reactivar
        mvc.perform(post("/api/v1/suppliers/{id}/deactivate", supplierId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_MANAGE))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/suppliers/{id}", supplierId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(post("/api/v1/suppliers/{id}/activate", supplierId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_MANAGE))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/suppliers/{id}", supplierId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void listSuppliers_filtersByQueryAndActiveAndSortsCorrectly() throws Exception {
        String tag = "T57S" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String rutA = nextValidRut();
        String rutB = nextValidRut();
        String rutC = nextValidRut();

        long idA = createSupplier(rutA, "Alpha Bebidas " + tag, "Alfa " + tag, 5);
        long idB = createSupplier(rutB, "Beta Lácteos " + tag, "Láctea " + tag, 2);
        long idC = createSupplier(rutC, "Gamma Inactivo " + tag, null, 1);

        mvc.perform(post("/api/v1/suppliers/{id}/deactivate", idC)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_MANAGE))))
                .andExpect(status().isNoContent());

        // Búsqueda sin tildes ("lacteos" encuentra "Beta Lácteos")
        mvc.perform(get("/api/v1/suppliers")
                        .param("q", "lacteos " + tag)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(idB));

        // Búsqueda por RUT con puntos
        mvc.perform(get("/api/v1/suppliers")
                        .param("q", formatWithDots(rutA))
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(idA));

        // Filtro active=true + sort por leadTimeDays,asc (usa HttpServletRequest.getParameterValues("sort"))
        MvcResult activeSorted = mvc.perform(get("/api/v1/suppliers")
                        .param("q", tag)
                        .param("active", "true")
                        .param("sort", "leadTimeDays,asc")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();

        JsonNode content = jsonMapper.readTree(activeSorted.getResponse().getContentAsString()).get("content");
        List<Long> ids = StreamSupport.stream(content.spliterator(), false)
                .map(n -> n.get("id").asLong())
                .toList();
        assertThat(ids).containsExactly(idB, idA);

        // Campo de ordenamiento no permitido -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/suppliers")
                        .param("sort", "notes,asc")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private long createSupplier(String rut, String legalName, String tradeName, int leadTimeDays) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierJson(rut, legalName, tradeName, "CREDIT", 15, leadTimeDays, List.of(1), null)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private String supplierJson(String rut,
                                String legalName,
                                String tradeName,
                                String paymentTermsType,
                                int paymentTermsDays,
                                int leadTimeDays,
                                List<Integer> deliveryWeekdays,
                                Long version) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("rut", rut);
        body.put("legalName", legalName);
        body.put("tradeName", tradeName);
        body.put("paymentTermsType", paymentTermsType);
        body.put("paymentTermsDays", paymentTermsDays);
        body.put("leadTimeDays", leadTimeDays);
        body.put("deliveryWeekdays", deliveryWeekdays);
        if (version != null) {
            body.put("version", version);
        }
        return jsonMapper.writeValueAsString(body);
    }

    private static String nextValidRut() {
        long body = RUT_SEQ.incrementAndGet();
        return body + "-" + computeDv(body);
    }

    private static String corruptCheckDigit(String validRut) {
        char dv = validRut.charAt(validRut.length() - 1);
        char badDv = dv == '0' ? '1' : '0';
        return validRut.substring(0, validRut.length() - 1) + badDv;
    }

    private static String formatWithDots(String normalizedRut) {
        String[] parts = normalizedRut.split("-");
        String digits = parts[0];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append('.');
            }
            sb.append(digits.charAt(i));
        }
        return sb + "-" + parts[1];
    }

    private static char computeDv(long body) {
        int sum = 0;
        int factor = 2;
        for (long rest = body; rest > 0; rest /= 10) {
            sum += (int) (rest % 10) * factor;
            factor = factor == 7 ? 2 : factor + 1;
        }
        int dv = 11 - (sum % 11);
        if (dv == 11) {
            return '0';
        }
        return dv == 10 ? 'K' : (char) ('0' + dv);
    }
}
