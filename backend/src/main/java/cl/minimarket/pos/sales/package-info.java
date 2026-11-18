/**
 * Módulo <b>Ventas</b> ({@code sales}).
 *
 * <p>Venta POS (incluye búsqueda y resolución de productos en {@code sales/pos}), pagos, suspendidas, anulaciones y devoluciones. Publica {@code SaleCompletedEvent} y {@code SaleReturnedEvent}. No depende de {@code billing}.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}, {@code catalog}, {@code promotions}, {@code customers}, {@code inventory}, {@code shrinkage}, {@code notification}, {@code cash}, {@code security}, {@code organization}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Ventas", allowedDependencies = {"shared", "audit", "catalog", "promotions", "customers", "inventory", "shrinkage", "notification", "cash", "security", "organization"})
package cl.minimarket.pos.sales;

import org.springframework.modulith.ApplicationModule;
