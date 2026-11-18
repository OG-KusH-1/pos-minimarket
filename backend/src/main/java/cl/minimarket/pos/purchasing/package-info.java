/**
 * Módulo <b>Compras</b> ({@code purchasing}).
 *
 * <p>Proveedores, relación producto-proveedor, órdenes de compra y recepciones (que actualizan el costo promedio ponderado).
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit}, {@code catalog}, {@code inventory}, {@code files}, {@code security}, {@code notification}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Compras", allowedDependencies = {"shared", "audit", "catalog", "inventory", "files", "security", "notification"})
package cl.minimarket.pos.purchasing;

import org.springframework.modulith.ApplicationModule;
