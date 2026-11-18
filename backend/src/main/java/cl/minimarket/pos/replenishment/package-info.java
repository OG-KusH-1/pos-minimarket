/**
 * Módulo <b>Reabastecimiento</b> ({@code replenishment}).
 *
 * <p>Parámetros, corridas y líneas de sugerencia de reabastecimiento ({@code ReplenishmentApi.run}).
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code catalog}, {@code inventory}, {@code purchasing}, {@code reporting}, {@code organization}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Reabastecimiento", allowedDependencies = {"shared", "catalog", "inventory", "purchasing", "reporting", "organization"})
package cl.minimarket.pos.replenishment;

import org.springframework.modulith.ApplicationModule;
