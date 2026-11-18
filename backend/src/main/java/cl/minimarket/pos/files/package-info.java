/**
 * Módulo <b>Archivos</b> ({@code files}).
 *
 * <p>Metadata de archivos y almacenamiento detrás del puerto {@code FileStorage} (adaptador MVP: sistema de archivos).
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Archivos", allowedDependencies = {"shared"})
package cl.minimarket.pos.files;

import org.springframework.modulith.ApplicationModule;
