/**
 * Módulo <b>Catálogo</b> ({@code catalog}).
 *
 * <p>Productos, códigos de barras, impuestos, packs, precios y configuración de balanza. Define el puerto {@code LotControlGuard}, que implementa {@code inventory}.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}, {@code files}, {@code security}, {@code organization}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Catálogo", allowedDependencies = {"shared", "audit", "files", "security", "organization"})
package cl.minimarket.pos.catalog;

import org.springframework.modulith.ApplicationModule;
