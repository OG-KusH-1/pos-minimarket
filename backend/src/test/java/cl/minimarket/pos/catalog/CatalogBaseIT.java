package cl.minimarket.pos.catalog;

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

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de Catalog A: categorías, marcas e impuestos ({@code T23}).
 */
class CatalogBaseIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void categoriesCrudTreeAndTwoLevelRule() throws Exception {
        String suffix = uniqueSuffix();
        String rootName = "Bebidas " + suffix;
        String subName = "Gaseosas " + suffix;
        String subRenamed = "Bebidas Gaseosas " + suffix;

        // Sin permiso de gestión → 403
        mvc.perform(post("/api/v1/categories")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "code": "BEB"}
                                """.formatted(rootName)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Crear categoría raíz
        MvcResult rootResult = mvc.perform(post("/api/v1/categories")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "code": "BEB"}
                                """.formatted(rootName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(rootName))
                .andExpect(jsonPath("$.code").value("BEB"))
                .andExpect(jsonPath("$.parentId").isEmpty())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        long rootId = jsonMapper.readTree(rootResult.getResponse().getContentAsString()).get("id").asLong();

        // Duplicado en mismo nivel → 409 DUPLICATED_VALUE
        mvc.perform(post("/api/v1/categories")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(rootName.toLowerCase(Locale.ROOT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"));

        // Crear subcategoría
        MvcResult subResult = mvc.perform(post("/api/v1/categories")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "code": "GAS", "parentId": %d}
                                """.formatted(subName, rootId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(rootId))
                .andReturn();
        long subId = jsonMapper.readTree(subResult.getResponse().getContentAsString()).get("id").asLong();

        // Tercer nivel → 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/categories")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Sin Azúcar %s", "parentId": %d}
                                """.formatted(suffix, subId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Editar subcategoría
        mvc.perform(put("/api/v1/categories/" + subId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "code": "BGAS", "parentId": %d}
                                """.formatted(subRenamed, rootId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(subRenamed))
                .andExpect(jsonPath("$.code").value("BGAS"));

        // Listar en modo árbol (?tree=true)
        MvcResult treeResult = mvc.perform(get("/api/v1/categories")
                        .param("tree", "true")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tree = jsonMapper.readTree(treeResult.getResponse().getContentAsString());
        JsonNode rootNode = findById(tree, rootId);
        assertThat(rootNode).isNotNull();
        assertThat(findById(rootNode.get("children"), subId)).isNotNull();
        assertThat(findById(rootNode.get("children"), subId).get("name").asString()).isEqualTo(subRenamed);

        // Activar / desactivar
        mvc.perform(post("/api/v1/categories/" + subId + "/deactivate")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE))))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/categories/" + subId + "/activate")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE))))
                .andExpect(status().isNoContent());
    }

    @Test
    void categoryDeactivateWithActiveProductsReturnsEntityInUse() throws Exception {
        String suffix = uniqueSuffix();
        MvcResult catResult = mvc.perform(post("/api/v1/categories")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Lácteos %s"}
                                """.formatted(suffix)))
                .andExpect(status().isCreated())
                .andReturn();
        long catId = jsonMapper.readTree(catResult.getResponse().getContentAsString()).get("id").asLong();

        long productId = ownerJdbc().sql("""
                        INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure, active)
                        VALUES (:sku, :name, :catId, 'UNIT', 'UNIT', true)
                        RETURNING id
                        """)
                .param("sku", "SKU-" + suffix)
                .param("name", "Leche " + suffix)
                .param("catId", catId)
                .query(Long.class)
                .single();

        // Con producto activo → 409 ENTITY_IN_USE
        mvc.perform(post("/api/v1/categories/" + catId + "/deactivate")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));

        // Al desactivar el producto, ya se puede desactivar la categoría
        ownerJdbc().sql("UPDATE products SET active = false WHERE id = :id")
                .param("id", productId)
                .update();

        mvc.perform(post("/api/v1/categories/" + catId + "/deactivate")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE))))
                .andExpect(status().isNoContent());
    }

    @Test
    void brandsCrudFilterAndActivateDeactivate() throws Exception {
        String suffix = uniqueSuffix();
        String brandName = "Soprole " + suffix;
        String brandRenamed = "Soprólé " + suffix;

        // Crear marca
        MvcResult created = mvc.perform(post("/api/v1/brands")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_BRAND_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(brandName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(brandName))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        long brandId = jsonMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // Duplicado insensible a mayúsculas → 409 DUPLICATED_VALUE
        mvc.perform(post("/api/v1/brands")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_BRAND_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(brandName.toUpperCase(Locale.ROOT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"));

        // Actualizar marca
        mvc.perform(put("/api/v1/brands/" + brandId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_BRAND_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(brandRenamed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(brandRenamed));

        // Búsqueda sin tildes (?q=soprole) y paginada
        mvc.perform(get("/api/v1/brands")
                        .param("q", "soprole " + suffix)
                        .param("active", "true")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(brandId));

        // Desactivar y verificar filtro active=false
        mvc.perform(post("/api/v1/brands/" + brandId + "/deactivate")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_BRAND_MANAGE))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/brands")
                        .param("q", suffix)
                        .param("active", "false")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(brandId))
                .andExpect(jsonPath("$.content[0].active").value(false));

        // Reactivar
        mvc.perform(post("/api/v1/brands/" + brandId + "/activate")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_BRAND_MANAGE))))
                .andExpect(status().isNoContent());
    }

    @Test
    void taxesCrudDateValidationAndAuditTaxChanged() throws Exception {
        String code = "TAX_" + uniqueAlphaSuffix();

        // Rango de fechas inválido (validTo <= validFrom) → 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/taxes")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_TAX_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "%s",
                                  "name": "Impuesto Prueba",
                                  "taxType": "ADDITIONAL",
                                  "rate": 10.0,
                                  "validFrom": "2026-01-10",
                                  "validTo": "2026-01-10"
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Crear impuesto válido
        MvcResult created = mvc.perform(post("/api/v1/taxes")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_TAX_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "%s",
                                  "name": "Impuesto Prueba",
                                  "taxType": "ADDITIONAL",
                                  "rate": 12.5,
                                  "siiCode": "99",
                                  "validFrom": "2026-01-01"
                                }
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.rate").value(12.5))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        long taxId = jsonMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // Actualizar tasa y verificar auditoría TAX_CHANGED
        mvc.perform(put("/api/v1/taxes/" + taxId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_TAX_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "%s",
                                  "name": "Impuesto Prueba Actualizado",
                                  "taxType": "ADDITIONAL",
                                  "rate": 15.0,
                                  "siiCode": "99",
                                  "validFrom": "2026-01-01",
                                  "validTo": "2027-12-31"
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(15.0));

        // Listar impuestos incluye el recién creado
        MvcResult listResult = mvc.perform(get("/api/v1/taxes")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = jsonMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(findById(list, taxId)).isNotNull();

        // Verificar registros en audit_log (2 eventos TAX_CHANGED: creación y actualización)
        long auditCount = appJdbc().sql("""
                        SELECT COUNT(*)
                        FROM audit_log
                        WHERE action = 'TAX_CHANGED'
                          AND entity_type = 'tax'
                          AND entity_id = :entityId
                        """)
                .param("entityId", Long.toString(taxId))
                .query(Long.class)
                .single();
        assertThat(auditCount).isEqualTo(2L);
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String uniqueAlphaSuffix() {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        StringBuilder sb = new StringBuilder(hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            sb.append(c >= '0' && c <= '9' ? (char) ('G' + (c - '0')) : Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static JsonNode findById(JsonNode array, long id) {
        if (array == null || !array.isArray()) {
            return null;
        }
        for (JsonNode node : array) {
            if (node.has("id") && node.get("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }
}
