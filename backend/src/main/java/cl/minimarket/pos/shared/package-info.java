/**
 * Módulo <b>Shared</b> ({@code shared}).
 *
 * <p>Kernel compartido y abierto: dinero ({@code Money}, {@code Quantity}, redondeos), RUT, catálogo de errores RFC 9457, paginación, {@code StoreContext}, idempotencia, secuencias, reloj de negocio y utilidades de exportación. No depende de ningún otro módulo; lo usan todos los demás.
 *
 * <p>Es de tipo OPEN: cualquier tipo suyo es accesible desde otros módulos sin declarar interfaces con nombre.
 * <p>Debe permanecer libre de dependencias hacia otros módulos (lo verifica {@code ArchitectureRulesTest}, porque Modulith no puede expresar 'ninguna dependencia' con {@code allowedDependencies} vacío).
 *
 * <p>Dependencias permitidas: ninguna (módulo base).
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package cl.minimarket.pos.shared;

import org.springframework.modulith.ApplicationModule;
