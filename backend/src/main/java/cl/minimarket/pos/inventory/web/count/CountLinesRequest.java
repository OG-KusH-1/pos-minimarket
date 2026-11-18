package cl.minimarket.pos.inventory.web.count;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Solicitud en lote para registrar o actualizar cantidades contadas ({@code API.md} F.3).
 *
 * @param lines listado entre 1 y 500 conteos de línea
 */
@Schema(description = "Actualización en lote de cantidades contadas en una toma abierta")
public record CountLinesRequest(
        @NotEmpty(message = "Debe enviar al menos una línea")
        @Size(min = 1, max = 500, message = "Se permiten hasta 500 líneas por solicitud")
        @Valid
        @Schema(description = "Líneas a registrar o corregir")
        List<LineEntry> lines) {

    /** Par {@code lineId} y cantidad contada no negativa. */
    @Schema(name = "CountLineInputEntry", description = "Cantidad física contada para una línea")
    public record LineEntry(
            @NotNull(message = "lineId es obligatorio")
            @Schema(description = "ID de la línea de toma", example = "201")
            Long lineId,

            @NotNull(message = "countedQty es obligatorio")
            @DecimalMin(value = "0", message = "countedQty no puede ser negativa")
            @Schema(description = "Cantidad física contada", example = "22.000")
            BigDecimal countedQty) {
    }
}
