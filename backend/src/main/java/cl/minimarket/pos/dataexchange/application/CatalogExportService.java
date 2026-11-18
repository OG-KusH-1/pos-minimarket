package cl.minimarket.pos.dataexchange.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductExportFilter;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductExportRow;
import cl.minimarket.pos.shared.export.CsvWriter;
import cl.minimarket.pos.shared.export.XlsxWriter;
import cl.minimarket.pos.shared.security.Permissions;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exportación masiva del catálogo de productos en formato CSV o XLSX ({@code HU-CAT-11},
 * {@code API.md} F.2).
 *
 * <p>Omite las columnas de costo ({@code avgCost}, {@code lastCost}) cuando el usuario autenticado
 * no posee el permiso {@code catalog:cost:read} ({@code ROLES_PERMISOS} D.2) y registra la
 * acción {@code DATA_EXPORTED} en la bitácora inmutable.
 */
@Service
public class CatalogExportService {

    private static final String ACTION_DATA_EXPORTED = "DATA_EXPORTED";
    private static final String ENTITY_REPORT = "report";
    private static final String ENTITY_ID_CATALOG = "catalog_products";

    private final ProductCatalogApi productCatalogApi;
    private final AuditService auditService;

    public CatalogExportService(ProductCatalogApi productCatalogApi, AuditService auditService) {
        this.productCatalogApi = productCatalogApi;
        this.auditService = auditService;
    }

    /** Resultado binario de la exportación del catálogo. */
    public record ExportedCatalog(byte[] content, String fileName, String contentType) {
    }

    /**
     * Exporta los productos que cumplen los filtros dados al formato solicitado y audita
     * {@code DATA_EXPORTED}.
     *
     * @param filter criterios de filtrado del catálogo
     * @param format {@code csv} o {@code xlsx}
     * @return archivo generado y sus encabezados de descarga
     */
    @Transactional
    public ExportedCatalog export(ProductExportFilter filter, String format) {
        String normalizedFormat = ImportTemplateService.normalizeFormat(format);
        boolean includeCosts = hasCostReadPermission();
        List<ProductExportRow> rows = productCatalogApi.findForExport(filter);

        byte[] bytes = "csv".equals(normalizedFormat)
                ? writeCsv(rows, includeCosts)
                : writeXlsx(rows, includeCosts);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("type", "CATALOG_PRODUCTS");
        auditPayload.put("format", normalizedFormat);
        auditPayload.put("includeCosts", includeCosts);
        auditPayload.put("rows", rows.size());
        auditPayload.put("filters", describeFilters(filter));

        auditService.record(new AuditEvent(
                ACTION_DATA_EXPORTED,
                ENTITY_REPORT,
                ENTITY_ID_CATALOG,
                null,
                auditPayload,
                null,
                null
        ));

        String fileName = "catalogo-productos." + normalizedFormat;
        String contentType = "csv".equals(normalizedFormat)
                ? "text/csv; charset=utf-8"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return new ExportedCatalog(bytes, fileName, contentType);
    }

    private static byte[] writeCsv(List<ProductExportRow> rows, boolean includeCosts) {
        List<String> headers = new ArrayList<>(List.of(
                "sku", "name", "category", "brand", "saleType", "unitOfMeasure", "priceGross"));
        if (includeCosts) {
            headers.add("avgCost");
            headers.add("lastCost");
        }
        headers.addAll(List.of("vatExempt", "restrictedType", "lotControlled", "active", "primaryBarcode"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CsvWriter csv = new CsvWriter(out, headers)) {
            for (ProductExportRow r : rows) {
                List<Object> values = new ArrayList<>();
                values.add(r.sku());
                values.add(r.name());
                values.add(r.categoryName());
                values.add(r.brandName());
                values.add(r.saleType().name());
                values.add(r.unitOfMeasure().name());
                values.add(r.priceGross());
                if (includeCosts) {
                    values.add(r.avgCost());
                    values.add(r.lastCost());
                }
                values.add(Boolean.toString(r.vatExempt()));
                values.add(r.restrictedType() != null ? r.restrictedType().name() : "");
                values.add(Boolean.toString(r.lotControlled()));
                values.add(Boolean.toString(r.active()));
                values.add(r.primaryBarcode());
                csv.writeRow(values);
            }
        }
        return out.toByteArray();
    }

    private static byte[] writeXlsx(List<ProductExportRow> rows, boolean includeCosts) {
        List<XlsxWriter.Column> columns = new ArrayList<>(List.of(
                XlsxWriter.Column.text("sku"),
                XlsxWriter.Column.text("name"),
                XlsxWriter.Column.text("category"),
                XlsxWriter.Column.text("brand"),
                XlsxWriter.Column.text("saleType"),
                XlsxWriter.Column.text("unitOfMeasure"),
                XlsxWriter.Column.clp("priceGross")));
        if (includeCosts) {
            columns.add(XlsxWriter.Column.clp("avgCost"));
            columns.add(XlsxWriter.Column.clp("lastCost"));
        }
        columns.addAll(List.of(
                XlsxWriter.Column.text("vatExempt"),
                XlsxWriter.Column.text("restrictedType"),
                XlsxWriter.Column.text("lotControlled"),
                XlsxWriter.Column.text("active"),
                XlsxWriter.Column.text("primaryBarcode")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter xlsx = new XlsxWriter(out, "Productos", columns)) {
            for (ProductExportRow r : rows) {
                List<Object> values = new ArrayList<>();
                values.add(r.sku());
                values.add(r.name());
                values.add(r.categoryName());
                values.add(r.brandName());
                values.add(r.saleType().name());
                values.add(r.unitOfMeasure().name());
                values.add(r.priceGross());
                if (includeCosts) {
                    values.add(r.avgCost());
                    values.add(r.lastCost());
                }
                values.add(Boolean.toString(r.vatExempt()));
                values.add(r.restrictedType() != null ? r.restrictedType().name() : "");
                values.add(Boolean.toString(r.lotControlled()));
                values.add(Boolean.toString(r.active()));
                values.add(r.primaryBarcode());
                xlsx.writeRow(values);
            }
            xlsx.finish();
        }
        return out.toByteArray();
    }

    private static boolean hasCostReadPermission() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> Permissions.CATALOG_COST_READ.equals(a.getAuthority()));
    }

    private static Map<String, Object> describeFilters(ProductExportFilter filter) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (filter == null) {
            return map;
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            map.put("q", filter.q().trim());
        }
        if (filter.categoryId() != null) {
            map.put("categoryId", filter.categoryId());
        }
        if (filter.brandId() != null) {
            map.put("brandId", filter.brandId());
        }
        if (filter.saleType() != null) {
            map.put("saleType", filter.saleType().name());
        }
        if (filter.active() != null) {
            map.put("active", filter.active());
        }
        if (filter.restricted() != null) {
            map.put("restricted", filter.restricted());
        }
        if (filter.lotControlled() != null) {
            map.put("lotControlled", filter.lotControlled());
        }
        return map;
    }
}
