package cl.minimarket.pos.reporting.inventory.web;

import cl.minimarket.pos.reporting.inventory.InventoryValuationGroupBy;
import cl.minimarket.pos.reporting.inventory.InventoryValuationRow;
import cl.minimarket.pos.reporting.inventory.application.InventoryValuationReportService;
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
 * Controlador REST para el reporte de valorización de inventario ({@code API.md} F.2,
 * {@code HU-REP-07}).
 *
 * <p>No es un endpoint global: requiere cabecera {@code X-Store-Id} y valida cualquier
 * {@code storeIds[]} explícito contra los locales asignados al usuario mediante
 * {@link StoreAccessValidator}.
 */
@RestController
@RequestMapping("/api/v1/reports/inventory-valuation")
@Tag(name = "Reporting")
public class InventoryValuationReportController {

    private final InventoryValuationReportService service;
    private final StoreAccessValidator storeAccessValidator;

    public InventoryValuationReportController(
            InventoryValuationReportService service,
            StoreAccessValidator storeAccessValidator) {
        this.service = Objects.requireNonNull(service, "service");
        this.storeAccessValidator = Objects.requireNonNull(storeAccessValidator, "storeAccessValidator");
    }

    /** Consulta la valorización actual del stock a costo promedio ponderado. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_INVENTORY_READ)")
    @Operation(
            summary = "Reporte de valorización de inventario",
            description = "Permiso: report:inventory:read. Valoriza el stock actual con products.avg_cost "
                    + "(sin rango obligatorio) agrupado por category, store o product. "
                    + "Errores: VALIDATION_ERROR, ACCESS_DENIED, STORE_ACCESS_DENIED.")
    @ApiResponse(responseCode = "200", description = "Valorización de inventario generada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (rango parcial/inválido o groupBy inválido)")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public ReportResponse<InventoryValuationRow> getInventoryValuationReport(
            @Parameter(description = "Fecha inicial opcional (YYYY-MM-DD); no altera la valorización actual.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha final opcional (YYYY-MM-DD); debe acompañarse de from si se indica.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Ids de locales a incluir (por defecto el local activo de X-Store-Id).")
            @RequestParam(required = false) List<Long> storeIds,
            @Parameter(
                    description = "Criterio de agrupación.",
                    schema = @Schema(allowableValues = {"category", "store", "product"}, defaultValue = "category"))
            @RequestParam(required = false) String groupBy) {
        List<Long> resolvedStores = resolveAccessibleStoreIds(storeIds);
        ReportFilter filter = new ReportFilter(from, to, resolvedStores).optionalRange();
        InventoryValuationGroupBy parsedGroupBy = InventoryValuationGroupBy.fromParam(groupBy);
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
