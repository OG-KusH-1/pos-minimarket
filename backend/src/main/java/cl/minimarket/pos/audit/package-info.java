/**
 * Módulo <b>Auditoría</b> ({@code audit}).
 *
 * <p>Bitácora inmutable ({@code audit_log}, append-only con hash encadenado). Expone {@code AuditService.record(AuditEvent)}, que participa en la transacción del llamador.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Auditoría", allowedDependencies = {"shared"})
package cl.minimarket.pos.audit;

import org.springframework.modulith.ApplicationModule;
