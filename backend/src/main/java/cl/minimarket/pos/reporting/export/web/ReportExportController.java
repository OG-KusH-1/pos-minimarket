package cl.minimarket.pos.reporting.export.web;

import cl.minimarket.pos.reporting.export.application.ReportExportService;
import cl.minimarket.pos.reporting.export.domain.ExportedReport;
import cl.minimarket.pos.reporting.inventory.InventoryValuationGroupBy;
import cl.minimarket.pos.reporting.margin.MarginGroupBy;
import cl.minimarket.pos.reporting.sales.application.AbcMetric;
import cl.minimarket.pos.reporting.sales.application.RankingMetric;
import cl.minimarket.pos.reporting.sales.application.RankingOrder;
import cl.minimarket.pos.reporting.sales.application.SalesGroupBy;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shrinkage.ShrinkageGroupBy;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreAccessValidator;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Controlador REST genérico para exportar cualquiera de los reportes a XLSX o PDF ({@code API.md}
 * F.1/F.2, {@code HU-REP-10}, T86).
 *
 * <p>Todos los endpoints aceptan los mismos filtros que el reporte respectivo más
 * {@code format=xlsx|pdf}, devuelven {@code Content-Disposition: attachment}, exigen el permiso de
 * lectura del reporte más {@code report:export:execute} y registran {@code DATA_EXPORTED} en la
 * bitácora de auditoría. Ninguno es {@code @GlobalEndpoint} (requieren {@code X-Store-Id}).
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reporting")
public class ReportExportController {

    private final ReportExportService exportService;
    private final ObjectProvider<StoreAccessValidator> storeAccessValidator;

    public ReportExportController(
            ReportExportService exportService,
            ObjectProvider<StoreAccessValidator> storeAccessValidator) {
        this.exportService = Objects.requireNonNull(exportService, "exportService");
        this.storeAccessValidator = Objects.requireNonNull(storeAccessValidator, "storeAccessValidator");
    }

    /** Exporta el dashboard ejecutivo diario en XLSX o PDF. */
    @GetMapping("/dashboard/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_DASHBOARD_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar dashboard del día",
            description = "Permisos: report:dashboard:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportDashboard(
            @Parameter(description = "Fecha de negocio (YYYY-MM-DD); por defecto hoy en la zona de negocio.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Locales a consolidar (opcional; por defecto el local activo X-Store-Id).")
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(description = "Formato de exportación.",
                    schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        Long activeStoreId = StoreContext.require().storeId();
        Long userId = CurrentUserId.find().orElse(null);
        boolean allStoreAccess = hasAuthority(Permissions.SECURITY_STORE_ALL_ACCESS);
        return toAttachmentResponse(
                exportService.exportDashboard(date, storeIds, activeStoreId, userId, allStoreAccess, format));
    }

    /** Exporta el reporte de ventas agrupadas en XLSX o PDF. */
    @GetMapping("/sales/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SALES_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar reporte de ventas",
            description = "Permisos: report:sales:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportSales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"day", "hour", "weekday", "category", "store"}, defaultValue = "day"))
            @RequestParam(required = false, defaultValue = "day") String groupBy,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds);
        SalesGroupBy parsedGroupBy = SalesGroupBy.fromParam(groupBy);
        return toAttachmentResponse(exportService.exportSales(filter, parsedGroupBy, format));
    }

    /** Exporta el reporte de ventas por cajero en XLSX o PDF. */
    @GetMapping("/sales-by-cashier/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SALES_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar ventas por cajero",
            description = "Permisos: report:sales:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportSalesByCashier(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds);
        return toAttachmentResponse(exportService.exportSalesByCashier(filter, format));
    }

    /** Exporta el reporte de ventas por medio de pago en XLSX o PDF. */
    @GetMapping("/sales-by-payment-method/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SALES_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar ventas por medio de pago",
            description = "Permisos: report:sales:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportSalesByPaymentMethod(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds);
        return toAttachmentResponse(exportService.exportSalesByPaymentMethod(filter, format));
    }

    /** Exporta el ranking de productos más o menos vendidos en XLSX o PDF. */
    @GetMapping("/product-ranking/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SALES_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar ranking de productos",
            description = "Permisos: report:sales:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportProductRanking(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"most", "least"}, defaultValue = "most"))
            @RequestParam(required = false, defaultValue = "most") String order,
            @Parameter(schema = @Schema(allowableValues = {"qty", "amount"}, defaultValue = "qty"))
            @RequestParam(required = false, defaultValue = "qty") String metric,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) Long categoryId,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds);
        RankingOrder parsedOrder = RankingOrder.fromParam(order);
        RankingMetric parsedMetric = RankingMetric.fromParam(metric);
        return toAttachmentResponse(
                exportService.exportProductRanking(filter, parsedOrder, parsedMetric, limit, categoryId, format));
    }

    /** Exporta el reporte de productos sin rotación en XLSX o PDF (oculta costos sin {@code catalog:cost:read}). */
    @GetMapping("/no-rotation/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_INVENTORY_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar productos sin rotación",
            description = "Permisos: report:inventory:read y report:export:execute. Valorización visible solo "
                    + "con catalog:cost:read. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportNoRotation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @RequestParam(required = false, defaultValue = "30") int days,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds);
        boolean includeCost = hasAuthority(Permissions.CATALOG_COST_READ);
        return toAttachmentResponse(exportService.exportNoRotation(filter, days, includeCost, format));
    }

    /** Exporta el análisis ABC en XLSX o PDF ({@code metric=margin} exige {@code report:margin:read}). */
    @GetMapping("/abc/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SALES_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar análisis ABC de productos",
            description = "Permisos: report:sales:read y report:export:execute (metric=margin exige además "
                    + "report:margin:read). Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportAbc(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"amount", "qty", "margin"}, defaultValue = "amount"))
            @RequestParam(required = false, defaultValue = "amount") String metric,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        AbcMetric parsedMetric = AbcMetric.fromParam(metric);
        if (parsedMetric == AbcMetric.MARGIN && !hasAuthority(Permissions.REPORT_MARGIN_READ)) {
            throw new AccessDeniedException(
                    "Se requiere el permiso report:margin:read para exportar el análisis ABC por margen.");
        }
        ReportFilter filter = buildFilter(from, to, storeIds);
        return toAttachmentResponse(exportService.exportAbc(filter, parsedMetric, format));
    }

    /** Exporta el reporte de margen y utilidad en XLSX o PDF. */
    @GetMapping("/margin/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_MARGIN_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar reporte de margen y utilidad",
            description = "Permisos: report:margin:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportMargin(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"product", "category", "brand", "day"}, defaultValue = "product"))
            @RequestParam(required = false) String groupBy,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds).requireRange();
        MarginGroupBy parsedGroupBy = MarginGroupBy.fromParam(groupBy);
        return toAttachmentResponse(exportService.exportMargin(filter, parsedGroupBy, format));
    }

    /** Exporta el reporte de valorización actual de inventario en XLSX o PDF. */
    @GetMapping("/inventory-valuation/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_INVENTORY_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar valorización de inventario",
            description = "Permisos: report:inventory:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportInventoryValuation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"category", "store", "product"}, defaultValue = "category"))
            @RequestParam(required = false) String groupBy,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds).optionalRange();
        InventoryValuationGroupBy parsedGroupBy = InventoryValuationGroupBy.fromParam(groupBy);
        return toAttachmentResponse(exportService.exportInventoryValuation(filter, parsedGroupBy, format));
    }

    /** Exporta el reporte de compras por proveedor en XLSX o PDF. */
    @GetMapping("/purchases-by-supplier/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_PURCHASING_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar compras por proveedor",
            description = "Permisos: report:purchasing:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportPurchasesBySupplier(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds).requireRange();
        return toAttachmentResponse(exportService.exportPurchasesBySupplier(filter, format));
    }

    /** Exporta el reporte de mermas aprobadas en XLSX o PDF. */
    @GetMapping("/shrinkage/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SHRINKAGE_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar reporte de mermas",
            description = "Permisos: report:shrinkage:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportShrinkage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"type", "product", "category", "store", "month"}, defaultValue = "type"))
            @RequestParam(required = false) String groupBy,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds).requireRange();
        ShrinkageGroupBy parsedGroupBy = ShrinkageGroupBy.fromParam(groupBy);
        return toAttachmentResponse(exportService.exportShrinkage(filter, parsedGroupBy, format));
    }

    /** Exporta el ranking de productos con mayor merma en XLSX o PDF. */
    @GetMapping("/shrinkage/top-products/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SHRINKAGE_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar ranking de productos con mayor merma",
            description = "Permisos: report:shrinkage:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportShrinkageTopProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @RequestParam(required = false) Integer limit,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds).requireRange();
        return toAttachmentResponse(exportService.exportShrinkageTopProducts(filter, limit, format));
    }

    /** Exporta el reporte de diferencias de caja en XLSX o PDF. */
    @GetMapping("/cash-differences/export")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_CASH_READ) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_EXPORT_EXECUTE)")
    @Operation(
            summary = "Exportar diferencias de caja",
            description = "Permisos: report:cash:read y report:export:execute. Audita DATA_EXPORTED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Archivo exportado en formato xlsx o pdf")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ResponseEntity<byte[]> exportCashDifferences(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @RequestParam(required = false) Long cashierId,
            @RequestParam(required = false) Boolean withDifference,
            @Parameter(schema = @Schema(allowableValues = {"xlsx", "pdf"}, defaultValue = "xlsx"))
            @RequestParam(required = false, defaultValue = "xlsx") String format) {
        ReportFilter filter = buildFilter(from, to, storeIds).requireRange();
        return toAttachmentResponse(
                exportService.exportCashDifferences(filter, cashierId, withDifference, format));
    }

    private ReportFilter buildFilter(LocalDate from, LocalDate to, List<Long> storeIds) {
        return new ReportFilter(from, to, resolveAccessibleStoreIds(storeIds));
    }

    private List<Long> resolveAccessibleStoreIds(List<Long> storeIds) {
        Long activeStoreId = StoreContext.require().storeId();
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of(activeStoreId);
        }
        List<Long> normalized = new ArrayList<>(storeIds.size());
        for (Long id : storeIds) {
            if (id == null || id <= 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Los identificadores de local deben ser enteros positivos.",
                        Map.of(),
                        List.of(new FieldErrorDetail(
                                "storeIds",
                                "INVALID_STORE_ID",
                                "Cada identificador de local debe ser mayor que cero.")));
            }
            if (!normalized.contains(id)) {
                normalized.add(id);
            }
        }
        StoreAccessValidator validator = storeAccessValidator.getIfAvailable();
        Optional<Long> userId = CurrentUserId.find();
        if (validator != null && userId.isPresent()) {
            for (Long storeId : normalized) {
                validator.requireAccess(userId.get(), storeId, null);
            }
        }
        return List.copyOf(normalized);
    }

    private static boolean hasAuthority(String permissionCode) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> permissionCode.equals(a.getAuthority()));
    }

    private static ResponseEntity<byte[]> toAttachmentResponse(ExportedReport exported) {
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
