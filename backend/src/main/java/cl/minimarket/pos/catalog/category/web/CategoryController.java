package cl.minimarket.pos.catalog.category.web;

import cl.minimarket.pos.catalog.category.application.CategoryService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints globales de categorías y subcategorías ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/categories")
@GlobalEndpoint
@Tag(name = "Catalog")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** Lista las categorías en forma plana o como árbol de 2 niveles ({@code ?tree=true}). */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_READ)")
    @Operation(summary = "Listar categorías", description = "Permiso: catalog:product:read")
    public List<CategoryResponse> list(@RequestParam(defaultValue = "false") boolean tree) {
        return categoryService.list(tree).stream()
                .map(CategoryResponse::fromNode)
                .toList();
    }

    /** Crea una categoría raíz o una subcategoría (máximo 2 niveles). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_CATEGORY_MANAGE)")
    @Operation(summary = "Crear categoría", description = "Permiso: catalog:category:manage")
    public CategoryResponse create(@Valid @RequestBody CategoryRequest request) {
        return CategoryResponse.from(categoryService.create(toCommand(request)));
    }

    /** Actualiza el nombre, código o padre de una categoría. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_CATEGORY_MANAGE)")
    @Operation(summary = "Actualizar categoría", description = "Permiso: catalog:category:manage")
    public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return CategoryResponse.from(categoryService.update(id, toCommand(request)));
    }

    /** Desactiva una categoría si no tiene productos activos. */
    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_CATEGORY_MANAGE)")
    @Operation(summary = "Desactivar categoría", description = "Permiso: catalog:category:manage")
    public void deactivate(@PathVariable Long id) {
        categoryService.deactivate(id);
    }

    /** Reactiva una categoría. */
    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_CATEGORY_MANAGE)")
    @Operation(summary = "Activar categoría", description = "Permiso: catalog:category:manage")
    public void activate(@PathVariable Long id) {
        categoryService.activate(id);
    }

    private static CategoryService.CategoryCommand toCommand(CategoryRequest request) {
        return new CategoryService.CategoryCommand(request.name(), request.code(), request.parentId());
    }
}
