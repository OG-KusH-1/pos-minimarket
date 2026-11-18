package cl.minimarket.pos.files.application;

import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileContent;
import cl.minimarket.pos.files.FileMetadata;
import cl.minimarket.pos.files.FileStorage;
import cl.minimarket.pos.files.StoredObject;
import cl.minimarket.pos.files.domain.StoredFile;
import cl.minimarket.pos.files.domain.StoredFileRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Objects;

/**
 * Servicio de aplicación del módulo {@code files}: registra metadatos en {@code stored_files}
 * y delega la persistencia física en {@link FileStorage}.
 */
@Service
public class FileService implements FileApi {

    private static final int MAX_ORIGINAL_NAME_LENGTH = 255;

    private final FileStorage storage;
    private final StoredFileRepository repository;

    public FileService(FileStorage storage, StoredFileRepository repository) {
        this.storage = storage;
        this.repository = repository;
    }

    @Override
    @Transactional
    public FileMetadata register(InputStream in, String originalName, String contentType,
                                 long size, FileCategory category) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(category, "category");

        String safeName = sanitizeOriginalName(originalName);
        StoredObject stored = storage.store(in, contentType, size, category);
        try {
            StoredFile entity = repository.save(new StoredFile(
                    stored.storageKey(),
                    safeName,
                    stored.contentType(),
                    stored.sizeBytes(),
                    stored.sha256(),
                    category
            ));
            return toMetadata(entity);
        } catch (RuntimeException e) {
            // Evita dejar binarios huérfanos en disco si falla el registro en BD
            try {
                storage.delete(stored.storageKey());
            } catch (RuntimeException ignored) {
                // No enmascarar la excepción original
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FileMetadata get(Long fileId) {
        StoredFile entity = repository.findByIdAndDeletedAtIsNull(fileId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Archivo no encontrado."));
        return toMetadata(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public FileContent open(Long fileId) {
        FileMetadata metadata = get(fileId);
        InputStream stream = storage.open(metadata.storageKey());
        return new FileContent(metadata, stream);
    }

    private static FileMetadata toMetadata(StoredFile entity) {
        return new FileMetadata(
                entity.getId(),
                entity.getStorageKey(),
                entity.getOriginalName(),
                entity.getContentType(),
                entity.getSizeBytes(),
                entity.getSha256(),
                entity.getCategory(),
                entity.getCreatedAt()
        );
    }

    private static String sanitizeOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "archivo";
        }
        // Extrae solo el nombre base, eliminando rutas cliente (p. ej. C:\fakepath\...)
        String base = originalName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.trim();
        if (base.isEmpty()) {
            return "archivo";
        }
        return base.length() > MAX_ORIGINAL_NAME_LENGTH
                ? base.substring(0, MAX_ORIGINAL_NAME_LENGTH)
                : base;
    }
}
