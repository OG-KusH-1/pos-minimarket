package cl.minimarket.pos.dataexchange.web;

import cl.minimarket.pos.catalog.ProductCatalogApi.ProductExportFilter;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.dataexchange.application.CatalogExportService;
import cl.minimarket.pos.dataexchange.application.CatalogExportService.ExportedCatalog;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Endpoint de exportación del catálogo de productos ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/products/export")
@GlobalEndpoint
@Tag(name = "DataExchange", description = "Importación y exportación masiva del catálogo")
public class CatalogExportController {

    private final CatalogExportService exportService;

    public CatalogExportController(CatalogExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * Exporta el catálogo filtrado a CSV o XLSX; oculta columnas de costo si el usuario no posee
     * {@code catalog:cost:read} y registra {@code DATA_EXPORTED} en auditoría.
     */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_EXPORT)")
    @Operation(summary = "Exportar catálogo de productos",
            description = "Permiso: catalog:product:export. Errores: VALIDATION_ERROR")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) SaleType saleType,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean restricted,
            @RequestParam(required = false) Boolean lotControlled,
            @RequestParam(value = "format", defaultValue = "xlsx") String format) {
        ProductExportFilter filter = new ProductExportFilter(
                q, categoryId, brandId, saleType, active, restricted, lotControlled);
        ExportedCatalog exported = exportService.export(filter, format);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(exported.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(exported.contentType()))
                .contentLength(exported.content().length)
                .body(exported.content());
    }
}
