/**
 * Módulo <b>Mermas</b> ({@code shrinkage}).
 *
 * <p>Registro de mermas, incluidas las sistémicas (diferencias de transferencia y devoluciones sin reingreso). Implementa {@code TransferDifferenceHandler} (definido por {@code inventory}).
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}, {@code inventory}, {@code catalog}, {@code files}, {@code security}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Mermas", allowedDependencies = {"shared", "audit", "inventory", "catalog", "files", "security"})
package cl.minimarket.pos.shrinkage;

import org.springframework.modulith.ApplicationModule;
