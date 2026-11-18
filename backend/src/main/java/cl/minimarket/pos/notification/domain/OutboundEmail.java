package cl.minimarket.pos.notification.domain;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Entidad JPA de la tabla outbox {@code outbound_emails} ({@code MODELO_DATOS.md} B.1, ADR-10).
 */
@Entity
@Table(name = "outbound_emails")
public class OutboundEmail extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "to_address", nullable = false, length = 254)
    private String toAddress;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "template", nullable = false, length = 60)
    private String template;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "attachment_file_id")
    private Long attachmentFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OutboundEmailStatus status = OutboundEmailStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboundEmail() {
    }

    /**
     * Crea un registro outbox en estado {@link OutboundEmailStatus#PENDING}.
     */
    public OutboundEmail(String toAddress,
                         String subject,
                         String template,
                         Map<String, Object> payload,
                         Long attachmentFileId) {
        this.toAddress = Objects.requireNonNull(toAddress, "toAddress");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.template = Objects.requireNonNull(template, "template");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.attachmentFileId = attachmentFileId;
        this.status = OutboundEmailStatus.PENDING;
        this.attempts = 0;
    }

    public Long getId() {
        return id;
    }

    public String getToAddress() {
        return toAddress;
    }

    public String getSubject() {
        return subject;
    }

    public String getTemplate() {
        return template;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Long getAttachmentFileId() {
        return attachmentFileId;
    }

    public OutboundEmailStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    /** Marca el correo como enviado exitosamente. */
    public void markSent(Instant sentAt) {
        this.attempts += 1;
        this.status = OutboundEmailStatus.SENT;
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
        this.lastError = null;
    }

    /** Registra un intento fallido; pasa a {@code FAILED} al alcanzar {@code maxAttempts}. */
    public void recordFailure(String errorMessage, int maxAttempts) {
        this.attempts += 1;
        this.lastError = errorMessage;
        if (this.attempts >= maxAttempts) {
            this.status = OutboundEmailStatus.FAILED;
        }
    }
}
