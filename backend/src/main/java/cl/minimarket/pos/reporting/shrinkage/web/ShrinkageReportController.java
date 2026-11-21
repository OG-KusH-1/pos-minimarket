package cl.minimarket.pos.reporting.shrinkage.web;

import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.reporting.shrinkage.ShrinkageGroupBy;
import cl.minimarket.pos.reporting.shrinkage.ShrinkageReportRow;
import cl.minimarket.pos.reporting.shrinkage.application.ShrinkageReportService;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreAccessValidator;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Controlador REST para los reportes de mermas ({@code API.md} F.2, {@code HU-MER-04},
 * {@code HU-MER-05}).
 *
 * <p>No es un endpoint global: requiere cabecera {@code X-Store-Id} y valida cualquier
 * {@code storeIds[]} explícito contra los locales asignados al usuario mediante
 * {@link StoreAccessValidator}.
 */
@RestController
@RequestMapping("/api/v1/reports/shrinkage")
@Tag(name = "Reporting")
public class ShrinkageReportController {

    private final ShrinkageReportService service;
    private final StoreAccessValidator storeAccessValidator;

    public ShrinkageReportController(
            ShrinkageReportService service,
            StoreAccessValidator storeAccessValidator) {
        this.service = Objects.requireNonNull(service, "service");
        this.storeAccessValidator = Objects.requireNonNull(storeAccessValidator, "storeAccessValidator");
    }

    /** Consulta el reporte de mermas aprobadas en unidades, costo y porcentaje sobre ventas netas. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SHRINKAGE_READ)")
    @Operation(
            summary = "Reporte de mermas",
            description = "Permiso: report:shrinkage:read. Contabiliza solo mermas APPROVED agrupadas "
                    + "por type, product, category, store o month con pctOfSales respecto a las ventas netas. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Reporte de mermas generado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (rango inválido o groupBy inválido)")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ReportResponse<ShrinkageReportRow> getShrinkageReport(
            @Parameter(description = "Fecha inicial de negocio (YYYY-MM-DD, inclusive).", required = true)
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha final de negocio (YYYY-MM-DD, inclusive).", required = true)
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Ids de locales a incluir (por defecto el local activo de X-Store-Id).")
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(
                    description = "Criterio de agrupación.",
                    schema = @Schema(
                            allowableValues = {"type", "product", "category", "store", "month"},
                            defaultValue = "type"))
            @RequestParam(required = false) String groupBy) {
        List<Long> resolvedStores = resolveAccessibleStoreIds(storeIds);
        ReportFilter filter = new ReportFilter(from, to, resolvedStores).requireRange();
        ShrinkageGroupBy parsedGroupBy = ShrinkageGroupBy.fromParam(groupBy);
        return service.generate(filter, parsedGroupBy);
    }

    /** Consulta el ranking de productos con mayor costo acumulado de mermas aprobadas. */
    @GetMapping("/top-products")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_SHRINKAGE_READ)")
    @Operation(
            summary = "Ranking de productos con mayor merma",
            description = "Permiso: report:shrinkage:read. Devuelve hasta limit productos ordenados por "
                    + "costo descendente de mermas APPROVED. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Ranking de mermas por producto generado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (rango inválido o limit fuera de 1..100)")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ReportResponse<ShrinkageReportRow> getTopShrinkageProductsReport(
            @Parameter(description = "Fecha inicial de negocio (YYYY-MM-DD, inclusive).", required = true)
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha final de negocio (YYYY-MM-DD, inclusive).", required = true)
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Ids de locales a incluir (por defecto el local activo de X-Store-Id).")
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(
                    description = "Cantidad máxima de productos a devolver (1 a 100).",
                    schema = @Schema(defaultValue = "10", minimum = "1", maximum = "100"))
            @RequestParam(required = false) Integer limit) {
        List<Long> resolvedStores = resolveAccessibleStoreIds(storeIds);
        ReportFilter filter = new ReportFilter(from, to, resolvedStores).requireRange();
        return service.generateTopProducts(filter, limit);
    }

    private List<Long> resolveAccessibleStoreIds(List<Long> storeIds) {
        Long activeStoreId = StoreContext.require().storeId();
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of(activeStoreId);
        }
        Long userId = CurrentUserId.find().orElseThrow(() ->
                new BusinessException(ErrorCode.UNAUTHENTICATED, "Debe iniciar sesión para continuar."));
        for (Long storeId : storeIds) {
            if (storeId == null || storeId <= 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Los identificadores de local deben ser enteros positivos.",
                        Map.of(),
                        List.of(new FieldErrorDetail("storeIds", "INVALID_STORE_ID",
                                "Cada storeId debe ser un entero positivo.")));
            }
        }
        List<Long> distinctIds = storeIds.stream().distinct().toList();
        for (Long storeId : distinctIds) {
            storeAccessValidator.requireAccess(userId, storeId, null);
        }
        return distinctIds;
    }
}
