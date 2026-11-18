/**
 * Módulo <b>Intercambio de datos</b> ({@code dataexchange}).
 *
 * <p>Importación y exportación masiva del catálogo (plantillas CSV/XLSX, dry-run y confirmación). No expone API pública: solo endpoints.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}, {@code files}, {@code security}, {@code catalog}, {@code inventory}, {@code purchasing}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Intercambio de datos", allowedDependencies = {"shared", "audit", "files", "security", "catalog", "inventory", "purchasing"})
package cl.minimarket.pos.dataexchange;

import org.springframework.modulith.ApplicationModule;
