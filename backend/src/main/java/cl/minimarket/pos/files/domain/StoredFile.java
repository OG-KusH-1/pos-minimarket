package cl.minimarket.pos.files.domain;

import cl.minimarket.pos.files.FileCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Entidad JPA para la tabla {@code stored_files} (MODELO_DATOS B.1).
 *
 * <p>Es append-only: no extiende {@code AuditableEntity} porque carece de {@code updated_at},
 * {@code updated_by} y {@code version}. El rol {@code pos_app} solo tiene permiso de {@code INSERT},
 * {@code SELECT} y {@code UPDATE (deleted_at)}.
 */
@Entity
@Table(name = "stored_files")
@EntityListeners(AuditingEntityListener.class)
public class StoredFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, unique = true, length = 200, updatable = false)
    private String storageKey;

    @Column(name = "original_name", nullable = false, length = 255, updatable = false)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", nullable = false, length = 64, updatable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30, updatable = false)
    private FileCategory category;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Constructor requerido por JPA. */
    protected StoredFile() {
    }

    /**
     * Crea un nuevo registro de metadatos de archivo.
     */
    public StoredFile(String storageKey, String originalName, String contentType,
                      long sizeBytes, String sha256, FileCategory category) {
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.category = category;
    }

    public Long getId() {
        return id;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public FileCategory getCategory() {
        return category;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
