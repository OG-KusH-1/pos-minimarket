package cl.minimarket.pos.files.application;

import cl.minimarket.pos.files.FileStorage;
import cl.minimarket.pos.files.domain.StoredFile;
import cl.minimarket.pos.files.domain.StoredFileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link FileCleanupService}.
 */
class FileCleanupServiceTest {

    @Test
    void continuesCleanupWhenOneRowHasInvalidStorageKey() {
        StoredFileRepository repository = mock(StoredFileRepository.class);
        FileStorage storage = mock(FileStorage.class);

        StoredFile badFile = mock(StoredFile.class);
        when(badFile.getId()).thenReturn(1L);
        when(badFile.getStorageKey()).thenReturn("../invalid-key");

        StoredFile goodFile = mock(StoredFile.class);
        when(goodFile.getId()).thenReturn(2L);
        when(goodFile.getStorageKey()).thenReturn("valid-uuid-key");

        when(repository.findByDeletedAtIsNotNull()).thenReturn(List.of(badFile, goodFile));
        doThrow(new IllegalArgumentException("Clave inválida"))
                .when(storage).delete("../invalid-key");

        FileCleanupService service = new FileCleanupService(repository, storage);
        int deletedCount = service.deleteMarkedBinaries();

        assertThat(deletedCount).isEqualTo(1);
        verify(storage).delete("../invalid-key");
        verify(storage).delete("valid-uuid-key");
    }
}
