package cl.minimarket.pos.shrinkage.application;

import cl.minimarket.pos.shrinkage.ShrinkageType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Comando de aplicación para registrar una merma manual ({@code API.md} F.3).
 *
 * @param productId identificador del producto
 * @param lotId identificador del lote (obligatorio si el producto controla lotes)
 * @param shrinkageType tipo de merma (distinto de {@link ShrinkageType#TRANSFER_DIFFERENCE})
 * @param quantity cantidad positiva mermada
 * @param reason motivo opcional (obligatorio para {@code THEFT_LOSS} e {@code INTERNAL_CONSUMPTION})
 * @param evidenceFileId identificador del archivo de evidencia en {@code stored_files}, opcional
 * @param responsibleUserId identificador del usuario responsable
 * @param occurredAt instante en que ocurrió la merma (no futuro)
 */
public record CreateShrinkageCommand(
        Long productId,
        Long lotId,
        ShrinkageType shrinkageType,
        BigDecimal quantity,
        String reason,
        Long evidenceFileId,
        Long responsibleUserId,
        Instant occurredAt) {
}
