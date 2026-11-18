package cl.minimarket.pos.inventory;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Puerto de salida del módulo de inventario para registrar mermas sistémicas generadas al recibir
 * una transferencia entre locales con cantidad recibida menor a la enviada.
 *
 * <p>Permite mantener desacoplado {@code inventory} de {@code shrinkage}: el módulo {@code
 * shrinkage} provee la implementación real (T63), mientras que {@code inventory} registra un bean
 * no-op por defecto cuando no existe otro candidato.
 */
public interface TransferDifferenceHandler {

    /**
     * Registra la diferencia negativa detectada en la recepción de una línea de transferencia.
     *
     * @param difference datos de la línea recibida con diferencia respecto a lo enviado.
     */
    void handle(TransferDifference difference);

    /**
     * Detalle inmutable de la diferencia producida en una línea de transferencia al momento de su
     * recepción.
     *
     * @param transferId identificador de la transferencia.
     * @param transferNumber folio correlativo de la transferencia.
     * @param transferLineId identificador de la línea de transferencia afectada.
     * @param originStoreId identificador del local de origen (donde se imputa la merma).
     * @param destinationStoreId identificador del local de destino que recepciona.
     * @param productId identificador del producto.
     * @param originLotId identificador del lote de origen descontado, o {@code null} si no aplica.
     * @param differenceQuantity cantidad faltante positiva ({@code qtySent - qtyReceived}).
     * @param unitCost costo unitario registrado en la línea al despachar.
     * @param reason motivo declarado de la diferencia en recepción.
     * @param reportedByUserId identificador del usuario que recepciona y reporta la diferencia.
     * @param occurredAt instante de recepción de la transferencia.
     */
    record TransferDifference(
            Long transferId,
            String transferNumber,
            Long transferLineId,
            Long originStoreId,
            Long destinationStoreId,
            Long productId,
            Long originLotId,
            BigDecimal differenceQuantity,
            BigDecimal unitCost,
            String reason,
            Long reportedByUserId,
            Instant occurredAt) {}
}
