package cl.minimarket.pos.shrinkage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * API pública del módulo {@code shrinkage} para que otros módulos (como {@code sales}) registren
 * mermas sistémicas ({@code PLAN.md} A.4, {@code MODELO_DATOS.md} B.6).
 */
public interface ShrinkageApi {

    /**
     * Registra una merma sistémica de tipo {@link ShrinkageType#DAMAGED} originada por una línea de
     * devolución de venta sin reingreso a stock ({@code restock = false}).
     *
     * <p>La merma nace siempre en {@link ShrinkageStatus#PENDING_APPROVAL}, con
     * {@code affects_stock = false} y {@code auto_approved = false}, por lo que nunca genera
     * movimiento de inventario al aprobarse.
     *
     * @param command datos de la línea de devolución sin reingreso
     * @return identificador de la merma creada
     */
    Long createFromSaleReturn(SaleReturnShrinkageCommand command);

    /**
     * Comando para crear una merma sistémica desde una devolución de venta sin reingreso.
     *
     * @param storeId identificador del local donde ocurrió la devolución
     * @param productId identificador del producto base
     * @param lotId identificador del lote asignado en la venta original, o {@code null}
     * @param saleReturnLineId identificador de la línea de devolución en {@code sale_return_lines}
     * @param quantity cantidad positiva devuelta sin reingreso
     * @param unitCost costo unitario neto; si es {@code null} se usa el {@code avg_cost} vigente
     * @param reason motivo opcional de la devolución/daño
     * @param reportedByUserId identificador del usuario que registra la devolución
     * @param occurredAt instante de la devolución; si es {@code null} se usa el reloj de negocio
     */
    record SaleReturnShrinkageCommand(
            Long storeId,
            Long productId,
            Long lotId,
            Long saleReturnLineId,
            BigDecimal quantity,
            BigDecimal unitCost,
            String reason,
            Long reportedByUserId,
            Instant occurredAt) {

        public SaleReturnShrinkageCommand {
            Objects.requireNonNull(storeId, "storeId");
            Objects.requireNonNull(productId, "productId");
            Objects.requireNonNull(saleReturnLineId, "saleReturnLineId");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(reportedByUserId, "reportedByUserId");
        }
    }
}
