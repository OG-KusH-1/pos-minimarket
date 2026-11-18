package cl.minimarket.pos.dataexchange.web;

import cl.minimarket.pos.dataexchange.application.CatalogImportService;
import cl.minimarket.pos.dataexchange.application.ImportTemplateService;
import cl.minimarket.pos.dataexchange.application.ImportTemplateService.GeneratedTemplate;
import cl.minimarket.pos.dataexchange.domain.ImportType;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * Endpoints de importación masiva del catálogo y descarga de plantillas ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/catalog/imports")
@GlobalEndpoint
@Tag(name = "DataExchange", description = "Importación y exportación masiva del catálogo")
public class CatalogImportController {

    private final CatalogImportService importService;
    private final ImportTemplateService templateService;

    public CatalogImportController(CatalogImportService importService,
                                   ImportTemplateService templateService) {
        this.importService = importService;
        this.templateService = templateService;
    }

    /**
     * Sube y valida un archivo de importación (CSV o XLSX, máximo 20 MB); si {@code dryRun=false},
     * aplica los cambios en una sola transacción.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_IMPORT)")
    @Operation(summary = "Crear importación masiva",
            description = "Permiso: catalog:product:import. Errores: FILE_INVALID, IMPORT_INVALID, VALIDATION_ERROR")
    public ImportJobResponse create(
            @RequestParam("file") MultipartFile file,
            @RequestParam("importType") ImportType importType,
            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestHeader(value = "X-Store-Id", required = false) Long storeId) {
        return ImportJobResponse.from(importService.createImport(file, importType, dryRun, storeId));
    }

    /**
     * Consulta el estado y resultado de una importación.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_IMPORT)")
    @Operation(summary = "Obtener trabajo de importación",
            description = "Permiso: catalog:product:import. Errores: NOT_FOUND")
    public ImportJobResponse get(@PathVariable Long id) {
        return ImportJobResponse.from(importService.getImport(id));
    }

    /**
     * Confirma y aplica en una sola transacción una importación previamente validada sin errores.
     */
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_IMPORT)")
    @Operation(summary = "Confirmar importación",
            description = "Permiso: catalog:product:import. Errores: NOT_FOUND, INVALID_STATE_TRANSITION, IMPORT_INVALID")
    public ImportJobResponse confirm(
            @PathVariable Long id,
            @RequestHeader(value = "X-Store-Id", required = false) Long storeId) {
        return ImportJobResponse.from(importService.confirmImport(id, storeId));
    }

    /**
     * Descarga la plantilla CSV o XLSX con los encabezados oficiales del tipo de importación.
     */
    @GetMapping("/templates/{importType}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_IMPORT)")
    @Operation(summary = "Descargar plantilla de importación",
            description = "Permiso: catalog:product:import. Errores: VALIDATION_ERROR")
    public ResponseEntity<byte[]> template(
            @PathVariable ImportType importType,
            @RequestParam(value = "format", defaultValue = "xlsx") String format) {
        GeneratedTemplate generated = templateService.generate(importType, format);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(generated.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(generated.contentType()))
                .contentLength(generated.content().length)
                .body(generated.content());
    }
}
