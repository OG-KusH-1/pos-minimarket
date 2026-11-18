package cl.minimarket.pos.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * API pública del módulo {@code notification} ({@code PLAN.md} A.4, ADR-10).
 *
 * <p>Permite a otros módulos ({@code purchasing}, {@code sales}, etc.) encolar correos electrónicos
 * en la tabla outbox {@code outbound_emails} dentro de la misma transacción de negocio, sin
 * acoplarse al envío SMTP ni fallar si el servidor de correo no está disponible.
 */
public interface EmailOutboxApi {

    /**
     * Encola un correo electrónico en {@code outbound_emails} con estado {@code PENDING}.
     *
     * <p>Debe invocarse dentro de una transacción activa (o abre una propia) para garantizar
     * atomicidad con la operación de negocio.
     *
     * @param message datos del correo a encolar
     * @return identificador del registro creado en {@code outbound_emails}
     */
    Long enqueue(EmailMessage message);

    /**
     * Mensaje de correo a encolar en la outbox.
     *
     * @param toAddress        dirección de correo del destinatario (obligatoria, máximo 254 caracteres)
     * @param subject          asunto del correo (obligatorio, máximo 200 caracteres)
     * @param template         nombre lógico de la plantilla (obligatorio, máximo 60 caracteres)
     * @param payload          variables de la plantilla serializables a JSON (opcional; vacío si es nulo)
     * @param attachmentFileId identificador opcional en {@code stored_files} del archivo adjunto
     */
    record EmailMessage(
            String toAddress,
            String subject,
            String template,
            Map<String, Object> payload,
            Long attachmentFileId
    ) {
        /** Valida campos obligatorios y normaliza el mapa de payload. */
        public EmailMessage {
            Objects.requireNonNull(toAddress, "toAddress");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(template, "template");
            if (toAddress.isBlank()) {
                throw new IllegalArgumentException("toAddress no puede estar en blanco");
            }
            if (subject.isBlank()) {
                throw new IllegalArgumentException("subject no puede estar en blanco");
            }
            if (template.isBlank()) {
                throw new IllegalArgumentException("template no puede estar en blanco");
            }
            payload = payload == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }
}
