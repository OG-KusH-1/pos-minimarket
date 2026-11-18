package cl.minimarket.pos.inventory.web.stock;

import cl.minimarket.pos.inventory.application.query.StockQueryService;
import cl.minimarket.pos.inventory.application.query.StockQueryService.StockFilter;
import cl.minimarket.pos.inventory.application.query.StockStatus;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Endpoints de consulta de existencias, alertas y límites de stock en el local activo
 * ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/stock")
@Tag(name = "Inventory")
public class StockController {

    private static final SortParser STOCK_SORT = SortParser.allowing(
                    "productId", "sku", "name", "quantity", "minQty", "maxQty", "lastMovementAt")
            .withDefault(Sort.by("name"));

    private final StockQueryService stockQueryService;

    public StockController(StockQueryService stockQueryService) {
        this.stockQueryService = Objects.requireNonNull(stockQueryService, "stockQueryService");
    }

    /** Lista el stock del local activo con filtros opcionales y paginación. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_STOCK_READ)")
    @Operation(summary = "Listar stock del local", description = "Permiso: inventory:stock:read")
    public PageResponse<StockResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) StockStatus status,
            @RequestParam(required = false) Boolean lotControlled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort) {
        Long storeId = StoreContext.require().storeId();
        Pageable pageable = PageQuery.of(page, size).toPageable(STOCK_SORT.parse(sort));
        StockFilter filter = new StockFilter(q, categoryId, status, lotControlled);
        return PageResponse.from(
                stockQueryService.listStock(storeId, filter, pageable, canReadCosts()),
                StockResponse::from);
    }

    /** Obtiene las alertas de stock bajo mínimo, en cero y próximos a vencer del local activo. */
    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_STOCK_READ)")
    @Operation(summary = "Alertas de inventario del local", description = "Permiso: inventory:stock:read")
    public StockAlertResponse alerts() {
        Long storeId = StoreContext.require().storeId();
        return StockAlertResponse.from(stockQueryService.getAlerts(storeId, canReadCosts()));
    }

    /** Consulta el stock de un producto en los locales asignados al usuario y el total empresa. */
    @GetMapping("/{productId}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_STOCK_READ)")
    @Operation(
            summary = "Stock de producto por locales asignados",
            description = "Permiso: inventory:stock:read")
    public StockByStoreResponse getByStore(@PathVariable Long productId) {
        Long storeId = StoreContext.require().storeId();
        Long scopedUserId = resolveScopedUserId();
        return StockByStoreResponse.from(
                stockQueryService.getByStore(storeId, productId, scopedUserId));
    }

    /** Actualiza los límites mínimo y máximo de stock de un producto en el local activo (RN-34). */
    @PutMapping("/{productId}/limits")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_STOCK_LIMITS_UPDATE)")
    @Operation(
            summary = "Actualizar límites de stock",
            description = "Permiso: inventory:stock-limits:update")
    public StockResponse updateLimits(
            @PathVariable Long productId,
            @Valid @RequestBody StockLimitsRequest request) {
        Long storeId = StoreContext.require().storeId();
        return StockResponse.from(stockQueryService.updateLimits(
                storeId,
                productId,
                request.minQty(),
                request.maxQty(),
                canReadCosts()));
    }

    private static boolean canReadCosts() {
        return hasAuthority(Permissions.CATALOG_COST_READ);
    }

    private static Long resolveScopedUserId() {
        if (hasAuthority(Permissions.SECURITY_STORE_ALL_ACCESS)) {
            return null;
        }
        return CurrentUserId.find().orElseThrow(() -> new AccessDeniedException(
                "No se pudo determinar el identificador numérico del usuario autenticado."));
    }

    private static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
