package cl.minimarket.pos.sales.web.pos;

import cl.minimarket.pos.sales.application.pos.PosProductResult;
import cl.minimarket.pos.sales.application.pos.PosProductService;
import cl.minimarket.pos.sales.application.pos.ScanResultResponse;
import cl.minimarket.pos.shared.context.RequiresTerminal;
import cl.minimarket.pos.shared.context.StoreContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints POS para búsqueda rápida y resolución de códigos de producto con stock en el local
 * activo ({@code API.md} F.2, {@code PLAN.md} H.3 T32).
 */
@RestController
@RequestMapping("/api/v1/pos/products")
@RequiresTerminal
@Tag(name = "Sales")
public class PosProductController {

    private final PosProductService posProductService;

    public PosProductController(PosProductService posProductService) {
        this.posProductService = posProductService;
    }

    /** Busca productos activos para el POS con su stock en el local activo (máximo 20). */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Buscar productos en POS",
            description = "Permiso: sales:pos:operate. Busca por nombre, SKU, atajo o prefijo de código."
    )
    public List<PosProductResult> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) Integer limit
    ) {
        Long storeId = StoreContext.require().storeId();
        return posProductService.search(storeId, q, limit);
    }

    /** Resuelve un código de barras, balanza, atajo o SKU para el POS. */
    @GetMapping("/resolve")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Resolver código en POS",
            description = "Permiso: sales:pos:operate. Resuelve código de barras, balanza, atajo o SKU."
    )
    public ScanResultResponse resolve(@RequestParam String code) {
        Long storeId = StoreContext.require().storeId();
        return posProductService.resolve(storeId, code);
    }
}
