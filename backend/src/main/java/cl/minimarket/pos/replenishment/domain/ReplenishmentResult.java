package cl.minimarket.pos.replenishment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resultado inmutable del cálculo puro, con las escalas requeridas por {@code replenishment_lines}.
 *
 * <p>Las métricas de demanda y niveles usan cuatro decimales; las cantidades y unidades de compra
 * finales usan tres. {@code daysOfCover} se presenta con dos decimales, como en el modelo de datos,
 * pero la fecha estimada se calcula con el valor intermedio de cuatro decimales para no adelantar
 * un quiebre por redondeo.</p>
 *
 * @param productId producto evaluado
 * @param supplierId proveedor elegido, o {@code null} si no hay candidato
 * @param productSupplierId asociación producto-proveedor elegida, o {@code null}
 * @param purchaseUnitName nombre de la unidad de compra, o {@code null} sin proveedor
 * @param unitsPerPurchaseUnit unidades base por unidad de compra U; uno cuando no hay proveedor
 * @param validDays cantidad N de días no censurados dentro de la historia del producto
 * @param censoredDays cantidad Cd de días con quiebre excluidos
 * @param averageDailyDemand demanda diaria media D, escala 4
 * @param demandStandardDeviation desviación estándar muestral, escala 4
 * @param leadTimeDays lead time efectivo L
 * @param reviewPeriodDays período de revisión R efectivo
 * @param onHand stock en mano, escala 3
 * @param inTransit stock en tránsito, escala 3
 * @param safetyStock stock de seguridad SS, escala 4
 * @param reorderPoint punto de reorden ROP, escala 4
 * @param targetLevel nivel objetivo T ya limitado por el tope superior, escala 4
 * @param suggestedQuantity cantidad sugerida en unidad base, escala 3
 * @param suggestedPurchaseUnits cantidad sugerida en unidades de compra n, escala 3
 * @param unitCostNet costo neto efectivo por unidad base, o {@code null} sin proveedor
 * @param estimatedStockoutDate quiebre estimado, o {@code null} cuando D es cero
 * @param daysOfCover días de cobertura OH/D presentados con escala 2, o {@code null} cuando D es cero
 * @param status resultado de la evaluación
 * @param flags ajustes y advertencias del cálculo, ordenados según el enum
 */
public record ReplenishmentResult(
        Long productId,
        Long supplierId,
        Long productSupplierId,
        String purchaseUnitName,
        BigDecimal unitsPerPurchaseUnit,
        int validDays,
        int censoredDays,
        BigDecimal averageDailyDemand,
        BigDecimal demandStandardDeviation,
        int leadTimeDays,
        int reviewPeriodDays,
        BigDecimal onHand,
        BigDecimal inTransit,
        BigDecimal safetyStock,
        BigDecimal reorderPoint,
        BigDecimal targetLevel,
        BigDecimal suggestedQuantity,
        BigDecimal suggestedPurchaseUnits,
        BigDecimal unitCostNet,
        LocalDate estimatedStockoutDate,
        BigDecimal daysOfCover,
        ReplenishmentStatus status,
        Set<ReplenishmentFlag> flags
) {
    public ReplenishmentResult {
        EnumSet<ReplenishmentFlag> copy = flags.isEmpty()
                ? EnumSet.noneOf(ReplenishmentFlag.class)
                : EnumSet.copyOf(flags);
        flags = Collections.unmodifiableSet(copy);
    }
}
