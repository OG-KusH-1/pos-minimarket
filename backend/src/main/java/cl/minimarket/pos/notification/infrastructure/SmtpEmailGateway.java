package cl.minimarket.pos.notification.infrastructure;

import cl.minimarket.pos.notification.EmailGateway;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Adaptador SMTP del puerto {@link EmailGateway} respaldado por {@link JavaMailSender}.
 */
@Component
public class SmtpEmailGateway implements EmailGateway {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    public SmtpEmailGateway(JavaMailSender mailSender, NotificationProperties properties) {
        this.mailSender = Objects.requireNonNull(mailSender, "mailSender");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void send(EmailDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            boolean multipart = delivery.attachment() != null;
            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage, multipart, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getFrom());
            helper.setTo(delivery.toAddress());
            helper.setSubject(delivery.subject());
            helper.setText(delivery.textBody(), delivery.htmlBody());
            if (delivery.attachment() != null) {
                EmailAttachment att = delivery.attachment();
                helper.addAttachment(
                        att.filename(),
                        new ByteArrayResource(att.content()),
                        att.contentType());
            }
            mailSender.send(mimeMessage);
        } catch (MessagingException ex) {
            throw new MailSendException("Error al construir mensaje MIME para " + delivery.toAddress(), ex);
        } catch (MailException ex) {
            throw ex;
        }
    }
}
