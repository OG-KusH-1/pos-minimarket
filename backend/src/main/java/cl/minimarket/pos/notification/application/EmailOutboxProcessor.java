package cl.minimarket.pos.notification.application;

import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileContent;
import cl.minimarket.pos.notification.EmailGateway;
import cl.minimarket.pos.notification.EmailGateway.EmailAttachment;
import cl.minimarket.pos.notification.EmailGateway.EmailDelivery;
import cl.minimarket.pos.notification.domain.OutboundEmail;
import cl.minimarket.pos.notification.domain.OutboundEmailRepository;
import cl.minimarket.pos.notification.infrastructure.EmailTemplateRenderer;
import cl.minimarket.pos.notification.infrastructure.EmailTemplateRenderer.RenderedEmail;
import cl.minimarket.pos.notification.infrastructure.NotificationProperties;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Procesa individualmente un correo de la outbox en su propia transacción ({@code REQUIRES_NEW}),
 * aislando fallos de un destinatario o del servidor SMTP respecto al resto del lote.
 */
@Service
public class EmailOutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxProcessor.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final OutboundEmailRepository repository;
    private final EmailGateway emailGateway;
    private final EmailTemplateRenderer renderer;
    private final FileApi fileApi;
    private final BusinessClock clock;
    private final NotificationProperties properties;

    public EmailOutboxProcessor(OutboundEmailRepository repository,
                                EmailGateway emailGateway,
                                EmailTemplateRenderer renderer,
                                FileApi fileApi,
                                BusinessClock clock,
                                NotificationProperties properties) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.emailGateway = Objects.requireNonNull(emailGateway, "emailGateway");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.fileApi = Objects.requireNonNull(fileApi, "fileApi");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * Intenta despachar un correo pendiente por su identificador.
     *
     * @param emailId identificador en {@code outbound_emails}
     * @return {@code true} si se envió exitosamente, {@code false} si falló o ya no estaba pendiente
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processOne(Long emailId) {
        OutboundEmail email = repository.findById(emailId).orElse(null);
        if (email == null || email.getStatus() != cl.minimarket.pos.notification.domain.OutboundEmailStatus.PENDING) {
            return false;
        }
        try {
            RenderedEmail rendered = renderer.render(email.getTemplate(), email.getSubject(), email.getPayload());
            EmailAttachment attachment = loadAttachment(email.getAttachmentFileId());
            emailGateway.send(new EmailDelivery(
                    email.getToAddress(),
                    email.getSubject(),
                    rendered.htmlBody(),
                    rendered.textBody(),
                    attachment));
            email.markSent(clock.now());
            repository.save(email);
            return true;
        } catch (RuntimeException ex) {
            String errorMsg = truncate(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            log.warn("Fallo al enviar correo outbox id={} (intento {}): {}",
                    emailId, email.getAttempts() + 1, errorMsg);
            email.recordFailure(errorMsg, properties.getMaxAttempts());
            repository.save(email);
            return false;
        }
    }

    private EmailAttachment loadAttachment(Long attachmentFileId) {
        if (attachmentFileId == null) {
            return null;
        }
        FileContent fileContent = fileApi.open(attachmentFileId);
        try (InputStream in = fileContent.stream()) {
            byte[] bytes = in.readAllBytes();
            return new EmailAttachment(
                    fileContent.metadata().originalName(),
                    fileContent.metadata().contentType(),
                    bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer el adjunto fileId=" + attachmentFileId, ex);
        }
    }

    private static String truncate(String msg) {
        return msg.length() <= MAX_ERROR_LENGTH ? msg : msg.substring(0, MAX_ERROR_LENGTH);
    }
}
