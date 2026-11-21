package cl.minimarket.pos.reporting.margin.web;

import cl.minimarket.pos.reporting.margin.MarginGroupBy;
import cl.minimarket.pos.reporting.margin.MarginRow;
import cl.minimarket.pos.reporting.margin.application.MarginReportService;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
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
 * Controlador REST para el reporte de margen y utilidad ({@code API.md} F.2, {@code HU-REP-04}).
 *
 * <p>No es un endpoint global: requiere cabecera {@code X-Store-Id} y valida cualquier
 * {@code storeIds[]} explícito contra los locales asignados al usuario mediante
 * {@link StoreAccessValidator}.
 */
@RestController
@RequestMapping("/api/v1/reports/margin")
@Tag(name = "Reporting")
public class MarginReportController {

    private final MarginReportService service;
    private final StoreAccessValidator storeAccessValidator;

    public MarginReportController(MarginReportService service, StoreAccessValidator storeAccessValidator) {
        this.service = Objects.requireNonNull(service, "service");
        this.storeAccessValidator = Objects.requireNonNull(storeAccessValidator, "storeAccessValidator");
    }

    /** Consulta el reporte de margen bruto y utilidad sobre ventas netas. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_MARGIN_READ)")
    @Operation(
            summary = "Reporte de margen y utilidad",
            description = "Permiso: report:margin:read. Calcula margen = neto − costo de la línea "
                    + "(snapshot) agrupado por product, category, brand o day. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Reporte de margen generado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (rango inválido o groupBy inválido)")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ReportResponse<MarginRow> getMarginReport(
            @Parameter(description = "Fecha inicial de negocio (YYYY-MM-DD, inclusive).", required = true)
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha final de negocio (YYYY-MM-DD, inclusive).", required = true)
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Ids de locales a incluir (por defecto el local activo de X-Store-Id).")
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(
                    description = "Criterio de agrupación.",
                    schema = @Schema(allowableValues = {"product", "category", "brand", "day"}, defaultValue = "product"))
            @RequestParam(required = false) String groupBy) {
        List<Long> resolvedStores = resolveAccessibleStoreIds(storeIds);
        ReportFilter filter = new ReportFilter(from, to, resolvedStores).requireRange();
        MarginGroupBy parsedGroupBy = MarginGroupBy.fromParam(groupBy);
        return service.generate(filter, parsedGroupBy);
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
