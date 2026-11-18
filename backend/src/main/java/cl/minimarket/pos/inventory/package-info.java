/**
 * Módulo <b>Inventario</b> ({@code inventory}).
 *
 * <p>Stock, lotes (FEFO), kardex, ajustes, tomas de inventario, transferencias y vencimientos. Expone {@code StockApi}; implementa {@code LotControlGuard} (definido por {@code catalog}) y define {@code TransferDifferenceHandler}.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}, {@code catalog}, {@code security}, {@code organization}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Inventario", allowedDependencies = {"shared", "audit", "catalog", "security", "organization"})
package cl.minimarket.pos.inventory;

import org.springframework.modulith.ApplicationModule;
