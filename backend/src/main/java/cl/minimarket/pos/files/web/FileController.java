package cl.minimarket.pos.files.web;

import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileContent;
import cl.minimarket.pos.files.FileMetadata;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Endpoints de carga y descarga de archivos ({@code API.md} F.2).
 *
 * <p>No son {@code @GlobalEndpoint} (L-02): requieren {@code X-Store-Id}.
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "Files")
public class FileController {

    private final FileApi fileApi;

    public FileController(FileApi fileApi) {
        this.fileApi = fileApi;
    }

    /**
     * Sube un archivo, valida su tipo real (magic bytes) y tamaño, y devuelve su metadata y URL.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Subir archivo")
    public FileResponse upload(@RequestParam("file") MultipartFile file,
                               @RequestParam("category") FileCategory category) throws IOException {
        requireUploadPermission(category);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "El archivo está vacío.");
        }
        try (InputStream in = file.getInputStream()) {
            FileMetadata metadata = fileApi.register(
                    in, file.getOriginalFilename(), file.getContentType(), file.getSize(), category);
            return FileResponse.from(metadata);
        }
    }

    /**
     * Descarga el contenido binario de un archivo con {@code X-Content-Type-Options: nosniff} y
     * {@code Content-Disposition}.
     */
    @GetMapping("/{id}/content")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Descargar contenido de archivo")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id) {
        FileMetadata metadata = fileApi.get(id);
        requireDownloadPermission(metadata.category());

        FileContent content = fileApi.open(id);
        boolean inline = metadata.contentType().startsWith("image/")
                || "application/pdf".equals(metadata.contentType());
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(sanitizeHeaderFilename(metadata.originalName()), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(metadata.contentType()))
                .contentLength(metadata.sizeBytes())
                .body(new InputStreamResource(content.stream()));
    }

    private static void requireUploadPermission(FileCategory category) {
        String required = switch (category) {
            case PRODUCT_IMAGE -> Permissions.CATALOG_PRODUCT_UPDATE;
            case SHRINKAGE_EVIDENCE -> Permissions.SHRINKAGE_RECORD_CREATE;
            case COMPANY_LOGO -> Permissions.CONFIG_COMPANY_UPDATE;
            case IMPORT -> Permissions.CATALOG_PRODUCT_IMPORT;
            case EXPORT -> throw new BusinessException(ErrorCode.FILE_INVALID,
                    "La categoría EXPORT no admite carga directa.");
        };
        requireAuthority(required);
    }

    private static void requireDownloadPermission(FileCategory category) {
        switch (category) {
            case PRODUCT_IMAGE, COMPANY_LOGO -> {
                // Cualquier usuario autenticado puede ver imágenes de producto o logo
            }
            case SHRINKAGE_EVIDENCE -> requireAuthority(Permissions.SHRINKAGE_RECORD_READ);
            case IMPORT -> requireAuthority(Permissions.CATALOG_PRODUCT_IMPORT);
            case EXPORT -> requireAuthority(Permissions.REPORT_EXPORT_EXECUTE);
        }
    }

    private static void requireAuthority(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("No autenticado");
        }
        boolean has = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permission::equals);
        if (!has) {
            throw new AccessDeniedException("Permiso requerido: " + permission);
        }
    }

    private static String sanitizeHeaderFilename(String name) {
        return name.replaceAll("[\\r\\n\"\\\\]", "_");
    }
}
