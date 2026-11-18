package cl.minimarket.pos.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Evento de dominio publicado al completarse una venta ({@code PLAN.md} A.4, ADR-19).
 *
 * <p>Lo consume de forma asíncrona ({@code @ApplicationModuleListener}) el módulo {@code billing}
 * para emitir la boleta electrónica (39 o 41) y futuros consumidores de reporte o fidelización sin
 * generar dependencias cíclicas hacia {@code sales}.
 *
 * @param saleId             identificador de la venta creada
 * @param number             folio interno de venta ({@code V-001-...})
 * @param storeId            identificador del local
 * @param terminalId         identificador de la terminal POS
 * @param cashSessionId      identificador del turno de caja
 * @param cashierUserId      identificador del cajero
 * @param customerId         identificador del cliente asociado (opcional)
 * @param soldAt             instante de emisión
 * @param businessDate       fecha comercial del turno
 * @param subtotalGross      subtotal bruto antes de descuentos
 * @param discountTotal      total de descuentos aplicados
 * @param totalGross         total bruto después de descuentos
 * @param netTotal           total neto afecto
 * @param taxTotal           total de impuestos
 * @param exemptTotal        total exento de IVA
 * @param roundingAdjustment ajuste de redondeo de efectivo en {@code [-5, +4]}
 * @param totalPayable       total a pagar ({@code totalGross + roundingAdjustment})
 * @param lines              líneas vendidas con su desglose neto/impuesto/exento
 */
public record SaleCompletedEvent(
        Long saleId,
        String number,
        Long storeId,
        Long terminalId,
        Long cashSessionId,
        Long cashierUserId,
        Long customerId,
        Instant soldAt,
        LocalDate businessDate,
        BigDecimal subtotalGross,
        BigDecimal discountTotal,
        BigDecimal totalGross,
        BigDecimal netTotal,
        BigDecimal taxTotal,
        BigDecimal exemptTotal,
        BigDecimal roundingAdjustment,
        BigDecimal totalPayable,
        List<Line> lines
) {

    public SaleCompletedEvent {
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(cashSessionId, "cashSessionId");
        Objects.requireNonNull(cashierUserId, "cashierUserId");
        Objects.requireNonNull(soldAt, "soldAt");
        Objects.requireNonNull(businessDate, "businessDate");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * Detalle de línea de venta incluido en {@link SaleCompletedEvent}.
     */
    public record Line(
            int lineNumber,
            Long productId,
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal unitPriceGross,
            BigDecimal lineTotalGross,
            BigDecimal lineNet,
            BigDecimal lineTax,
            BigDecimal lineExempt
    ) {
    }
}
