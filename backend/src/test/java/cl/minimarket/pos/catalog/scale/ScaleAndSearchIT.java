package cl.minimarket.pos.catalog.scale;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.scale.domain.ScaleBarcodeConfigRepository;
import cl.minimarket.pos.shared.rut.Ean;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de {@code catalog/scale}: endpoints de configuración de balanza,
 * resolución de códigos en {@link ProductCatalogApi#resolveCode(String)} y búsqueda trigram con y
 * sin tildes en {@link ProductCatalogApi#search(String, int)} ({@code T28}).
 */
class ScaleAndSearchIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    ProductCatalogApi productCatalogApi;

    @Autowired
    ScaleBarcodeConfigRepository scaleConfigRepository;

    @Test
    void scaleConfigsCrudPermissionsDuplicateAndRangeValidation() throws Exception {
        String prefix = nextUnusedThreeDigitPrefix();
        String otherPrefix = nextUnusedThreeDigitPrefix();

        // Sin permiso config:scale-barcode:manage → 403 ACCESS_DENIED
        mvc.perform(post("/api/v1/scale-barcode-configs")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Balanza Precio %s",
                                  "prefix": "%s",
                                  "embeddedType": "PRICE",
                                  "pluStart": 3,
                                  "pluLength": 4,
                                  "valueStart": 7,
                                  "valueLength": 5,
                                  "valueDecimals": 0
                                }
                                """.formatted(prefix, prefix)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Rango > 13 → 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/scale-barcode-configs")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_SCALE_BARCODE_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Balanza Inválida",
                                  "prefix": "%s",
                                  "embeddedType": "WEIGHT",
                                  "pluStart": 10,
                                  "pluLength": 4,
                                  "valueStart": 7,
                                  "valueLength": 5,
                                  "valueDecimals": 3
                                }
                                """.formatted(prefix)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Crear configuración válida
        MvcResult createResult = mvc.perform(post("/api/v1/scale-barcode-configs")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_SCALE_BARCODE_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Balanza Precio %s",
                                  "prefix": "%s",
                                  "embeddedType": "PRICE",
                                  "pluStart": 3,
                                  "pluLength": 4,
                                  "valueStart": 7,
                                  "valueLength": 5,
                                  "valueDecimals": 0
                                }
                                """.formatted(prefix, prefix)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.prefix").value(prefix))
                .andExpect(jsonPath("$.embeddedType").value("PRICE"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        long configId = jsonMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        // Prefijo duplicado → 409 DUPLICATED_VALUE
        mvc.perform(post("/api/v1/scale-barcode-configs")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_SCALE_BARCODE_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Otra Balanza",
                                  "prefix": "%s",
                                  "embeddedType": "WEIGHT",
                                  "pluStart": 3,
                                  "pluLength": 4,
                                  "valueStart": 7,
                                  "valueLength": 5,
                                  "valueDecimals": 3
                                }
                                """.formatted(prefix)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"));

        // Actualizar configuración
        mvc.perform(put("/api/v1/scale-barcode-configs/" + configId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_SCALE_BARCODE_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Balanza Actualizada %s",
                                  "prefix": "%s",
                                  "embeddedType": "PRICE",
                                  "pluStart": 3,
                                  "pluLength": 4,
                                  "valueStart": 7,
                                  "valueLength": 5,
                                  "valueDecimals": 0
                                }
                                """.formatted(otherPrefix, otherPrefix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value(otherPrefix))
                .andExpect(jsonPath("$.name").value("Balanza Actualizada " + otherPrefix));

        // Listar con permiso de lectura
        MvcResult listResult = mvc.perform(get("/api/v1/scale-barcode-configs")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = jsonMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(list).anyMatch(node -> node.get("id").asLong() == configId);
    }

    @Test
    void resolveCodeSupportsWeightAndPriceScaleBarcodesAndRejectsInvalidCheckDigit() throws Exception {
        String suffix = uniqueSuffix();
        long categoryId = createCategory("Frutas " + suffix);

        // 1. Producto WEIGHT con SCALE_PLU de 5 dígitos para prefijo "20" (semilla)
        String weightPlu = uniqueDigits(5);
        long weightProductId = createProduct(
                "MANZ-" + suffix,
                "Manzana Roja " + suffix,
                categoryId,
                "WEIGHT",
                "KG",
                1990,
                weightPlu);

        String weightBody = "20" + weightPlu + "01250";
        String validWeightCode = weightBody + Ean.checkDigit(weightBody);
        Optional<ProductCatalogApi.ScanResult> weightScan = productCatalogApi.resolveCode(validWeightCode);
        assertThat(weightScan).isPresent();
        assertThat(weightScan.get().product().id()).isEqualTo(weightProductId);
        assertThat(weightScan.get().source()).isEqualTo(ProductCatalogApi.ScanSource.SCALE);
        assertThat(weightScan.get().quantity()).isEqualByComparingTo("1.250");
        assertThat(weightScan.get().embeddedPrice()).isNull();

        // Dígito verificador inválido → vacío (el POS responde 404 CODE_NOT_FOUND)
        int badDigit = (Ean.checkDigit(weightBody) + 1) % 10;
        assertThat(productCatalogApi.resolveCode(weightBody + badDigit)).isEmpty();

        // 2. Configuración PRICE con prefijo de 3 dígitos y producto UNIT con SCALE_PLU de 4 dígitos
        String pricePrefix = nextUnusedThreeDigitPrefix();
        mvc.perform(post("/api/v1/scale-barcode-configs")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_SCALE_BARCODE_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Balanza Precio %s",
                                  "prefix": "%s",
                                  "embeddedType": "PRICE",
                                  "pluStart": 3,
                                  "pluLength": 4,
                                  "valueStart": 7,
                                  "valueLength": 5,
                                  "valueDecimals": 0
                                }
                                """.formatted(pricePrefix, pricePrefix)))
                .andExpect(status().isCreated());

        String pricePlu = uniqueDigits(4);
        long priceProductId = createProduct(
                "QUES-" + suffix,
                "Queso Laminado " + suffix,
                categoryId,
                "UNIT",
                "UNIT",
                4000,
                pricePlu);

        // Precio embebido 3000 / 4000 = 0.750
        String priceBody = pricePrefix + pricePlu + "03000";
        String validPriceCode = priceBody + Ean.checkDigit(priceBody);
        Optional<ProductCatalogApi.ScanResult> priceScan = productCatalogApi.resolveCode(validPriceCode);
        assertThat(priceScan).isPresent();
        assertThat(priceScan.get().product().id()).isEqualTo(priceProductId);
        assertThat(priceScan.get().source()).isEqualTo(ProductCatalogApi.ScanSource.SCALE);
        assertThat(priceScan.get().embeddedPrice()).isEqualByComparingTo("3000");
        assertThat(priceScan.get().quantity()).isEqualByComparingTo("0.750");
    }

    @Test
    void trigramSearchMatchesWithAndWithoutAccentsAndExcludesInactive() throws Exception {
        String suffix = uniqueSuffix();
        long categoryId = createCategory("Abarrotes " + suffix);

        long accentedId = createProduct(
                "AZU1-" + suffix,
                "Azúcar Rubia Orgánica " + suffix,
                categoryId,
                "UNIT",
                "UNIT",
                1590,
                null);
        long unaccentedId = createProduct(
                "AZU2-" + suffix,
                "Azucar Blanca Refinada " + suffix,
                categoryId,
                "UNIT",
                "UNIT",
                1290,
                null);
        long inactiveId = createProduct(
                "AZU3-" + suffix,
                "Azúcar Desactivada " + suffix,
                categoryId,
                "UNIT",
                "UNIT",
                990,
                null);

        mvc.perform(post("/api/v1/products/" + inactiveId + "/deactivate")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_DEACTIVATE))))
                .andExpect(status().isNoContent());

        // Búsqueda sin tilde encuentra ambos productos activos (con y sin tilde), excluye el inactivo
        List<ProductCatalogApi.ProductSnapshot> byTermNoAccent = productCatalogApi.search("azucar", 50);
        assertThat(byTermNoAccent)
                .extracting(ProductCatalogApi.ProductSnapshot::id)
                .contains(accentedId, unaccentedId)
                .doesNotContain(inactiveId);

        // Búsqueda con tilde también encuentra ambos productos activos (f_unaccent en ambos lados)
        List<ProductCatalogApi.ProductSnapshot> byTermWithAccent = productCatalogApi.search("azúcar", 50);
        assertThat(byTermWithAccent)
                .extracting(ProductCatalogApi.ProductSnapshot::id)
                .contains(accentedId, unaccentedId)
                .doesNotContain(inactiveId);

        // Búsqueda por SKU exacto aparece primero
        List<ProductCatalogApi.ProductSnapshot> bySku = productCatalogApi.search("AZU1-" + suffix, 10);
        assertThat(bySku).isNotEmpty();
        assertThat(bySku.getFirst().id()).isEqualTo(accentedId);
    }

    private long createCategory(String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/categories")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createProduct(String sku,
                               String name,
                               long categoryId,
                               String saleType,
                               String unitOfMeasure,
                               int priceGross,
                               String scalePlu) throws Exception {
        String barcodesJson = scalePlu == null
                ? "[]"
                : """
                  [{"code": "%s", "barcodeType": "SCALE_PLU", "isPrimary": true}]
                  """.formatted(scalePlu);

        MvcResult res = mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "%s",
                                  "name": "%s",
                                  "categoryId": %d,
                                  "saleType": "%s",
                                  "unitOfMeasure": "%s",
                                  "vatExempt": false,
                                  "initialPriceGross": %d,
                                  "barcodes": %s
                                }
                                """.formatted(sku, name, categoryId, saleType, unitOfMeasure, priceGross, barcodesJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private String nextUnusedThreeDigitPrefix() {
        for (int i = 0; i < 100; i++) {
            String candidate = String.valueOf(ThreadLocalRandom.current().nextInt(210, 999));
            if (!scaleConfigRepository.existsByPrefix(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No se encontró prefijo de 3 dígitos libre.");
    }

    private static String uniqueDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        return sb.toString();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
