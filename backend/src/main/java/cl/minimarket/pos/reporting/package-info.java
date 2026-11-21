/**
 * Módulo <b>Reportes</b> ({@code reporting}).
 *
 * <p>Dashboard, reportes, exportaciones y agregados diarios ({@code daily_product_sales}). Es el único módulo autorizado a leer por SQL las tablas de otros módulos; solo escribe sus propios agregados.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code files}, {@code audit}.
 * {@code audit} se agregó en T86 (2026-11-20, registrado en {@code CAMBIOS_BASE.md}): las exportaciones
 * de reportes registran {@code DATA_EXPORTED} mediante {@code AuditService}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Reportes", allowedDependencies = {"shared", "files", "audit"})
package cl.minimarket.pos.reporting;

import org.springframework.modulith.ApplicationModule;
