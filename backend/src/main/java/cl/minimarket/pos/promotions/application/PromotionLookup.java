package cl.minimarket.pos.promotions.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Consultas de lectura directa para validar referencias externas ({@code stores},
 * {@code categories}, {@code brands}) y resolver nombres visibles en {@code PromotionResponse} sin
 * introducir dependencias de módulo no permitidas por Spring Modulith ({@code PLAN.md} A.4).
 */
@Component
class PromotionLookup {

    private final JdbcClient jdbc;

    PromotionLookup(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    Set<Long> findExistingStoreIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT id FROM stores WHERE id IN (:ids)")
                .param("ids", ids)
                .query(Long.class)
                .list());
    }

    Map<Long, String> findCategoryNames(Collection<Long> ids) {
        return findNames("categories", ids);
    }

    Map<Long, String> findBrandNames(Collection<Long> ids) {
        return findNames("brands", ids);
    }

    private Map<Long, String> findNames(String table, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT id, name FROM " + table + " WHERE id IN (:ids)")
                .param("ids", ids)
                .query(rs -> {
                    Map<Long, String> result = new HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getLong("id"), rs.getString("name"));
                    }
                    return result;
                });
    }
}
