package cl.minimarket.pos.catalog.brand.web;

import cl.minimarket.pos.catalog.brand.application.BrandService;
import cl.minimarket.pos.catalog.brand.domain.Brand;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints globales de marcas ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/brands")
@GlobalEndpoint
@Tag(name = "Catalog")
public class BrandController {

    private static final SortParser SORT = SortParser.allowing("id", "name", "active")
            .withDefault(Sort.by("name"));

    private final BrandService brandService;

    public BrandController(BrandService brandService) {
        this.brandService = brandService;
    }

    /** Lista las marcas paginadas con filtro por texto y estado. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_READ)")
    @Operation(summary = "Listar marcas", description = "Permiso: catalog:product:read")
    public PageResponse<BrandResponse> list(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) Boolean active,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) List<String> sort) {
        Pageable pageable = PageQuery.of(page, size).toPageable(SORT.parse(sort));
        Page<Brand> result = brandService.list(q, active, pageable);
        return PageResponse.from(result, BrandResponse::from);
    }

    /** Crea una nueva marca. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_BRAND_MANAGE)")
    @Operation(summary = "Crear marca", description = "Permiso: catalog:brand:manage")
    public BrandResponse create(@Valid @RequestBody BrandRequest request) {
        return BrandResponse.from(brandService.create(request.name()));
    }

    /** Actualiza el nombre de una marca existente. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_BRAND_MANAGE)")
    @Operation(summary = "Actualizar marca", description = "Permiso: catalog:brand:manage")
    public BrandResponse update(@PathVariable Long id, @Valid @RequestBody BrandRequest request) {
        return BrandResponse.from(brandService.update(id, request.name()));
    }

    /** Desactiva una marca. */
    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_BRAND_MANAGE)")
    @Operation(summary = "Desactivar marca", description = "Permiso: catalog:brand:manage")
    public void deactivate(@PathVariable Long id) {
        brandService.deactivate(id);
    }

    /** Reactiva una marca. */
    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_BRAND_MANAGE)")
    @Operation(summary = "Activar marca", description = "Permiso: catalog:brand:manage")
    public void activate(@PathVariable Long id) {
        brandService.activate(id);
    }
}
