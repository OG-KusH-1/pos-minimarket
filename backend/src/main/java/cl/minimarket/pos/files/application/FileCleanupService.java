package cl.minimarket.pos.files.application;

import cl.minimarket.pos.files.FileStorage;
import cl.minimarket.pos.files.domain.StoredFile;
import cl.minimarket.pos.files.domain.StoredFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Elimina de {@link FileStorage} los binarios de los registros {@code stored_files} marcados con
 * {@code deleted_at} por el job de purga de {@code shared}.
 */
@Service
public class FileCleanupService {

    private static final Logger LOG = LoggerFactory.getLogger(FileCleanupService.class);

    private final StoredFileRepository repository;
    private final FileStorage storage;

    public FileCleanupService(StoredFileRepository repository, FileStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    /**
     * Borra los archivos físicos correspondientes a filas con {@code deleted_at IS NOT NULL}.
     * Si una fila falla (p. ej. clave inválida), registra un warning y continúa con las demás.
     *
     * @return cantidad de binarios eliminados sin error
     */
    @Transactional(readOnly = true)
    public int deleteMarkedBinaries() {
        List<StoredFile> deleted = repository.findByDeletedAtIsNotNull();
        int deletedCount = 0;
        for (StoredFile file : deleted) {
            try {
                storage.delete(file.getStorageKey());
                deletedCount++;
            } catch (RuntimeException ex) {
                LOG.warn("No se pudo eliminar el binario de stored_file id={} key={}",
                        file.getId(), file.getStorageKey(), ex);
            }
        }
        return deletedCount;
    }
}
