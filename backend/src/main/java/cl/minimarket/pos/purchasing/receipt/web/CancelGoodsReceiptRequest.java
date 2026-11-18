package cl.minimarket.pos.purchasing.receipt.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de cancelación de una recepción de mercadería ({@code API.md} F.3 {@code CancelRequest}).
 */
@Schema(name = "GoodsReceiptCancelRequest", description = "Motivo de cancelación de la recepción de mercadería.")
public record CancelGoodsReceiptRequest(
        @Schema(description = "Motivo de cancelación (3 a 250 caracteres).", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 3, max = 250)
        String reason
) {
}
