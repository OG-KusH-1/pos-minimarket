package cl.minimarket.pos.replenishment.application.run;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.replenishment.domain.ReplenishmentFlag;
import cl.minimarket.pos.replenishment.domain.ReplenishmentStatus;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Consultas de corridas y líneas de reabastecimiento ({@code API.md} F.2): listado paginado de
 * corridas con {@code countsByStatus}, detalle de corrida y listado paginado de líneas con
 * filtros por estado, proveedor, flag y categoría.
 *
 * <p>Las líneas se leen con SQL nativo sobre la <b>tabla propia</b> del módulo (el filtro por
 * {@code flag} necesita {@code = ANY(flags)}); el nombre del producto, de la unidad de compra y
 * del proveedor se resuelven por las APIs públicas de {@code catalog} y {@code purchasing}, sin
 * SQL hacia tablas de otros módulos.
 */
@Service
public class ReplenishmentQueryService {

    /** Vista de línea para la capa web: datos propios + enriquecimiento de catálogo/proveedor. */
    public record LineView(
            Long id,
            Long runId,
            Long productId,
            String productSku,
            String productName,
            Long supplierId,
            String supplierName,
            String purchaseUnitName,
            BigDecimal unitsPerPurchaseUnit,
            int validDays,
            int censoredDays,
            BigDecimal avgDailyDemand,
            BigDecimal demandStd,
            int leadTimeDays,
            int reviewPeriodDays,
            BigDecimal onHand,
            BigDecimal inTransit,
            BigDecimal safetyStock,
            BigDecimal reorderPoint,
            BigDecimal targetLevel,
            BigDecimal suggestedQty,
            BigDecimal suggestedPurchaseUnits,
            BigDecimal finalPurchaseUnits,
            BigDecimal unitCostNet,
            LocalDate estimatedStockoutDate,
            BigDecimal daysOfCover,
            ReplenishmentStatus status,
            List<String> flags
    ) {
    }

    /** Filtros del listado de líneas; {@code null} significa sin filtro. */
    public record LineFilter(
            ReplenishmentStatus status,
            Long supplierId,
            ReplenishmentFlag flag,
            Long categoryId
    ) {
    }

    /** Orden por defecto del listado de líneas: id ascendente (orden del cálculo). */
    static final String DEFAULT_LINES_SORT = " ORDER BY l.id ASC ";

    private static final Map<String, String> LINE_SORT_COLUMNS = Map.of(
            "id", "l.id",
            "productId", "l.product_id",
            "avgDailyDemand", "l.avg_daily_demand",
            "onHand", "l.on_hand",
            "suggestedQty", "l.suggested_qty",
            "suggestedPurchaseUnits", "l.suggested_purchase_units",
            "status", "l.status");

    private final ReplenishmentRunRepository runRepository;
    private final ReplenishmentLineRepository lineRepository;
    private final ProductCatalogApi productCatalogApi;
    private final ProductSupplierApi productSupplierApi;
    private final JdbcClient jdbc;

    public ReplenishmentQueryService(ReplenishmentRunRepository runRepository,
                                     ReplenishmentLineRepository lineRepository,
                                     ProductCatalogApi productCatalogApi,
                                     ProductSupplierApi productSupplierApi,
                                     JdbcClient jdbc) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.lineRepository = Objects.requireNonNull(lineRepository, "lineRepository");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.productSupplierApi = Objects.requireNonNull(productSupplierApi, "productSupplierApi");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Lista las corridas del local, de la más reciente a la más antigua, con el conteo de líneas
     * por estado de cada una.
     */
    @Transactional(readOnly = true)
    public PageResponse<RunWithCounts> listRuns(Long storeId, Pageable pageable) {
        org.springframework.data.domain.Page<ReplenishmentRun> page =
                runRepository.findByStoreId(storeId, pageable);
        Map<Long, Map<String, Long>> counts = countsByRunIds(
                page.getContent().stream().map(ReplenishmentRun::getId).toList());
        List<RunWithCounts> content = page.getContent().stream()
                .map(run -> new RunWithCounts(
                        run.getId(),
                        run.getStoreId(),
                        run.getAsOfDate(),
                        run.getStatus(),
                        run.getLinesCount(),
                        counts.getOrDefault(run.getId(), Map.of()),
                        run.getStartedAt(),
                        run.getFinishedAt(),
                        run.getError()))
                .toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(),
                page.getSort().stream()
                        .map(order -> order.getProperty() + "," + order.getDirection().name().toLowerCase())
                        .toList());
    }

    /** Detalle de una corrida del local con su conteo por estado. */
    @Transactional(readOnly = true)
    public RunWithCounts getRun(Long storeId, long runId) {
        ReplenishmentRun run = requireRun(storeId, runId);
        return new RunWithCounts(
                run.getId(),
                run.getStoreId(),
                run.getAsOfDate(),
                run.getStatus(),
                run.getLinesCount(),
                countsByRunIds(List.of(runId)).getOrDefault(runId, Map.of()),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getError());
    }

    /**
     * Lista las líneas de una corrida con filtros y paginación, enriquecidas con los datos del
     * catálogo y del proveedor elegido.
     */
    @Transactional(readOnly = true)
    public PageResponse<LineView> listLines(Long storeId, long runId, LineFilter filter,
                                            String orderClause, Pageable pageable) {
        requireRun(storeId, runId);

        List<Long> productIds = null;
        if (filter.categoryId() != null) {
            productIds = productIdsInCategory(runId, filter.categoryId());
            if (productIds.isEmpty()) {
                return new PageResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(),
                        0, 0, List.of());
            }
        }

        StringBuilder where = new StringBuilder(" WHERE l.run_id = :runId ");
        Map<String, Object> params = new HashMap<>();
        params.put("runId", runId);
        if (filter.status() != null) {
            where.append(" AND l.status = :status ");
            params.put("status", filter.status().name());
        }
        if (filter.supplierId() != null) {
            where.append(" AND l.supplier_id = :supplierId ");
            params.put("supplierId", filter.supplierId());
        }
        if (filter.flag() != null) {
            where.append(" AND CAST(:flag AS varchar) = ANY(l.flags) ");
            params.put("flag", filter.flag().name());
        }
        if (productIds != null) {
            where.append(" AND l.product_id IN (:productIds) ");
            params.put("productIds", productIds);
        }

        Long total = jdbc.sql("SELECT COUNT(*) FROM replenishment_lines l" + where)
                .params(params)
                .query(Long.class)
                .single();
        List<LineRow> rows = jdbc.sql(
                        "SELECT l.* FROM replenishment_lines l" + where + orderClause
                                + " LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", pageable.getPageSize())
                .param("offset", pageable.getOffset())
                .query((rs, rowNum) -> new LineRow(
                        rs.getLong("id"),
                        rs.getLong("run_id"),
                        rs.getLong("product_id"),
                        (Long) rs.getObject("supplier_id"),
                        (Long) rs.getObject("product_supplier_id"),
                        rs.getInt("valid_days"),
                        rs.getInt("censored_days"),
                        rs.getBigDecimal("avg_daily_demand"),
                        rs.getBigDecimal("demand_std"),
                        rs.getInt("lead_time_days"),
                        rs.getInt("review_period_days"),
                        rs.getBigDecimal("on_hand"),
                        rs.getBigDecimal("in_transit"),
                        rs.getBigDecimal("safety_stock"),
                        rs.getBigDecimal("reorder_point"),
                        rs.getBigDecimal("target_level"),
                        rs.getBigDecimal("suggested_qty"),
                        rs.getBigDecimal("suggested_purchase_units"),
                        rs.getBigDecimal("final_purchase_units"),
                        rs.getBigDecimal("unit_cost_net"),
                        rs.getDate("estimated_stockout_date") != null
                                ? rs.getDate("estimated_stockout_date").toLocalDate() : null,
                        rs.getBigDecimal("days_of_cover"),
                        ReplenishmentStatus.valueOf(rs.getString("status")),
                        List.of((String[]) rs.getArray("flags").getArray())))
                .list();

        return new PageResponse<>(enrich(rows), pageable.getPageNumber(), pageable.getPageSize(),
                total, total == 0 ? 0 : (int) Math.ceil((double) total / pageable.getPageSize()),
                sortDescription(pageable));
    }

    /**
     * Columna de orden validada por la lista blanca del controlador, ya con el orden por defecto
     * aplicado.
     */
    public static String orderClause(List<Map.Entry<String, Boolean>> orders) {
        if (orders.isEmpty()) {
            return DEFAULT_LINES_SORT;
        }
        StringBuilder clause = new StringBuilder(" ORDER BY ");
        for (int i = 0; i < orders.size(); i++) {
            if (i > 0) {
                clause.append(", ");
            }
            clause.append(LINE_SORT_COLUMNS.get(orders.get(i).getKey()))
                    .append(orders.get(i).getValue() ? " ASC" : " DESC");
        }
        clause.append(" ");
        return clause.toString();
    }

    /** Columna real de una propiedad ordenable, o {@code null} si no está en la lista blanca. */
    static String sortColumn(String property) {
        return LINE_SORT_COLUMNS.get(property);
    }

    /** Columna por defecto ({@code l.id}), usada como último criterio estable. */
    static String defaultSortColumn() {
        return "l.id";
    }

    /**
     * Vista enriquecida de una sola línea (la respuesta del {@code PATCH}); valida que la corrida
     * dueña pertenezca al local.
     */
    @Transactional(readOnly = true)
    public LineView lineView(Long storeId, ReplenishmentLine line) {
        requireRun(storeId, line.getRunId());
        LineRow row = new LineRow(
                line.getId(),
                line.getRunId(),
                line.getProductId(),
                line.getSupplierId(),
                line.getProductSupplierId(),
                line.getValidDays(),
                line.getCensoredDays(),
                line.getAvgDailyDemand(),
                line.getDemandStd(),
                line.getLeadTimeDays(),
                line.getReviewPeriodDays(),
                line.getOnHand(),
                line.getInTransit(),
                line.getSafetyStock(),
                line.getReorderPoint(),
                line.getTargetLevel(),
                line.getSuggestedQty(),
                line.getSuggestedPurchaseUnits(),
                line.getFinalPurchaseUnits(),
                line.getUnitCostNet(),
                line.getEstimatedStockoutDate(),
                line.getDaysOfCover(),
                line.getStatus(),
                line.getFlags());
        return enrich(List.of(row)).getFirst();
    }

    /**
     * Ids de producto de la corrida cuya categoría (directa) pertenece a la cadena de la
     * categoría del filtro: la propia categoría o cualquiera de sus descendientes (dos niveles).
     */
    private List<Long> productIdsInCategory(long runId, Long categoryId) {
        Set<Long> allowed = new HashSet<>(productCatalogApi.categoryChain(categoryId));
        Map<Long, ProductSnapshot> snapshots = new HashMap<>();
        List<Long> lineProductIds = lineRepository.findProductIdsByRunId(runId);
        for (ProductSnapshot snapshot : productCatalogApi.findByIds(lineProductIds)) {
            snapshots.put(snapshot.id(), snapshot);
        }
        // Caché por categoría distinta: la cadena de cada una se consulta una sola vez.
        Map<Long, Boolean> matches = new HashMap<>();
        List<Long> result = new ArrayList<>();
        for (Long productId : lineProductIds) {
            ProductSnapshot snapshot = snapshots.get(productId);
            if (snapshot == null || snapshot.categoryId() == null) {
                continue;
            }
            boolean inCategory = matches.computeIfAbsent(snapshot.categoryId(), id -> {
                try {
                    return productCatalogApi.categoryChain(id).stream().anyMatch(allowed::contains);
                } catch (BusinessException ex) {
                    return false;
                }
            });
            if (inCategory) {
                result.add(productId);
            }
        }
        return result;
    }

    /** Enriquecimiento de líneas con catálogo (SKU, nombre) y proveedor (nombre, unidad de compra). */
    private List<LineView> enrich(List<LineRow> rows) {
        Map<Long, ProductSnapshot> products = new LinkedHashMap<>();
        for (ProductSnapshot snapshot : productCatalogApi.findByIds(
                rows.stream().map(LineRow::productId).distinct().toList())) {
            products.put(snapshot.id(), snapshot);
        }
        Map<Long, ProductSupplierApi.SupplierRef> suppliers = new LinkedHashMap<>();
        for (Long supplierId : rows.stream().map(LineRow::supplierId)
                .filter(Objects::nonNull).distinct().toList()) {
            productSupplierApi.findSupplierById(supplierId).ifPresent(ref -> suppliers.put(supplierId, ref));
        }
        // Unidades de compra y nombre por asociación producto-proveedor elegida (solo lectura).
        Map<Long, List<ProductSupplierApi.ProductSupplierCandidate>> candidatesByProduct = new LinkedHashMap<>();
        for (Long productId : rows.stream().map(LineRow::productId).distinct().toList()) {
            candidatesByProduct.put(productId, productSupplierApi.candidates(productId));
        }

        List<LineView> views = new ArrayList<>(rows.size());
        for (LineRow row : rows) {
            ProductSnapshot product = products.get(row.productId());
            ProductSupplierApi.SupplierRef supplier = row.supplierId() == null
                    ? null : suppliers.get(row.supplierId());
            ProductSupplierApi.ProductSupplierCandidate candidate = row.productSupplierId() == null
                    ? null
                    : candidatesByProduct.getOrDefault(row.productId(), List.of()).stream()
                            .filter(c -> Objects.equals(c.id(), row.productSupplierId()))
                            .findFirst()
                            .orElse(null);
            views.add(new LineView(
                    row.id(),
                    row.runId(),
                    row.productId(),
                    product == null ? null : product.sku(),
                    product == null ? null : product.name(),
                    row.supplierId(),
                    supplier == null ? null : displayName(supplier),
                    candidate == null ? null : candidate.purchaseUnitName(),
                    candidate == null ? null : candidate.unitsPerPurchaseUnit(),
                    row.validDays(),
                    row.censoredDays(),
                    row.avgDailyDemand(),
                    row.demandStd(),
                    row.leadTimeDays(),
                    row.reviewPeriodDays(),
                    row.onHand(),
                    row.inTransit(),
                    row.safetyStock(),
                    row.reorderPoint(),
                    row.targetLevel(),
                    row.suggestedQty(),
                    row.suggestedPurchaseUnits(),
                    row.finalPurchaseUnits(),
                    row.unitCostNet(),
                    row.estimatedStockoutDate(),
                    row.daysOfCover(),
                    row.status(),
                    row.flags()));
        }
        return views;
    }

    /** Nombre comercial del proveedor, o la razón social si no tiene nombre de fantasía. */
    private static String displayName(ProductSupplierApi.SupplierRef supplier) {
        return supplier.tradeName() != null && !supplier.tradeName().isBlank()
                ? supplier.tradeName()
                : supplier.legalName();
    }

    private ReplenishmentRun requireRun(Long storeId, long runId) {
        return runRepository.findByIdAndStoreId(runId, storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Corrida de reabastecimiento no encontrada."));
    }

    private Map<Long, Map<String, Long>> countsByRunIds(List<Long> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<String, Long>> result = new LinkedHashMap<>();
        for (ReplenishmentLineRepository.RunStatusCount row :
                lineRepository.countByStatusForRuns(runIds)) {
            result.computeIfAbsent(row.getRunId(), id -> new LinkedHashMap<>())
                    .put(row.getStatus().name(), row.getTotal());
        }
        return result;
    }

    private static List<String> sortDescription(Pageable pageable) {
        return pageable.getSort().stream()
                .map(order -> order.getProperty() + "," + order.getDirection().name().toLowerCase())
                .toList();
    }

    /** Corrida con su conteo de líneas por estado ({@code API.md} F.3 {@code ReplenishmentRunResponse}). */
    public record RunWithCounts(
            Long id,
            Long storeId,
            LocalDate asOfDate,
            ReplenishmentRunStatus status,
            int linesCount,
            Map<String, Long> countsByStatus,
            java.time.Instant startedAt,
            java.time.Instant finishedAt,
            String error
    ) {
    }

    /** Fila cruda de línea leída del SQL nativo, antes del enriquecimiento. */
    private record LineRow(
            Long id,
            Long runId,
            Long productId,
            Long supplierId,
            Long productSupplierId,
            int validDays,
            int censoredDays,
            BigDecimal avgDailyDemand,
            BigDecimal demandStd,
            int leadTimeDays,
            int reviewPeriodDays,
            BigDecimal onHand,
            BigDecimal inTransit,
            BigDecimal safetyStock,
            BigDecimal reorderPoint,
            BigDecimal targetLevel,
            BigDecimal suggestedQty,
            BigDecimal suggestedPurchaseUnits,
            BigDecimal finalPurchaseUnits,
            BigDecimal unitCostNet,
            LocalDate estimatedStockoutDate,
            BigDecimal daysOfCover,
            ReplenishmentStatus status,
            List<String> flags
    ) {
    }
}
