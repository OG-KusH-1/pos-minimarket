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
 * Solicitud de creación o actualización de borrador de transferencia entre locales ({@code API.md}
 * F.3).
 *
 * @param destinationStoreId identificador del local de destino.
 * @param notes observaciones opcionales de la transferencia.
 * @param lines líneas de productos y cantidades a transferir (1 a 300).
 * @param version versión leída previamente para control optimista al actualizar (opcional).
 */
@Schema(
        name = "StockTransferRequest",
        description = "Solicitud de creación o actualización de un borrador de transferencia entre locales.")
public record StockTransferRequest(
        @Schema(description = "Identificador del local de destino (distinto del local activo).")
                @NotNull(message = "El local de destino es obligatorio")
                Long destinationStoreId,
        @Schema(description = "Observaciones opcionales de la transferencia.")
                @Size(max = 2000, message = "Las notas no pueden superar 2000 caracteres")
                String notes,
        @Schema(description = "Líneas de productos y cantidades a transferir (entre 1 y 300).")
                @NotEmpty(message = "Debe incluir al menos una línea")
                @Size(min = 1, max = 300, message = "Máximo 300 líneas por transferencia")
                @Valid
                List<Line> lines,
        @Schema(description = "Versión optimista leída previamente (requerida para control de concurrencia al editar).")
                Long version) {

    /**
     * Línea solicitada dentro de un borrador de transferencia.
     *
     * @param productId identificador del producto.
     * @param qtySent cantidad a transferir (positiva).
     * @param originLotId identificador opcional de lote específico en el local de origen.
     */
    @Schema(
            name = "StockTransferLineRequest",
            description = "Línea de producto y cantidad solicitada dentro de un borrador de transferencia.")
    public record Line(
            @Schema(description = "Identificador del producto a transferir.")
                    @NotNull(message = "El producto es obligatorio")
                    Long productId,
            @Schema(description = "Cantidad solicitada a transferir (> 0).")
                    @NotNull(message = "La cantidad enviada es obligatoria")
                    @DecimalMin(value = "0.001", message = "La cantidad debe ser mayor a cero")
                    BigDecimal qtySent,
            @Schema(description = "Identificador opcional del lote específico en origen; si se omite en productos con lote se aplica FEFO al enviar.")
                    Long originLotId) {}
}
