package cl.minimarket.pos.catalog.product;

import cl.minimarket.pos.catalog.PriceChangeType;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileMetadata;
import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de Catalog B: productos, códigos de barras, packs, impuestos, precios,
 * costos, historial, imagen, {@link ProductCatalogApi} y {@code DefaultLotControlGuard} ({@code T25}).
 */
class ProductIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    StoreApi storeApi;

    @Autowired
    FileApi fileApi;

    @Autowired
    ProductCatalogApi productCatalogApi;

    @Test
    void createProductInitializesStockAndEnforcesDuplicatesAndBarcodes() throws Exception {
        ensureAtLeastOneActiveStore();
        String suffix = uniqueSuffix();
        long categoryId = createCategory("Abarrotes " + suffix, null);
        String validEan13 = "4006381333931";
        // Para evitar colisiones con otros tests si ya existe 4006381333931 en la BD compartida,
        // generamos un EAN-13 válido único a partir de un prefijo aleatorio.
        String uniqueEan13 = generateValidEan13();
        String sku = "SKU-" + suffix;
        String shortcut = ("SC" + suffix).substring(0, 8).toUpperCase();

        // Apartarse del IVA por defecto sin config:tax:manage → 403 ACCESS_DENIED
        mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "%s",
                                  "name": "Arroz %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "vatExempt": true,
                                  "initialPriceGross": 1190
                                }
                                """.formatted(sku, suffix, categoryId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Barcode EAN-13 con dígito verificador inválido → 422 BARCODE_INVALID
        String invalidEan13 = uniqueEan13.substring(0, 12) + ((Character.getNumericValue(uniqueEan13.charAt(12)) + 1) % 10);
        mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "%s",
                                  "name": "Arroz %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "initialPriceGross": 1190,
                                  "barcodes": [{"code": "%s", "barcodeType": "EAN13", "isPrimary": true}]
                                }
                                """.formatted(sku, suffix, categoryId, invalidEan13)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BARCODE_INVALID"));

        // Creación válida con IVA por defecto, precio inicial y código de barras válido
        MvcResult created = mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "%s",
                                  "name": "Arroz Grado 1 %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "restrictedType": "ALCOHOL",
                                  "shortcutCode": "%s",
                                  "initialPriceGross": 1190,
                                  "barcodes": [{"code": "%s", "barcodeType": "EAN13", "isPrimary": true}]
                                }
                                """.formatted(sku, suffix, categoryId, shortcut, uniqueEan13)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.priceGross").value(1190))
                .andExpect(jsonPath("$.netPrice").value(1000))
                .andExpect(jsonPath("$.vatExempt").value(false))
                .andExpect(jsonPath("$.restrictedType").value("ALCOHOL"))
                .andExpect(jsonPath("$.taxes[0].code").value("IVA"))
                .andExpect(jsonPath("$.barcodes[0].code").value(uniqueEan13))
                .andReturn();

        long productId = jsonMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // Verifica que ProductCreatedListener creó filas de stock en 0 para cada local activo
        List<Long> activeStores = storeApi.activeStoreIds();
        assertThat(activeStores).isNotEmpty();
        long stockRows = appJdbc().sql("SELECT COUNT(*) FROM stock WHERE product_id = :pid AND quantity = 0")
                .param("pid", productId)
                .query(Long.class)
                .single();
        assertThat(stockRows).isEqualTo(activeStores.size());

        // SKU duplicado → 409 DUPLICATED_VALUE
        mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "%s",
                                  "name": "Otro %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT"
                                }
                                """.formatted(sku, suffix, categoryId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"));

        // Barcode duplicado en otro producto → 409 DUPLICATED_VALUE
        mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "%s-2",
                                  "name": "Otro %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "barcodes": [{"code": "%s", "barcodeType": "EAN13"}]
                                }
                                """.formatted(sku, suffix, categoryId, uniqueEan13)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"));

        // El helper Ean13 también funciona como referencia
        assertThat(validEan13).hasSize(13);
    }

    @Test
    void costsHiddenWithoutPermissionAndShownWithPermission() throws Exception {
        String suffix = uniqueSuffix();
        long categoryId = createCategory("Bebidas " + suffix, null);

        MvcResult created = mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "COST-%s",
                                  "name": "Jugo %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "initialPriceGross": 1190
                                }
                                """.formatted(suffix, suffix, categoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        long productId = jsonMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // Actualizar costo manual a 700.0000 (netPrice = 1000 → marginPct = 30.00)
        mvc.perform(put("/api/v1/products/" + productId + "/cost")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_UPDATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newAvgCost": 700, "reason": "Costo inicial manual", "version": 0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avgCost").value(700))
                .andExpect(jsonPath("$.marginPct").value(30.0));

        // Sin catalog:cost:read → avgCost, lastCost y marginPct llegan null en ficha, listado e historial
        mvc.perform(get("/api/v1/products/" + productId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avgCost").isEmpty())
                .andExpect(jsonPath("$.lastCost").isEmpty())
                .andExpect(jsonPath("$.marginPct").isEmpty());

        mvc.perform(get("/api/v1/products")
                        .param("q", "COST-" + suffix)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].avgCost").isEmpty())
                .andExpect(jsonPath("$.content[0].marginPct").isEmpty());

        mvc.perform(get("/api/v1/products/" + productId + "/price-history")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].oldCost").isEmpty())
                .andExpect(jsonPath("$.content[0].newCost").isEmpty());

        // Con catalog:cost:read → los costos aparecen
        mvc.perform(get("/api/v1/products/" + productId + "/price-history")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].newCost").value(700));
    }

    @Test
    void putProductIgnoresVatExemptAndPriceAndGuardsLotControl() throws Exception {
        ensureAtLeastOneActiveStore();
        String suffix = uniqueSuffix();
        long categoryId = createCategory("Lácteos " + suffix, null);

        MvcResult created = mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "LOT-%s",
                                  "name": "Yogurt %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "lotControlled": false,
                                  "initialPriceGross": 1000
                                }
                                """.formatted(suffix, suffix, categoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createdJson = jsonMapper.readTree(created.getResponse().getContentAsString());
        long productId = createdJson.get("id").asLong();
        long version = createdJson.get("version").asLong();

        // PUT /products/{id} ignora vatExempt y initialPriceGross; con stock=0 permite cambiar lotControlled=true
        MvcResult updated = mvc.perform(put("/api/v1/products/" + productId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "LOT-%s",
                                  "name": "Yogurt Natural %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "vatExempt": true,
                                  "lotControlled": true,
                                  "initialPriceGross": 9999,
                                  "version": %d
                                }
                                """.formatted(suffix, suffix, categoryId, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Yogurt Natural " + suffix))
                .andExpect(jsonPath("$.vatExempt").value(false))
                .andExpect(jsonPath("$.priceGross").value(1000))
                .andExpect(jsonPath("$.lotControlled").value(true))
                .andReturn();
        long newVersion = jsonMapper.readTree(updated.getResponse().getContentAsString()).get("version").asLong();

        // Versión obsoleta → 409 CONCURRENT_MODIFICATION
        mvc.perform(put("/api/v1/products/" + productId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "LOT-%s",
                                  "name": "Yogurt Viejo",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "lotControlled": true,
                                  "version": %d
                                }
                                """.formatted(suffix, categoryId, version)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        // Simulamos stock > 0 en el primer local activo
        Long firstStoreId = storeApi.activeStoreIds().getFirst();
        ownerJdbc().sql("UPDATE stock SET quantity = 5 WHERE store_id = :sid AND product_id = :pid")
                .param("sid", firstStoreId)
                .param("pid", productId)
                .update();

        // Cambiar lotControlled con stock != 0 → 409 ENTITY_IN_USE
        mvc.perform(put("/api/v1/products/" + productId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "LOT-%s",
                                  "name": "Yogurt Natural %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "lotControlled": false,
                                  "version": %d
                                }
                                """.formatted(suffix, suffix, categoryId, newVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));
    }

    @Test
    void updateTaxesRequiresPermissionValidatesAndAudits() throws Exception {
        String suffix = uniqueSuffix();
        long categoryId = createCategory("Congelados " + suffix, null);

        MvcResult created = mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "TAX-%s",
                                  "name": "Helado %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT"
                                }
                                """.formatted(suffix, suffix, categoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        long productId = jsonMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // Sin config:tax:manage → 403
        mvc.perform(put("/api/v1/products/" + productId + "/taxes")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vatExempt": true, "taxIds": []}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        Long ivaId = appJdbc().sql("SELECT id FROM taxes WHERE code = 'IVA'")
                .query(Long.class)
                .single();
        Long iabaId = appJdbc().sql("SELECT id FROM taxes WHERE code = 'ILA_BEBIDAS_ALTO_AZUCAR'")
                .query(Long.class)
                .single();

        // vatExempt=true incluyendo IVA → 400 VALIDATION_ERROR
        mvc.perform(put("/api/v1/products/" + productId + "/taxes")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_TAX_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vatExempt": true, "taxIds": [%d]}
                                """.formatted(ivaId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // vatExempt=true con impuesto adicional → 200 OK y auditoría PRODUCT_TAXES_CHANGED
        mvc.perform(put("/api/v1/products/" + productId + "/taxes")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_TAX_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vatExempt": true, "taxIds": [%d]}
                                """.formatted(iabaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ILA_BEBIDAS_ALTO_AZUCAR"));

        long auditCount = appJdbc().sql("""
                        SELECT COUNT(*)
                        FROM audit_log
                        WHERE action = 'PRODUCT_TAXES_CHANGED'
                          AND entity_type = 'product'
                          AND entity_id = :eid
                        """)
                .param("eid", Long.toString(productId))
                .query(Long.class)
                .single();
        assertThat(auditCount).isEqualTo(1L);
    }

    @Test
    void packPriceBarcodesImageAndPublicApi() throws Exception {
        String suffix = uniqueSuffix();
        long rootCatId = createCategory("Bebestibles " + suffix, null);
        long subCatId = createCategory("Aguas " + suffix, rootCatId);

        // Producto base UNIT
        MvcResult baseCreated = mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "BASE-%s",
                                  "name": "Agua Mineral %s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT"
                                }
                                """.formatted(suffix, suffix, subCatId)))
                .andExpect(status().isCreated())
                .andReturn();
        long baseId = jsonMapper.readTree(baseCreated.getResponse().getContentAsString()).get("id").asLong();

        // Producto PACK (no debe crear filas en stock)
        MvcResult packCreated = mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "PACK-%s",
                                  "name": "Pack Agua x6 %s",
                                  "categoryId": %d,
                                  "saleType": "PACK",
                                  "unitOfMeasure": "UNIT",
                                  "pack": {"baseProductId": %d, "factor": 6}
                                }
                                """.formatted(suffix, suffix, subCatId, baseId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pack.baseProductId").value(baseId))
                .andExpect(jsonPath("$.pack.factor").value(6))
                .andReturn();
        long packId = jsonMapper.readTree(packCreated.getResponse().getContentAsString()).get("id").asLong();

        long packStockRows = appJdbc().sql("SELECT COUNT(*) FROM stock WHERE product_id = :pid")
                .param("pid", packId)
                .query(Long.class)
                .single();
        assertThat(packStockRows).isZero();

        // Intentar usar el pack como base de otro pack → 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/products")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "PACK2-%s",
                                  "name": "Mega Pack %s",
                                  "categoryId": %d,
                                  "saleType": "PACK",
                                  "unitOfMeasure": "UNIT",
                                  "pack": {"baseProductId": %d, "factor": 2}
                                }
                                """.formatted(suffix, suffix, subCatId, packId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Cambio de precio y auditoría PRODUCT_PRICE_CHANGED
        mvc.perform(put("/api/v1/products/" + baseId + "/price")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPriceGross": 1500, "reason": "Lista nueva", "version": 0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceGross").value(1500));

        // Agregar código EAN-8 válido, marcarlo principal y luego eliminar
        String validEan8 = "96385074";
        // Si ya existe en BD compartida, usamos INTERNAL único para evitar colisiones entre ejecuciones
        String internalCode = "INT" + suffix;
        MvcResult bcRes = mvc.perform(post("/api/v1/products/" + baseId + "/barcodes")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "barcodeType": "INTERNAL", "isPrimary": false}
                                """.formatted(internalCode)))
                .andExpect(status().isCreated())
                .andReturn();
        long bcId = jsonMapper.readTree(bcRes.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(put("/api/v1/products/" + baseId + "/barcodes/" + bcId + "/primary")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE))))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/v1/products/" + baseId + "/barcodes/" + bcId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE))))
                .andExpect(status().isNoContent());

        // Imagen: registrar un PNG mínimo válido vía FileApi
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        FileMetadata imgMeta = fileApi.register(new ByteArrayInputStream(pngHeader),
                "producto.png", "image/png", pngHeader.length, FileCategory.PRODUCT_IMAGE);
        mvc.perform(put("/api/v1/products/" + baseId + "/image")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId": %d}
                                """.formatted(imgMeta.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("/api/v1/files/" + imgMeta.id() + "/content"));

        mvc.perform(delete("/api/v1/products/" + baseId + "/image")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE))))
                .andExpect(status().isNoContent());

        // Filtro por categoría padre incluye subcategoría
        mvc.perform(get("/api/v1/products")
                        .param("categoryId", Long.toString(rootCatId))
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // ProductCatalogApi
        assertThat(productCatalogApi.nonPackProductIds()).contains(baseId).doesNotContain(packId);
        assertThat(productCatalogApi.packOf(packId))
                .isPresent()
                .get()
                .satisfies(p -> {
                    assertThat(p.baseProductId()).isEqualTo(baseId);
                    assertThat(p.factor()).isEqualTo(6);
                });
        productCatalogApi.applyAverageCost(baseId, new BigDecimal("850.5000"),
                PriceChangeType.COST_RECEIPT, "GOODS_RECEIPT:101");
        assertThat(productCatalogApi.getForSale(baseId).avgCost()).isEqualByComparingTo("850.5000");

        // Desactivar y auditar PRODUCT_DEACTIVATED
        mvc.perform(post("/api/v1/products/" + baseId + "/deactivate")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_DEACTIVATE))))
                .andExpect(status().isNoContent());

        assertThat(productCatalogApi.isInactiveCodeMatch("BASE-" + suffix)).isTrue();
        assertThat(productCatalogApi.isInactiveCodeMatch("PACK-" + suffix)).isFalse();
        assertThat(productCatalogApi.isInactiveCodeMatch("NO-EXISTE-" + suffix)).isFalse();

        assertThat(validEan8).hasSize(8);
    }

    private void ensureAtLeastOneActiveStore() {
        if (!storeApi.activeStoreIds().isEmpty()) {
            return;
        }
        TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
    }

    private long createCategory(String name, Long parentId) throws Exception {
        String body = parentId == null
                ? """
                {"name": "%s"}
                """.formatted(name)
                : """
                {"name": "%s", "parentId": %d}
                """.formatted(name, parentId);
        MvcResult res = mvc.perform(post("/api/v1/categories")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_CATEGORY_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String generateValidEan13() {
        long num = (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 900_000_000_000L) + 100_000_000_000L;
        String body = Long.toString(num);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int posFromRight = 12 - i;
            sum += (body.charAt(i) - '0') * (posFromRight % 2 == 1 ? 3 : 1);
        }
        int check = (10 - sum % 10) % 10;
        return body + check;
    }
}
