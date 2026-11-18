package cl.minimarket.pos.inventory.web.lot;

import cl.minimarket.pos.inventory.application.lot.LotQueryService;
import cl.minimarket.pos.inventory.application.lot.LotQueryService.LotExpiryAlertView;
import cl.minimarket.pos.inventory.web.stock.ExpiryAlertResponse;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Endpoint REST para consultar alertas de lotes vencidos o próximos a vencer en el local activo
 * ({@code API.md} F.2, {@code HU-INV-08}).
 */
@RestController
@RequestMapping("/api/v1/expiry-alerts")
@Tag(name = "Inventory")
public class ExpiryAlertController {

    private static final SortParser ALERT_SORT = SortParser.of(Map.of(
                    "id", "id",
                    "lotId", "lotId",
                    "sku", "sku",
                    "name", "productName",
                    "productName", "productName",
                    "lotNumber", "lotNumber",
                    "expiryDate", "expiryDate",
                    "daysToExpiry", "expiryDate",
                    "quantity", "quantity"))
            .withDefault(Sort.by(Sort.Order.asc("expiryDate"), Sort.Order.asc("lotId")));

    private final LotQueryService lotQueryService;

    public ExpiryAlertController(LotQueryService lotQueryService) {
        this.lotQueryService = Objects.requireNonNull(lotQueryService, "lotQueryService");
    }

    /**
     * Lista paginada de alertas de vencimiento para lotes con saldo positivo dentro de la ventana
     * configurada o solicitada. Incluye {@code valuation} solo si el usuario posee
     * {@code catalog:cost:read}.
     */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_EXPIRY_READ)")
    @Operation(
            summary = "Alertas de lotes próximos a vencer",
            description = "Permiso: inventory:expiry:read (y catalog:cost:read opcional para valuation). "
                    + "Excluye lotes con saldo 0 y usa por defecto alerts.expiry.days_ahead del local o global.")
    @ApiResponse(responseCode = "200", description = "Página de alertas de vencimiento")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR si days, paginación u orden son inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public PageResponse<ExpiryAlertResponse> list(
            @Parameter(description = "Ventana en días (>= 0); si se omite usa alerts.expiry.days_ahead.")
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest) {
        Long storeId = StoreContext.require().storeId();
        List<String> rawSort = LotController.resolveRawSort(sort, httpRequest);
        Pageable pageable = PageQuery.of(page, size).toPageable(ALERT_SORT.parse(rawSort));
        return PageResponse.from(
                lotQueryService.findExpiryAlerts(storeId, days, canReadCosts(), pageable),
                ExpiryAlertController::toResponse);
    }

    private static boolean canReadCosts() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> Permissions.CATALOG_COST_READ.equals(a.getAuthority()));
    }

    private static ExpiryAlertResponse toResponse(LotExpiryAlertView view) {
        return new ExpiryAlertResponse(
                view.id(),
                view.productId(),
                view.sku(),
                view.productName(),
                view.lotNumber(),
                view.expiryDate(),
                view.daysRemaining(),
                view.quantity(),
                view.valuation());
    }
}
