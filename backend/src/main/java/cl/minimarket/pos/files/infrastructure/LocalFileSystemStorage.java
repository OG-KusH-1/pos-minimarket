package cl.minimarket.pos.files.infrastructure;

import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileStorage;
import cl.minimarket.pos.files.StoredObject;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Adaptador de {@link FileStorage} sobre el sistema de archivos local (ADR-09).
 *
 * <p>Almacena los binarios bajo {@code pos.files.root} con nombres aleatorios UUID. Escribe primero
 * a un archivo temporal y lo mueve atómicamente tras validar magic bytes y tamaño real.
 */
@Component
public class LocalFileSystemStorage implements FileStorage {

    private static final int HEADER_BUFFER_SIZE = 512;
    private static final int COPY_BUFFER_SIZE = 8192;

    private final Path root;

    public LocalFileSystemStorage(FilesProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(InputStream in, String contentType, long size, FileCategory category) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(category, "category");

        if (size >= 0) {
            FileValidator.validateDeclaredSize(size, category);
        }

        long maxBytes = FileValidator.maxSizeFor(category);
        String storageKey = UUID.randomUUID().toString();
        Path tempFile = null;

        try {
            Files.createDirectories(root);
            BufferedInputStream buffered = new BufferedInputStream(in, HEADER_BUFFER_SIZE);
            buffered.mark(HEADER_BUFFER_SIZE);

            byte[] header = new byte[HEADER_BUFFER_SIZE];
            int headerLen = readUpTo(buffered, header);
            String verifiedMime = FileValidator.detectAndValidateContentType(header, headerLen, contentType, category);

            buffered.reset();

            tempFile = Files.createTempFile(root, "upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long totalRead = 0;

            try (OutputStream out = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = buffered.read(buffer)) != -1) {
                    totalRead += read;
                    if (totalRead > maxBytes) {
                        throw new BusinessException(ErrorCode.FILE_INVALID,
                                "El archivo excede el tamaño máximo permitido (" + (maxBytes / (1024 * 1024)) + " MB).");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }

            if (totalRead == 0) {
                throw new BusinessException(ErrorCode.FILE_INVALID, "El archivo está vacío.");
            }

            Path target = resolveSafe(storageKey);
            Files.move(tempFile, target);
            tempFile = null;

            String sha256 = HexFormat.of().formatHex(digest.digest());
            return new StoredObject(storageKey, verifiedMime, totalRead, sha256);
        } catch (BusinessException e) {
            deleteQuietly(tempFile);
            throw e;
        } catch (IOException e) {
            deleteQuietly(tempFile);
            throw new UncheckedIOException("Error almacenando el archivo en disco", e);
        } catch (NoSuchAlgorithmException e) {
            deleteQuietly(tempFile);
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        Path path = resolveSafe(storageKey);
        if (!Files.exists(path)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "El contenido del archivo no existe.");
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Error leyendo el archivo desde disco", e);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path path = resolveSafe(storageKey);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Error eliminando el archivo del disco", e);
        }
    }

    private Path resolveSafe(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey vacío");
        }
        // Solo se admiten claves UUID para impedir path traversal
        UUID uuid;
        try {
            uuid = UUID.fromString(storageKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("storageKey inválido: " + storageKey, e);
        }
        Path resolved = root.resolve(uuid.toString()).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Ruta de almacenamiento fuera de la raíz");
        }
        return resolved;
    }

    private static int readUpTo(InputStream in, byte[] target) throws IOException {
        int total = 0;
        while (total < target.length) {
            int n = in.read(target, total, target.length - total);
            if (n == -1) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Mejor esfuerzo al limpiar archivos temporales
            }
        }
    }
}
