package cl.minimarket.pos.notification;

import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileContent;
import cl.minimarket.pos.files.FileMetadata;
import cl.minimarket.pos.notification.EmailOutboxApi.EmailMessage;
import cl.minimarket.pos.notification.application.EmailOutboxProcessor;
import cl.minimarket.pos.notification.application.EmailOutboxService;
import cl.minimarket.pos.notification.domain.OutboundEmail;
import cl.minimarket.pos.notification.domain.OutboundEmailRepository;
import cl.minimarket.pos.notification.domain.OutboundEmailStatus;
import cl.minimarket.pos.notification.infrastructure.EmailTemplateRenderer;
import cl.minimarket.pos.notification.infrastructure.NotificationProperties;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.MailSendException;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio y procesador de la outbox de correo ({@code T58}).
 */
class EmailOutboxServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-18T12:00:00Z");

    @Test
    void processOneMarksSentAndAttachesFile() throws Exception {
        OutboundEmailRepository repo = mock(OutboundEmailRepository.class);
        EmailGateway gateway = mock(EmailGateway.class);
        FileApi fileApi = mock(FileApi.class);
        BusinessClock clock = BusinessClock.fixed(FIXED_NOW, ZoneId.of("America/Santiago"));
        NotificationProperties props = new NotificationProperties();
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();

        OutboundEmail email = new OutboundEmail(
                "proveedor@test.cl",
                "Orden de Compra L01-OC-00000001",
                "purchase-order",
                Map.of("orderNumber", "L01-OC-00000001", "storeName", "Casa Matriz", "supplierName", "Proveedor SA"),
                42L);
        setId(email, 10L);

        when(repo.findById(10L)).thenReturn(Optional.of(email));
        when(repo.findByStatusOrderByCreatedAtAsc(eq(OutboundEmailStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(email));
        FileMetadata meta = new FileMetadata(
                42L, "key-42", "OC-L01-OC-00000001.pdf", "application/pdf", 4L, "sha",
                FileCategory.EXPORT, FIXED_NOW);
        when(fileApi.open(42L)).thenReturn(new FileContent(meta, new ByteArrayInputStream(new byte[]{1, 2, 3, 4})));

        EmailOutboxProcessor processor = new EmailOutboxProcessor(repo, gateway, renderer, fileApi, clock, props);
        EmailOutboxService service = new EmailOutboxService(repo, processor, props);

        int sent = service.processPending();

        assertThat(sent).isEqualTo(1);
        assertThat(email.getStatus()).isEqualTo(OutboundEmailStatus.SENT);
        assertThat(email.getAttempts()).isEqualTo(1);
        assertThat(email.getSentAt()).isEqualTo(FIXED_NOW);
        verify(gateway).send(any(EmailGateway.EmailDelivery.class));
    }

    @Test
    void processOneRetriesAndMarksFailedAfterMaxAttempts() throws Exception {
        OutboundEmailRepository repo = mock(OutboundEmailRepository.class);
        EmailGateway gateway = mock(EmailGateway.class);
        FileApi fileApi = mock(FileApi.class);
        BusinessClock clock = BusinessClock.fixed(FIXED_NOW, ZoneId.of("America/Santiago"));
        NotificationProperties props = new NotificationProperties();
        props.setMaxAttempts(2);
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();

        OutboundEmail email = new OutboundEmail(
                "proveedor@test.cl",
                "Orden de Compra",
                "purchase-order",
                Map.of("orderNumber", "OC-1"),
                null);
        setId(email, 11L);

        when(repo.findById(11L)).thenReturn(Optional.of(email));
        doThrow(new MailSendException("SMTP caído")).when(gateway).send(any());

        EmailOutboxProcessor processor = new EmailOutboxProcessor(repo, gateway, renderer, fileApi, clock, props);

        assertThat(processor.processOne(11L)).isFalse();
        assertThat(email.getStatus()).isEqualTo(OutboundEmailStatus.PENDING);
        assertThat(email.getAttempts()).isEqualTo(1);
        assertThat(email.getLastError()).contains("SMTP caído");

        assertThat(processor.processOne(11L)).isFalse();
        assertThat(email.getStatus()).isEqualTo(OutboundEmailStatus.FAILED);
        assertThat(email.getAttempts()).isEqualTo(2);
    }

    @Test
    void enqueueNormalizesAndSavesPendingEmail() throws Exception {
        OutboundEmailRepository repo = mock(OutboundEmailRepository.class);
        EmailOutboxProcessor processor = mock(EmailOutboxProcessor.class);
        NotificationProperties props = new NotificationProperties();
        when(repo.save(any(OutboundEmail.class))).thenAnswer(inv -> {
            OutboundEmail saved = inv.getArgument(0);
            setId(saved, 99L);
            return saved;
        });

        EmailOutboxService service = new EmailOutboxService(repo, processor, props);
        Long id = service.enqueue(new EmailMessage(
                "  dest@test.cl ", " Asunto ", "purchase-order", Map.of("k", "v"), null));

        assertThat(id).isEqualTo(99L);
    }

    private static void setId(OutboundEmail entity, Long id) throws Exception {
        Field field = OutboundEmail.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
