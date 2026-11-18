/**
 * Módulo <b>Promociones</b> ({@code promotions}).
 *
 * <p>Promociones y motor de evaluación ({@code PromotionEngine}; mientras no exista la fase 5 se usa una implementación no-op).
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code catalog}, {@code audit}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Promociones", allowedDependencies = {"shared", "catalog", "audit"})
package cl.minimarket.pos.promotions;

import org.springframework.modulith.ApplicationModule;
