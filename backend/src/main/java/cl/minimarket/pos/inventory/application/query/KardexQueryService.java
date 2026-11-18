package cl.minimarket.pos.inventory.application.query;

import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consultas de solo lectura sobre el kardex ({@code stock_movements}) de un producto en el local
 * activo (T24, HU-INV-02, RN-02).
 */
@Service
public class KardexQueryService {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "sm.id",
            "occurredAt", "sm.occurred_at",
            "movementType", "sm.movement_type",
            "quantityDelta", "sm.quantity_delta",
            "balanceAfter", "sm.balance_after"
    );

    private final JdbcClient jdbc;

    public KardexQueryService(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Lista los movimientos del kardex para un producto en el local activo.
     *
     * @param storeId     identificador del local activo
     * @param filter      criterios de filtrado ({@code productId} obligatorio)
     * @param pageable    paginación y ordenamiento
     * @param includeCost {@code true} si el usuario posee {@code catalog:cost:read}
     * @return página de movimientos de stock
     */
    @Transactional(readOnly = true)
    public Page<StockMovementView> listMovements(
            Long storeId,
            KardexFilter filter,
            Pageable pageable,
            boolean includeCost) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(filter.productId(), "productId");

        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(storeId, filter, params);

        long total = jdbc.sql("""
                SELECT COUNT(*)
                FROM stock_movements sm
                """ + where)
                .params(params)
                .query(Long.class)
                .single();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderBy = buildOrderBy(pageable.getSort());
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        List<StockMovementView> rows = jdbc.sql("""
                SELECT sm.id,
                       sm.occurred_at,
                       sm.movement_type,
                       sm.quantity_delta,
                       sm.balance_after,
                       sm.unit_cost,
                       l.lot_number,
                       sm.source_type,
                       sm.source_id,
                       sm.reason,
                       u.username
                FROM stock_movements sm
                JOIN users u ON u.id = sm.user_id
                LEFT JOIN lots l ON l.id = sm.lot_id
                """ + where + "\n" + orderBy + "\nLIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, rowNum) -> mapMovement(rs, includeCost))
                .list();

        return new PageImpl<>(rows, pageable, total);
    }

    private String buildWhere(Long storeId, KardexFilter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("""
                WHERE sm.store_id = :storeId
                  AND sm.product_id = :productId
                """);
        params.put("storeId", storeId);
        params.put("productId", filter.productId());

        if (filter.from() != null) {
            where.append(" AND sm.occurred_at >= :from");
            params.put("from", filter.from());
        }
        if (filter.to() != null) {
            where.append(" AND sm.occurred_at <= :to");
            params.put("to", filter.to());
        }
        if (filter.movementType() != null) {
            where.append(" AND sm.movement_type = :movementType");
            params.put("movementType", filter.movementType().name());
        }
        if (filter.lotId() != null) {
            where.append(" AND sm.lot_id = :lotId");
            params.put("lotId", filter.lotId());
        }
        return where.toString();
    }

    private String buildOrderBy(Sort sort) {
        List<String> clauses = new ArrayList<>();
        boolean includesId = false;
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column != null) {
                clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
                if ("id".equals(order.getProperty())) {
                    includesId = true;
                }
            }
        }
        if (!includesId) {
            clauses.add("sm.id DESC");
        }
        return "ORDER BY " + String.join(", ", clauses);
    }

    private StockMovementView mapMovement(ResultSet rs, boolean includeCost) throws SQLException {
        BigDecimal unitCost = includeCost ? Rounding.unitCost(rs.getBigDecimal("unit_cost")) : null;
        return new StockMovementView(
                rs.getLong("id"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                StockMovementType.valueOf(rs.getString("movement_type")),
                Rounding.quantity(rs.getBigDecimal("quantity_delta")),
                Rounding.quantity(rs.getBigDecimal("balance_after")),
                unitCost,
                rs.getString("lot_number"),
                StockSourceType.valueOf(rs.getString("source_type")),
                rs.getLong("source_id"),
                rs.getString("reason"),
                rs.getString("username"));
    }

    /** Filtros de consulta del kardex. */
    public record KardexFilter(
            Long productId,
            OffsetDateTime from,
            OffsetDateTime to,
            StockMovementType movementType,
            Long lotId
    ) {
    }

    /** Proyección de una fila del kardex. */
    public record StockMovementView(
            Long id,
            OffsetDateTime occurredAt,
            StockMovementType movementType,
            BigDecimal quantityDelta,
            BigDecimal balanceAfter,
            BigDecimal unitCost,
            String lotNumber,
            StockSourceType sourceType,
            Long sourceId,
            String reason,
            String username
    ) {
    }
}
