/**
 * Módulo <b>Caja</b> ({@code cash}).
 *
 * <p>Turnos de caja, movimientos y arqueo. Define {@code CashFlowContributor} (inversión de dependencia: {@code sales} y {@code customers} lo implementan, evitando el ciclo cash-sales).
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}, {@code security}, {@code organization}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Caja", allowedDependencies = {"shared", "audit", "security", "organization"})
package cl.minimarket.pos.cash;

import org.springframework.modulith.ApplicationModule;
