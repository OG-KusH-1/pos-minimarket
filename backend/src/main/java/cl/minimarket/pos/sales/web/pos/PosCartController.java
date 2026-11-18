package cl.minimarket.pos.sales.web.pos;

import cl.minimarket.pos.sales.application.pricing.PricingModels.DiscountSpec;
import cl.minimarket.pos.sales.application.pricing.PricingModels.LineInput;
import cl.minimarket.pos.sales.application.pricing.PricingModels.PaymentInput;
import cl.minimarket.pos.sales.application.pricing.PricingModels.QuoteCommand;
import cl.minimarket.pos.sales.application.pricing.PricingModels.QuoteResult;
import cl.minimarket.pos.sales.application.pricing.PricingService;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.RequiresTerminal;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint POS de cotización de carrito sin persistencia ({@code API.md} F.2, {@code PLAN.md} H.3 T32).
 */
@RestController
@RequestMapping("/api/v1/pos/cart")
@RequiresTerminal
@Tag(name = "Sales")
public class PosCartController {

    private final PricingService pricingService;

    public PosCartController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    /** Cotiza un carrito aplicando promociones, descuentos, prorrateo, impuestos, redondeo y overrides. */
    @PostMapping("/quote")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Cotizar carrito POS",
            description = "Permiso: sales:pos:operate. Calcula totales, impuestos, redondeo y overrides requeridos."
    )
    public CartQuoteResponse quote(
            @Valid @RequestBody CartQuoteRequest request,
            Authentication authentication
    ) {
        Long storeId = StoreContext.require().storeId();
        Long userId = CurrentUserId.find().orElse(null);
        boolean canApplyDiscount = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> Permissions.SALES_DISCOUNT_APPLY.equals(a.getAuthority()));

        List<LineInput> lines = request.lines().stream()
                .map(l -> new LineInput(
                        l.productId(),
                        l.quantity(),
                        l.scannedCode(),
                        l.embeddedPrice(),
                        toDiscountSpec(l.manualDiscount())
                ))
                .toList();

        List<PaymentInput> payments = request.payments() == null
                ? List.of()
                : request.payments().stream()
                        .map(p -> new PaymentInput(p.method(), p.amount(), p.tenderedAmount()))
                        .toList();

        QuoteResult result = pricingService.quote(new QuoteCommand(
                storeId,
                userId,
                canApplyDiscount,
                lines,
                toDiscountSpec(request.totalDiscount()),
                request.customerId(),
                payments
        ));

        return CartQuoteResponse.from(result);
    }

    private static DiscountSpec toDiscountSpec(DiscountInput input) {
        if (input == null) {
            return null;
        }
        return new DiscountSpec(input.kind(), input.value(), input.reason());
    }
}
