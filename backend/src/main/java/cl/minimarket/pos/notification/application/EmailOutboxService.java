package cl.minimarket.pos.notification.application;

import cl.minimarket.pos.notification.EmailOutboxApi;
import cl.minimarket.pos.notification.domain.OutboundEmail;
import cl.minimarket.pos.notification.domain.OutboundEmailRepository;
import cl.minimarket.pos.notification.domain.OutboundEmailStatus;
import cl.minimarket.pos.notification.infrastructure.NotificationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Implementación de {@link EmailOutboxApi} y job programado de despacho de la outbox
 * ({@code outbound_emails}, ADR-10).
 */
@Service
public class EmailOutboxService implements EmailOutboxApi {

    private final OutboundEmailRepository repository;
    private final EmailOutboxProcessor processor;
    private final NotificationProperties properties;

    public EmailOutboxService(OutboundEmailRepository repository,
                              EmailOutboxProcessor processor,
                              NotificationProperties properties) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    @Transactional
    public Long enqueue(EmailMessage message) {
        Objects.requireNonNull(message, "message");
        OutboundEmail entity = new OutboundEmail(
                message.toAddress().trim(),
                message.subject().trim(),
                message.template().trim(),
                new LinkedHashMap<>(message.payload()),
                message.attachmentFileId());
        return repository.save(entity).getId();
    }

    /**
     * Job periódico que despacha los correos pendientes en orden de creación.
     */
    @Scheduled(fixedDelayString = "${pos.notification.poll-delay-ms:5000}")
    public void scheduledDispatch() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        processPending();
    }

    /**
     * Procesa un lote de correos pendientes y retorna la cantidad enviada exitosamente.
     *
     * @return número de correos enviados en este ciclo
     */
    public int processPending() {
        int batchSize = Math.max(1, properties.getBatchSize());
        List<OutboundEmail> pending = repository.findByStatusOrderByCreatedAtAsc(
                OutboundEmailStatus.PENDING, PageRequest.of(0, batchSize));
        int sentCount = 0;
        for (OutboundEmail email : pending) {
            if (processor.processOne(email.getId())) {
                sentCount++;
            }
        }
        return sentCount;
    }
}
