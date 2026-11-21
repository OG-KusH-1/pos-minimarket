package cl.minimarket.pos.reporting.cash.web;

import cl.minimarket.pos.reporting.cash.CashDifferenceRow;
import cl.minimarket.pos.reporting.cash.application.CashDifferenceReportService;
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
 * Controlador REST para el reporte de diferencias de caja ({@code API.md} F.2, {@code HU-REP-09}).
 *
 * <p>No es un endpoint global: requiere cabecera {@code X-Store-Id} y valida cualquier
 * {@code storeIds[]} explícito contra los locales asignados al usuario mediante
 * {@link StoreAccessValidator}.
 */
@RestController
@RequestMapping("/api/v1/reports/cash-differences")
@Tag(name = "Reporting")
public class CashDifferenceReportController {

    private final CashDifferenceReportService service;
    private final StoreAccessValidator storeAccessValidator;

    public CashDifferenceReportController(
            CashDifferenceReportService service,
            StoreAccessValidator storeAccessValidator) {
        this.service = Objects.requireNonNull(service, "service");
        this.storeAccessValidator = Objects.requireNonNull(storeAccessValidator, "storeAccessValidator");
    }

    /** Consulta las diferencias de arqueo de turnos cerrados en el período. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_CASH_READ)")
    @Operation(
            summary = "Reporte de diferencias de caja",
            description = "Permiso: report:cash:read. Lista turnos cerrados con esperado, contado, "
                    + "diferencia y aprobador, filtrando opcionalmente por cashierId y withDifference. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Reporte de diferencias de caja generado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (rango inválido, cashierId o storeIds inválidos)")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ReportResponse<CashDifferenceRow> getCashDifferencesReport(
            @Parameter(description = "Fecha inicial de negocio (YYYY-MM-DD, inclusive).", required = true)
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha final de negocio (YYYY-MM-DD, inclusive).", required = true)
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Ids de locales a incluir (por defecto el local activo de X-Store-Id).")
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(description = "Identificador opcional del cajero.")
            @RequestParam(required = false) Long cashierId,
            @Parameter(description = "Si es true, devuelve solo turnos con diferencia distinta de cero.")
            @RequestParam(required = false) Boolean withDifference) {
        List<Long> resolvedStores = resolveAccessibleStoreIds(storeIds);
        ReportFilter filter = new ReportFilter(from, to, resolvedStores).requireRange();
        return service.generate(filter, cashierId, withDifference);
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
