/**
 * Módulo <b>Seguridad</b> ({@code security}).
 *
 * <p>Login, tokens JWT y refresh, usuarios, roles, permisos, límites por rol y overrides de supervisor. Implementa {@code StoreAccessValidator} (definido por {@code shared}).
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}, {@code organization}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Seguridad", allowedDependencies = {"shared", "audit", "organization"})
package cl.minimarket.pos.security;

import org.springframework.modulith.ApplicationModule;
