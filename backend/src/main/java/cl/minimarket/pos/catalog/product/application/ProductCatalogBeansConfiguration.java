package cl.minimarket.pos.catalog.product.application;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.product.domain.Product;
import cl.minimarket.pos.catalog.product.domain.ProductRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

/**
 * Registra implementaciones por defecto de {@link CodeResolver} y {@link ProductSearch} mientras
 * T28 no provea los beans definitivos en {@code catalog/scale}.
 */
@Configuration
public class ProductCatalogBeansConfiguration {

    /**
     * Resolvedor básico sin soporte de balanza: busca por código de barras exacto, {@code shortcut_code}
     * o {@code sku} en productos activos.
     */
    @Bean
    @ConditionalOnMissingBean(CodeResolver.class)
    CodeResolver defaultCodeResolver(JdbcClient jdbc,
                                      ProductRepository productRepository,
                                      ProductSnapshotMapper mapper) {
        return code -> {
            if (code == null || code.isBlank()) {
                return Optional.empty();
            }
            String trimmed = code.trim();
            Optional<Long> byBarcode = jdbc.sql("""
                    SELECT pb.product_id
                    FROM product_barcodes pb
                    JOIN products p ON p.id = pb.product_id
                    WHERE pb.code = :code
                      AND pb.barcode_type <> 'SCALE_PLU'
                      AND p.active = true
                    LIMIT 1
                    """)
                    .param("code", trimmed)
                    .query(Long.class)
                    .optional();
            if (byBarcode.isPresent()) {
                return mapper.findSnapshotsByIds(List.of(byBarcode.get())).stream()
                        .findFirst()
                        .map(s -> new ProductCatalogApi.ScanResult(
                                s, null, null, ProductCatalogApi.ScanSource.BARCODE));
            }

            Optional<Product> byShortcut = productRepository.findByShortcutCodeAndActiveTrue(trimmed);
            if (byShortcut.isPresent()) {
                return mapper.findSnapshotsByIds(List.of(byShortcut.get().getId())).stream()
                        .findFirst()
                        .map(s -> new ProductCatalogApi.ScanResult(
                                s, null, null, ProductCatalogApi.ScanSource.SHORTCUT));
            }

            Optional<Product> bySku = productRepository.findBySkuAndActiveTrue(trimmed);
            return bySku.flatMap(p -> mapper.findSnapshotsByIds(List.of(p.getId())).stream()
                    .findFirst()
                    .map(s -> new ProductCatalogApi.ScanResult(
                            s, null, null, ProductCatalogApi.ScanSource.SKU)));
        };
    }

    /**
     * Búsqueda básica de productos activos por nombre, SKU, atajo o prefijo de código de barras.
     */
    @Bean
    @ConditionalOnMissingBean(ProductSearch.class)
    ProductSearch defaultProductSearch(JdbcClient jdbc, ProductSnapshotMapper mapper) {
        return (q, limit) -> {
            if (q == null || q.isBlank() || limit <= 0) {
                return List.of();
            }
            String trimmed = q.trim();
            String likePattern = "%" + trimmed.toLowerCase() + "%";
            String prefixPattern = trimmed + "%";
            List<Long> ids = jdbc.sql("""
                    SELECT p.id
                    FROM products p
                    WHERE p.active = true
                      AND (f_unaccent(lower(p.name)) ILIKE f_unaccent(:likePattern)
                           OR lower(p.sku) LIKE :likePattern
                           OR p.shortcut_code = :exact
                           OR EXISTS (
                               SELECT 1 FROM product_barcodes pb
                               WHERE pb.product_id = p.id AND pb.code LIKE :prefixPattern
                           ))
                    ORDER BY p.name ASC
                    LIMIT :limit
                    """)
                    .param("likePattern", likePattern)
                    .param("exact", trimmed)
                    .param("prefixPattern", prefixPattern)
                    .param("limit", limit)
                    .query(Long.class)
                    .list();
            return mapper.findSnapshotsByIds(ids);
        };
    }
}
