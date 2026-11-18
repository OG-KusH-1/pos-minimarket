package cl.minimarket.pos.sales.web.suspended;

import cl.minimarket.pos.sales.application.suspended.SuspendedSaleModels.CartData;
import cl.minimarket.pos.sales.application.suspended.SuspendedSaleModels.CartLineData;
import cl.minimarket.pos.sales.application.suspended.SuspendedSaleModels.DiscountData;
import cl.minimarket.pos.sales.application.suspended.SuspendedSaleModels.SuspendCommand;
import cl.minimarket.pos.sales.application.suspended.SuspendedSaleModels.SuspendedSaleDetail;
import cl.minimarket.pos.sales.application.suspended.SuspendedSaleService;
import cl.minimarket.pos.sales.web.pos.CartLineInput;
import cl.minimarket.pos.sales.web.pos.DiscountInput;
import cl.minimarket.pos.shared.context.RequiresTerminal;
import cl.minimarket.pos.shared.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints POS para suspender, listar, retomar y eliminar carritos en espera ({@code API.md} F.2).
 *
 * <p>Todos los endpoints requieren local ({@code X-Store-Id}) y terminal activos
 * ({@code X-Terminal-Id}) mediante {@link RequiresTerminal}.
 */
@RestController
@RequestMapping("/api/v1/pos/suspended-sales")
@Tag(name = "Sales", description = "Operaciones de venta y carritos suspendidos en POS")
@RequiresTerminal
public class SuspendedSaleController {

    private final SuspendedSaleService suspendedSaleService;

    public SuspendedSaleController(SuspendedSaleService suspendedSaleService) {
        this.suspendedSaleService = suspendedSaleService;
    }

    /** Lista las ventas suspendidas vigentes de la terminal activa. */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.SALES_POS_OPERATE + "')")
    @Operation(summary = "Listar ventas suspendidas de la terminal activa")
    public List<SuspendedSaleResponse> list() {
        return suspendedSaleService.listForCurrentTerminal().stream()
                .map(SuspendedSaleController::toResponse)
                .toList();
    }

    /** Suspende el carrito actual de la terminal activa respetando el límite configurado. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.SALES_POS_OPERATE + "')")
    @Operation(summary = "Suspender el carrito actual en la terminal activa")
    public SuspendedSaleResponse suspend(@Valid @RequestBody SuspendSaleRequest request) {
        SuspendedSaleDetail detail = suspendedSaleService.suspend(new SuspendCommand(
                request.label(),
                request.customerId(),
                toCartData(request.cart())
        ));
        return toResponse(detail);
    }

    /** Retoma una venta suspendida vigente de la terminal activa y la elimina de la tabla. */
    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('" + Permissions.SALES_POS_OPERATE + "')")
    @Operation(summary = "Retomar una venta suspendida y liberarla de la cola")
    public SuspendedSaleResponse resume(@PathVariable Long id) {
        return toResponse(suspendedSaleService.resume(id));
    }

    /**
     * Elimina una venta suspendida de la terminal activa. Si pertenece a otro cajero y quien
     * ejecuta no tiene {@code sales:suspended:delete-others}, exige {@code X-Override-Token}
     * válido para {@code SUSPENDED_SALE_DELETE}.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('"
            + Permissions.SALES_POS_OPERATE + "', '"
            + Permissions.SALES_SUSPENDED_DELETE_OTHERS + "')")
    @Operation(summary = "Eliminar una venta suspendida (propia o ajena con permiso/override)")
    public void delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-Override-Token", required = false) String overrideToken
    ) {
        boolean canDeleteOthers = hasAuthority(Permissions.SALES_SUSPENDED_DELETE_OTHERS);
        suspendedSaleService.delete(id, overrideToken, canDeleteOthers);
    }

    private static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    private static CartData toCartData(SuspendedSaleCart cart) {
        List<CartLineData> lines = cart.lines().stream()
                .map(l -> new CartLineData(
                        l.productId(),
                        l.quantity(),
                        l.scannedCode(),
                        l.embeddedPrice(),
                        toDiscountData(l.manualDiscount())))
                .toList();
        return new CartData(lines, toDiscountData(cart.totalDiscount()));
    }

    private static DiscountData toDiscountData(DiscountInput discount) {
        if (discount == null) {
            return null;
        }
        return new DiscountData(discount.kind(), discount.value(), discount.reason());
    }

    private static SuspendedSaleResponse toResponse(SuspendedSaleDetail detail) {
        List<CartLineInput> lines = detail.cart().lines().stream()
                .map(l -> new CartLineInput(
                        l.productId(),
                        l.quantity(),
                        l.scannedCode(),
                        l.embeddedPrice(),
                        toDiscountInput(l.manualDiscount())))
                .toList();
        SuspendedSaleCart cart = new SuspendedSaleCart(
                lines,
                toDiscountInput(detail.cart().totalDiscount()));
        return new SuspendedSaleResponse(
                detail.id(),
                detail.label(),
                detail.userName(),
                detail.customerId(),
                cart,
                detail.itemCount(),
                detail.estimatedTotal(),
                detail.createdAt(),
                detail.expiresAt()
        );
    }

    private static DiscountInput toDiscountInput(DiscountData discount) {
        if (discount == null) {
            return null;
        }
        return new DiscountInput(discount.kind(), discount.value(), discount.reason());
    }
}
