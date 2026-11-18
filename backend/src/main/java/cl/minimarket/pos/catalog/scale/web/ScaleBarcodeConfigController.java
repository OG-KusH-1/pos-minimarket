package cl.minimarket.pos.catalog.scale.web;

import cl.minimarket.pos.catalog.scale.application.ScaleBarcodeConfigService;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints globales de configuración de códigos de balanza ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/scale-barcode-configs")
@GlobalEndpoint
@Tag(name = "Catalog")
public class ScaleBarcodeConfigController {

    private final ScaleBarcodeConfigService configService;

    public ScaleBarcodeConfigController(ScaleBarcodeConfigService configService) {
        this.configService = configService;
    }

    /** Lista todas las configuraciones de códigos de balanza. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_READ)")
    @Operation(summary = "Listar configuraciones de balanza", description = "Permiso: catalog:product:read")
    public List<ScaleBarcodeConfigResponse> list() {
        return configService.list().stream()
                .map(ScaleBarcodeConfigResponse::from)
                .toList();
    }

    /** Crea una nueva configuración de código de balanza. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_SCALE_BARCODE_MANAGE)")
    @Operation(summary = "Crear configuración de balanza", description = "Permiso: config:scale-barcode:manage")
    public ScaleBarcodeConfigResponse create(@Valid @RequestBody ScaleBarcodeConfigRequest request) {
        return ScaleBarcodeConfigResponse.from(configService.create(toCommand(request)));
    }

    /** Actualiza una configuración de código de balanza existente. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_SCALE_BARCODE_MANAGE)")
    @Operation(summary = "Actualizar configuración de balanza", description = "Permiso: config:scale-barcode:manage")
    public ScaleBarcodeConfigResponse update(@PathVariable Long id,
                                             @Valid @RequestBody ScaleBarcodeConfigRequest request) {
        return ScaleBarcodeConfigResponse.from(configService.update(id, toCommand(request)));
    }

    private static ScaleBarcodeConfigService.ScaleConfigCommand toCommand(ScaleBarcodeConfigRequest request) {
        return new ScaleBarcodeConfigService.ScaleConfigCommand(
                request.name(),
                request.prefix(),
                request.embeddedType(),
                request.pluStart(),
                request.pluLength(),
                request.valueStart(),
                request.valueLength(),
                request.valueDecimals()
        );
    }
}
