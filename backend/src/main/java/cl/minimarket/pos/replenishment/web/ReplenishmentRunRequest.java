package cl.minimarket.pos.replenishment.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Solicitud de corrida de reabastecimiento ({@code API.md} F.3 {@code RunRequest}):
 * {@code asOfDate ≤ hoy}, con la fecha de negocio actual como default.
 */
@Schema(name = "RunRequest", description = "Ejecuta una corrida síncrona de reabastecimiento.")
public record ReplenishmentRunRequest(
        @Schema(description = "Fecha de corte; la ventana termina el día anterior. Por defecto, hoy de negocio.")
        LocalDate asOfDate
) {
}
