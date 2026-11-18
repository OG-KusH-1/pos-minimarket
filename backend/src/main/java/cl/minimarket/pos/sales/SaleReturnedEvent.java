package cl.minimarket.pos.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Evento de dominio emitido al registrar una anulación o devolución de venta ({@code PLAN.md} A.4).
 *
 * <p>Creado en T36 como contrato público del módulo {@code sales} para {@code billing} (emisión de
 * nota de crédito SII tipo 61); será publicado por T71 al implementar anulaciones y devoluciones.
 *
 * @param saleReturnId  identificador de la devolución o anulación ({@code sale_returns.id})
 * @param number        folio interno ({@code D-001-...})
 * @param saleId        identificador de la venta original
 * @param saleNumber    folio interno de la venta original
 * @param returnType    tipo ({@code VOID} o {@code RETURN})
 * @param storeId       identificador del local
 * @param terminalId    identificador de la terminal POS
 * @param cashSessionId identificador del turno de caja donde ocurre el reembolso
 * @param createdBy     identificador del usuario que registra la devolución
 * @param createdAt     instante de registro
 * @param businessDate  fecha comercial del turno
 * @param refundTotal   monto total reembolsado
 * @param lines         líneas devueltas con su cantidad y monto reembolsado
 */
public record SaleReturnedEvent(
        Long saleReturnId,
        String number,
        Long saleId,
        String saleNumber,
        String returnType,
        Long storeId,
        Long terminalId,
        Long cashSessionId,
        Long createdBy,
        Instant createdAt,
        LocalDate businessDate,
        BigDecimal refundTotal,
        List<Line> lines
) {

    public SaleReturnedEvent {
        Objects.requireNonNull(saleReturnId, "saleReturnId");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(cashSessionId, "cashSessionId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(businessDate, "businessDate");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * Línea incluida en la devolución o anulación.
     */
    public record Line(
            Long saleLineId,
            Long productId,
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal refundAmount
    ) {
    }
}
