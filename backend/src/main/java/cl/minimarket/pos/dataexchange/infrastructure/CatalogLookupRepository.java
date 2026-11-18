package cl.minimarket.pos.dataexchange.infrastructure;

import cl.minimarket.pos.catalog.SaleType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Consultas de solo lectura sobre tablas de {@code catalog} para resolver y validar referencias en
 * importaciones masivas sin acoplarse a entidades internas del módulo {@code catalog}.
 */
@Repository
public class CatalogLookupRepository {

    private final JdbcClient jdbc;

    public CatalogLookupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Proyección mínima de producto para validar importaciones de proveedores y stock inicial. */
    public record ProductRef(Long id, String sku, SaleType saleType, boolean lotControlled, boolean active) {
    }

    /** Busca una categoría raíz activa por nombre (insensible a mayúsculas). */
    public Optional<Long> findRootCategoryIdByName(String name) {
        return jdbc.sql("""
                        SELECT id
                        FROM categories
                        WHERE parent_id IS NULL
                          AND active = true
                          AND lower(name) = lower(:name)
                        LIMIT 1
                        """)
                .param("name", name)
                .query(Long.class)
                .optional();
    }

    /** Busca una subcategoría activa por nombre bajo una categoría padre específica. */
    public Optional<Long> findSubcategoryIdByName(Long parentId, String name) {
        return jdbc.sql("""
                        SELECT id
                        FROM categories
                        WHERE parent_id = :parentId
                          AND active = true
                          AND lower(name) = lower(:name)
                        LIMIT 1
                        """)
                .param("parentId", parentId)
                .param("name", name)
                .query(Long.class)
                .optional();
    }

    /** Busca una marca activa por nombre (insensible a mayúsculas). */
    public Optional<Long> findBrandIdByName(String name) {
        return jdbc.sql("""
                        SELECT id
                        FROM brands
                        WHERE active = true
                          AND lower(name) = lower(:name)
                        LIMIT 1
                        """)
                .param("name", name)
                .query(Long.class)
                .optional();
    }

    /** Verifica si existe un impuesto activo con el código dado. */
    public boolean existsActiveTaxByCode(String code) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM taxes WHERE code = :code AND active = true
                        )
                        """)
                .param("code", code)
                .query(Boolean.class)
                .single());
    }

    /** Verifica si existe un producto con el SKU dado. */
    public boolean existsProductBySku(String sku) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM products WHERE sku = :sku
                        )
                        """)
                .param("sku", sku)
                .query(Boolean.class)
                .single());
    }

    /** Verifica si existe un código de barras registrado en algún producto. */
    public boolean existsBarcode(String code) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM product_barcodes WHERE code = :code
                        )
                        """)
                .param("code", code)
                .query(Boolean.class)
                .single());
    }

    /** Busca un producto por su SKU exacto. */
    public Optional<ProductRef> findProductBySku(String sku) {
        return jdbc.sql("""
                        SELECT id, sku, sale_type, lot_controlled, active
                        FROM products
                        WHERE sku = :sku
                        """)
                .param("sku", sku)
                .query((rs, rowNum) -> new ProductRef(
                        rs.getLong("id"),
                        rs.getString("sku"),
                        SaleType.valueOf(rs.getString("sale_type")),
                        rs.getBoolean("lot_controlled"),
                        rs.getBoolean("active")
                ))
                .optional();
    }
}
