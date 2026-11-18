/**
 * Módulo <b>Reportes</b> ({@code reporting}).
 *
 * <p>Dashboard, reportes, exportaciones y agregados diarios ({@code daily_product_sales}). Es el único módulo autorizado a leer por SQL las tablas de otros módulos; solo escribe sus propios agregados.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code files}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Reportes", allowedDependencies = {"shared", "files"})
package cl.minimarket.pos.reporting;

import org.springframework.modulith.ApplicationModule;
