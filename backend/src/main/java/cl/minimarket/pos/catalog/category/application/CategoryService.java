package cl.minimarket.pos.catalog.category.application;

import cl.minimarket.pos.catalog.category.domain.Category;
import cl.minimarket.pos.catalog.category.domain.CategoryRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Casos de uso de categorías y subcategorías de producto ({@code categories}).
 *
 * <p>Mantiene la regla de dos niveles (categoría raíz y subcategoría; MODELO_DATOS B.3) y evita
 * desactivar categorías que tengan productos activos asociados.
 */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final JdbcClient jdbcClient;

    public CategoryService(CategoryRepository categoryRepository, JdbcClient jdbcClient) {
        this.categoryRepository = categoryRepository;
        this.jdbcClient = jdbcClient;
    }

    /** Comando de creación o edición de una categoría. */
    public record CategoryCommand(
            String name,
            String code,
            Long parentId
    ) {
    }

    /** Nodo de categoría con sus hijos directos (vacío en listados planos o subcategorías). */
    public record CategoryNode(
            Long id,
            String name,
            String code,
            Long parentId,
            boolean active,
            List<CategoryNode> children
    ) {
    }

    /**
     * Lista las categorías registradas.
     *
     * @param tree si es {@code true}, devuelve solo las raíces con sus subcategorías anidadas en
     *             {@code children}; si es {@code false} o {@code null}, devuelve la lista plana
     * @return lista ordenada por nombre
     */
    @Transactional(readOnly = true)
    public List<CategoryNode> list(boolean tree) {
        List<Category> all = categoryRepository.findAllByOrderByNameAsc();
        if (!tree) {
            return all.stream()
                    .map(c -> toNode(c, List.of()))
                    .toList();
        }
        Map<Long, List<CategoryNode>> childrenByParent = new LinkedHashMap<>();
        List<Category> roots = new ArrayList<>();
        for (Category category : all) {
            if (category.getParentId() == null) {
                roots.add(category);
            } else {
                childrenByParent
                        .computeIfAbsent(category.getParentId(), k -> new ArrayList<>())
                        .add(toNode(category, List.of()));
            }
        }
        return roots.stream()
                .map(root -> toNode(root, List.copyOf(childrenByParent.getOrDefault(root.getId(), List.of()))))
                .toList();
    }

    /**
     * Crea una nueva categoría o subcategoría validando profundidad máxima de 2 niveles y unicidad
     * de nombre dentro del mismo padre.
     */
    @Transactional
    public Category create(CategoryCommand cmd) {
        String name = cmd.name().trim();
        String code = trimToNull(cmd.code());
        Long parentId = cmd.parentId();

        validateParentForCreateOrUpdate(null, parentId);
        assertUniqueName(parentId, name, null);

        Category category = new Category();
        category.setName(name);
        category.setCode(code);
        category.setParentId(parentId);
        category.setActive(true);
        return categoryRepository.save(category);
    }

    /**
     * Actualiza una categoría existente validando la jerarquía de 2 niveles y la unicidad del nombre.
     */
    @Transactional
    public Category update(Long id, CategoryCommand cmd) {
        Category category = findOrThrow(id);
        String name = cmd.name().trim();
        String code = trimToNull(cmd.code());
        Long parentId = cmd.parentId();

        validateParentForCreateOrUpdate(id, parentId);
        assertUniqueName(parentId, name, id);

        category.setName(name);
        category.setCode(code);
        category.setParentId(parentId);
        return categoryRepository.save(category);
    }

    /**
     * Desactiva una categoría si no tiene productos activos asociados (ni directamente ni en sus
     * subcategorías).
     */
    @Transactional
    public void deactivate(Long id) {
        Category category = findOrThrow(id);
        if (hasActiveProducts(id)) {
            throw new BusinessException(ErrorCode.ENTITY_IN_USE,
                    "No se puede desactivar la categoría porque tiene productos activos.");
        }
        if (category.isActive()) {
            category.setActive(false);
            categoryRepository.save(category);
        }
    }

    /** Reactiva una categoría previamente desactivada. */
    @Transactional
    public void activate(Long id) {
        Category category = findOrThrow(id);
        if (!category.isActive()) {
            category.setActive(true);
            categoryRepository.save(category);
        }
    }

    private Category findOrThrow(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Categoría no encontrada."));
    }

    private void validateParentForCreateOrUpdate(Long selfId, Long parentId) {
        if (parentId == null) {
            return;
        }
        if (selfId != null && selfId.equals(parentId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Una categoría no puede ser su propia categoría padre.");
        }
        Category parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Categoría padre no encontrada."));
        if (parent.getParentId() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "No se permite un tercer nivel de categorías.");
        }
        if (selfId != null && categoryRepository.existsByParentId(selfId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "No se puede convertir en subcategoría una categoría que ya tiene subcategorías.");
        }
    }

    private void assertUniqueName(Long parentId, String name, Long excludeId) {
        boolean exists = parentId == null
                ? categoryRepository.existsRootByNameIgnoreCase(name, excludeId)
                : categoryRepository.existsChildByNameIgnoreCase(parentId, name, excludeId);
        if (exists) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe una categoría con el nombre '" + name + "' en ese nivel.");
        }
    }

    private boolean hasActiveProducts(Long categoryId) {
        Boolean exists = jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM products p
                    JOIN categories c ON p.category_id = c.id
                    WHERE (c.id = :categoryId OR c.parent_id = :categoryId)
                      AND p.active = true
                )
                """)
                .param("categoryId", categoryId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    private static CategoryNode toNode(Category category, List<CategoryNode> children) {
        return new CategoryNode(
                category.getId(),
                category.getName(),
                category.getCode(),
                category.getParentId(),
                category.isActive(),
                children
        );
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
