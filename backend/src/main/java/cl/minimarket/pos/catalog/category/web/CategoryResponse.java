package cl.minimarket.pos.catalog.category.web;

import cl.minimarket.pos.catalog.category.application.CategoryService;
import cl.minimarket.pos.catalog.category.domain.Category;

import java.util.List;

/**
 * Respuesta pública de categoría ({@code API.md} F.3).
 */
public record CategoryResponse(
        Long id,
        String name,
        String code,
        Long parentId,
        boolean active,
        List<CategoryResponse> children
) {

    /** Convierte una entidad {@link Category} a DTO con lista de hijos vacía. */
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getCode(),
                category.getParentId(),
                category.isActive(),
                List.of()
        );
    }

    /** Convierte un nodo jerárquico {@link CategoryService.CategoryNode} a DTO recursivamente. */
    public static CategoryResponse fromNode(CategoryService.CategoryNode node) {
        List<CategoryResponse> childResponses = node.children().stream()
                .map(CategoryResponse::fromNode)
                .toList();
        return new CategoryResponse(
                node.id(),
                node.name(),
                node.code(),
                node.parentId(),
                node.active(),
                childResponses
        );
    }
}
