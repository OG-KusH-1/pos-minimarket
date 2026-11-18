package cl.minimarket.pos.inventory.web.transfer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Solicitud de recepción en destino de una transferencia enviada ({@code API.md} F.3).
 *
 * @param lines detalle de cantidades efectivamente recibidas por línea de transferencia.
 */
@Schema(
        name = "TransferReceiveRequest",
        description = "Solicitud de recepción en el local de destino de una transferencia enviada.")
public record TransferReceiveRequest(
        @Schema(description = "Detalle de cantidades recibidas para todas las líneas de la transferencia.")
                @NotEmpty(message = "Debe informar las líneas recibidas")
                @Size(min = 1, max = 300, message = "Máximo 300 líneas por recepción")
                @Valid
                List<Line> lines) {

    /**
     * Recepción informada para una línea de transferencia.
     *
     * @param lineId identificador de {@code stock_transfer_lines}.
     * @param qtyReceived cantidad efectivamente recibida ({@code >= 0}).
     * @param differenceReason motivo obligatorio cuando {@code qtyReceived < qtySent}.
     */
    @Schema(
            name = "TransferReceiveLineRequest",
            description = "Cantidad recibida y motivo de diferencia para una línea de transferencia.")
    public record Line(
            @Schema(description = "Identificador de la línea de transferencia.")
                    @NotNull(message = "El identificador de línea es obligatorio")
                    Long lineId,
            @Schema(description = "Cantidad efectivamente recibida en destino (entre 0 y qtySent).")
                    @NotNull(message = "La cantidad recibida es obligatoria")
                    @DecimalMin(value = "0", message = "La cantidad recibida no puede ser negativa")
                    BigDecimal qtyReceived,
            @Schema(description = "Motivo obligatorio cuando la cantidad recibida es menor a la enviada.")
                    @Size(max = 250, message = "El motivo de diferencia no puede superar 250 caracteres")
                    String differenceReason) {}
}
