package cl.minimarket.pos.inventory.web.stock;

import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.application.query.KardexQueryService;
import cl.minimarket.pos.inventory.application.query.KardexQueryService.KardexFilter;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Endpoint de solo lectura para consultar el kardex ({@code stock_movements}) de un producto en el
 * local activo ({@code API.md} F.2, HU-INV-02).
 */
@RestController
@RequestMapping("/api/v1/kardex")
@Tag(name = "Inventory")
public class KardexController {

    private static final SortParser KARDEX_SORT = SortParser.allowing(
                    "id", "occurredAt", "movementType", "quantityDelta", "balanceAfter")
            .withDefault(Sort.by(Sort.Direction.DESC, "occurredAt"));

    private final KardexQueryService kardexQueryService;

    public KardexController(KardexQueryService kardexQueryService) {
        this.kardexQueryService = Objects.requireNonNull(kardexQueryService, "kardexQueryService");
    }

    /** Lista los movimientos de kardex de un producto en el local activo. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_KARDEX_READ)")
    @Operation(summary = "Consultar kardex de producto", description = "Permiso: inventory:kardex:read")
    public PageResponse<StockMovementResponse> list(
            @RequestParam Long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) StockMovementType movementType,
            @RequestParam(required = false) Long lotId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort) {
        Long storeId = StoreContext.require().storeId();
        Pageable pageable = PageQuery.of(page, size).toPageable(KARDEX_SORT.parse(sort));
        KardexFilter filter = new KardexFilter(productId, from, to, movementType, lotId);
        return PageResponse.from(
                kardexQueryService.listMovements(storeId, filter, pageable, canReadCosts()),
                StockMovementResponse::from);
    }

    private static boolean canReadCosts() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> Permissions.CATALOG_COST_READ.equals(a.getAuthority()));
    }
}
