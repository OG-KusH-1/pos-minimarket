package cl.minimarket.pos.inventory.application.query;

import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consultas operativas de existencias por local, comparación entre locales, alertas de stock y
 * actualización de límites mínimo/máximo (T24, HU-INV-01, HU-INV-04).
 */
@Service
public class StockQueryService {

    private static final int BELOW_MIN_ALERT_LIMIT = 50;
    private static final int EXPIRING_ALERT_LIMIT = 20;
    private static final int DEFAULT_EXPIRY_DAYS_AHEAD = 15;
    private static final String SETTING_EXPIRY_DAYS_AHEAD = "alerts.expiry.days_ahead";

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "productId", "s.product_id",
            "sku", "p.sku",
            "name", "p.name",
            "quantity", "s.quantity",
            "minQty", "s.min_qty",
            "maxQty", "s.max_qty",
            "lastMovementAt", "s.last_movement_at"
    );

    private final JdbcClient jdbc;
    private final StockApi stockApi;
    private final SettingsApi settingsApi;
    private final BusinessClock clock;

    public StockQueryService(
            JdbcClient jdbc,
            StockApi stockApi,
            SettingsApi settingsApi,
            BusinessClock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.stockApi = Objects.requireNonNull(stockApi, "stockApi");
        this.settingsApi = Objects.requireNonNull(settingsApi, "settingsApi");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Lista el stock del local activo con filtros opcionales, paginación y ordenamiento.
     *
     * @param storeId          identificador del local activo
     * @param filter           criterios de filtrado
     * @param pageable         paginación y orden validado
     * @param includeValuation {@code true} si el usuario posee {@code catalog:cost:read}
     * @return página de existencias
     */
    @Transactional(readOnly = true)
    public Page<StockItemView> listStock(
            Long storeId,
            StockFilter filter,
            Pageable pageable,
            boolean includeValuation) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(filter, "filter");

        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(storeId, filter, params);

        long total = jdbc.sql("""
                SELECT COUNT(*)
                FROM stock s
                JOIN products p ON p.id = s.product_id
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

        List<StockItemView> rows = jdbc.sql("""
                SELECT s.product_id,
                       p.sku,
                       p.name,
                       c.name AS category_name,
                       p.unit_of_measure,
                       s.quantity,
                       s.min_qty,
                       s.max_qty,
                       p.avg_cost,
                       s.last_movement_at
                FROM stock s
                JOIN products p ON p.id = s.product_id
                JOIN categories c ON c.id = p.category_id
                """ + where + "\n" + orderBy + "\nLIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, rowNum) -> mapStockItem(rs, includeValuation))
                .list();

        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * Consulta la existencia de un producto en los locales asignados al usuario (o todos los
     * activos si tiene acceso global), junto con la cantidad en tránsito y el total de empresa.
     *
     * @param currentStoreId local activo de la request (para tránsito entrante)
     * @param productId      identificador del producto
     * @param scopedUserId   id del usuario para filtrar {@code user_stores}, o {@code null} si
     *                       tiene {@code security:store:all-access}
     * @return desglose por local y totales
     */
    @Transactional(readOnly = true)
    public StockByStoreView getByStore(Long currentStoreId, Long productId, Long scopedUserId) {
        Objects.requireNonNull(currentStoreId, "currentStoreId");
        Objects.requireNonNull(productId, "productId");

        ensureNonPackProductExists(productId);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("productId", productId);
        StringBuilder sql = new StringBuilder("""
                SELECT st.id AS store_id,
                       st.name AS store_name,
                       s.quantity,
                       s.min_qty,
                       s.max_qty
                FROM stock s
                JOIN stores st ON st.id = s.store_id
                WHERE s.product_id = :productId
                  AND st.active = true
                """);
        if (scopedUserId != null) {
            sql.append("""
                      AND EXISTS (
                          SELECT 1 FROM user_stores us
                          WHERE us.store_id = st.id AND us.user_id = :scopedUserId
                      )
                    """);
            params.put("scopedUserId", scopedUserId);
        }
        sql.append(" ORDER BY st.id ASC");

        List<StoreStockView> stores = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, rowNum) -> new StoreStockView(
                        rs.getLong("store_id"),
                        rs.getString("store_name"),
                        Rounding.quantity(rs.getBigDecimal("quantity")),
                        optionalQuantity(rs.getBigDecimal("min_qty")),
                        optionalQuantity(rs.getBigDecimal("max_qty"))))
                .list();

        BigDecimal inTransit = Rounding.quantity(
                stockApi.inTransitInbound(currentStoreId).getOrDefault(productId, BigDecimal.ZERO));
        BigDecimal companyQuantity = stockApi.companyQuantity(productId);

        return new StockByStoreView(productId, stores, inTransit, companyQuantity);
    }

    /**
     * Actualiza los límites {@code min_qty} y {@code max_qty} de un producto en el local activo,
     * validando RN-34 ({@code minQty >= 0} y {@code maxQty >= minQty}).
     *
     * @param storeId          identificador del local activo
     * @param productId        identificador del producto
     * @param minQty           nuevo mínimo ({@code >= 0})
     * @param maxQty           nuevo máximo (opcional; si se informa debe ser {@code >= minQty})
     * @param includeValuation {@code true} si el usuario posee {@code catalog:cost:read}
     * @return registro de stock actualizado
     */
    @Transactional
    public StockItemView updateLimits(
            Long storeId,
            Long productId,
            BigDecimal minQty,
            BigDecimal maxQty,
            boolean includeValuation) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(productId, "productId");

        validateLimits(minQty, maxQty);
        BigDecimal normalizedMin = Rounding.quantity(minQty);
        BigDecimal normalizedMax = maxQty != null ? Rounding.quantity(maxQty) : null;

        int updated = jdbc.sql("""
                UPDATE stock
                SET min_qty = :minQty,
                    max_qty = :maxQty,
                    updated_at = now()
                WHERE store_id = :storeId AND product_id = :productId
                """)
                .param("minQty", normalizedMin)
                .param("maxQty", normalizedMax)
                .param("storeId", storeId)
                .param("productId", productId)
                .update();

        if (updated == 0) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "No existe registro de stock para el producto en el local activo.");
        }

        return jdbc.sql("""
                SELECT s.product_id,
                       p.sku,
                       p.name,
                       c.name AS category_name,
                       p.unit_of_measure,
                       s.quantity,
                       s.min_qty,
                       s.max_qty,
                       p.avg_cost,
                       s.last_movement_at
                FROM stock s
                JOIN products p ON p.id = s.product_id
                JOIN categories c ON c.id = p.category_id
                WHERE s.store_id = :storeId AND s.product_id = :productId
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .query((rs, rowNum) -> mapStockItem(rs, includeValuation))
                .single();
    }

    /**
     * Obtiene el resumen de alertas de inventario del local activo: productos bajo mínimo (máx 50),
     * conteo en cero y lotes próximos a vencer (máx 20).
     *
     * @param storeId          identificador del local activo
     * @param includeValuation {@code true} si el usuario posee {@code catalog:cost:read}
     * @return resumen de alertas
     */
    @Transactional(readOnly = true)
    public StockAlertView getAlerts(Long storeId, boolean includeValuation) {
        Objects.requireNonNull(storeId, "storeId");

        List<StockItemView> belowMin = jdbc.sql("""
                SELECT s.product_id,
                       p.sku,
                       p.name,
                       c.name AS category_name,
                       p.unit_of_measure,
                       s.quantity,
                       s.min_qty,
                       s.max_qty,
                       p.avg_cost,
                       s.last_movement_at
                FROM stock s
                JOIN products p ON p.id = s.product_id
                JOIN categories c ON c.id = p.category_id
                WHERE s.store_id = :storeId
                  AND s.min_qty IS NOT NULL
                  AND s.quantity <= s.min_qty
                ORDER BY (s.quantity - s.min_qty) ASC, p.name ASC, s.product_id ASC
                LIMIT :limit
                """)
                .param("storeId", storeId)
                .param("limit", BELOW_MIN_ALERT_LIMIT)
                .query((rs, rowNum) -> mapStockItem(rs, includeValuation))
                .list();

        long zeroCount = jdbc.sql("""
                SELECT COUNT(*)
                FROM stock
                WHERE store_id = :storeId AND quantity = 0
                """)
                .param("storeId", storeId)
                .query(Long.class)
                .single();

        Integer configuredDays = settingsApi.get(SETTING_EXPIRY_DAYS_AHEAD, storeId, Integer.class);
        int daysAhead = configuredDays != null && configuredDays > 0
                ? configuredDays
                : DEFAULT_EXPIRY_DAYS_AHEAD;
        LocalDate today = clock.businessDate();
        LocalDate cutoffDate = today.plusDays(daysAhead);

        long expiringCount = jdbc.sql("""
                SELECT COUNT(*)
                FROM lots
                WHERE store_id = :storeId
                  AND quantity > 0
                  AND expiry_date IS NOT NULL
                  AND expiry_date <= :cutoffDate
                """)
                .param("storeId", storeId)
                .param("cutoffDate", cutoffDate)
                .query(Long.class)
                .single();

        List<ExpiryAlertView> expiring = expiringCount == 0
                ? List.of()
                : jdbc.sql("""
                        SELECT l.id AS lot_id,
                               l.product_id,
                               p.sku,
                               p.name,
                               l.lot_number,
                               l.expiry_date,
                               l.quantity,
                               COALESCE(l.unit_cost, p.avg_cost) AS effective_cost
                        FROM lots l
                        JOIN products p ON p.id = l.product_id
                        WHERE l.store_id = :storeId
                          AND l.quantity > 0
                          AND l.expiry_date IS NOT NULL
                          AND l.expiry_date <= :cutoffDate
                        ORDER BY l.expiry_date ASC, l.id ASC
                        LIMIT :limit
                        """)
                        .param("storeId", storeId)
                        .param("cutoffDate", cutoffDate)
                        .param("limit", EXPIRING_ALERT_LIMIT)
                        .query((rs, rowNum) -> mapExpiryAlert(rs, today, includeValuation))
                        .list();

        return new StockAlertView(belowMin, zeroCount, expiringCount, expiring);
    }

    private void ensureNonPackProductExists(Long productId) {
        String saleType = jdbc.sql("""
                SELECT sale_type
                FROM products
                WHERE id = :productId
                """)
                .param("productId", productId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "Producto no encontrado."));

        if ("PACK".equals(saleType)) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "Los productos de tipo PACK no mantienen existencias propias.");
        }
    }

    private void validateLimits(BigDecimal minQty, BigDecimal maxQty) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (minQty == null || minQty.compareTo(BigDecimal.ZERO) < 0) {
            errors.add(new FieldErrorDetail(
                    "minQty",
                    "MIN_NEGATIVE",
                    "El stock mínimo debe ser mayor o igual a 0."));
        }
        if (minQty != null && maxQty != null && maxQty.compareTo(minQty) < 0) {
            errors.add(new FieldErrorDetail(
                    "maxQty",
                    "MIN_MAX_ORDER",
                    "El stock máximo no puede ser menor que el stock mínimo."));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Los límites de stock son inválidos.",
                    Map.of(),
                    errors);
        }
    }

    private String buildWhere(Long storeId, StockFilter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("WHERE s.store_id = :storeId");
        params.put("storeId", storeId);

        if (filter.q() != null && !filter.q().isBlank()) {
            where.append("""
                      AND (f_unaccent(lower(p.name)) ILIKE f_unaccent(lower(:qPattern))
                           OR lower(p.sku) LIKE lower(:qPattern))
                    """);
            params.put("qPattern", "%" + filter.q().trim() + "%");
        }
        if (filter.categoryId() != null) {
            where.append(" AND p.category_id = :categoryId");
            params.put("categoryId", filter.categoryId());
        }
        if (filter.lotControlled() != null) {
            where.append(" AND p.lot_controlled = :lotControlled");
            params.put("lotControlled", filter.lotControlled());
        }
        if (filter.status() != null) {
            switch (filter.status()) {
                case ZERO -> where.append(" AND s.quantity = 0");
                case BELOW_MIN -> where.append(" AND s.min_qty IS NOT NULL AND s.quantity <= s.min_qty");
                case ABOVE_MAX -> where.append(" AND s.max_qty IS NOT NULL AND s.quantity > s.max_qty");
                case OK -> where.append("""
                          AND s.quantity > 0
                          AND (s.min_qty IS NULL OR s.quantity > s.min_qty)
                          AND (s.max_qty IS NULL OR s.quantity <= s.max_qty)
                        """);
            }
        }
        return where.toString();
    }

    private String buildOrderBy(Sort sort) {
        List<String> clauses = new ArrayList<>();
        boolean includesProductId = false;
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column != null) {
                clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
                if ("productId".equals(order.getProperty())) {
                    includesProductId = true;
                }
            }
        }
        if (!includesProductId) {
            clauses.add("s.product_id ASC");
        }
        return "ORDER BY " + String.join(", ", clauses);
    }

    private StockItemView mapStockItem(ResultSet rs, boolean includeValuation) throws SQLException {
        BigDecimal quantity = Rounding.quantity(rs.getBigDecimal("quantity"));
        BigDecimal minQty = optionalQuantity(rs.getBigDecimal("min_qty"));
        BigDecimal maxQty = optionalQuantity(rs.getBigDecimal("max_qty"));
        BigDecimal avgCost = rs.getBigDecimal("avg_cost");
        BigDecimal valuation = includeValuation && avgCost != null
                ? Rounding.costTotal(quantity.multiply(avgCost))
                : null;
        return new StockItemView(
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("category_name"),
                UnitOfMeasure.valueOf(rs.getString("unit_of_measure")),
                quantity,
                minQty,
                maxQty,
                StockStatus.evaluate(quantity, minQty, maxQty),
                valuation,
                rs.getObject("last_movement_at", OffsetDateTime.class));
    }

    private ExpiryAlertView mapExpiryAlert(
            ResultSet rs,
            LocalDate today,
            boolean includeValuation) throws SQLException {
        LocalDate expiryDate = rs.getObject("expiry_date", LocalDate.class);
        long daysToExpiry = ChronoUnit.DAYS.between(today, expiryDate);
        BigDecimal quantity = Rounding.quantity(rs.getBigDecimal("quantity"));
        BigDecimal effectiveCost = rs.getBigDecimal("effective_cost");
        BigDecimal valuation = includeValuation && effectiveCost != null
                ? Rounding.costTotal(quantity.multiply(effectiveCost))
                : null;
        return new ExpiryAlertView(
                rs.getLong("lot_id"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("lot_number"),
                expiryDate,
                daysToExpiry,
                quantity,
                valuation);
    }

    private static BigDecimal optionalQuantity(BigDecimal value) {
        return value != null ? Rounding.quantity(value) : null;
    }

    /** Filtros de consulta para {@link #listStock}. */
    public record StockFilter(
            String q,
            Long categoryId,
            StockStatus status,
            Boolean lotControlled
    ) {
    }

    /** Proyección interna de una fila de stock en el local activo. */
    public record StockItemView(
            Long productId,
            String sku,
            String name,
            String categoryName,
            UnitOfMeasure unitOfMeasure,
            BigDecimal quantity,
            BigDecimal minQty,
            BigDecimal maxQty,
            StockStatus status,
            BigDecimal valuation,
            OffsetDateTime lastMovementAt
    ) {
    }

    /** Existencia de un producto en un local específico. */
    public record StoreStockView(
            Long storeId,
            String storeName,
            BigDecimal quantity,
            BigDecimal minQty,
            BigDecimal maxQty
    ) {
    }

    /** Desglose de existencia de un producto por locales asignados y totales. */
    public record StockByStoreView(
            Long productId,
            List<StoreStockView> stores,
            BigDecimal inTransit,
            BigDecimal companyQuantity
    ) {
    }

    /** Alerta de lote próximo a vencer o vencido. */
    public record ExpiryAlertView(
            Long lotId,
            Long productId,
            String sku,
            String name,
            String lotNumber,
            LocalDate expiryDate,
            long daysToExpiry,
            BigDecimal quantity,
            BigDecimal valuation
    ) {
    }

    /** Resumen consolidado de alertas de stock y vencimiento del local activo. */
    public record StockAlertView(
            List<StockItemView> belowMin,
            long zeroCount,
            long expiringCount,
            List<ExpiryAlertView> expiring
    ) {
    }
}
