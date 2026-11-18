package cl.minimarket.pos.catalog.scale.application;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.product.application.ProductSearch;
import cl.minimarket.pos.catalog.product.application.ProductSnapshotMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implementación definitiva de {@link ProductSearch} usando el índice GIN trigram sobre
 * {@code f_unaccent(lower(name))} ({@code ix_products_name_trgm}), SKU, atajo y prefijo de código
 * de barras ({@code PLAN.md} A.4, {@code MODELO_DATOS} B.3).
 *
 * <p>Reemplaza al bean por defecto de {@code ProductCatalogBeansConfiguration} al registrarse en el
 * contexto.
 */
@Component
public class TrigramProductSearch implements ProductSearch {

    private final JdbcClient jdbc;
    private final ProductSnapshotMapper snapshotMapper;

    public TrigramProductSearch(JdbcClient jdbc, ProductSnapshotMapper snapshotMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper");
    }

    @Override
    public List<ProductCatalogApi.ProductSnapshot> search(String q, int limit) {
        if (q == null || q.isBlank() || limit <= 0) {
            return List.of();
        }
        String trimmed = q.trim();
        String escaped = escapeLike(trimmed);
        String likePattern = "%" + escaped + "%";
        String prefixPattern = escaped + "%";

        List<Long> ids = jdbc.sql("""
                SELECT p.id
                FROM products p
                WHERE p.active = true
                  AND (f_unaccent(lower(p.name)) LIKE f_unaccent(lower(:likePattern)) ESCAPE '\\'
                       OR lower(p.sku) LIKE lower(:likePattern) ESCAPE '\\'
                       OR p.shortcut_code = :exact
                       OR EXISTS (
                           SELECT 1 FROM product_barcodes pb
                           WHERE pb.product_id = p.id
                             AND pb.code LIKE :prefixPattern ESCAPE '\\'
                       ))
                ORDER BY
                  CASE
                    WHEN p.shortcut_code = :exact
                      OR lower(p.sku) = lower(:exact)
                      OR EXISTS (
                          SELECT 1 FROM product_barcodes pb
                          WHERE pb.product_id = p.id AND pb.code = :exact
                      )
                    THEN 0
                    WHEN lower(p.sku) LIKE lower(:prefixPattern) ESCAPE '\\'
                      OR f_unaccent(lower(p.name)) LIKE f_unaccent(lower(:prefixPattern)) ESCAPE '\\'
                      OR EXISTS (
                          SELECT 1 FROM product_barcodes pb
                          WHERE pb.product_id = p.id AND pb.code LIKE :prefixPattern ESCAPE '\\'
                      )
                    THEN 1
                    ELSE 2
                  END ASC,
                  similarity(f_unaccent(lower(p.name)), f_unaccent(lower(:exact))) DESC,
                  p.name ASC,
                  p.id ASC
                LIMIT :limit
                """)
                .param("likePattern", likePattern)
                .param("prefixPattern", prefixPattern)
                .param("exact", trimmed)
                .param("limit", limit)
                .query(Long.class)
                .list();

        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductCatalogApi.ProductSnapshot> byId = new LinkedHashMap<>();
        for (ProductCatalogApi.ProductSnapshot snapshot : snapshotMapper.findSnapshotsByIds(ids)) {
            byId.put(snapshot.id(), snapshot);
        }
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
