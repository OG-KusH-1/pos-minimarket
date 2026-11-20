package cl.minimarket.pos.replenishment.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Solicitud de generación de borradores de OC desde una corrida ({@code API.md} F.2
 * {@code POST /replenishment/runs/{id}/purchase-orders}).
 *
 * <p>Sin filtros genera los borradores de todas las líneas {@code ACCEPTED} de la corrida;
 * si no hay ninguna, de las {@code SUGGESTED}. Con {@code lineIds} limita la generación a esas
 * líneas (todas deben pertenecer a la corrida, estar en {@code SUGGESTED} o {@code ACCEPTED},
 * tener proveedor y cantidad efectiva mayor que cero).
 */
@Schema(name = "GenerateDraftsRequest", description = "Generación de borradores de OC por líneas de sugerencia.")
public record GenerateDraftsRequest(
        @Schema(description = "Identificadores de líneas a incluir; vacío u omitido = comportamiento por defecto.")
        List<@NotNull(message = "El identificador de línea no puede ser nulo.") Long> lineIds
) {
}
