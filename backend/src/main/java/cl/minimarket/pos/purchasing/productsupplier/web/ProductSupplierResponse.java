package cl.minimarket.pos.purchasing.productsupplier.web;

import cl.minimarket.pos.purchasing.productsupplier.application.ProductSupplierQueryService.ProductSupplierView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Representación HTTP de una asociación producto-proveedor ({@code API.md} F.3 {@code ProductSupplierResponse}).
 *
 * <p>{@code costPerPurchaseUnit} y {@code unitCostNet} se omiten ({@code null}) cuando el usuario no
 * posee {@code catalog:cost:read}.
 */
@Schema(name = "ProductSupplierResponse", description = "Detalle de la asociación producto-proveedor.")
public record ProductSupplierResponse(
        @Schema(description = "Identificador único de la asociación.") Long id,
        @Schema(description = "Identificador del producto.") Long productId,
        @Schema(description = "SKU del producto.") String productSku,
        @Schema(description = "Nombre del producto.") String productName,
        @Schema(description = "Identificador del proveedor.") Long supplierId,
        @Schema(description = "Nombre (razón social) del proveedor.") String supplierName,
        @Schema(description = "SKU asignado por el proveedor.") String supplierSku,
        @Schema(description = "Nombre de la unidad de compra.") String purchaseUnitName,
        @Schema(description = "Unidades base por unidad de compra.") BigDecimal unitsPerPurchaseUnit,
        @Schema(description = "Costo neto por unidad de compra (null sin permiso catalog:cost:read).")
        BigDecimal costPerPurchaseUnit,
        @Schema(description = "Costo neto por unidad base (null sin permiso catalog:cost:read).")
        BigDecimal unitCostNet,
        @Schema(description = "Cantidad mínima de pedido en unidades de compra.") BigDecimal minOrderQty,
        @Schema(description = "Lead time específico en días (null usa el del proveedor).")
        Integer leadTimeDaysOverride,
        @Schema(description = "Indica si es el proveedor preferido del producto.") boolean isPreferred,
        @Schema(description = "Fecha/hora UTC de última compra registrada.") Instant lastPurchaseAt,
        @Schema(description = "Indica si la asociación está activa.") boolean active,
        @Schema(description = "Versión de concurrencia optimista.") Long version
) {

    /** Proyecta desde la vista de lectura/servicio {@link ProductSupplierView}. */
    public static ProductSupplierResponse from(ProductSupplierView view) {
        return new ProductSupplierResponse(
                view.id(),
                view.productId(),
                view.productSku(),
                view.productName(),
                view.supplierId(),
                view.supplierName(),
                view.supplierSku(),
                view.purchaseUnitName(),
                view.unitsPerPurchaseUnit(),
                view.costPerPurchaseUnit(),
                view.unitCostNet(),
                view.minOrderQty(),
                view.leadTimeDaysOverride(),
                view.isPreferred(),
                view.lastPurchaseAt(),
                view.active(),
                view.version()
        );
    }
}
