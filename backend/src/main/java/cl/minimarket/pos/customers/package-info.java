/**
 * Módulo <b>Clientes</b> ({@code customers}).
 *
 * <p>Clientes y cuenta corriente (fiado). Implementa {@code CashFlowContributor} (definido por {@code cash}) para los abonos.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}, {@code security}, {@code cash}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Clientes", allowedDependencies = {"shared", "audit", "security", "cash"})
package cl.minimarket.pos.customers;

import org.springframework.modulith.ApplicationModule;
