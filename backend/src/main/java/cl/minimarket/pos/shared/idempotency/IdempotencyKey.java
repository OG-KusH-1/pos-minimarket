package cl.minimarket.pos.shared.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Fila de {@code idempotency_keys} (MODELO_DATOS B.2): una clave {@code Idempotency-Key} de un
 * usuario sobre un endpoint, con el hash del request y, al completarse, la respuesta guardada.
 *
 * <p>Es una vista de <b>solo lectura</b> ({@link Immutable}): todas las escrituras pasan por
 * sentencias nativas de {@link IdempotencyKeyRepository}, porque necesitan {@code INSERT ... ON
 * CONFLICT DO NOTHING} (para resolver la carrera entre dos requests simultáneas con la misma
 * clave) y {@code CAST(... AS jsonb)}. No es una entidad AUD: la tabla no tiene {@code version}
 * ni {@code updated_*}.
 *
 * <p>{@code resource_type} y {@code resource_id} existen en la tabla pero hoy no se llenan
 * (punto de extensión: un caso de uso podría publicar qué recurso creó).
 */
@Entity
@Immutable
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    /** Estado: la request original sigue ejecutándose. */
    public static final String IN_PROGRESS = "IN_PROGRESS";

    /** Estado: la request terminó y la respuesta está guardada. */
    public static final String COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idem_key", nullable = false, updatable = false)
    private UUID key;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "endpoint", nullable = false, length = 100, updatable = false)
    private String endpoint;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "status", nullable = false, length = 15)
    private String status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Constructor para JPA. */
    protected IdempotencyKey() {
    }

    public Long getId() {
        return id;
    }

    public UUID getKey() {
        return key;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getStatus() {
        return status;
    }

    /** @return código HTTP guardado; {@code null} mientras esté {@code IN_PROGRESS} */
    public Integer getResponseStatus() {
        return responseStatus;
    }

    /** @return cuerpo JSON guardado como texto; {@code null} si no hubo cuerpo o sigue en curso */
    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
