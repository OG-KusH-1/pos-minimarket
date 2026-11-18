package cl.minimarket.pos.notification;

import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileMetadata;
import cl.minimarket.pos.notification.EmailOutboxApi.EmailMessage;
import cl.minimarket.pos.notification.application.EmailOutboxService;
import cl.minimarket.pos.notification.domain.OutboundEmail;
import cl.minimarket.pos.notification.domain.OutboundEmailRepository;
import cl.minimarket.pos.notification.domain.OutboundEmailStatus;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba de integración de la outbox de correo ({@code EmailOutboxApi} + {@code JavaMailSender})
 * contra un contenedor Mailpit real ({@code T58}).
 */
class EmailOutboxIT extends AbstractIntegrationIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.33.1")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    static {
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("pos.notification.scheduler-enabled", () -> "false");
    }

    @Autowired
    EmailOutboxApi emailOutboxApi;

    @Autowired
    EmailOutboxService emailOutboxService;

    @Autowired
    OutboundEmailRepository outboundEmailRepository;

    @Autowired
    FileApi fileApi;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void enqueueAndDispatchDeliversEmailWithAttachmentToMailpit() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        byte[] fakePdf = "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);
        FileMetadata attachment = fileApi.register(
                new ByteArrayInputStream(fakePdf),
                "OC-TEST-" + tag + ".pdf",
                "application/pdf",
                fakePdf.length,
                FileCategory.EXPORT);

        String subject = "Orden Test " + tag;
        Long emailId = emailOutboxApi.enqueue(new EmailMessage(
                "proveedor-" + tag + "@example.cl",
                subject,
                "purchase-order",
                Map.of("orderNumber", "OC-" + tag, "storeName", "Casa Matriz", "supplierName", "Proveedor Test"),
                attachment.id()));

        OutboundEmail pending = outboundEmailRepository.findById(emailId).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(OutboundEmailStatus.PENDING);

        int sent = emailOutboxService.processPending();
        assertThat(sent).isGreaterThanOrEqualTo(1);

        OutboundEmail updated = outboundEmailRepository.findById(emailId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboundEmailStatus.SENT);
        assertThat(updated.getSentAt()).isNotNull();
        assertThat(updated.getAttempts()).isEqualTo(1);

        // Verifica recepción real en Mailpit vía su API HTTP
        String apiUrl = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/messages";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(apiUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode root = jsonMapper.readTree(response.body());
        boolean found = false;
        for (JsonNode msg : root.path("messages")) {
            if (subject.equals(msg.path("Subject").asString())) {
                found = true;
                assertThat(msg.path("Attachments").asInt()).isEqualTo(1);
                break;
            }
        }
        assertThat(found).isTrue();
    }
}
