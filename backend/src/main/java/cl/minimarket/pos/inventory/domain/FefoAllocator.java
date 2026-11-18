package cl.minimarket.pos.inventory.domain;

import cl.minimarket.pos.inventory.LotAllocation;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Regla de dominio pura para asignar salidas de inventario bajo criterio FEFO
 * (First-Expired, First-Out: primero el lote con fecha de vencimiento más próxima y, a igualdad o
 * ausencia de fecha, {@code SIN-LOTE} / lotes sin vencimiento al final por orden de {@code id}).
 *
 * <p>Reutilizable tanto en la ejecución transaccional de {@code StockService} (sobre filas
 * bloqueadas con {@code SELECT ... FOR UPDATE}) como en las consultas de sugerencia FEFO para el
 * punto de venta.</p>
 */
public final class FefoAllocator {

    private FefoAllocator() {
    }

    /**
     * Representa un lote candidato ya ordenado según criterio FEFO
     * ({@code expiry_date ASC NULLS LAST, id ASC}).
     *
     * @param lotId identificador del lote
     * @param lotNumber código de lote
     * @param expiryDate fecha de vencimiento (puede ser {@code null}, p. ej. {@code SIN-LOTE})
     * @param availableQuantity saldo disponible en el lote (en la unidad base del producto)
     */
    public record AvailableLot(
            Long lotId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal availableQuantity) {

        public AvailableLot {
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(lotNumber, "lotNumber");
            Objects.requireNonNull(availableQuantity, "availableQuantity");
        }
    }

    /**
     * Resultado de asignación sobre un lote específico tras aplicar el consumo solicitado.
     *
     * @param lotId identificador del lote consumido
     * @param lotNumber código de lote
     * @param expiryDate fecha de vencimiento del lote
     * @param allocatedQuantity cantidad asignada/descontada de este lote
     * @param balanceAfter saldo remanente en el lote tras la deducción
     */
    public record LotDeduction(
            Long lotId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal allocatedQuantity,
            BigDecimal balanceAfter) {

        /**
         * Convierte la deducción al DTO público del contrato {@code StockApi}.
         *
         * @return asignación pública de lote
         */
        public LotAllocation toLotAllocation() {
            return new LotAllocation(lotId, allocatedQuantity);
        }
    }

    /**
     * Resultado consolidado de una sugerencia FEFO, incluyendo las asignaciones parciales o totales
     * y el faltante no cubierto por los lotes disponibles.
     *
     * @param deductions asignaciones ordenadas por vencimiento más próximo
     * @param shortage cantidad faltante (cero si los lotes cubren la demanda completa)
     */
    public record FefoAllocationResult(
            List<LotDeduction> deductions,
            BigDecimal shortage) {

        public FefoAllocationResult {
            deductions = List.copyOf(deductions);
            Objects.requireNonNull(shortage, "shortage");
        }
    }

    /**
     * Calcula una sugerencia de consumo FEFO sobre {@code orderedLots} sin lanzar excepción cuando
     * el saldo disponible es menor a {@code requestedQuantity}.
     *
     * @param requestedQuantity cantidad total positiva solicitada
     * @param orderedLots lotes disponibles previamente ordenados por
     *     {@code expiry_date ASC NULLS LAST, id ASC}
     * @return asignaciones parciales o totales en orden FEFO y cantidad faltante
     */
    public static FefoAllocationResult suggest(
            BigDecimal requestedQuantity,
            List<AvailableLot> orderedLots) {
        Objects.requireNonNull(requestedQuantity, "requestedQuantity");
        Objects.requireNonNull(orderedLots, "orderedLots");

        BigDecimal remaining = Rounding.quantity(requestedQuantity);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "La cantidad a asignar por FEFO debe ser mayor a cero");
        }

        List<LotDeduction> deductions = new ArrayList<>();
        for (AvailableLot lot : orderedLots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal available = Rounding.quantity(lot.availableQuantity());
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal take = available.min(remaining);
            BigDecimal balanceAfter = Rounding.quantity(available.subtract(take));
            deductions.add(new LotDeduction(
                    lot.lotId(),
                    lot.lotNumber(),
                    lot.expiryDate(),
                    take,
                    balanceAfter));
            remaining = Rounding.quantity(remaining.subtract(take));
        }

        return new FefoAllocationResult(deductions, remaining);
    }

    /**
     * Distribuye {@code requestedQuantity} entre los {@code orderedLots} entregados en orden FEFO.
     *
     * @param productId identificador del producto (para diagnóstico en error)
     * @param requestedQuantity cantidad total positiva a descontar
     * @param orderedLots lotes disponibles previamente ordenados por
     *     {@code expiry_date ASC NULLS LAST, id ASC}
     * @return lista inmutable de deducciones por cada lote utilizado
     * @throws BusinessException con {@link ErrorCode#STOCK_INSUFFICIENT} si la suma disponible
     *     entre los lotes no alcanza para cubrir {@code requestedQuantity}
     */
    public static List<LotDeduction> allocate(
            Long productId,
            BigDecimal requestedQuantity,
            List<AvailableLot> orderedLots) {
        Objects.requireNonNull(productId, "productId");
        FefoAllocationResult result = suggest(requestedQuantity, orderedLots);
        if (result.shortage().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(
                    ErrorCode.STOCK_INSUFFICIENT,
                    "Saldo insuficiente en lotes disponibles para el producto id=" + productId
                            + "; faltante=" + result.shortage());
        }
        return result.deductions();
    }
}
