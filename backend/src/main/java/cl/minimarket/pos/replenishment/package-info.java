/**
 * Módulo <b>Reabastecimiento</b> ({@code replenishment}).
 *
 * <p>Parámetros, corridas y líneas de sugerencia de reabastecimiento ({@code ReplenishmentApi.run}).
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}, {@code catalog}, {@code inventory}, {@code purchasing}, {@code reporting}, {@code organization}.
 * {@code audit} se agregó en T80 (2026-11-19, registrado en {@code CAMBIOS_BASE.md}): las corridas
 * y la generación de borradores son acciones sensibles que se registran con {@code AuditService}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 * </p>
 */
@ApplicationModule(displayName = "Reabastecimiento", allowedDependencies = {"shared", "audit", "catalog", "inventory", "purchasing", "reporting", "organization"})
package cl.minimarket.pos.replenishment;

import org.springframework.modulith.ApplicationModule;
