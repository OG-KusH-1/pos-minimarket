package cl.minimarket.pos.notification;

import java.util.Objects;

/**
 * Puerto de envío de correo electrónico del módulo {@code notification} ({@code PLAN.md} A.4,
 * ADR-10).
 *
 * <p>El adaptador por defecto utiliza {@code JavaMailSender} (SMTP; Mailpit en desarrollo local).
 */
public interface EmailGateway {

    /**
     * Envía un correo electrónico ya renderizado.
     *
     * @param delivery datos completos del correo a enviar
     */
    void send(EmailDelivery delivery);

    /**
     * Representación de un correo listo para ser despachado por el gateway.
     *
     * @param toAddress  destinatario
     * @param subject    asunto
     * @param htmlBody   cuerpo HTML del mensaje
     * @param textBody   cuerpo en texto plano alternativo
     * @param attachment adjunto opcional ({@code null} si no aplica)
     */
    record EmailDelivery(
            String toAddress,
            String subject,
            String htmlBody,
            String textBody,
            EmailAttachment attachment
    ) {
        public EmailDelivery {
            Objects.requireNonNull(toAddress, "toAddress");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(htmlBody, "htmlBody");
            Objects.requireNonNull(textBody, "textBody");
        }
    }

    /**
     * Archivo adjunto en memoria para despacho SMTP.
     *
     * @param filename    nombre de archivo presentado al destinatario
     * @param contentType tipo MIME (p. ej. {@code application/pdf})
     * @param content     contenido binario
     */
    record EmailAttachment(
            String filename,
            String contentType,
            byte[] content
    ) {
        public EmailAttachment {
            Objects.requireNonNull(filename, "filename");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(content, "content");
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
