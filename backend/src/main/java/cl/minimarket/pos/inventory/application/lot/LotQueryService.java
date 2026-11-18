package cl.minimarket.pos.inventory.application.lot;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.domain.FefoAllocator;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Quantity;
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
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consultas de lotes, alertas de vencimiento y sugerencia FEFO para inventario y punto de venta.
 */
@Service
public class LotQueryService {

    private static final int DEFAULT_EXPIRY_DAYS_AHEAD = 15;
    private static final String SETTING_EXPIRY_DAYS_AHEAD = "alerts.expiry.days_ahead";

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "l.id",
            "lotNumber", "l.lot_number",
            "expiryDate", "l.expiry_date",
            "quantity", "l.quantity",
            "receivedAt", "l.received_at",
            "status", "l.status",
            "sku", "p.sku",
            "productName", "p.name");

    private static final Map<String, String> ALERT_SORT_COLUMNS = Map.of(
            "id", "l.id",
            "lotId", "l.id",
            "expiryDate", "l.expiry_date",
            "quantity", "l.quantity",
            "lotNumber", "l.lot_number",
            "sku", "p.sku",
            "productName", "p.name");

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final SettingsApi settingsApi;
    private final ProductCatalogApi productCatalogApi;

    public LotQueryService(
            JdbcClient jdbc,
            BusinessClock clock,
            SettingsApi settingsApi,
            ProductCatalogApi productCatalogApi) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settingsApi = Objects.requireNonNull(settingsApi, "settingsApi");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
    }

    /**
     * Lista lotes paginados con filtros opcionales por local, producto, estado, ventana de
     * vencimiento y búsqueda libre por SKU, nombre o número de lote.
     */
    @Transactional(readOnly = true)
    public Page<LotView> findLots(
            Long storeId,
            Long productId,
            String status,
            Integer expiringWithinDays,
            String q,
            Pageable pageable) {
        Objects.requireNonNull(pageable, "pageable");
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        Map<String, Object> params = new LinkedHashMap<>();

        if (storeId != null) {
            where.append(" AND l.store_id = :storeId ");
            params.put("storeId", storeId);
        }
        if (productId != null) {
            where.append(" AND l.product_id = :productId ");
            params.put("productId", productId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND l.status = :status ");
            params.put("status", status.trim().toUpperCase());
        }
        LocalDate today = clock.businessDate();
        if (expiringWithinDays != null) {
            if (expiringWithinDays < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "expiringWithinDays debe ser mayor o igual a cero");
            }
            where.append(" AND l.expiry_date IS NOT NULL AND l.expiry_date <= :cutoffDate ");
            params.put("cutoffDate", today.plusDays(expiringWithinDays));
        }
        if (q != null && !q.isBlank()) {
            where.append("""
                      AND (f_unaccent(lower(l.lot_number)) ILIKE f_unaccent(lower(:pattern))
                           OR lower(p.sku) LIKE lower(:pattern)
                           OR f_unaccent(lower(p.name)) ILIKE f_unaccent(lower(:pattern)))
                    """);
            params.put("pattern", "%" + q.trim() + "%");
        }

        String fromClause = """
                FROM lots l
                JOIN stores st ON st.id = l.store_id
                JOIN products p ON p.id = l.product_id
                """ + where;

        long total = bindParams(
                jdbc.sql("SELECT COUNT(*) " + fromClause), params)
                .query(Long.class)
                .single();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderBy = resolveOrderBy(
                pageable.getSort(), SORT_COLUMNS, "l.expiry_date ASC NULLS LAST, l.id ASC", true);
        StringBuilder dataSql = new StringBuilder("""
                SELECT l.id,
                       l.store_id,
                       st.code AS store_code,
                       st.name AS store_name,
                       l.product_id,
                       p.sku,
                       p.name AS product_name,
                       p.unit_of_measure,
                       l.lot_number,
                       l.expiry_date,
                       l.quantity,
                       l.unit_cost,
                       l.received_at,
                       l.status,
                       l.version
                """).append(fromClause).append(orderBy);

        Map<String, Object> pagedParams = new LinkedHashMap<>(params);
        if (pageable.isPaged()) {
            dataSql.append(" LIMIT :limit OFFSET :offset ");
            pagedParams.put("limit", pageable.getPageSize());
            pagedParams.put("offset", pageable.getOffset());
        }

        List<LotView> content = bindParams(jdbc.sql(dataSql.toString()), pagedParams)
                .query((rs, rowNum) -> mapLotView(rs, today))
                .list();

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Obtiene un lote por su identificador.
     */
    @Transactional(readOnly = true)
    public LotView getById(Long lotId) {
        Objects.requireNonNull(lotId, "lotId");
        LocalDate today = clock.businessDate();
        return jdbc.sql("""
                SELECT l.id,
                       l.store_id,
                       st.code AS store_code,
                       st.name AS store_name,
                       l.product_id,
                       p.sku,
                       p.name AS product_name,
                       p.unit_of_measure,
                       l.lot_number,
                       l.expiry_date,
                       l.quantity,
                       l.unit_cost,
                       l.received_at,
                       l.status,
                       l.version
                FROM lots l
                JOIN stores st ON st.id = l.store_id
                JOIN products p ON p.id = l.product_id
                WHERE l.id = :lotId
                """)
                .param("lotId", lotId)
                .query((rs, rowNum) -> mapLotView(rs, today))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Lote no encontrado: " + lotId));
    }

    /**
     * Lista lotes paginados con saldo positivo vencidos o próximos a vencer dentro de la ventana
     * configurada ({@code alerts.expiry.days_ahead}), calculando {@code valuation} cuando
     * {@code includeValuation} es {@code true}.
     *
     * @param storeId identificador del local (opcional)
     * @param daysAheadOverride ventana en días (opcional; usa {@code alerts.expiry.days_ahead})
     * @param includeValuation {@code true} si el usuario posee {@code catalog:cost:read}
     * @param pageable paginación y ordenamiento
     * @return página de alertas de vencimiento
     */
    @Transactional(readOnly = true)
    public Page<LotExpiryAlertView> findExpiryAlerts(
            Long storeId,
            Integer daysAheadOverride,
            boolean includeValuation,
            Pageable pageable) {
        Objects.requireNonNull(pageable, "pageable");
        int daysAhead = resolveDaysAhead(storeId, daysAheadOverride);
        LocalDate today = clock.businessDate();
        LocalDate cutoff = today.plusDays(daysAhead);

        StringBuilder where = new StringBuilder("""
                WHERE l.quantity > 0
                  AND l.expiry_date IS NOT NULL
                  AND l.expiry_date <= :cutoff
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cutoff", cutoff);
        if (storeId != null) {
            where.append(" AND l.store_id = :storeId ");
            params.put("storeId", storeId);
        }

        String fromClause = """
                FROM lots l
                JOIN stores st ON st.id = l.store_id
                JOIN products p ON p.id = l.product_id
                """ + where;

        long total = bindParams(jdbc.sql("SELECT COUNT(*) " + fromClause), params)
                .query(Long.class)
                .single();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderBy = resolveOrderBy(
                pageable.getSort(), ALERT_SORT_COLUMNS, "l.expiry_date ASC, l.id ASC", false);
        StringBuilder dataSql = new StringBuilder("""
                SELECT l.id,
                       l.store_id,
                       st.code AS store_code,
                       st.name AS store_name,
                       l.product_id,
                       p.sku,
                       p.name AS product_name,
                       l.lot_number,
                       l.expiry_date,
                       l.quantity,
                       COALESCE(l.unit_cost, p.avg_cost) AS effective_cost
                """).append(fromClause).append(orderBy);

        Map<String, Object> pagedParams = new LinkedHashMap<>(params);
        if (pageable.isPaged()) {
            dataSql.append(" LIMIT :limit OFFSET :offset ");
            pagedParams.put("limit", pageable.getPageSize());
            pagedParams.put("offset", pageable.getOffset());
        }

        List<LotExpiryAlertView> content = bindParams(jdbc.sql(dataSql.toString()), pagedParams)
                .query((rs, rowNum) -> mapExpiryAlert(rs, today, includeValuation))
                .list();

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Sugiere la asignación FEFO para una cantidad solicitada de un producto en un local, sin
     * modificar saldos ni bloquear filas. Cuando el saldo disponible en lotes es insuficiente,
     * devuelve las asignaciones parciales en orden FEFO junto con el faltante ({@code shortage}).
     */
    @Transactional(readOnly = true)
    public FefoSuggestion suggestFefo(Long storeId, Long productId, BigDecimal quantity) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantity, "quantity");

        ProductCatalogApi.ProductSnapshot product = productCatalogApi.findByIds(List.of(productId)).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Producto no encontrado: " + productId));

        if (product.saleType() == SaleType.PACK) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "La sugerencia FEFO debe solicitarse sobre el producto base: " + productId);
        }
        BigDecimal normalizedQty = Rounding.quantity(quantity);
        if (normalizedQty.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "La cantidad debe ser mayor a cero");
        }
        if (product.unitOfMeasure() == UnitOfMeasure.UNIT && !Quantity.of(normalizedQty).isWholeNumber()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "La cantidad debe ser entera para el producto por unidad: " + productId);
        }
        if (!product.lotControlled()) {
            return new FefoSuggestion(productId, normalizedQty, List.of(), normalizedQty);
        }

        List<FefoAllocator.AvailableLot> availableLots = jdbc.sql("""
                SELECT id, lot_number, expiry_date, quantity
                FROM lots
                WHERE store_id = :storeId
                  AND product_id = :productId
                  AND quantity > 0
                ORDER BY expiry_date ASC NULLS LAST, id ASC
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .query((rs, rowNum) -> {
                    Date sqlExpiry = rs.getDate("expiry_date");
                    return new FefoAllocator.AvailableLot(
                            rs.getLong("id"),
                            rs.getString("lot_number"),
                            sqlExpiry != null ? sqlExpiry.toLocalDate() : null,
                            rs.getBigDecimal("quantity"));
                })
                .list();

        Map<Long, BigDecimal> availableById = new LinkedHashMap<>();
        for (FefoAllocator.AvailableLot lot : availableLots) {
            availableById.put(lot.lotId(), Rounding.quantity(lot.availableQuantity()));
        }

        FefoAllocator.FefoAllocationResult result =
                FefoAllocator.suggest(normalizedQty, availableLots);

        List<FefoSuggestionView> allocations = result.deductions().stream()
                .map(d -> new FefoSuggestionView(
                        d.lotId(),
                        d.lotNumber(),
                        d.expiryDate(),
                        availableById.getOrDefault(d.lotId(), d.allocatedQuantity()),
                        d.allocatedQuantity()))
                .toList();

        return new FefoSuggestion(productId, normalizedQty, allocations, result.shortage());
    }

    private int resolveDaysAhead(Long storeId, Integer override) {
        if (override != null) {
            if (override < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "daysAhead debe ser mayor o igual a cero");
            }
            return override;
        }
        Integer configured = settingsApi.get(SETTING_EXPIRY_DAYS_AHEAD, storeId, Integer.class);
        return configured != null && configured >= 0 ? configured : DEFAULT_EXPIRY_DAYS_AHEAD;
    }

    private JdbcClient.StatementSpec bindParams(
            JdbcClient.StatementSpec spec, Map<String, Object> params) {
        JdbcClient.StatementSpec current = spec;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            current = current.param(entry.getKey(), entry.getValue());
        }
        return current;
    }

    private String resolveOrderBy(
            Sort sort,
            Map<String, String> allowedColumns,
            String defaultOrderBy,
            boolean expiryNullsLast) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY " + defaultOrderBy + " ";
        }
        StringBuilder sb = new StringBuilder(" ORDER BY ");
        boolean first = true;
        boolean hasIdTieBreaker = false;
        for (Sort.Order order : sort) {
            String column = allowedColumns.get(order.getProperty());
            if (column == null) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append(column).append(order.isAscending() ? " ASC" : " DESC");
            if (expiryNullsLast && "expiryDate".equals(order.getProperty())) {
                sb.append(" NULLS LAST");
            }
            if ("l.id".equals(column)) {
                hasIdTieBreaker = true;
            }
            first = false;
        }
        if (first) {
            return " ORDER BY " + defaultOrderBy + " ";
        }
        if (!hasIdTieBreaker) {
            sb.append(", l.id ASC");
        }
        sb.append(" ");
        return sb.toString();
    }

    private static LotView mapLotView(ResultSet rs, LocalDate today) throws SQLException {
        Date sqlExpiry = rs.getDate("expiry_date");
        LocalDate expiryDate = sqlExpiry != null ? sqlExpiry.toLocalDate() : null;
        Long daysToExpiry = expiryDate != null ? ChronoUnit.DAYS.between(today, expiryDate) : null;
        BigDecimal unitCost = rs.getBigDecimal("unit_cost");
        return new LotView(
                rs.getLong("id"),
                rs.getLong("store_id"),
                rs.getString("store_code"),
                rs.getString("store_name"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("unit_of_measure"),
                rs.getString("lot_number"),
                expiryDate,
                Rounding.quantity(rs.getBigDecimal("quantity")),
                unitCost != null ? Rounding.unitCost(unitCost) : null,
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getString("status"),
                daysToExpiry,
                daysToExpiry != null && daysToExpiry < 0,
                rs.getLong("version"));
    }

    private static LotExpiryAlertView mapExpiryAlert(
            ResultSet rs,
            LocalDate today,
            boolean includeValuation) throws SQLException {
        LocalDate expiryDate = rs.getObject("expiry_date", LocalDate.class);
        long daysRemaining = ChronoUnit.DAYS.between(today, expiryDate);
        BigDecimal quantity = Rounding.quantity(rs.getBigDecimal("quantity"));
        BigDecimal effectiveCost = rs.getBigDecimal("effective_cost");
        BigDecimal valuation = includeValuation && effectiveCost != null
                ? Rounding.costTotal(quantity.multiply(effectiveCost))
                : null;
        return new LotExpiryAlertView(
                rs.getLong("id"),
                rs.getLong("store_id"),
                rs.getString("store_code"),
                rs.getString("store_name"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("lot_number"),
                expiryDate,
                quantity,
                daysRemaining,
                daysRemaining < 0,
                valuation);
    }

    /**
     * Proyección de un lote para listados y detalle.
     */
    public record LotView(
            Long id,
            Long storeId,
            String storeCode,
            String storeName,
            Long productId,
            String sku,
            String productName,
            String unitOfMeasure,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal quantity,
            BigDecimal unitCost,
            OffsetDateTime receivedAt,
            String status,
            Long daysToExpiry,
            boolean expired,
            long version) {
    }

    /**
     * Alerta de lote vencido o próximo a vencer.
     */
    public record LotExpiryAlertView(
            Long id,
            Long storeId,
            String storeCode,
            String storeName,
            Long productId,
            String sku,
            String productName,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal quantity,
            long daysRemaining,
            boolean expired,
            BigDecimal valuation) {
    }

    /**
     * Resultado completo de sugerencia FEFO para un producto en un local.
     */
    public record FefoSuggestion(
            Long productId,
            BigDecimal requestedQuantity,
            List<FefoSuggestionView> allocations,
            BigDecimal shortage) {

        public FefoSuggestion {
            allocations = List.copyOf(allocations);
        }
    }

    /**
     * Sugerencia de consumo FEFO para un lote específico.
     */
    public record FefoSuggestionView(
            Long lotId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal availableQuantity,
            BigDecimal suggestedQuantity) {
    }
}
