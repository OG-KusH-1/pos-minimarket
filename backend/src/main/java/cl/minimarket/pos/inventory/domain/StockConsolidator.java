package cl.minimarket.pos.inventory.domain;

import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Valida y consolida las líneas de un {@link StockCommand} agrupándolas por producto y por clave de
 * lote en orden determinista ({@code product_id} ascendente y clave de lote ascendente) conforme a
 * ADR-13 / RN-03.
 *
 * <p>El orden ascendente por {@code product_id} (y por lote dentro de cada producto) garantiza que
 * transacciones concurrentes que afectan múltiples productos o lotes adquieran los bloqueos de fila
 * en PostgreSQL exactamente en la misma secuencia, evitando deadlocks.</p>
 */
public final class StockConsolidator {

    private static final int MAX_REASON_LENGTH = 250;
    private static final int MAX_LOT_NUMBER_LENGTH = 40;

    private StockConsolidator() {
    }

    /**
     * Valida el comando y devuelve sus líneas consolidadas y ordenadas por {@code productId}
     * ascendente, incluyendo el desglose consolidado por lote ({@link ConsolidatedLotLine}).
     *
     * @param cmd comando recibido por {@code StockApi.apply}
     * @return lista inmutable de líneas consolidadas en orden ascendente de {@code productId}
     * @throws BusinessException si el comando tiene líneas vacías, cantidades no positivas, costos
     *                           negativos, motivo demasiado largo o datos de lote inválidos
     */
    public static List<ConsolidatedStockLine> consolidate(StockCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        if (cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El comando de stock debe contener al menos una línea");
        }
        if (cmd.reason() != null && cmd.reason().length() > MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El motivo no puede superar los " + MAX_REASON_LENGTH + " caracteres");
        }

        // TreeMap ordena automáticamente por productId ascendente (RN-03).
        Map<Long, ProductAccumulator> byProduct = new TreeMap<>();
        for (StockLine line : cmd.lines()) {
            BigDecimal qty = Rounding.quantity(line.quantity());
            if (qty.signum() <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "La cantidad de stock debe ser mayor que cero para el producto " + line.productId());
            }
            BigDecimal cost = null;
            if (line.unitCost() != null) {
                if (line.unitCost().signum() < 0) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "El costo unitario no puede ser negativo para el producto " + line.productId());
                }
                cost = Rounding.unitCost(line.unitCost());
            }
            String normalizedLotNumber = normalizeLotNumber(line.lotNumber());
            if (normalizedLotNumber != null && normalizedLotNumber.length() > MAX_LOT_NUMBER_LENGTH) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "El número de lote no puede superar los " + MAX_LOT_NUMBER_LENGTH + " caracteres");
            }
            if (line.lotId() == null && normalizedLotNumber == null && line.expiryDate() != null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "No se puede indicar fecha de vencimiento sin especificar lotId o lotNumber");
            }

            LotKey lotKey = LotKey.of(line.lotId(), normalizedLotNumber, line.expiryDate());
            byProduct.computeIfAbsent(line.productId(), id -> new ProductAccumulator())
                    .add(lotKey, qty, cost, line.sourceLineId());
        }

        return byProduct.entrySet().stream()
                .map(entry -> entry.getValue().toLine(entry.getKey()))
                .toList();
    }

    private static String normalizeLotNumber(String lotNumber) {
        if (lotNumber == null) {
            return null;
        }
        String trimmed = lotNumber.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private enum LotKeyKind {
        EXPLICIT_ID,
        LOT_NUMBER_AND_EXPIRY,
        UNSPECIFIED
    }

    private record LotKey(LotKeyKind kind, Long lotId, String lotNumber, LocalDate expiryDate)
            implements Comparable<LotKey> {

        private static final Comparator<LotKey> COMPARATOR = Comparator
                .comparing(LotKey::kind)
                .thenComparing(LotKey::lotId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LotKey::lotNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LotKey::expiryDate, Comparator.nullsLast(Comparator.naturalOrder()));

        static LotKey of(Long lotId, String lotNumber, LocalDate expiryDate) {
            if (lotId != null) {
                return new LotKey(LotKeyKind.EXPLICIT_ID, lotId, lotNumber, expiryDate);
            }
            if (lotNumber != null) {
                return new LotKey(LotKeyKind.LOT_NUMBER_AND_EXPIRY, null, lotNumber, expiryDate);
            }
            return new LotKey(LotKeyKind.UNSPECIFIED, null, null, null);
        }

        @Override
        public int compareTo(LotKey other) {
            return COMPARATOR.compare(this, other);
        }
    }

    private static final class CostAccumulator {
        private BigDecimal totalQuantity = BigDecimal.ZERO;
        private BigDecimal weightedCostSum = BigDecimal.ZERO;
        private boolean hasExplicitCost = false;
        private boolean missingExplicitCost = false;

        void add(BigDecimal qty, BigDecimal unitCost) {
            totalQuantity = totalQuantity.add(qty);
            if (unitCost != null) {
                hasExplicitCost = true;
                weightedCostSum = weightedCostSum.add(qty.multiply(unitCost));
            } else {
                missingExplicitCost = true;
            }
        }

        BigDecimal resolveWeightedCost(Long productId) {
            if (hasExplicitCost && !missingExplicitCost) {
                return Rounding.unitCost(
                        weightedCostSum.divide(totalQuantity, Rounding.INTERMEDIATE));
            }
            if (hasExplicitCost) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Líneas consolidadas del producto " + productId
                                + " mezclan costo explícito y costo promedio vigente");
            }
            return null;
        }
    }

    private static final class ProductAccumulator {
        private final CostAccumulator productCost = new CostAccumulator();
        private final Map<LotKey, CostAccumulator> byLot = new TreeMap<>();
        private Long sourceLineId;
        private boolean sourceLineConflict = false;
        private boolean initialized = false;

        void add(LotKey lotKey, BigDecimal qty, BigDecimal unitCost, Long lineId) {
            productCost.add(qty, unitCost);
            byLot.computeIfAbsent(lotKey, k -> new CostAccumulator()).add(qty, unitCost);

            if (!initialized) {
                sourceLineId = lineId;
                initialized = true;
            } else if (!Objects.equals(sourceLineId, lineId)) {
                sourceLineConflict = true;
            }
        }

        ConsolidatedStockLine toLine(Long productId) {
            BigDecimal resolvedCost = productCost.resolveWeightedCost(productId);
            Long resolvedSourceLineId = sourceLineConflict ? null : sourceLineId;
            List<ConsolidatedLotLine> lotLines = byLot.entrySet().stream()
                    .map(entry -> {
                        LotKey key = entry.getKey();
                        CostAccumulator acc = entry.getValue();
                        return new ConsolidatedLotLine(
                                key.lotId(),
                                key.lotNumber(),
                                key.expiryDate(),
                                Rounding.quantity(acc.totalQuantity),
                                acc.resolveWeightedCost(productId));
                    })
                    .toList();

            return new ConsolidatedStockLine(
                    productId,
                    Rounding.quantity(productCost.totalQuantity),
                    resolvedCost,
                    resolvedSourceLineId,
                    lotLines);
        }
    }
}
