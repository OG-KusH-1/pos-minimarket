/**
 * Módulo <b>Notificaciones</b> ({@code notification}).
 *
 * <p>Outbox de correo ({@code outbound_emails}) y puerto {@code EmailGateway} (SMTP; Mailpit en local).
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code files}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Notificaciones", allowedDependencies = {"shared", "files"})
package cl.minimarket.pos.notification;

import org.springframework.modulith.ApplicationModule;
