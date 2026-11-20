package cl.minimarket.pos.replenishment.web;

import cl.minimarket.pos.replenishment.application.run.ReplenishmentQueryService.LineView;
import cl.minimarket.pos.replenishment.domain.ReplenishmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Representación HTTP de una línea de sugerencia de reabastecimiento ({@code API.md} F.3
 * {@code ReplenishmentLineResponse}).
 *
 * <p>{@code unitCostNet} y {@code estimatedCost} salen {@code null} sin el permiso
 * {@code catalog:cost:read} (F.1, nota D.2). {@code purchaseUnitName} y
 * {@code unitsPerPurchaseUnit} se resuelven por la asociación producto-proveedor elegida.
 */
@Schema(name = "ReplenishmentLineResponse", description = "Sugerencia de reabastecimiento de un producto.")
public record ReplenishmentLineResponse(
        @Schema(description = "Identificador único de la línea.") Long id,
        @Schema(description = "Producto evaluado.") ProductRef product,
        @Schema(description = "Proveedor elegido (null en NO_SUPPLIER).") SupplierRef supplier,
        @Schema(description = "Nombre de la unidad de compra (null sin proveedor).") String purchaseUnitName,
        @Schema(description = "Unidades base por unidad de compra U (null sin proveedor).")
        BigDecimal unitsPerPurchaseUnit,
        @Schema(description = "Días válidos N de la ventana.") int validDays,
        @Schema(description = "Días censurados Cd (con quiebre).") int censoredDays,
        @Schema(description = "Demanda diaria media D (escala 4).") BigDecimal avgDailyDemand,
        @Schema(description = "Desviación estándar muestral σ (escala 4).") BigDecimal demandStd,
        @Schema(description = "Lead time efectivo L en días.") int leadTimeDays,
        @Schema(description = "Período de revisión R efectivo.") int reviewPeriodDays,
        @Schema(description = "Stock en mano OH.") BigDecimal onHand,
        @Schema(description = "En tránsito IT (OC abiertas + transferencias).") BigDecimal inTransit,
        @Schema(description = "Stock de seguridad SS (escala 4).") BigDecimal safetyStock,
        @Schema(description = "Punto de reorden ROP (escala 4).") BigDecimal reorderPoint,
        @Schema(description = "Nivel objetivo T ya limitado por el tope (escala 4).") BigDecimal targetLevel,
        @Schema(description = "Cantidad sugerida en unidad base (escala 3).") BigDecimal suggestedQty,
        @Schema(description = "Cantidad sugerida en unidades de compra n (escala 3).")
        BigDecimal suggestedPurchaseUnits,
        @Schema(description = "Unidades finales editadas por el usuario, o null si no se editó.")
        BigDecimal finalPurchaseUnits,
        @Schema(description = "Quiebre de stock estimado, o null si D es 0.") LocalDate estimatedStockoutDate,
        @Schema(description = "Días de cobertura OH/D (escala 2), o null si D es 0.") BigDecimal daysOfCover,
        @Schema(description = "Estado de la línea.") ReplenishmentStatus status,
        @Schema(description = "Flags de calidad y ajustes del cálculo.") List<String> flags,
        @Schema(description = "Costo neto por unidad base (null sin permiso catalog:cost:read).")
        BigDecimal unitCostNet,
        @Schema(description = "Costo estimado de la línea (null sin permiso catalog:cost:read).")
        BigDecimal estimatedCost
) {

    /** Referencia breve del producto. */
    @Schema(name = "ReplenishmentLineProduct", description = "Producto de la sugerencia.")
    public record ProductRef(Long id, String sku, String name) {
    }

    /** Referencia breve del proveedor. */
    @Schema(name = "ReplenishmentLineSupplier", description = "Proveedor de la sugerencia.")
    public record SupplierRef(Long id, String name) {
    }

    /**
     * Proyecta desde la vista de servicio.
     *
     * @param view        vista de consulta enriquecida
     * @param includeCosts si el usuario puede ver costos ({@code catalog:cost:read})
     */
    public static ReplenishmentLineResponse from(LineView view, boolean includeCosts) {
        BigDecimal cost = includeCosts ? view.unitCostNet() : null;
        BigDecimal estimatedCost = includeCosts && view.unitCostNet() != null
                ? estimatedCost(view)
                : null;
        return new ReplenishmentLineResponse(
                view.id(),
                new ProductRef(view.productId(), view.productSku(), view.productName()),
                view.supplierId() == null
                        ? null
                        : new SupplierRef(view.supplierId(), view.supplierName()),
                view.purchaseUnitName(),
                view.unitsPerPurchaseUnit(),
                view.validDays(),
                view.censoredDays(),
                view.avgDailyDemand(),
                view.demandStd(),
                view.leadTimeDays(),
                view.reviewPeriodDays(),
                view.onHand(),
                view.inTransit(),
                view.safetyStock(),
                view.reorderPoint(),
                view.targetLevel(),
                view.suggestedQty(),
                view.suggestedPurchaseUnits(),
                view.finalPurchaseUnits(),
                view.estimatedStockoutDate(),
                view.daysOfCover(),
                view.status(),
                view.flags(),
                cost,
                estimatedCost);
    }

    /**
     * Costo estimado de la línea: unidades base efectivas por costo neto unitario, con los
     * redondeos del módulo de compras (cantidad a 3 decimales y total CLP a 0).
     */
    private static BigDecimal estimatedCost(LineView view) {
        BigDecimal units = view.finalPurchaseUnits() != null
                ? view.finalPurchaseUnits()
                : view.suggestedPurchaseUnits();
        BigDecimal unitsPerPurchaseUnit = view.unitsPerPurchaseUnit();
        BigDecimal unitCostNet = view.unitCostNet();
        if (units == null || unitsPerPurchaseUnit == null || unitCostNet == null) {
            return null;
        }
        return units.multiply(unitsPerPurchaseUnit).multiply(unitCostNet)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
