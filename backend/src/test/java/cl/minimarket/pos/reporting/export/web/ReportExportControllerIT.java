package cl.minimarket.pos.reporting.export.web;

import cl.minimarket.pos.shared.export.CsvXlsxReader;
import cl.minimarket.pos.shared.export.CsvXlsxReader.ImportRow;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de los endpoints genéricos {@code /export} en formato XLSX y PDF para
 * todos los reportes, con validación de filtros, permisos y auditoría {@code DATA_EXPORTED}
 * ({@code T86}, {@code HU-REP-10}, {@code API.md} F.2).
 */
class ReportExportControllerIT extends AbstractIntegrationIT {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final LocalDate DAY_1 = LocalDate.of(2032, 6, 10);
    private static final LocalDate DAY_2 = LocalDate.of(2032, 6, 11);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private BusinessClock businessClock;

    private final CsvXlsxReader xlsxReader = new CsvXlsxReader();

    private String sfx;
    private int seq;

    private long storeA;
    private long storeB;
    private long storeUnauthorized;

    private long viewerUserId;
    private long cashier1Id;
    private long cashier2Id;
    private long approverId;

    private long catBeverages;
    private long catSnacks;

    private long prodCola;
    private long prodChips;
    private long prodNoRotation;

    private long supplier1;

    @BeforeEach
    void seed() {
        sfx = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        seq = 0;

        storeA = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        storeB = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        storeUnauthorized = TestData.insertStore(jdbc, TestData.uniqueStoreCode());

        viewerUserId = insertUser("exp_mgr_" + sfx, "Gerente Export " + sfx);
        assignStore(viewerUserId, storeA, true);
        assignStore(viewerUserId, storeB, false);

        cashier1Id = insertUser("exp_c1_" + sfx, "Cajero Alfa " + sfx);
        cashier2Id = insertUser("exp_c2_" + sfx, "Cajero Beta " + sfx);
        approverId = insertUser("exp_sup_" + sfx, "Supervisor Export " + sfx);

        long termA = insertTerminal(storeA, "EA1-" + sfx);
        long termB = insertTerminal(storeB, "EB1-" + sfx);

        long sessA1 = insertClosedSession(storeA, termA, cashier1Id, DAY_1, 100000, 98000, -2000, approverId);
        insertClosedSession(storeA, termA, cashier2Id, DAY_2, 50000, 50000, 0, null);
        long sessB1 = insertClosedSession(storeB, termB, cashier2Id, DAY_1, 30000, 30000, 0, null);

        catBeverages = insertCategory("Bebidas T86 " + sfx);
        catSnacks = insertCategory("Snacks T86 " + sfx);

        prodCola = insertProduct("T86-COLA-" + sfx, "Cola Ñandú " + sfx, catBeverages, 1190, "600.0000");
        prodChips = insertProduct("T86-CHIP-" + sfx, "Papas T86 " + sfx, catSnacks, 2380, "1000.0000");
        prodNoRotation = insertProduct("T86-NROT-" + sfx, "Conserva Sin Giro " + sfx, catSnacks, 1500, "700.0000");

        insertStock(storeA, prodCola, "10.000");
        insertStock(storeA, prodNoRotation, "5.000");
        insertStock(storeB, prodCola, "4.000");

        // DAY_1 en storeA: 4x prodCola (4760 bruto, 4000 neto, 760 IVA, costo 2400)
        long sale1 = insertSale(storeA, termA, sessA1, cashier1Id, DAY_1, LocalTime.of(10, 0),
                "COMPLETED", 4760, 4000, 760);
        insertSaleLine(sale1, 1, storeA, DAY_1, prodCola, "T86-COLA-" + sfx, "Cola Ñandú " + sfx,
                "4.000", 1190, 4760, 4000, 760, "600.0000", "2400.00");
        insertPayment(sale1, "CASH", 4760, 5000L, 240, "APPROVED");

        // DAY_2 en storeA: 1x prodChips (2380 bruto, 2000 neto, 380 IVA, costo 1000)
        long sale2 = insertSale(storeA, termA, sessA1, cashier2Id, DAY_2, LocalTime.of(15, 30),
                "COMPLETED", 2380, 2000, 380);
        insertSaleLine(sale2, 1, storeA, DAY_2, prodChips, "T86-CHIP-" + sfx, "Papas T86 " + sfx,
                "1.000", 2380, 2380, 2000, 380, "1000.0000", "1000.00");
        insertPayment(sale2, "DEBIT", 2380, null, 0, "APPROVED");

        // DAY_1 en storeB: 2x prodCola (2380 bruto, 2000 neto) para probar aislamiento/multi-local
        long saleB = insertSale(storeB, termB, sessB1, cashier2Id, DAY_1, LocalTime.of(12, 0),
                "COMPLETED", 2380, 2000, 380);
        insertSaleLine(saleB, 1, storeB, DAY_1, prodCola, "T86-COLA-" + sfx, "Cola Ñandú " + sfx,
                "2.000", 1190, 2380, 2000, 380, "600.0000", "1200.00");
        insertPayment(saleB, "CASH", 2380, 2380L, 0, "APPROVED");

        // Compras por proveedor en storeA dentro de [DAY_1, DAY_2]
        supplier1 = insertSupplier("Proveedor T86 " + sfx);
        OffsetDateTime poSent = utcAt(DAY_1, LocalTime.of(9, 0));
        long po1 = insertPo(storeA, supplier1, "RECEIVED", poSent, 60000);
        insertPoLine(po1, prodCola, "100.000", "100.000", "600.0000", 60000);
        insertReceipt(storeA, supplier1, po1, "CONFIRMED", poSent.plusDays(1), 60000);

        // Mermas en storeA: 1 APPROVED ($600 sobre ventas netas $6000 = 10.00 %) y 1 PENDING_APPROVAL excluida
        insertShrinkage(storeA, prodCola, "EXPIRED", "1.000", "600.0000", "600.00",
                "APPROVED", utcAt(DAY_1, LocalTime.of(14, 0)));
        insertShrinkage(storeA, prodChips, "DAMAGED", "2.000", "1000.0000", "2000.00",
                "PENDING_APPROVAL", utcAt(DAY_2, LocalTime.of(14, 0)));
    }

    @Test
    void rejects403AccessDeniedWithoutReportExportExecuteAcrossAllReportsWithoutAuditing() throws Exception {
        long auditBefore = countDataExportedAuditsForViewer();

        RequestPostProcessor readOnlyAuth = auth(
                Permissions.REPORT_DASHBOARD_READ,
                Permissions.REPORT_SALES_READ,
                Permissions.REPORT_MARGIN_READ,
                Permissions.REPORT_INVENTORY_READ,
                Permissions.REPORT_PURCHASING_READ,
                Permissions.REPORT_SHRINKAGE_READ,
                Permissions.REPORT_CASH_READ);

        List<String> pathsWithRange = List.of(
                "/api/v1/reports/sales/export",
                "/api/v1/reports/sales-by-cashier/export",
                "/api/v1/reports/sales-by-payment-method/export",
                "/api/v1/reports/product-ranking/export",
                "/api/v1/reports/no-rotation/export",
                "/api/v1/reports/abc/export",
                "/api/v1/reports/margin/export",
                "/api/v1/reports/inventory-valuation/export",
                "/api/v1/reports/purchases-by-supplier/export",
                "/api/v1/reports/shrinkage/export",
                "/api/v1/reports/shrinkage/top-products/export",
                "/api/v1/reports/cash-differences/export");

        for (String format : List.of("xlsx", "pdf")) {
            mvc.perform(get("/api/v1/reports/dashboard/export")
                            .header("X-Store-Id", storeA)
                            .param("date", DAY_1.toString())
                            .param("format", format)
                            .with(readOnlyAuth))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            for (String path : pathsWithRange) {
                mvc.perform(get(path)
                                .header("X-Store-Id", storeA)
                                .param("from", DAY_1.toString())
                                .param("to", DAY_2.toString())
                                .param("format", format)
                                .with(readOnlyAuth))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            }
        }

        // Usuario con report:export:execute pero sin el permiso del reporte -> 403 ACCESS_DENIED
        mvc.perform(get("/api/v1/reports/sales/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .with(auth(Permissions.REPORT_EXPORT_EXECUTE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // /abc/export?metric=margin sin report:margin:read -> 403 ACCESS_DENIED
        mvc.perform(get("/api/v1/reports/abc/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("metric", "margin")
                        .with(auth(Permissions.REPORT_SALES_READ, Permissions.REPORT_EXPORT_EXECUTE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(countDataExportedAuditsForViewer()).isEqualTo(auditBefore);
    }

    @Test
    void validatesFormatRangeStoreAccessAndStoreHeader() throws Exception {
        mvc.perform(get("/api/v1/reports/sales/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("format", "csv")
                        .with(auth(Permissions.REPORT_SALES_READ, Permissions.REPORT_EXPORT_EXECUTE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/reports/sales/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_1.plusDays(400).toString())
                        .param("format", "xlsx")
                        .with(auth(Permissions.REPORT_SALES_READ, Permissions.REPORT_EXPORT_EXECUTE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/reports/sales/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("storeIds", storeA + "," + storeUnauthorized)
                        .with(auth(Permissions.REPORT_SALES_READ, Permissions.REPORT_EXPORT_EXECUTE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));

        mvc.perform(get("/api/v1/reports/sales/export")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .with(auth(Permissions.REPORT_SALES_READ, Permissions.REPORT_EXPORT_EXECUTE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void exportsSalesAndDashboardRespectingFiltersInXlsxAndPdfAndAuditsDataExported() throws Exception {
        // 1. /sales/export en XLSX filtrado SOLO a DAY_1 (excluye DAY_2 y storeB) -> $4.760
        byte[] salesDay1Xlsx = performExport(
                get("/api/v1/reports/sales/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_1.toString())
                        .param("groupBy", "day")
                        .param("format", "xlsx")
                        .with(auth(Permissions.REPORT_SALES_READ, Permissions.REPORT_EXPORT_EXECUTE)),
                "reporte-ventas.xlsx",
                XLSX_MIME);

        List<ImportRow> salesDay1Rows = xlsxReader.readAll(
                new ByteArrayInputStream(salesDay1Xlsx), "reporte-ventas.xlsx");
        assertThat(salesDay1Rows).hasSize(2); // 1 día + fila Total
        assertThat(salesDay1Rows.get(0).values())
                .containsEntry("Clave", DAY_1.toString())
                .containsEntry("Total Bruto", "4760")
                .containsEntry("Total Neto", "4000");
        assertThat(salesDay1Rows.get(1).values())
                .containsEntry("Clave", "TOTAL")
                .containsEntry("Total Bruto", "4760");

        // 2. /sales/export en PDF con groupBy=category en [DAY_1, DAY_2] -> incluye Bebidas y Snacks
        byte[] salesCatPdf = performExport(
                get("/api/v1/reports/sales/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("groupBy", "category")
                        .param("format", "pdf")
                        .with(auth(Permissions.REPORT_SALES_READ, Permissions.REPORT_EXPORT_EXECUTE)),
                "reporte-ventas.pdf",
                MediaType.APPLICATION_PDF_VALUE);

        String salesPdfText = extractPdfText(salesCatPdf);
        assertThat(salesPdfText)
                .contains("Reporte de Ventas")
                .contains("Bebidas T86 " + sfx)
                .contains("Snacks T86 " + sfx)
                .contains("$7.140");

        // 3. /dashboard/export en XLSX y PDF filtrado por date=DAY_1
        byte[] dashXlsx = performExport(
                get("/api/v1/reports/dashboard/export")
                        .header("X-Store-Id", storeA)
                        .param("date", DAY_1.toString())
                        .param("format", "xlsx")
                        .with(auth(Permissions.REPORT_DASHBOARD_READ, Permissions.REPORT_EXPORT_EXECUTE)),
                "reporte-dashboard.xlsx",
                XLSX_MIME);
        List<ImportRow> dashRows = xlsxReader.readAll(new ByteArrayInputStream(dashXlsx), "reporte-dashboard.xlsx");
        assertThat(dashRows.getFirst().values())
                .containsEntry("Sección", "Resumen")
                .containsEntry("Concepto", "Ventas netas del día")
                .containsEntry("Monto CLP", "4760");

        byte[] dashPdf = performExport(
                get("/api/v1/reports/dashboard/export")
                        .header("X-Store-Id", storeA)
                        .param("date", DAY_1.toString())
                        .param("format", "pdf")
                        .with(auth(Permissions.REPORT_DASHBOARD_READ, Permissions.REPORT_EXPORT_EXECUTE)),
                "reporte-dashboard.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(dashPdf))
                .contains("Dashboard del Día")
                .contains("Cola Ñandú " + sfx)
                .contains("$4.760")
                .doesNotContain("Papas T86 " + sfx);

        // Verificar auditoría DATA_EXPORTED para sales y dashboard
        JsonNode salesAudit = findLatestAuditPayload("sales");
        assertThat(salesAudit.get("type").asString()).isEqualTo("SALES");
        assertThat(salesAudit.get("format").asString()).isEqualTo("pdf");
        assertThat(salesAudit.get("rows").asInt()).isEqualTo(2);
        assertThat(salesAudit.get("filters").get("groupBy").asString()).isEqualTo("CATEGORY");
        assertThat(salesAudit.get("filters").get("from").asString()).isEqualTo(DAY_1.toString());

        JsonNode dashAudit = findLatestAuditPayload("dashboard");
        assertThat(dashAudit.get("type").asString()).isEqualTo("DASHBOARD");
        assertThat(dashAudit.get("format").asString()).isEqualTo("pdf");
        assertThat(dashAudit.get("filters").get("date").asString()).isEqualTo(DAY_1.toString());
    }

    @Test
    void exportsRemainingSalesAndNoRotationReportsInXlsxAndPdfWithCostGuard() throws Exception {
        RequestPostProcessor allExportAuth = auth(
                Permissions.REPORT_SALES_READ,
                Permissions.REPORT_MARGIN_READ,
                Permissions.REPORT_INVENTORY_READ,
                Permissions.REPORT_EXPORT_EXECUTE);

        // sales-by-cashier (xlsx + pdf)
        byte[] cashierXlsx = performExport(
                get("/api/v1/reports/sales-by-cashier/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_1.toString())
                        .param("format", "xlsx")
                        .with(allExportAuth),
                "reporte-ventas-cajero.xlsx",
                XLSX_MIME);
        List<ImportRow> cashierRows = xlsxReader.readAll(
                new ByteArrayInputStream(cashierXlsx), "reporte-ventas-cajero.xlsx");
        assertThat(cashierRows).hasSize(2); // cashier1 + Total
        assertThat(cashierRows.get(0).values())
                .containsEntry("Cajero", "Cajero Alfa " + sfx)
                .containsEntry("Total CLP", "4760");

        byte[] cashierPdf = performExport(
                get("/api/v1/reports/sales-by-cashier/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("format", "pdf")
                        .with(allExportAuth),
                "reporte-ventas-cajero.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(cashierPdf))
                .contains("Cajero Alfa " + sfx)
                .contains("Cajero Beta " + sfx);

        // sales-by-payment-method (xlsx + pdf)
        byte[] methodXlsx = performExport(
                get("/api/v1/reports/sales-by-payment-method/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("format", "xlsx")
                        .with(allExportAuth),
                "reporte-ventas-medio-pago.xlsx",
                XLSX_MIME);
        List<ImportRow> methodRows = xlsxReader.readAll(
                new ByteArrayInputStream(methodXlsx), "reporte-ventas-medio-pago.xlsx");
        assertThat(methodRows).hasSize(3); // CASH, DEBIT, TOTAL

        byte[] methodPdf = performExport(
                get("/api/v1/reports/sales-by-payment-method/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("format", "pdf")
                        .with(allExportAuth),
                "reporte-ventas-medio-pago.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(methodPdf)).contains("CASH").contains("DEBIT");

        // product-ranking con categoryId=catSnacks, limit=1 (xlsx + pdf)
        byte[] rankXlsx = performExport(
                get("/api/v1/reports/product-ranking/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("order", "most")
                        .param("metric", "amount")
                        .param("limit", "1")
                        .param("categoryId", String.valueOf(catSnacks))
                        .param("format", "xlsx")
                        .with(allExportAuth),
                "reporte-ranking-productos.xlsx",
                XLSX_MIME);
        List<ImportRow> rankRows = xlsxReader.readAll(
                new ByteArrayInputStream(rankXlsx), "reporte-ranking-productos.xlsx");
        assertThat(rankRows).hasSize(1);
        assertThat(rankRows.getFirst().values()).containsEntry("SKU", "T86-CHIP-" + sfx);

        byte[] rankPdf = performExport(
                get("/api/v1/reports/product-ranking/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("categoryId", String.valueOf(catSnacks))
                        .param("format", "pdf")
                        .with(allExportAuth),
                "reporte-ranking-productos.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(rankPdf)).contains("Papas T86 " + sfx).doesNotContain("Cola Ñandú");

        // no-rotation SIN catalog:cost:read -> omite columna Valorización en XLSX y PDF
        byte[] noRotNoCostXlsx = performExport(
                get("/api/v1/reports/no-rotation/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("days", "30")
                        .param("format", "xlsx")
                        .with(allExportAuth),
                "reporte-sin-rotacion.xlsx",
                XLSX_MIME);
        List<ImportRow> noRotNoCostRows = xlsxReader.readAll(
                new ByteArrayInputStream(noRotNoCostXlsx), "reporte-sin-rotacion.xlsx");
        assertThat(noRotNoCostRows).hasSize(1);
        assertThat(noRotNoCostRows.getFirst().values())
                .containsEntry("SKU", "T86-NROT-" + sfx)
                .doesNotContainKey("Valorización");

        byte[] noRotNoCostPdf = performExport(
                get("/api/v1/reports/no-rotation/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("days", "30")
                        .param("format", "pdf")
                        .with(allExportAuth),
                "reporte-sin-rotacion.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(noRotNoCostPdf))
                .contains("Conserva Sin Giro " + sfx)
                .doesNotContain("Valorización");

        // no-rotation CON catalog:cost:read -> incluye Valorización (5 * 700 = 3500)
        byte[] noRotWithCostXlsx = performExport(
                get("/api/v1/reports/no-rotation/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("days", "30")
                        .param("format", "xlsx")
                        .with(auth(Permissions.REPORT_INVENTORY_READ, Permissions.REPORT_EXPORT_EXECUTE,
                                Permissions.CATALOG_COST_READ)),
                "reporte-sin-rotacion.xlsx",
                XLSX_MIME);
        List<ImportRow> noRotWithCostRows = xlsxReader.readAll(
                new ByteArrayInputStream(noRotWithCostXlsx), "reporte-sin-rotacion.xlsx");
        assertThat(noRotWithCostRows.getFirst().values()).containsEntry("Valorización", "3500");

        // abc metric=margin con report:margin:read (xlsx + pdf)
        byte[] abcXlsx = performExport(
                get("/api/v1/reports/abc/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("metric", "margin")
                        .param("format", "xlsx")
                        .with(allExportAuth),
                "reporte-abc.xlsx",
                XLSX_MIME);
        List<ImportRow> abcRows = xlsxReader.readAll(new ByteArrayInputStream(abcXlsx), "reporte-abc.xlsx");
        assertThat(abcRows).hasSize(2);
        assertThat(abcRows.getFirst().values()).containsEntry("Clase ABC", "A");

        byte[] abcPdf = performExport(
                get("/api/v1/reports/abc/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("metric", "margin")
                        .param("format", "pdf")
                        .with(allExportAuth),
                "reporte-abc.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(abcPdf)).contains("Análisis Pareto ABC").contains("Cola Ñandú");
    }

    @Test
    void exportsMarginInventoryPurchasingShrinkageAndCashDifferencesWithFiltersAndAudit() throws Exception {
        RequestPostProcessor managerExportAuth = auth(
                Permissions.REPORT_MARGIN_READ,
                Permissions.REPORT_INVENTORY_READ,
                Permissions.REPORT_PURCHASING_READ,
                Permissions.REPORT_SHRINKAGE_READ,
                Permissions.REPORT_CASH_READ,
                Permissions.REPORT_EXPORT_EXECUTE);

        // 1. margin (groupBy=product en DAY_1..DAY_1 -> solo Cola Ñandú)
        byte[] marginXlsx = performExport(
                get("/api/v1/reports/margin/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_1.toString())
                        .param("groupBy", "product")
                        .param("format", "xlsx")
                        .with(managerExportAuth),
                "reporte-margen.xlsx",
                XLSX_MIME);
        List<ImportRow> marginRows = xlsxReader.readAll(new ByteArrayInputStream(marginXlsx), "reporte-margen.xlsx");
        assertThat(marginRows).hasSize(2); // prodCola + Total
        assertThat(marginRows.getFirst().values())
                .containsEntry("Grupo", "Cola Ñandú " + sfx)
                .containsEntry("Venta Neta CLP", "4000")
                .containsEntry("Margen Bruto", "1600");

        byte[] marginPdf = performExport(
                get("/api/v1/reports/margin/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_1.toString())
                        .param("groupBy", "product")
                        .param("format", "pdf")
                        .with(managerExportAuth),
                "reporte-margen.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(marginPdf)).contains("Reporte de Margen y Utilidad").contains("Cola Ñandú");

        // 2. inventory-valuation sin rango de fechas y con multi-local storeA,storeB
        byte[] invXlsx = performExport(
                get("/api/v1/reports/inventory-valuation/export")
                        .header("X-Store-Id", storeA)
                        .param("groupBy", "store")
                        .param("storeIds", storeA + "," + storeB)
                        .param("format", "xlsx")
                        .with(managerExportAuth),
                "reporte-valorizacion-inventario.xlsx",
                XLSX_MIME);
        List<ImportRow> invRows = xlsxReader.readAll(
                new ByteArrayInputStream(invXlsx), "reporte-valorizacion-inventario.xlsx");
        assertThat(invRows).hasSize(3); // storeA, storeB, TOTAL

        byte[] invPdf = performExport(
                get("/api/v1/reports/inventory-valuation/export")
                        .header("X-Store-Id", storeA)
                        .param("groupBy", "store")
                        .param("storeIds", storeA + "," + storeB)
                        .param("format", "pdf")
                        .with(managerExportAuth),
                "reporte-valorizacion-inventario.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(invPdf))
                .contains("Valorización de Inventario")
                .contains("9.500,00")
                .contains("2.400,00")
                .contains("11.900,00")
                .doesNotContain("ERROR");

        // 3. purchases-by-supplier
        byte[] purXlsx = performExport(
                get("/api/v1/reports/purchases-by-supplier/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("format", "xlsx")
                        .with(managerExportAuth),
                "reporte-compras-proveedor.xlsx",
                XLSX_MIME);
        List<ImportRow> purRows = xlsxReader.readAll(
                new ByteArrayInputStream(purXlsx), "reporte-compras-proveedor.xlsx");
        assertThat(purRows).hasSize(2); // supplier1 + Total
        assertThat(purRows.getFirst().values())
                .containsEntry("ID", String.valueOf(supplier1))
                .containsEntry("Total Neto CLP", "60000")
                .containsEntry("Cumplimiento %", "100");

        byte[] purPdf = performExport(
                get("/api/v1/reports/purchases-by-supplier/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("format", "pdf")
                        .with(managerExportAuth),
                "reporte-compras-proveedor.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(purPdf)).contains("Proveedor T86 " + sfx).contains("$60.000");

        // 4. shrinkage (solo APPROVED -> EXPIRED 10.00 %) + shrinkage/top-products
        byte[] shrXlsx = performExport(
                get("/api/v1/reports/shrinkage/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("groupBy", "type")
                        .param("format", "xlsx")
                        .with(managerExportAuth),
                "reporte-mermas.xlsx",
                XLSX_MIME);
        List<ImportRow> shrRows = xlsxReader.readAll(new ByteArrayInputStream(shrXlsx), "reporte-mermas.xlsx");
        assertThat(shrRows).hasSize(2); // EXPIRED + TOTAL (DAMAGED PENDING_APPROVAL excluida)
        assertThat(shrRows.getFirst().values())
                .containsEntry("Clave", "EXPIRED")
                .containsEntry("Costo", "600")
                .containsEntry("% sobre Ventas", "10");

        byte[] shrTopPdf = performExport(
                get("/api/v1/reports/shrinkage/top-products/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("limit", "1")
                        .param("format", "pdf")
                        .with(managerExportAuth),
                "reporte-mermas-top-productos.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(shrTopPdf)).contains("Cola Ñandú").contains("10,00 %");

        // 5. cash-differences con withDifference=true y cashierId=cashier1Id -> solo turno con -2000 numérico
        byte[] cashXlsx = performExport(
                get("/api/v1/reports/cash-differences/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("cashierId", String.valueOf(cashier1Id))
                        .param("withDifference", "true")
                        .param("format", "xlsx")
                        .with(managerExportAuth),
                "reporte-diferencias-caja.xlsx",
                XLSX_MIME);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(cashXlsx))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2); // header + 1 fila + Total
            Cell diffCell = sheet.getRow(1).getCell(6);
            assertThat(diffCell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(diffCell.getNumericCellValue()).isEqualTo(-2000d);
        }

        byte[] cashPdf = performExport(
                get("/api/v1/reports/cash-differences/export")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("cashierId", String.valueOf(cashier1Id))
                        .param("withDifference", "true")
                        .param("format", "pdf")
                        .with(managerExportAuth),
                "reporte-diferencias-caja.pdf",
                MediaType.APPLICATION_PDF_VALUE);
        assertThat(extractPdfText(cashPdf))
                .contains("Cajero Alfa " + sfx)
                .contains("-$2.000")
                .doesNotContain("Cajero Beta");

        JsonNode cashAudit = findLatestAuditPayload("cash_differences");
        assertThat(cashAudit.get("type").asString()).isEqualTo("CASH_DIFFERENCES");
        assertThat(cashAudit.get("format").asString()).isEqualTo("pdf");
        assertThat(cashAudit.get("rows").asInt()).isEqualTo(1);
        assertThat(cashAudit.get("filters").get("cashierId").asLong()).isEqualTo(cashier1Id);
        assertThat(cashAudit.get("filters").get("withDifference").asBoolean()).isTrue();
    }

    // -----------------------------------------------------------------------------------------
    // Helpers de invocación, lectura binaria, auditoría y siembra
    // -----------------------------------------------------------------------------------------

    private byte[] performExport(
            MockHttpServletRequestBuilder request,
            String expectedFileName,
            String expectedContentType) throws Exception {
        MvcResult result = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, allOf(
                        startsWith("attachment;"),
                        containsString(expectedFileName))))
                .andExpect(content().contentTypeCompatibleWith(expectedContentType))
                .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();
        if (MediaType.APPLICATION_PDF_VALUE.equals(expectedContentType)) {
            assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        }
        return bytes;
    }

    private static String extractPdfText(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ").trim();
        }
    }

    private long countDataExportedAuditsForViewer() {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM audit_log
                        WHERE action = 'DATA_EXPORTED'
                          AND entity_type = 'report'
                          AND user_id = :uid
                        """)
                .param("uid", viewerUserId)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    private JsonNode findLatestAuditPayload(String entityId) throws Exception {
        String rawJson = jdbc.sql("""
                        SELECT new_value::text
                        FROM audit_log
                        WHERE action = 'DATA_EXPORTED'
                          AND entity_type = 'report'
                          AND entity_id = :entityId
                          AND user_id = :uid
                        ORDER BY id DESC
                        LIMIT 1
                        """)
                .param("entityId", entityId)
                .param("uid", viewerUserId)
                .query(String.class)
                .single();
        return jsonMapper.readTree(rawJson);
    }

    private RequestPostProcessor auth(String... permissionCodes) {
        List<GrantedAuthority> authorities = Arrays.stream(permissionCodes)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        return user(String.valueOf(viewerUserId)).authorities(authorities);
    }

    private long insertUser(String username, String fullName) {
        return jdbc.sql("""
                        INSERT INTO users (username, full_name, password_hash)
                        VALUES (:u, :name, 'x')
                        RETURNING id
                        """)
                .param("u", username)
                .param("name", fullName)
                .query(Long.class)
                .single();
    }

    private void assignStore(long userId, long storeId, boolean isDefault) {
        jdbc.sql("""
                        INSERT INTO user_stores (user_id, store_id, is_default)
                        VALUES (:u, :s, :def)
                        ON CONFLICT (user_id, store_id) DO NOTHING
                        """)
                .param("u", userId)
                .param("s", storeId)
                .param("def", isDefault)
                .update();
    }

    private long insertTerminal(long storeId, String code) {
        return jdbc.sql("""
                        INSERT INTO terminals (store_id, code, name, active)
                        VALUES (:s, :c, :n, true)
                        RETURNING id
                        """)
                .param("s", storeId)
                .param("c", code)
                .param("n", "Caja " + code)
                .query(Long.class)
                .single();
    }

    private long insertClosedSession(
            long storeId,
            long terminalId,
            long cashierId,
            LocalDate date,
            long expected,
            long counted,
            long diff,
            Long approvedBy) {
        OffsetDateTime closedAt = utcAt(date, LocalTime.of(21, 0));
        return jdbc.sql("""
                        INSERT INTO cash_sessions (
                            number, store_id, terminal_id, cashier_user_id, status, business_date,
                            opened_at, opening_amount, closed_at, closed_by,
                            expected_total, counted_total, difference_total,
                            difference_approved_by, difference_approved_at
                        )
                        VALUES (
                            :num, :s, :t, :c, 'CLOSED', :d,
                            :opened, 50000, :closed, :c,
                            :exp, :cnt, :diff,
                            :appBy, :appAt
                        )
                        RETURNING id
                        """)
                .param("num", "CS86-" + sfx + "-" + (++seq))
                .param("s", storeId)
                .param("t", terminalId)
                .param("c", cashierId)
                .param("d", date)
                .param("opened", closedAt.minusHours(8))
                .param("closed", closedAt)
                .param("exp", expected)
                .param("cnt", counted)
                .param("diff", diff)
                .param("appBy", approvedBy, Types.BIGINT)
                .param("appAt", approvedBy != null ? closedAt : null, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class)
                .single();
    }

    private long insertCategory(String name) {
        return jdbc.sql("INSERT INTO categories (name, active) VALUES (:n, true) RETURNING id")
                .param("n", name)
                .query(Long.class)
                .single();
    }

    private long insertProduct(String sku, String name, long categoryId, long priceGross, String avgCost) {
        return jdbc.sql("""
                        INSERT INTO products (
                            sku, name, category_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, lot_controlled, active
                        )
                        VALUES (:sku, :name, :cat, 'UNIT', 'UNIT', :price, :cost, false, false, true)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("cat", categoryId)
                .param("price", priceGross)
                .param("cost", new BigDecimal(avgCost))
                .query(Long.class)
                .single();
    }

    private void insertStock(long storeId, long productId, String qty) {
        jdbc.sql("""
                        INSERT INTO stock (store_id, product_id, quantity)
                        VALUES (:s, :p, :q)
                        ON CONFLICT (store_id, product_id) DO UPDATE SET quantity = EXCLUDED.quantity
                        """)
                .param("s", storeId)
                .param("p", productId)
                .param("q", new BigDecimal(qty))
                .update();
    }

    private long insertSale(
            long storeId,
            long terminalId,
            long cashSessionId,
            long cashierId,
            LocalDate businessDate,
            LocalTime time,
            String status,
            long totalGross,
            long netTotal,
            long taxTotal) {
        return jdbc.sql("""
                        INSERT INTO sales (
                            number, store_id, terminal_id, cash_session_id, cashier_user_id, status,
                            sold_at, business_date, idempotency_key,
                            subtotal_gross, discount_total, total_gross, net_total, tax_total, exempt_total,
                            rounding_adjustment, total_payable, paid_total, change_total
                        )
                        VALUES (
                            :num, :s, :t, :cs, :u, :status,
                            :soldAt, :d, :idem,
                            :total, 0, :total, :net, :tax, 0,
                            0, :total, :total, 0
                        )
                        RETURNING id
                        """)
                .param("num", "V86-" + sfx + "-" + (++seq))
                .param("s", storeId)
                .param("t", terminalId)
                .param("cs", cashSessionId)
                .param("u", cashierId)
                .param("status", status)
                .param("soldAt", utcAt(businessDate, time))
                .param("d", businessDate)
                .param("idem", UUID.randomUUID())
                .param("total", totalGross)
                .param("net", netTotal)
                .param("tax", taxTotal)
                .query(Long.class)
                .single();
    }

    private void insertSaleLine(
            long saleId,
            int lineNumber,
            long storeId,
            LocalDate businessDate,
            long productId,
            String sku,
            String name,
            String qty,
            long unitPrice,
            long lineTotal,
            long lineNet,
            long lineTax,
            String unitCost,
            String lineCost) {
        jdbc.sql("""
                        INSERT INTO sale_lines (
                            sale_id, line_number, store_id, business_date, product_id, sku, product_name,
                            sale_type, quantity, stock_product_id, stock_quantity,
                            unit_price_gross, line_subtotal_gross, discount_amount, line_total_gross,
                            line_net, line_tax, line_exempt, unit_cost, line_cost, restricted
                        )
                        VALUES (
                            :saleId, :ln, :s, :d, :p, :sku, :name,
                            'UNIT', :qty, :p, :qty,
                            :price, :total, 0, :total,
                            :net, :tax, 0, :uCost, :lCost, false
                        )
                        """)
                .param("saleId", saleId)
                .param("ln", lineNumber)
                .param("s", storeId)
                .param("d", businessDate)
                .param("p", productId)
                .param("sku", sku)
                .param("name", name)
                .param("qty", new BigDecimal(qty))
                .param("price", unitPrice)
                .param("total", lineTotal)
                .param("net", lineNet)
                .param("tax", lineTax)
                .param("uCost", new BigDecimal(unitCost))
                .param("lCost", new BigDecimal(lineCost))
                .update();
    }

    private void insertPayment(long saleId, String method, long amount, Long tendered, long change, String status) {
        jdbc.sql("""
                        INSERT INTO sale_payments (sale_id, method, amount, tendered_amount, change_amount, status)
                        VALUES (:saleId, :method, :amount, :tendered, :change, :status)
                        """)
                .param("saleId", saleId)
                .param("method", method)
                .param("amount", amount)
                .param("tendered", tendered, Types.NUMERIC)
                .param("change", change)
                .param("status", status)
                .update();
    }

    private long insertSupplier(String legalName) {
        String rut = (70_000_000 + (Math.abs(sfx.hashCode()) % 20_000_000) + (++seq)) + "-1";
        return jdbc.sql("""
                        INSERT INTO suppliers (rut, legal_name, payment_terms_type, lead_time_days, active)
                        VALUES (:rut, :name, 'CREDIT', 2, true)
                        RETURNING id
                        """)
                .param("rut", rut)
                .param("name", legalName)
                .query(Long.class)
                .single();
    }

    private long insertPo(long storeId, long supplierId, String status, OffsetDateTime sentAt, long netTotal) {
        return jdbc.sql("""
                        INSERT INTO purchase_orders (
                            number, store_id, supplier_id, status, origin, sent_at,
                            net_total, tax_total, total, created_at
                        )
                        VALUES (:num, :s, :sup, :st, 'MANUAL', :sentAt, :net, 0, :net, :createdAt)
                        RETURNING id
                        """)
                .param("num", "PO86-" + sfx + "-" + (++seq))
                .param("s", storeId)
                .param("sup", supplierId)
                .param("st", status)
                .param("sentAt", sentAt)
                .param("net", netTotal)
                .param("createdAt", sentAt.minusHours(1))
                .query(Long.class)
                .single();
    }

    private void insertPoLine(
            long poId, long productId, String qtyOrdered, String qtyReceived, String unitCost, long lineNet) {
        BigDecimal ordered = new BigDecimal(qtyOrdered);
        jdbc.sql("""
                        INSERT INTO purchase_order_lines (
                            purchase_order_id, product_id, qty_purchase_units, units_per_purchase_unit,
                            qty_ordered, unit_cost_net, qty_received, line_net_total
                        )
                        VALUES (:poId, :p, :qty, 1.000, :qty, :cost, :rec, :net)
                        """)
                .param("poId", poId)
                .param("p", productId)
                .param("qty", ordered)
                .param("cost", new BigDecimal(unitCost))
                .param("rec", new BigDecimal(qtyReceived))
                .param("net", lineNet)
                .update();
    }

    private void insertReceipt(
            long storeId, long supplierId, Long poId, String status, OffsetDateTime receivedAt, long netTotal) {
        jdbc.sql("""
                        INSERT INTO goods_receipts (
                            number, purchase_order_id, store_id, supplier_id, status,
                            supplier_document_type, supplier_document_number, received_at, received_by, net_total
                        )
                        VALUES (:num, :poId, :s, :sup, :st, 'INVOICE', :docNum, :recAt, :u, :net)
                        """)
                .param("num", "GR86-" + sfx + "-" + (++seq))
                .param("poId", poId, Types.BIGINT)
                .param("s", storeId)
                .param("sup", supplierId)
                .param("st", status)
                .param("docNum", "FAC86-" + seq)
                .param("recAt", receivedAt)
                .param("u", viewerUserId)
                .param("net", netTotal)
                .update();
    }

    private void insertShrinkage(
            long storeId, long productId, String type, String qty,
            String unitCost, String totalCost, String status, OffsetDateTime occurredAt) {
        Long approved = "APPROVED".equals(status) ? approverId : null;
        OffsetDateTime approvedAt = "APPROVED".equals(status) ? occurredAt : null;
        jdbc.sql("""
                        INSERT INTO shrinkages (
                            number, store_id, product_id, shrinkage_type, quantity, unit_cost, total_cost,
                            responsible_user_id, reported_by, status, approved_by, approved_at,
                            auto_approved, affects_stock, occurred_at
                        )
                        VALUES (
                            :num, :s, :p, :type, :qty, :uCost, :tCost,
                            :rep, :rep, :st, :appBy, :appAt,
                            false, true, :occ
                        )
                        """)
                .param("num", "SH86-" + sfx + "-" + (++seq))
                .param("s", storeId)
                .param("p", productId)
                .param("type", type)
                .param("qty", new BigDecimal(qty))
                .param("uCost", new BigDecimal(unitCost))
                .param("tCost", new BigDecimal(totalCost))
                .param("rep", viewerUserId)
                .param("st", status)
                .param("appBy", approved, Types.BIGINT)
                .param("appAt", approvedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("occ", occurredAt)
                .update();
    }

    private OffsetDateTime utcAt(LocalDate day, LocalTime time) {
        return OffsetDateTime.ofInstant(
                businessClock.startOfDay(day).plusSeconds(time.toSecondOfDay()), ZoneOffset.UTC);
    }
}
