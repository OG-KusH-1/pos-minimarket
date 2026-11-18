package cl.minimarket.pos.dataexchange;

import cl.minimarket.pos.shared.export.CsvWriter;
import cl.minimarket.pos.shared.export.CsvXlsxReader;
import cl.minimarket.pos.shared.export.CsvXlsxReader.ImportRow;
import cl.minimarket.pos.shared.export.XlsxWriter;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del módulo {@code dataexchange}: importación con {@code dryRun},
 * confirmación transaccional, límite de 20 MB, plantillas y exportación con/sin costos
 * ({@code T64}, {@code HU-CAT-10}, {@code HU-CAT-11}).
 */
class CatalogDataExchangeIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void requiresPermissionsForImportAndExport() throws Exception {
        mvc.perform(get("/api/v1/catalog/imports/templates/PRODUCTS")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(get("/api/v1/products/export")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void downloadsTemplatesInCsvAndXlsx() throws Exception {
        mvc.perform(get("/api/v1/catalog/imports/templates/PRODUCTS")
                        .param("format", "csv")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_IMPORT))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("plantilla-products.csv")));

        MvcResult xlsxResult = mvc.perform(get("/api/v1/catalog/imports/templates/INITIAL_STOCK")
                        .param("format", "xlsx")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_IMPORT))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("plantilla-initial_stock.xlsx")))
                .andReturn();

        assertThat(xlsxResult.getResponse().getContentAsByteArray()).hasSizeGreaterThan(100);
    }

    @Test
    void dryRunWithTwoInvalidRowsReportsErrorsWithoutWritingToDatabase() throws Exception {
        String suffix = uniqueSuffix();
        String categoryName = "CatDryRun-" + suffix;
        insertCategory(categoryName);

        String validSku = "SKU-OK-" + suffix;
        byte[] csvBytes = buildProductsCsv(List.of(
                List.of(validSku, "Producto Válido " + suffix, categoryName, "", "", "UNIT", "1500", "", "IVA", "", "false"),
                List.of("SKU-BAD1-" + suffix, "Sin Categoría", "CategoriaInexistente-" + suffix, "", "", "UNIT", "1000", "", "", "", "false"),
                List.of("SKU-BAD2-" + suffix, "Precio Negativo", categoryName, "", "", "UNIT", "-50", "", "", "", "false")
        ));

        MockMultipartFile file = new MockMultipartFile("file", "productos.csv", "text/csv", csvBytes);

        MvcResult result = mvc.perform(multipart("/api/v1/catalog/imports")
                        .file(file)
                        .param("importType", "PRODUCTS")
                        .param("dryRun", "true")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_IMPORT))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.okRows").value(1))
                .andExpect(jsonPath("$.errorRows").value(2))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].row").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("category"))
                .andExpect(jsonPath("$.errors[1].row").value(4))
                .andExpect(jsonPath("$.errors[1].field").value("priceGross"))
                .andReturn();

        long jobId = jsonMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        // No debe haberse creado ni siquiera el producto válido en dry-run
        Integer count = appJdbc().sql("SELECT COUNT(*) FROM products WHERE sku = :sku")
                .param("sku", validSku)
                .query(Integer.class)
                .single();
        assertThat(count).isZero();

        // Intentar confirmar un trabajo con errores falla con IMPORT_INVALID
        mvc.perform(post("/api/v1/catalog/imports/{id}/confirm", jobId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_IMPORT))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IMPORT_INVALID"));
    }

    @Test
    void confirmsValidProductsImportInSingleTransactionAndAuditsDataImported() throws Exception {
        String suffix = uniqueSuffix();
        String categoryName = "CatImport-" + suffix;
        insertCategory(categoryName);

        String sku1 = "SKU-IMP1-" + suffix;
        String sku2 = "SKU-IMP2-" + suffix;
        byte[] xlsxBytes = buildProductsXlsx(List.of(
                List.of(sku1, "Arroz Importado " + suffix, categoryName, "", "", "UNIT", "1290", "", "IVA", "", "false"),
                List.of(sku2, "Azúcar Importada " + suffix, categoryName, "", "", "WEIGHT", "1490", "", "IVA", "", "false")
        ));

        MockMultipartFile file = new MockMultipartFile(
                "file", "catalogo.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxBytes);

        MvcResult dryResult = mvc.perform(multipart("/api/v1/catalog/imports")
                        .file(file)
                        .param("importType", "PRODUCTS")
                        .param("dryRun", "true")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_IMPORT))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.errorRows").value(0))
                .andReturn();

        long jobId = jsonMapper.readTree(dryResult.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/catalog/imports/{id}/confirm", jobId)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_IMPORT),
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.okRows").value(2));

        Integer createdCount = appJdbc().sql("SELECT COUNT(*) FROM products WHERE sku IN (:s1, :s2)")
                .param("s1", sku1)
                .param("s2", sku2)
                .query(Integer.class)
                .single();
        assertThat(createdCount).isEqualTo(2);

        Integer auditCount = appJdbc().sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'DATA_IMPORTED' AND entity_type = 'import_job' AND entity_id = :id
                        """)
                .param("id", String.valueOf(jobId))
                .query(Integer.class)
                .single();
        assertThat(auditCount).isEqualTo(1);

        // Reconfirmar un trabajo ya COMPLETED devuelve 409 INVALID_STATE_TRANSITION
        mvc.perform(post("/api/v1/catalog/imports/{id}/confirm", jobId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_IMPORT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void rejectsFilesExceedingTwentyMegabytesWithFileInvalid() throws Exception {
        byte[] oversized = new byte[20 * 1024 * 1024 + 128];
        oversized[0] = 's';
        oversized[1] = 'k';
        oversized[2] = 'u';
        MockMultipartFile file = new MockMultipartFile("file", "grande.csv", "text/csv", oversized);

        mvc.perform(multipart("/api/v1/catalog/imports")
                        .file(file)
                        .param("importType", "PRODUCTS")
                        .param("dryRun", "true")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_IMPORT))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FILE_INVALID"));
    }

    @Test
    void exportsCatalogWithAndWithoutCostsAndAuditsDataExported() throws Exception {
        String suffix = uniqueSuffix();
        Long catId = insertCategory("CatExport-" + suffix);
        String sku = "SKU-EXP-" + suffix;
        insertProduct(sku, "Leche Export " + suffix, catId, new BigDecimal("1190"), new BigDecimal("750.0000"));

        // Sin catalog:cost:read -> archivo sin columnas de costo
        MvcResult noCostResult = mvc.perform(get("/api/v1/products/export")
                        .param("q", sku)
                        .param("format", "xlsx")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_EXPORT))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("catalogo-productos.xlsx")))
                .andReturn();

        List<ImportRow> noCostRows = new CsvXlsxReader().readAll(
                new ByteArrayInputStream(noCostResult.getResponse().getContentAsByteArray()),
                "catalogo-productos.xlsx");
        assertThat(noCostRows).hasSize(1);
        assertThat(noCostRows.getFirst().values()).containsEntry("sku", sku);
        assertThat(noCostRows.getFirst().values()).doesNotContainKeys("avgCost", "lastCost");

        // Con catalog:cost:read -> incluye avgCost y lastCost
        MvcResult withCostResult = mvc.perform(get("/api/v1/products/export")
                        .param("q", sku)
                        .param("format", "csv")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_EXPORT),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("catalogo-productos.csv")))
                .andReturn();

        List<ImportRow> withCostRows = new CsvXlsxReader().readAll(
                new ByteArrayInputStream(withCostResult.getResponse().getContentAsByteArray()),
                "catalogo-productos.csv");
        assertThat(withCostRows).hasSize(1);
        assertThat(withCostRows.getFirst().values()).containsKeys("avgCost", "lastCost");
        assertThat(new BigDecimal(withCostRows.getFirst().values().get("avgCost"))).isEqualByComparingTo("750.0000");

        Integer auditCount = appJdbc().sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'DATA_EXPORTED' AND entity_type = 'report' AND entity_id = 'catalog_products'
                        """)
                .query(Integer.class)
                .single();
        assertThat(auditCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void importsInitialStockIntoActiveStore() throws Exception {
        String suffix = uniqueSuffix();
        Long catId = insertCategory("CatStock-" + suffix);
        String sku = "SKU-STK-" + suffix;
        Long productId = insertProduct(sku, "Producto Stock " + suffix, catId, new BigDecimal("990"), BigDecimal.ZERO);
        Long storeId = findOrCreateActiveStore(suffix);
        ensureStockRow(storeId, productId);

        byte[] csvBytes = buildInitialStockCsv(List.of(
                List.of(sku, "25.000", "450", "", "")
        ));
        MockMultipartFile file = new MockMultipartFile("file", "stock.csv", "text/csv", csvBytes);

        MvcResult dryResult = mvc.perform(multipart("/api/v1/catalog/imports")
                        .file(file)
                        .param("importType", "INITIAL_STOCK")
                        .param("dryRun", "true")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_IMPORT))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.errorRows").value(0))
                .andReturn();

        long jobId = jsonMapper.readTree(dryResult.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/catalog/imports/{id}/confirm", jobId)
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_IMPORT),
                                new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        BigDecimal qty = appJdbc().sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId)
                .param("p", productId)
                .query(BigDecimal.class)
                .single();
        assertThat(qty).isEqualByComparingTo("25.000");

        BigDecimal avgCost = appJdbc().sql("SELECT avg_cost FROM products WHERE id = :p")
                .param("p", productId)
                .query(BigDecimal.class)
                .single();
        assertThat(avgCost).isEqualByComparingTo("450.0000");
    }

    // ---- Helpers de datos de prueba ----

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private Long insertCategory(String name) {
        return ownerJdbc().sql("""
                        INSERT INTO categories (name, active)
                        VALUES (:name, true)
                        RETURNING id
                        """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private Long insertProduct(String sku, String name, Long categoryId, BigDecimal priceGross, BigDecimal avgCost) {
        return ownerJdbc().sql("""
                        INSERT INTO products (
                            sku, name, category_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, lot_controlled, allow_manual_discount, active
                        )
                        VALUES (:sku, :name, :catId, 'UNIT', 'UNIT', :price, :cost, false, false, true, true)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("catId", categoryId)
                .param("price", priceGross)
                .param("cost", avgCost)
                .query(Long.class)
                .single();
    }

    private Long findOrCreateActiveStore(String suffix) {
        return ownerJdbc().sql("SELECT id FROM stores WHERE active = true ORDER BY id ASC LIMIT 1")
                .query(Long.class)
                .optional()
                .orElseGet(() -> ownerJdbc().sql("""
                                INSERT INTO stores (code, name, active)
                                VALUES (:code, :name, true)
                                RETURNING id
                                """)
                        .param("code", "S-" + suffix.substring(0, 4))
                        .param("name", "Local " + suffix)
                        .query(Long.class)
                        .single());
    }

    private void ensureStockRow(Long storeId, Long productId) {
        ownerJdbc().sql("""
                        INSERT INTO stock (store_id, product_id, quantity)
                        VALUES (:s, :p, 0)
                        ON CONFLICT (store_id, product_id) DO NOTHING
                        """)
                .param("s", storeId)
                .param("p", productId)
                .update();
    }

    private static byte[] buildProductsCsv(List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CsvWriter csv = new CsvWriter(out, List.of(
                "sku", "name", "category", "subcategory", "brand",
                "saleType", "priceGross", "barcodes", "taxes", "restricted", "lotControlled"))) {
            for (List<String> row : rows) {
                csv.writeRow(row);
            }
        }
        return out.toByteArray();
    }

    private static byte[] buildProductsXlsx(List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<XlsxWriter.Column> cols = List.of(
                XlsxWriter.Column.text("sku"),
                XlsxWriter.Column.text("name"),
                XlsxWriter.Column.text("category"),
                XlsxWriter.Column.text("subcategory"),
                XlsxWriter.Column.text("brand"),
                XlsxWriter.Column.text("saleType"),
                XlsxWriter.Column.text("priceGross"),
                XlsxWriter.Column.text("barcodes"),
                XlsxWriter.Column.text("taxes"),
                XlsxWriter.Column.text("restricted"),
                XlsxWriter.Column.text("lotControlled")
        );
        try (XlsxWriter xlsx = new XlsxWriter(out, "Productos", cols)) {
            for (List<String> row : rows) {
                xlsx.writeRow(row);
            }
            xlsx.finish();
        }
        return out.toByteArray();
    }

    private static byte[] buildInitialStockCsv(List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CsvWriter csv = new CsvWriter(out, List.of("sku", "quantity", "unitCost", "lotNumber", "expiryDate"))) {
            for (List<String> row : rows) {
                csv.writeRow(row);
            }
        }
        return out.toByteArray();
    }
}
