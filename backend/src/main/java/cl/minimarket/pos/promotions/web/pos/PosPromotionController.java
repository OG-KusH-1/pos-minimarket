package cl.minimarket.pos.promotions.web.pos;

import cl.minimarket.pos.promotions.application.PromotionService;
import cl.minimarket.pos.shared.context.StoreContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Endpoint POS de promociones vigentes en el local y la hora actuales ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/pos/promotions")
@Tag(name = "Promotions")
public class PosPromotionController {

    private final PromotionService promotionService;

    public PosPromotionController(PromotionService promotionService) {
        this.promotionService = Objects.requireNonNull(promotionService, "promotionService");
    }

    /** Lista las promociones vigentes en el local activo y la hora actual. */
    @GetMapping("/active")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Promociones vigentes en POS",
            description = "Permiso: sales:pos:operate. Requiere header X-Store-Id.")
    @ApiResponse(responseCode = "200", description = "Lista de promociones vigentes en el local y la hora actuales")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public List<ActivePromotionResponse> active() {
        Long storeId = StoreContext.require().storeId();
        return promotionService.listActiveForPos(storeId).stream()
                .map(ActivePromotionResponse::from)
                .toList();
    }
}
