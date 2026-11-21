package cl.minimarket.pos.reporting.sales.web;

import cl.minimarket.pos.reporting.sales.application.AbcMetric;
import cl.minimarket.pos.reporting.sales.application.RankingMetric;
import cl.minimarket.pos.reporting.sales.application.RankingOrder;
import cl.minimarket.pos.reporting.sales.application.SalesGroupBy;
import cl.minimarket.pos.reporting.sales.application.SalesReportsService;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Endpoints REST de reportes de venta, cajero, medio de pago, ranking, sin rotación y análisis
 * ABC ({@code API.md} F.2/F.3, T84). Todos operan con contexto de local ({@code X-Store-Id}) y
 * admiten múltiples {@code storeIds} validados contra los locales asignados al usuario.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reporting")
public class SalesReportsController {

    private final SalesReportsService service;
    private final ObjectProvider<StoreAccessValidator> storeAccessValidator;

    public SalesReportsController(
            SalesReportsService service,
            ObjectProvider<StoreAccessValidator> storeAccessValidator
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.storeAccessValidator = Objects.requireNonNull(storeAccessValidator, "storeAccessValidator");
    }

    /** Reporte de ventas netas de devoluciones agrupadas por día, hora, día de semana, categoría o local. */
    @GetMapping("/sales")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SALES_READ)")
    @Operation(
            summary = "Reporte de ventas",
            description = "Permiso: report:sales:read. Rango obligatorio <= 366 días; agrupa por "
                    + "day, hour, weekday, category o store. Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Reporte de ventas generado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por fechas, rango > 366 días o groupBy inválido")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ReportResponse<SalesReportRow> sales(
            @Parameter(description = "Fecha inicial del rango (YYYY-MM-DD, obligatoria).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha final del rango (YYYY-MM-DD, obligatoria, >= from, rango <= 366 días).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Locales a incluir (por defecto el local activo X-Store-Id).")
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(
                    description = "Criterio de agrupación.",
                    schema = @Schema(allowableValues = {"day", "hour", "weekday", "category", "store"}, defaultValue = "day"))
            @RequestParam(required = false, defaultValue = "day") String groupBy
    ) {
        ReportFilter filter = buildFilter(from, to, storeIds);
        SalesGroupBy parsedGroupBy = SalesGroupBy.fromParam(groupBy);
        return mapResponse(service.salesReport(filter, parsedGroupBy), SalesReportRow::from);
    }

    /** Reporte de ventas netas, anulaciones, devoluciones y overrides por cajero. */
    @GetMapping("/sales-by-cashier")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SALES_READ)")
    @Operation(
            summary = "Ventas por cajero",
            description = "Permiso: report:sales:read. Incluye voids, devoluciones, descuentos y "
                    + "overrides por cajero. Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Reporte por cajero generado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por fechas o rango > 366 días")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ReportResponse<CashierSalesRow> salesByCashier(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds
    ) {
        ReportFilter filter = buildFilter(from, to, storeIds);
        return mapResponse(service.salesByCashier(filter), CashierSalesRow::from);
    }

    /** Reporte de recaudación neta de reembolsos por medio de pago. */
    @GetMapping("/sales-by-payment-method")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SALES_READ)")
    @Operation(
            summary = "Ventas por medio de pago",
            description = "Permiso: report:sales:read. Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Reporte por medio de pago generado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por fechas o rango > 366 días")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ReportResponse<PaymentMethodRow> salesByPaymentMethod(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds
    ) {
        ReportFilter filter = buildFilter(from, to, storeIds);
        return mapResponse(service.salesByPaymentMethod(filter), PaymentMethodRow::from);
    }

    /** Ranking de productos más o menos vendidos en cantidad o monto neto. */
    @GetMapping("/product-ranking")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SALES_READ)")
    @Operation(
            summary = "Ranking de productos",
            description = "Permiso: report:sales:read. Soporta order=most|least, metric=qty|amount, "
                    + "limit <= 100 y categoryId (incluye subcategorías). Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Ranking de productos generado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por filtros, rango o limit inválido")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ReportResponse<ProductRankingRow> productRanking(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"most", "least"}, defaultValue = "most"))
            @RequestParam(required = false, defaultValue = "most") String order,
            @Parameter(schema = @Schema(allowableValues = {"qty", "amount"}, defaultValue = "qty"))
            @RequestParam(required = false, defaultValue = "qty") String metric,
            @Parameter(description = "Cantidad máxima de productos (1..100).")
            @RequestParam(required = false, defaultValue = "20") int limit,
            @Parameter(description = "Categoría opcional (incluye subcategorías).")
            @RequestParam(required = false) Long categoryId
    ) {
        ReportFilter filter = buildFilter(from, to, storeIds);
        RankingOrder parsedOrder = RankingOrder.fromParam(order);
        RankingMetric parsedMetric = RankingMetric.fromParam(metric);
        return mapResponse(
                service.productRanking(filter, parsedOrder, parsedMetric, limit, categoryId),
                ProductRankingRow::from);
    }

    /** Productos con existencia positiva y sin ventas netas durante los últimos {@code days} días. */
    @GetMapping("/no-rotation")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_INVENTORY_READ)")
    @Operation(
            summary = "Productos sin rotación",
            description = "Permiso: report:inventory:read. Valorización visible solo con "
                    + "catalog:cost:read. Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Listado de productos sin rotación generado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por fechas, rango o days inválido")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ReportResponse<NoRotationRow> noRotation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(description = "Ventana de días sin ventas hasta la fecha final (por defecto 30).")
            @RequestParam(required = false, defaultValue = "30") int days
    ) {
        ReportFilter filter = buildFilter(from, to, storeIds);
        boolean includeCost = hasAuthority(Permissions.CATALOG_COST_READ);
        return mapResponse(service.noRotation(filter, days, includeCost), NoRotationRow::from);
    }

    /** Análisis Pareto ABC (A ≤ 80 %, B ≤ 95 %, C resto) por monto, cantidad o margen. */
    @GetMapping("/abc")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SALES_READ)")
    @Operation(
            summary = "Análisis ABC de productos",
            description = "Permiso: report:sales:read (metric=margin exige además report:margin:read). "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Clasificación ABC generada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por fechas, rango o metric inválido")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (sin permiso o sin report:margin:read) o STORE_ACCESS_DENIED")
    public ReportResponse<AbcRow> abc(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(schema = @Schema(allowableValues = {"amount", "qty", "margin"}, defaultValue = "amount"))
            @RequestParam(required = false, defaultValue = "amount") String metric
    ) {
        AbcMetric parsedMetric = AbcMetric.fromParam(metric);
        if (parsedMetric == AbcMetric.MARGIN && !hasAuthority(Permissions.REPORT_MARGIN_READ)) {
            throw new AccessDeniedException(
                    "Se requiere el permiso report:margin:read para ejecutar el análisis ABC por margen.");
        }
        ReportFilter filter = buildFilter(from, to, storeIds);
        return mapResponse(service.abc(filter, parsedMetric), AbcRow::from);
    }

    private ReportFilter buildFilter(LocalDate from, LocalDate to, List<Long> storeIds) {
        List<Long> resolvedStoreIds = resolveStoreIds(storeIds);
        return new ReportFilter(from, to, resolvedStoreIds);
    }

    private List<Long> resolveStoreIds(List<Long> requestedStoreIds) {
        Long activeStoreId = StoreContext.require().storeId();
        if (requestedStoreIds == null || requestedStoreIds.isEmpty()) {
            return List.of(activeStoreId);
        }
        List<Long> normalized = new ArrayList<>(requestedStoreIds.size());
        for (Long id : requestedStoreIds) {
            if (id == null || id <= 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Todos los identificadores de local deben ser enteros positivos.",
                        Map.of(),
                        List.of(new FieldErrorDetail(
                                "storeIds",
                                "INVALID_ID",
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
        return auth != null
                && auth.getAuthorities().stream()
                        .anyMatch(a -> permissionCode.equals(a.getAuthority()));
    }

    private static <S, T> ReportResponse<T> mapResponse(ReportResponse<S> source, Function<S, T> mapper) {
        List<T> mappedRows = source.rows().stream().map(mapper).toList();
        T mappedTotals = source.totals() != null ? mapper.apply(source.totals()) : null;
        return new ReportResponse<>(source.filters(), source.generatedAt(), mappedRows, mappedTotals);
    }
}
