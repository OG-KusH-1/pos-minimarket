package cl.minimarket.pos.catalog.tax.web;

import cl.minimarket.pos.catalog.tax.application.TaxService;
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
 * Endpoints globales de impuestos configurables ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/taxes")
@GlobalEndpoint
@Tag(name = "Catalog")
public class TaxController {

    private final TaxService taxService;

    public TaxController(TaxService taxService) {
        this.taxService = taxService;
    }

    /** Lista todos los impuestos configurados. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_READ)")
    @Operation(summary = "Listar impuestos", description = "Permiso: catalog:product:read")
    public List<TaxResponse> list() {
        return taxService.list().stream()
                .map(TaxResponse::from)
                .toList();
    }

    /** Crea un nuevo impuesto configurable y audita {@code TAX_CHANGED}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_TAX_MANAGE)")
    @Operation(summary = "Crear impuesto", description = "Permiso: config:tax:manage")
    public TaxResponse create(@Valid @RequestBody TaxRequest request) {
        return TaxResponse.from(taxService.create(toCommand(request)));
    }

    /** Actualiza un impuesto existente y audita {@code TAX_CHANGED}. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_TAX_MANAGE)")
    @Operation(summary = "Actualizar impuesto", description = "Permiso: config:tax:manage")
    public TaxResponse update(@PathVariable Long id, @Valid @RequestBody TaxRequest request) {
        return TaxResponse.from(taxService.update(id, toCommand(request)));
    }

    private static TaxService.TaxCommand toCommand(TaxRequest request) {
        return new TaxService.TaxCommand(
                request.code(),
                request.name(),
                request.taxType(),
                request.rate(),
                request.siiCode(),
                request.validFrom(),
                request.validTo()
        );
    }
}
