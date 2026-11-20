package cl.minimarket.pos.replenishment.application.run;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.purchasing.OpenOrdersQuery;
import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.replenishment.application.params.ReplenishmentParam;
import cl.minimarket.pos.replenishment.application.params.ReplenishmentParamRepository;
import cl.minimarket.pos.replenishment.application.params.ReplenishmentParamsResolver;
import cl.minimarket.pos.replenishment.domain.DemandDay;
import cl.minimarket.pos.replenishment.domain.ReplenishmentCalculator;
import cl.minimarket.pos.replenishment.domain.ReplenishmentInput;
import cl.minimarket.pos.replenishment.domain.ReplenishmentParameters;
import cl.minimarket.pos.replenishment.domain.ReplenishmentResult;
import cl.minimarket.pos.reporting.SalesHistoryApi;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Corrida síncrona de reabastecimiento ({@code POST /replenishment/runs}, {@code API.md} F.4
 * flujo 8 y {@code PLAN.md} C.5).
 *
 * <p>Una corrida:
 * <ol>
 *   <li>valida la fecha de corte (no futura) y arma el snapshot de parámetros;</li>
 *   <li>refresca el agregado diario de ventas del rango {@code [asOf − Wmáx, asOf − 1]}, con
 *       {@code Wmáx} la mayor ventana configurada (o el default 30);</li>
 *   <li>evalúa el universo de productos activos que no son pack, resolviendo parámetros por
 *       producto → categoría (cadena completa) → local → global y delegando en el calculador
 *       puro {@link ReplenishmentCalculator};</li>
 *   <li>persiste la corrida y sus líneas; los productos con {@code enabled=false} resuelto,
 *       inactivos o packs quedan fuera (no se evalúan, C.5).</li>
 * </ol>
 *
 * <p>La generación de borradores de OC es una acción posterior y separada
 * ({@link ReplenishmentDraftService}): así lo define C.5 ("acción del usuario tras
 * revisar/editar {@code final_purchase_units}").
 */
@Service
public class ReplenishmentRunService {

    private static final String ACTION_RUN_EXECUTED = "REPLENISHMENT_RUN_EXECUTED";
    private static final String ENTITY_RUN = "replenishment_run";

    private final ReplenishmentRunRepository runRepository;
    private final ReplenishmentLineRepository lineRepository;
    private final ReplenishmentParamRepository paramRepository;
    private final SalesHistoryApi salesHistoryApi;
    private final ProductCatalogApi productCatalogApi;
    private final ProductSupplierApi productSupplierApi;
    private final OpenOrdersQuery openOrdersQuery;
    private final StockApi stockApi;
    private final BusinessClock clock;
    private final JsonMapper jsonMapper;
    private final AuditService auditService;

    public ReplenishmentRunService(ReplenishmentRunRepository runRepository,
                                   ReplenishmentLineRepository lineRepository,
                                   ReplenishmentParamRepository paramRepository,
                                   SalesHistoryApi salesHistoryApi,
                                   ProductCatalogApi productCatalogApi,
                                   ProductSupplierApi productSupplierApi,
                                   OpenOrdersQuery openOrdersQuery,
                                   StockApi stockApi,
                                   BusinessClock clock,
                                   JsonMapper jsonMapper,
                                   AuditService auditService) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.lineRepository = Objects.requireNonNull(lineRepository, "lineRepository");
        this.paramRepository = Objects.requireNonNull(paramRepository, "paramRepository");
        this.salesHistoryApi = Objects.requireNonNull(salesHistoryApi, "salesHistoryApi");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.productSupplierApi = Objects.requireNonNull(productSupplierApi, "productSupplierApi");
        this.openOrdersQuery = Objects.requireNonNull(openOrdersQuery, "openOrdersQuery");
        this.stockApi = Objects.requireNonNull(stockApi, "stockApi");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
    }

    /**
     * Ejecuta la corrida del local indicado con la fecha de corte pedida (o hoy de negocio).
     *
     * @param storeId       local de la corrida
     * @param requestedAsOf fecha de corte pedida, o {@code null} para la fecha de negocio actual
     * @return la corrida completada, con sus líneas ya persistidas
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si la fecha de corte es futura
     */
    @Transactional
    public ReplenishmentRun execute(Long storeId, LocalDate requestedAsOf) {
        Objects.requireNonNull(storeId, "storeId");
        LocalDate today = clock.businessDate();
        LocalDate asOf = requestedAsOf == null ? today : requestedAsOf;
        if (asOf.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "La fecha de corte de la corrida no puede ser futura.");
        }

        List<ReplenishmentParam> rows = paramRepository.findAll();
        int maxWindowDays = rows.stream()
                .map(ReplenishmentParam::getWindowDays)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(ReplenishmentParameters.defaults(true).windowDays());

        // La corrida se crea ya con el contexto que usará (rango refrescado y ventana máxima).
        ReplenishmentRun run = new ReplenishmentRun(
                storeId,
                asOf,
                snapshotJson(asOf, maxWindowDays, storeId, rows),
                clock.now());
        run = runRepository.save(run);

        List<ReplenishmentResult> results = calculateAll(storeId, asOf, maxWindowDays, rows);
        Long runId = run.getId();
        List<ReplenishmentLine> lines = results.stream()
                .map(result -> new ReplenishmentLine(runId, result))
                .toList();
        lineRepository.saveAll(lines);
        run.complete(lines.size(), clock.now());
        run = runRepository.save(run);

        auditService.record(new AuditEvent(
                ACTION_RUN_EXECUTED,
                ENTITY_RUN,
                String.valueOf(run.getId()),
                null,
                Map.of("asOfDate", asOf.toString(),
                        "linesCount", lines.size(),
                        "countsByStatus", countsByStatus(results)),
                null,
                null));
        return run;
    }

    /** Evalúa todo el universo de productos del local y devuelve sus resultados en orden de id. */
    private List<ReplenishmentResult> calculateAll(Long storeId,
                                                   LocalDate asOf,
                                                   int maxWindowDays,
                                                   List<ReplenishmentParam> rows) {
        LocalDate windowStart = asOf.minusDays(maxWindowDays);
        LocalDate windowEnd = asOf.minusDays(1);

        List<Long> universeIds = productCatalogApi.nonPackProductIds();
        if (universeIds.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductSnapshot> products = new HashMap<>();
        for (ProductSnapshot snapshot : productCatalogApi.findByIds(universeIds)) {
            if (snapshot.active() && snapshot.saleType() != SaleType.PACK) {
                products.put(snapshot.id(), snapshot);
            }
        }
        if (products.isEmpty()) {
            return List.of();
        }
        List<Long> evaluatedIds = products.keySet().stream().sorted().toList();

        // El refresco del agregado es la primera operación de lectura: F.2 lo exige antes de
        // leer la demanda, para que la historia incluya las ventas más recientes.
        salesHistoryApi.refreshDailySales(windowStart, windowEnd);
        Map<Long, List<DemandDay>> history = groupHistory(salesHistoryApi.dailyDemand(
                storeId, evaluatedIds, windowStart, windowEnd));

        Map<Long, BigDecimal> pendingOrders = openOrdersQuery.pendingQuantities(storeId);
        Map<Long, BigDecimal> inTransit = stockApi.inTransitInbound(storeId);

        Map<Long, List<Long>> categoryChains = new HashMap<>();
        List<ReplenishmentResult> results = new ArrayList<>(evaluatedIds.size());
        for (Long productId : evaluatedIds) {
            ProductSnapshot product = products.get(productId);
            List<Long> chain = categoryChains.computeIfAbsent(product.categoryId(), categoryId ->
                    categoryId == null ? List.of() : productCatalogApi.categoryChain(categoryId));
            ReplenishmentParameters parameters = ReplenishmentParamsResolver.resolve(
                    productId, chain, storeId, rows);
            if (!parameters.enabled()) {
                // Producto deshabilitado por parámetros: no se evalúa ni se guarda línea (C.5).
                continue;
            }
            results.add(evaluateProduct(storeId, asOf, product, parameters, history, pendingOrders, inTransit));
        }
        return results;
    }

    /** Arma la entrada del calculador para un producto y delega en él. */
    private ReplenishmentResult evaluateProduct(Long storeId,
                                                LocalDate asOf,
                                                ProductSnapshot product,
                                                ReplenishmentParameters parameters,
                                                Map<Long, List<DemandDay>> history,
                                                Map<Long, BigDecimal> pendingOrders,
                                                Map<Long, BigDecimal> inTransit) {
        Long productId = product.id();
        BigDecimal transit = inTransit(productId, pendingOrders.get(productId), inTransit.get(productId));
        StockApi.StockLimits limits = stockApi.limits(storeId, productId);
        ReplenishmentInput input = new ReplenishmentInput(
                productId,
                product.active(),
                product.saleType() == SaleType.PACK,
                // El agregado diario ya acota la historia a los días posteriores a la creación del
                // producto (B.11), de modo que el límite inferior de la propia ventana es
                // equivalente a su fecha de creación para los días que pueden llegar.
                asOf.minusDays(parameters.windowDays()),
                asOf,
                history.getOrDefault(productId, List.of()),
                parameters,
                productSupplierApi.candidates(productId),
                stockApi.onHand(storeId, productId),
                transit,
                limits.minQuantity(),
                limits.maxQuantity(),
                product.shelfLifeDays());
        return ReplenishmentCalculator.calculate(input);
    }

    /** Tránsito total del producto: OC abiertas (SENT/PARTIALLY_RECEIVED) + transferencias entrantes. */
    private static BigDecimal inTransit(Long productId, BigDecimal pendingOrder, BigDecimal transferIn) {
        BigDecimal total = BigDecimal.ZERO
                .add(pendingOrder == null ? BigDecimal.ZERO : pendingOrder)
                .add(transferIn == null ? BigDecimal.ZERO : transferIn);
        return total.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    /** Agrupa la demanda diaria por producto, conservando el orden por fecha de la API. */
    private static Map<Long, List<DemandDay>> groupHistory(List<SalesHistoryApi.DailyDemand> rows) {
        Map<Long, List<DemandDay>> byProduct = new LinkedHashMap<>();
        for (SalesHistoryApi.DailyDemand row : rows) {
            byProduct.computeIfAbsent(row.productId(), id -> new ArrayList<>())
                    .add(new DemandDay(row.saleDate(), row.quantity(), row.hadStockout()));
        }
        return byProduct;
    }

    /** Snapshot jsonb de la corrida: rango refrescado, ventana máxima y default del local. */
    private String snapshotJson(LocalDate asOf, int maxWindowDays, Long storeId, List<ReplenishmentParam> rows) {
        ReplenishmentParameters defaults = ReplenishmentParamsResolver.resolve(null, List.of(), storeId, rows);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("asOfDate", asOf.toString());
        snapshot.put("refreshFrom", asOf.minusDays(maxWindowDays).toString());
        snapshot.put("refreshTo", asOf.minusDays(1).toString());
        snapshot.put("maxWindowDays", maxWindowDays);
        // LinkedHashMap: el período automático (null) es un valor legítimo del snapshot.
        Map<String, Object> defaultsJson = new LinkedHashMap<>();
        defaultsJson.put("windowDays", defaults.windowDays());
        defaultsJson.put("minHistoryDays", defaults.minHistoryDays());
        defaultsJson.put("serviceLevelZ", defaults.serviceLevelZ());
        defaultsJson.put("safetyMinDays", defaults.safetyMinDays());
        defaultsJson.put("reviewPeriodDays", defaults.reviewPeriodDays());
        defaultsJson.put("outlierK", defaults.outlierK());
        defaultsJson.put("shelfLifeFactor", defaults.shelfLifeFactor());
        defaultsJson.put("enabled", defaults.enabled());
        snapshot.put("defaults", defaultsJson);
        return jsonMapper.writeValueAsString(snapshot);
    }

    /** Conteo de resultados por estado (nombres del enum, como en la respuesta HTTP). */
    private static Map<String, Long> countsByStatus(List<ReplenishmentResult> results) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ReplenishmentResult result : results) {
            counts.merge(result.status().name(), 1L, Long::sum);
        }
        return counts;
    }
}
