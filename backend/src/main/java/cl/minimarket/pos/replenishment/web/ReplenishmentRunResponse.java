package cl.minimarket.pos.replenishment.web;

import cl.minimarket.pos.replenishment.application.run.ReplenishmentQueryService.RunWithCounts;
import cl.minimarket.pos.replenishment.application.run.ReplenishmentRunStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Representación HTTP de una corrida de reabastecimiento ({@code API.md} F.3
 * {@code ReplenishmentRunResponse}).
 *
 * @param id             identificador de la corrida
 * @param storeId        local de la corrida
 * @param asOfDate       fecha de corte
 * @param status         estado de la corrida
 * @param linesCount     cantidad de líneas evaluadas
 * @param countsByStatus conteo de líneas por estado (solo los estados presentes)
 * @param startedAt      instante de inicio
 * @param finishedAt     instante de término, o {@code null} si no terminó
 * @param error          detalle del error, o {@code null} si completó
 */
@Schema(name = "ReplenishmentRunResponse", description = "Corrida de reabastecimiento.")
public record ReplenishmentRunResponse(
        @Schema(description = "Identificador único de la corrida.") Long id,
        @Schema(description = "Local de la corrida.") Long storeId,
        @Schema(description = "Fecha de corte (la ventana termina el día anterior).") LocalDate asOfDate,
        @Schema(description = "Estado de la corrida.") ReplenishmentRunStatus status,
        @Schema(description = "Cantidad de líneas evaluadas.") int linesCount,
        @Schema(description = "Conteo de líneas por estado (solo los estados presentes).")
        Map<String, Long> countsByStatus,
        @Schema(description = "Instante de inicio (UTC).") Instant startedAt,
        @Schema(description = "Instante de término (UTC).") Instant finishedAt,
        @Schema(description = "Detalle del error si la corrida falló.") String error
) {

    /** Proyecta desde la vista de servicio. */
    public static ReplenishmentRunResponse from(RunWithCounts view) {
        return new ReplenishmentRunResponse(
                view.id(),
                view.storeId(),
                view.asOfDate(),
                view.status(),
                view.linesCount(),
                view.countsByStatus(),
                view.startedAt(),
                view.finishedAt(),
                view.error());
    }
}
