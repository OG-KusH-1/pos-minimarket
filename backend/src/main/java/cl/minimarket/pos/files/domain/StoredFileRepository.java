package cl.minimarket.pos.files.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio de metadatos de archivos almacenados ({@code stored_files}).
 */
public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    /** Busca un archivo activo (no marcado como eliminado). */
    Optional<StoredFile> findByIdAndDeletedAtIsNull(Long id);

    /** Lista los archivos marcados con {@code deleted_at} para limpieza de binarios en disco. */
    List<StoredFile> findByDeletedAtIsNotNull();
}
