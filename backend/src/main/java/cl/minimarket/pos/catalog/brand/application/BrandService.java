package cl.minimarket.pos.catalog.brand.application;

import cl.minimarket.pos.catalog.brand.domain.Brand;
import cl.minimarket.pos.catalog.brand.domain.BrandRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Casos de uso de marcas de producto ({@code brands}).
 */
@Service
public class BrandService {

    private final BrandRepository brandRepository;

    public BrandService(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    /** Lista las marcas paginadas aplicando filtros opcionales de texto ({@code q}) y estado ({@code active}). */
    @Transactional(readOnly = true)
    public Page<Brand> list(String q, Boolean active, Pageable pageable) {
        Specification<Brand> spec = (root, query, cb) -> cb.conjunction();
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.like(
                    cb.function("f_unaccent", String.class, cb.lower(root.get("name"))),
                    cb.function("f_unaccent", String.class, cb.literal(pattern))
            ));
        }
        if (active != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return brandRepository.findAll(spec, pageable);
    }

    /** Crea una nueva marca verificando unicidad de nombre (insensible a mayúsculas). */
    @Transactional
    public Brand create(String rawName) {
        String name = rawName.trim();
        if (brandRepository.existsByNameIgnoreCase(name, null)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe una marca con el nombre '" + name + "'.");
        }
        Brand brand = new Brand();
        brand.setName(name);
        brand.setActive(true);
        return brandRepository.save(brand);
    }

    /** Actualiza el nombre de una marca existente. */
    @Transactional
    public Brand update(Long id, String rawName) {
        Brand brand = findOrThrow(id);
        String name = rawName.trim();
        if (brandRepository.existsByNameIgnoreCase(name, id)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe una marca con el nombre '" + name + "'.");
        }
        brand.setName(name);
        return brandRepository.save(brand);
    }

    /** Desactiva una marca. */
    @Transactional
    public void deactivate(Long id) {
        Brand brand = findOrThrow(id);
        if (brand.isActive()) {
            brand.setActive(false);
            brandRepository.save(brand);
        }
    }

    /** Reactiva una marca. */
    @Transactional
    public void activate(Long id) {
        Brand brand = findOrThrow(id);
        if (!brand.isActive()) {
            brand.setActive(true);
            brandRepository.save(brand);
        }
    }

    private Brand findOrThrow(Long id) {
        return brandRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Marca no encontrada."));
    }
}
