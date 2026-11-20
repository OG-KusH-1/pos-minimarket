package cl.minimarket.pos.replenishment.domain;

import cl.minimarket.pos.purchasing.ProductSupplierApi.ProductSupplierCandidate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Calculadora de dominio pura para las sugerencias de reabastecimiento ({@code PLAN.md} C.3-C.6).
 *
 * <p>No accede a base de datos, relojes, servicios Spring ni APIs de entrada/salida. Recibe una
 * fotografía completa de los datos de un producto y devuelve todos los valores necesarios para
 * persistir una línea. Cada resultado intermedio con significado de negocio se normaliza a cuatro
 * decimales HALF_UP; las cantidades finales se normalizan a tres. Las operaciones internas de una
 * misma fórmula (por ejemplo, {@code z·σ·√L}) se evalúan con {@link MathContext#DECIMAL64} y se
 * redondean al completar la fórmula, lo que reproduce exactamente los ejemplos de C.7.</p>
 */
public final class ReplenishmentCalculator {

    private static final int INTERMEDIATE_SCALE = 4;
    private static final int QUANTITY_SCALE = 3;
    private static final int DAYS_OF_COVER_SCALE = 2;
    private static final MathContext MATH = MathContext.DECIMAL64;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO_INTERMEDIATE = new BigDecimal("0.0000");
    private static final BigDecimal ZERO_QUANTITY = new BigDecimal("0.000");
    private static final BigDecimal ONE_INTERMEDIATE = new BigDecimal("1.0000");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal MAD_FACTOR = new BigDecimal("1.4826");

    private static final Comparator<ProductSupplierCandidate> SUPPLIER_PRIORITY = Comparator
            .comparing((ProductSupplierCandidate candidate) -> !candidate.isPreferred())
            .thenComparing(ProductSupplierCandidate::unitCostNet)
            .thenComparingInt(ProductSupplierCandidate::effectiveLeadTimeDays)
            .thenComparing(ProductSupplierCandidate::id);

    private ReplenishmentCalculator() {
    }

    /**
     * Selecciona el proveedor según C.4: preferido, menor costo neto efectivo por unidad base,
     * menor lead time y menor identificador de la asociación producto-proveedor.
     *
     * <p>El candidato público ya expone {@code unitCostNet = costPerPurchaseUnit / U}; por eso no
     * se vuelve a dividir ni se introduce una segunda fuente de redondeo.</p>
     *
     * @param candidates candidatos activos, incluidos sus proveedores activos
     * @return candidato de mayor prioridad, o vacío si no existe abastecimiento disponible
     */
    public static Optional<ProductSupplierCandidate> selectSupplier(
            List<ProductSupplierCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream().min(SUPPLIER_PRIORITY);
    }

    /**
     * Evalúa un producto siguiendo el flujo completo de C.5.
     *
     * <p>Los productos inactivos, packs o con el parámetro {@code enabled=false} devuelven
     * {@link ReplenishmentStatus#EXCLUDED} sin analizar historia ni proveedor. Si no hay historia
     * suficiente se fija D=0; si hay demanda cero solo se repone cuando existe un mínimo que aún
     * no fue alcanzado. La ausencia de proveedor no impide calcular: usa U=1, MOQ=1 y L=0, y el
     * resultado positivo queda {@link ReplenishmentStatus#NO_SUPPLIER}.</p>
     *
     * @param input fotografía inmutable de demanda, inventario, parámetros y abastecimiento
     * @return línea calculada, incluidos niveles, cantidades, fecha de quiebre y flags
     */
    public static ReplenishmentResult calculate(ReplenishmentInput input) {
        if (!input.active() || input.pack() || !input.parameters().enabled()) {
            return excludedResult(input);
        }

        ProductSupplierCandidate supplier = selectSupplier(input.suppliers()).orElse(null);
        BigDecimal unitsPerPurchaseUnit = supplier == null
                ? ONE_INTERMEDIATE
                : positiveIntermediate(supplier.unitsPerPurchaseUnit(), "unitsPerPurchaseUnit");
        BigDecimal minimumOrderQuantity = supplier == null
                ? ONE_INTERMEDIATE
                : positiveIntermediate(supplier.minOrderQty(), "minOrderQty");
        int leadTimeDays = supplier == null ? 0 : supplier.effectiveLeadTimeDays();
        if (leadTimeDays < 0) {
            throw new IllegalArgumentException("effectiveLeadTimeDays no puede ser negativo");
        }
        int reviewPeriodDays = reviewPeriod(input.parameters(), supplier);

        DemandStats demand = analyzeHistory(input);
        EnumSet<ReplenishmentFlag> flags = demand.flags();
        if (leadTimeDays == 0) {
            flags.add(ReplenishmentFlag.LEAD_TIME_ZERO);
        }

        BigDecimal onHand = quantity(input.onHand());
        BigDecimal inTransit = quantity(input.inTransit());
        if (onHand.signum() < 0 || inTransit.signum() < 0) {
            throw new IllegalArgumentException("El stock y el tránsito no pueden ser negativos");
        }
        BigDecimal inventoryPosition = intermediate(onHand.add(inTransit, MATH));
        BigDecimal minimumQuantity = nullableQuantity(input.minQuantity());
        BigDecimal maximumQuantity = nullableQuantity(input.maxQuantity());

        Levels levels = calculateLevels(
                input, demand, leadTimeDays, reviewPeriodDays, minimumQuantity, maximumQuantity, flags);
        StockoutEstimate stockout = estimateStockout(input.asOfDate(), onHand, demand.average());
        Calculation calculation = new Calculation(
                input,
                supplier,
                unitsPerPurchaseUnit,
                demand,
                leadTimeDays,
                reviewPeriodDays,
                onHand,
                inTransit,
                levels,
                stockout,
                flags);

        if (demand.average().signum() == 0
                && (minimumQuantity == null || inventoryPosition.compareTo(minimumQuantity) > 0)) {
            flags.add(ReplenishmentFlag.ZERO_DEMAND);
            return calculation.result(ReplenishmentStatus.NO_ACTION, ZERO_INTERMEDIATE);
        }
        if (inventoryPosition.compareTo(levels.reorderPoint()) > 0) {
            return calculation.result(ReplenishmentStatus.NO_ACTION, ZERO_INTERMEDIATE);
        }

        BigDecimal need = intermediate(levels.targetLevel().subtract(inventoryPosition, MATH));
        if (need.signum() <= 0) {
            return calculation.result(ReplenishmentStatus.NO_ACTION, ZERO_INTERMEDIATE);
        }

        BigDecimal purchaseUnits = determinePurchaseUnits(
                need,
                inventoryPosition,
                levels.upperLevel(),
                unitsPerPurchaseUnit,
                minimumOrderQuantity,
                flags);
        ReplenishmentStatus status = supplier == null
                ? ReplenishmentStatus.NO_SUPPLIER
                : ReplenishmentStatus.SUGGESTED;
        return calculation.result(status, purchaseUnits);
    }

    /**
     * Filtra la ventana y calcula demanda. Los días anteriores a la creación no son válidos ni
     * censurados; los días con quiebre sí cuentan para Cd y para determinar SHORT_HISTORY.
     */
    private static DemandStats analyzeHistory(ReplenishmentInput input) {
        ReplenishmentParameters parameters = input.parameters();
        LocalDate from = input.asOfDate().minusDays(parameters.windowDays());
        List<BigDecimal> validQuantities = new ArrayList<>();
        int censoredDays = 0;

        for (DemandDay day : input.demandHistory()) {
            boolean inWindow = !day.saleDate().isBefore(from) && day.saleDate().isBefore(input.asOfDate());
            boolean sinceCreation = !day.saleDate().isBefore(input.productCreatedOn());
            if (!inWindow || !sinceCreation) {
                continue;
            }
            if (day.hadStockout()) {
                censoredDays++;
            } else {
                validQuantities.add(intermediate(day.quantity()));
            }
        }

        EnumSet<ReplenishmentFlag> flags = EnumSet.noneOf(ReplenishmentFlag.class);
        int validDays = validQuantities.size();
        if (censoredDays > 0) {
            flags.add(ReplenishmentFlag.CENSORED_DAYS);
        }
        if (validDays + censoredDays < parameters.windowDays()) {
            flags.add(ReplenishmentFlag.SHORT_HISTORY);
        }
        if (validDays == 0 || validDays < parameters.minHistoryDays()) {
            flags.add(ReplenishmentFlag.NEW_PRODUCT);
            return new DemandStats(validDays, censoredDays, ZERO_INTERMEDIATE, ZERO_INTERMEDIATE, flags);
        }

        List<BigDecimal> adjusted = capOutliers(validQuantities, parameters.outlierK(), flags);
        BigDecimal sum = ZERO_INTERMEDIATE;
        for (BigDecimal quantity : adjusted) {
            sum = intermediate(sum.add(quantity, MATH));
        }
        BigDecimal average = intermediate(sum.divide(BigDecimal.valueOf(validDays), MATH));
        BigDecimal standardDeviation = sampleStandardDeviation(adjusted, average);
        return new DemandStats(validDays, censoredDays, average, standardDeviation, flags);
    }

    /** Aplica el recorte superior mediana/MAD; MAD=0 deja intacta toda la serie. */
    private static List<BigDecimal> capOutliers(
            List<BigDecimal> quantities,
            BigDecimal outlierK,
            Set<ReplenishmentFlag> flags) {
        BigDecimal median = median(quantities);
        List<BigDecimal> absoluteDeviations = quantities.stream()
                .map(quantity -> intermediate(quantity.subtract(median, MATH).abs()))
                .toList();
        BigDecimal medianAbsoluteDeviation = median(absoluteDeviations);
        if (medianAbsoluteDeviation.signum() == 0) {
            return List.copyOf(quantities);
        }

        BigDecimal cap = intermediate(median.add(
                outlierK.multiply(MAD_FACTOR, MATH).multiply(medianAbsoluteDeviation, MATH),
                MATH));
        List<BigDecimal> adjusted = new ArrayList<>(quantities.size());
        for (BigDecimal quantity : quantities) {
            if (quantity.compareTo(cap) > 0) {
                adjusted.add(cap);
                flags.add(ReplenishmentFlag.OUTLIERS_CAPPED);
            } else {
                adjusted.add(quantity);
            }
        }
        return List.copyOf(adjusted);
    }

    /** Calcula la mediana; en series pares promedia los dos valores centrales a escala cuatro. */
    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return intermediate(sorted.get(middle - 1).add(sorted.get(middle), MATH).divide(TWO, MATH));
    }

    /** Desviación muestral de los valores ya recortados y de D ya redondeada; N&lt;2 devuelve cero. */
    private static BigDecimal sampleStandardDeviation(List<BigDecimal> values, BigDecimal average) {
        if (values.size() < 2) {
            return ZERO_INTERMEDIATE;
        }
        BigDecimal squaredDeviations = ZERO_INTERMEDIATE;
        for (BigDecimal value : values) {
            BigDecimal deviation = intermediate(value.subtract(average, MATH));
            BigDecimal square = intermediate(deviation.multiply(deviation, MATH));
            squaredDeviations = intermediate(squaredDeviations.add(square, MATH));
        }
        BigDecimal variance = intermediate(
                squaredDeviations.divide(BigDecimal.valueOf(values.size() - 1L), MATH));
        return intermediate(variance.sqrt(MATH));
    }

    /** Calcula SS, ROP, T y Upper usando siempre los resultados redondeados de las fórmulas previas. */
    private static Levels calculateLevels(
            ReplenishmentInput input,
            DemandStats demand,
            int leadTimeDays,
            int reviewPeriodDays,
            BigDecimal minimumQuantity,
            BigDecimal maximumQuantity,
            Set<ReplenishmentFlag> flags) {
        ReplenishmentParameters parameters = input.parameters();
        BigDecimal statisticalSafety = ZERO_INTERMEDIATE;
        if (leadTimeDays > 0) {
            BigDecimal leadTimeSquareRoot = BigDecimal.valueOf(leadTimeDays).sqrt(MATH);
            statisticalSafety = intermediate(parameters.serviceLevelZ()
                    .multiply(demand.standardDeviation(), MATH)
                    .multiply(leadTimeSquareRoot, MATH));
        }
        BigDecimal minimumSafety = intermediate(
                parameters.safetyMinDays().multiply(demand.average(), MATH));
        BigDecimal safetyStock = statisticalSafety.max(minimumSafety);

        BigDecimal minimumOrZero = minimumQuantity == null ? ZERO_INTERMEDIATE : minimumQuantity;
        BigDecimal reorderPoint = intermediate(demand.average()
                        .multiply(BigDecimal.valueOf(leadTimeDays), MATH)
                        .add(safetyStock, MATH))
                .max(minimumOrZero);
        BigDecimal demandTarget = intermediate(demand.average()
                .multiply(BigDecimal.valueOf(leadTimeDays + reviewPeriodDays), MATH)
                .add(safetyStock, MATH));
        BigDecimal targetLevel = demandTarget.max(minimumOrZero);

        BigDecimal shelfCap = null;
        if (input.shelfLifeDays() != null) {
            shelfCap = intermediate(demand.average()
                    .multiply(BigDecimal.valueOf(input.shelfLifeDays()), MATH)
                    .multiply(parameters.shelfLifeFactor(), MATH));
            if (shelfCap.compareTo(demandTarget) < 0) {
                flags.add(ReplenishmentFlag.SHORT_SHELF_LIFE);
            }
        }
        BigDecimal upperLevel = minimumNullable(maximumQuantity, shelfCap);
        if (upperLevel != null) {
            targetLevel = intermediate(targetLevel.min(upperLevel));
        }
        return new Levels(safetyStock, reorderPoint, targetLevel, upperLevel);
    }

    /**
     * Convierte Need a unidades de compra y aplica MOQ y Upper en el orden exacto de C.3.10.
     * El cociente y el disponible hasta el tope son intermedios de cuatro decimales antes de ceil
     * o floor. Si MOQ obliga a superar Upper, se respeta MOQ: el recorte solo opera cuando n&gt;MOQ.
     */
    private static BigDecimal determinePurchaseUnits(
            BigDecimal need,
            BigDecimal inventoryPosition,
            BigDecimal upperLevel,
            BigDecimal unitsPerPurchaseUnit,
            BigDecimal minimumOrderQuantity,
            Set<ReplenishmentFlag> flags) {
        BigDecimal requiredUnits = intermediate(need.divide(unitsPerPurchaseUnit, MATH));
        BigDecimal purchaseUnits = requiredUnits.setScale(0, RoundingMode.CEILING);
        if (purchaseUnits.compareTo(minimumOrderQuantity) < 0) {
            purchaseUnits = minimumOrderQuantity;
            flags.add(ReplenishmentFlag.MOQ_APPLIED);
        }

        BigDecimal projectedPosition = intermediate(inventoryPosition.add(
                purchaseUnits.multiply(unitsPerPurchaseUnit, MATH), MATH));
        if (upperLevel != null
                && projectedPosition.compareTo(upperLevel) > 0
                && purchaseUnits.compareTo(minimumOrderQuantity) > 0) {
            BigDecimal availableUnits = intermediate(upperLevel.subtract(inventoryPosition, MATH)
                    .divide(unitsPerPurchaseUnit, MATH));
            purchaseUnits = availableUnits.setScale(0, RoundingMode.FLOOR).max(minimumOrderQuantity);
            flags.add(ReplenishmentFlag.CAPPED_BY_UPPER);
        }
        if (purchaseUnits.signum() == 0) {
            purchaseUnits = BigDecimal.ONE.max(minimumOrderQuantity);
        }
        return intermediate(purchaseUnits);
    }

    /** R explícito o default automático de siete días ajustado por frecuencia semanal de despacho. */
    private static int reviewPeriod(
            ReplenishmentParameters parameters,
            ProductSupplierCandidate supplier) {
        if (parameters.reviewPeriodDays() != null) {
            return parameters.reviewPeriodDays();
        }
        int deliveryDays = supplier == null ? 0 : supplier.deliveryWeekdays().size();
        if (deliveryDays == 0) {
            return 7;
        }
        return (7 + deliveryDays - 1) / deliveryDays;
    }

    /** La cobertura se conserva a cuatro decimales para floor y se presenta a dos en la línea. */
    private static StockoutEstimate estimateStockout(
            LocalDate asOfDate,
            BigDecimal onHand,
            BigDecimal averageDemand) {
        if (averageDemand.signum() == 0) {
            return new StockoutEstimate(null, null);
        }
        BigDecimal coverage = intermediate(onHand.divide(averageDemand, MATH));
        long fullDays = coverage.setScale(0, RoundingMode.FLOOR).longValueExact();
        return new StockoutEstimate(
                asOfDate.plusDays(fullDays),
                coverage.setScale(DAYS_OF_COVER_SCALE, ROUNDING));
    }

    private static ReplenishmentResult excludedResult(ReplenishmentInput input) {
        return new ReplenishmentResult(
                input.productId(),
                null,
                null,
                null,
                ZERO_QUANTITY,
                0,
                0,
                ZERO_INTERMEDIATE,
                ZERO_INTERMEDIATE,
                0,
                0,
                quantity(input.onHand()),
                quantity(input.inTransit()),
                ZERO_INTERMEDIATE,
                ZERO_INTERMEDIATE,
                ZERO_INTERMEDIATE,
                ZERO_QUANTITY,
                ZERO_QUANTITY,
                null,
                null,
                null,
                ReplenishmentStatus.EXCLUDED,
                Set.of());
    }

    private static BigDecimal positiveIntermediate(BigDecimal value, String fieldName) {
        BigDecimal normalized = intermediate(quantity(value));
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " debe ser mayor que cero");
        }
        return normalized;
    }

    private static BigDecimal nullableQuantity(BigDecimal value) {
        return value == null ? null : intermediate(quantity(value));
    }

    private static BigDecimal minimumNullable(BigDecimal first, BigDecimal second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.min(second);
    }

    private static BigDecimal intermediate(BigDecimal value) {
        return value.setScale(INTERMEDIATE_SCALE, ROUNDING);
    }

    private static BigDecimal quantity(BigDecimal value) {
        return value.setScale(QUANTITY_SCALE, ROUNDING);
    }

    private record DemandStats(
            int validDays,
            int censoredDays,
            BigDecimal average,
            BigDecimal standardDeviation,
            EnumSet<ReplenishmentFlag> flags) {
    }

    private record Levels(
            BigDecimal safetyStock,
            BigDecimal reorderPoint,
            BigDecimal targetLevel,
            BigDecimal upperLevel) {
    }

    private record StockoutEstimate(LocalDate estimatedDate, BigDecimal daysOfCover) {
    }

    /** Agrupa los valores invariantes para emitir NO_ACTION o una sugerencia sin recalcularlos. */
    private record Calculation(
            ReplenishmentInput input,
            ProductSupplierCandidate supplier,
            BigDecimal unitsPerPurchaseUnit,
            DemandStats demand,
            int leadTimeDays,
            int reviewPeriodDays,
            BigDecimal onHand,
            BigDecimal inTransit,
            Levels levels,
            StockoutEstimate stockout,
            Set<ReplenishmentFlag> flags) {

        private ReplenishmentResult result(ReplenishmentStatus status, BigDecimal purchaseUnits) {
            BigDecimal suggestedQuantity = quantity(
                    purchaseUnits.multiply(unitsPerPurchaseUnit, MATH));
            return new ReplenishmentResult(
                    input.productId(),
                    supplier == null ? null : supplier.supplierId(),
                    supplier == null ? null : supplier.id(),
                    supplier == null ? null : supplier.purchaseUnitName(),
                    quantity(unitsPerPurchaseUnit),
                    demand.validDays(),
                    demand.censoredDays(),
                    demand.average(),
                    demand.standardDeviation(),
                    leadTimeDays,
                    reviewPeriodDays,
                    onHand,
                    inTransit,
                    levels.safetyStock(),
                    levels.reorderPoint(),
                    levels.targetLevel(),
                    suggestedQuantity,
                    quantity(purchaseUnits),
                    supplier == null ? null : intermediate(supplier.unitCostNet()),
                    stockout.estimatedDate(),
                    stockout.daysOfCover(),
                    status,
                    flags);
        }
    }
}
