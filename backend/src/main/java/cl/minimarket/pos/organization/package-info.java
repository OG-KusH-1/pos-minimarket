/**
 * Módulo <b>Organización</b> ({@code organization}).
 *
 * <p>Empresa, locales, terminales y configuración ({@code settings}). Define los puertos {@code StoreUsageGuard} y {@code TerminalUsageGuard}.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Organización", allowedDependencies = {"shared", "audit"})
package cl.minimarket.pos.organization;

import org.springframework.modulith.ApplicationModule;
