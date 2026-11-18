package cl.minimarket.pos.inventory.application.count;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductExportFilter;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductExportRow;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.inventory.application.count.InventoryCountLookupRepository.CountHeaderRow;
import cl.minimarket.pos.inventory.application.count.InventoryCountLookupRepository.CountLineRow;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CategoryLookupView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CategoryRefView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountDetailView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountFilter;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountLineFilter;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountLineInput;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountLineView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountProgressView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.DifferenceSummaryView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.LotStockInfo;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.UserRefView;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Quantity;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Servicio de aplicación para el ciclo completo de tomas físicas de inventario ({@code HU-INV-06},
 * {@code RN-20}, {@code API.md} F.2).
 *
 * <p>Orquesta la apertura ({@code FULL} y {@code CYCLIC}), generación de líneas (desglosando por lote
 * cuando existen lotes activos), registro de conteos con snapshot instantáneo de existencia teórica,
 * modo ciego, envío a revisión, aprobación con segregación de funciones (SOD) y aplicación de deltas
 * vía {@link StockApi#apply(StockCommand)}, rechazo hacia {@code OPEN} y cancelación.
 */
@Service
public class InventoryCountService {

    private static final int MAX_LINES_PER_BATCH = 500;
    private static final int MIN_REASON_LENGTH = 3;
    private static final int MAX_REASON_LENGTH = 250;
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final InventoryCountRepository countRepo;
    private final InventoryCountLineRepository lineRepo;
    private final InventoryCountLookupRepository lookup;
    private final StockApi stockApi;
    private final ProductCatalogApi productCatalogApi;
    private final SequenceService sequenceService;
    private final AuditService auditService;
    private final BusinessClock clock;

    public InventoryCountService(
            InventoryCountRepository countRepo,
            InventoryCountLineRepository lineRepo,
            InventoryCountLookupRepository lookup,
            StockApi stockApi,
            ProductCatalogApi productCatalogApi,
            SequenceService sequenceService,
            AuditService auditService,
            BusinessClock clock) {
        this.countRepo = Objects.requireNonNull(countRepo, "countRepo");
        this.lineRepo = Objects.requireNonNull(lineRepo, "lineRepo");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.stockApi = Objects.requireNonNull(stockApi, "stockApi");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.sequenceService = Objects.requireNonNull(sequenceService, "sequenceService");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Inicia una nueva toma física de inventario en el local activo y genera sus líneas pendientes.
     *
     * @param storeId identificador del local activo
     * @param countType tipo de toma ({@code FULL} o {@code CYCLIC})
     * @param categoryId identificador de categoría raíz cuando {@code countType == CYCLIC}
     * @param blind {@code true} para ocultar existencias teóricas a quien ejecuta sin permiso de aprobar
     * @param notes notas opcionales
     * @param userId usuario que inicia la toma
     * @param canReadCosts si el usuario actual puede ver costos valorizados
     * @param canApprove si el usuario actual puede aprobar tomas
     * @return detalle inicial de la toma creada
     */
    @Transactional
    public CountDetailView create(
            Long storeId,
            CountType countType,
            Long categoryId,
            boolean blind,
            String notes,
            Long userId,
            boolean canReadCosts,
            boolean canApprove) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(countType, "countType");
        Objects.requireNonNull(userId, "userId");

        if (countType == CountType.CYCLIC && categoryId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Una toma cíclica requiere categoryId");
        }
        if (countType == CountType.FULL && categoryId != null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Una toma completa (FULL) no debe especificar categoryId");
        }

        CategoryRefView categoryRef = null;
        List<EligibleProduct> eligibleProducts;
        if (countType == CountType.CYCLIC) {
            CategoryLookupView cat = lookup.findCategory(categoryId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.NOT_FOUND,
                            "Categoría no encontrada: " + categoryId));
            if (!cat.active()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "La categoría seleccionada está inactiva");
            }
            categoryRef = new CategoryRefView(cat.id(), cat.name());
            eligibleProducts = resolveCyclicProducts(categoryId);
            if (eligibleProducts.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "La categoría seleccionada no tiene productos activos elegibles para iniciar la toma cíclica");
            }
        } else {
            eligibleProducts = resolveFullProducts(storeId);
            if (eligibleProducts.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "No existen productos con stock positivo en el local para iniciar una toma completa");
            }
        }

        if (lookup.existsOpenScope(storeId, categoryId)) {
            throw new BusinessException(
                    ErrorCode.DUPLICATED_VALUE,
                    "Ya existe una toma de inventario abierta para el mismo local y alcance");
        }

        String number = sequenceService.next(SequenceType.INVENTORY_COUNT, storeId);
        String normalizedNotes = normalizeOptionalText(notes);
        InventoryCount count = InventoryCount.open(
                number, storeId, countType, categoryId, blind, userId, normalizedNotes);

        try {
            count = countRepo.saveAndFlush(count);
        } catch (DataIntegrityViolationException ex) {
            if (isOpenScopeConflict(ex)) {
                BusinessException conflict = new BusinessException(
                        ErrorCode.DUPLICATED_VALUE,
                        "Ya existe una toma de inventario abierta para el mismo local y alcance");
                conflict.initCause(ex);
                throw conflict;
            }
            throw ex;
        }

        List<InventoryCountLine> generatedLines = buildInitialLines(storeId, count.getId(), eligibleProducts);
        lineRepo.saveAll(generatedLines);

        Map<Long, UserRefView> users = lookup.findUserRefs(List.of(userId));
        UserRefView starter = users.getOrDefault(userId, new UserRefView(userId, "Usuario #" + userId));
        DifferenceSummaryView emptyDiff = canReadCosts && (!blind || canApprove)
                ? new DifferenceSummaryView(0, Rounding.costTotal(BigDecimal.ZERO), Rounding.costTotal(BigDecimal.ZERO))
                : null;

        return new CountDetailView(
                count.getId(),
                count.getNumber(),
                count.getStoreId(),
                count.getCountType(),
                categoryRef,
                count.getStatus(),
                count.isBlind(),
                starter,
                count.getCreatedAt(),
                count.getSubmittedAt(),
                null,
                count.getApprovedAt(),
                count.getRejectionReason(),
                count.getNotes(),
                new CountProgressView(generatedLines.size(), 0L),
                emptyDiff,
                count.getVersion());
    }

    /**
     * Lista paginada de tomas de inventario del local activo con filtros por estado y tipo.
     */
    @Transactional(readOnly = true)
    public Page<CountDetailView> list(
            Long storeId,
            CountFilter filter,
            Pageable pageable,
            boolean canReadCosts,
            boolean canApprove) {
        Objects.requireNonNull(storeId, "storeId");
        Page<CountHeaderRow> page = lookup.findCounts(storeId, filter, pageable);
        if (!page.hasContent()) {
            return Page.empty(pageable);
        }

        List<Long> countIds = page.getContent().stream().map(CountHeaderRow::id).toList();
        Map<Long, CountProgressView> progressById = lookup.findProgressByCountIds(countIds);

        List<Long> diffVisibleIds = canReadCosts
                ? page.getContent().stream()
                        .filter(row -> !row.blind() || canApprove)
                        .map(CountHeaderRow::id)
                        .toList()
                : List.of();
        Map<Long, DifferenceSummaryView> diffById = lookup.findDifferenceSummariesByCountIds(diffVisibleIds);

        List<CountDetailView> views = page.getContent().stream()
                .map(row -> {
                    boolean showDiff = canReadCosts && (!row.blind() || canApprove);
                    return new CountDetailView(
                            row.id(),
                            row.number(),
                            row.storeId(),
                            row.countType(),
                            row.category(),
                            row.status(),
                            row.blind(),
                            row.startedBy(),
                            row.startedAt(),
                            row.submittedAt(),
                            row.approvedBy(),
                            row.approvedAt(),
                            row.rejectionReason(),
                            row.notes(),
                            progressById.getOrDefault(row.id(), new CountProgressView(0L, 0L)),
                            showDiff ? diffById.get(row.id()) : null,
                            row.version());
                })
                .toList();

        return new PageImpl<>(views, pageable, page.getTotalElements());
    }

    /**
     * Obtiene el detalle de una toma perteneciente al local activo.
     */
    @Transactional(readOnly = true)
    public CountDetailView get(Long storeId, Long countId, boolean canReadCosts, boolean canApprove) {
        InventoryCount count = requireCount(storeId, countId);
        return buildDetailView(count, canReadCosts, canApprove);
    }

    /**
     * Lista paginada de líneas de una toma con filtro por conteo realizado y búsqueda por SKU/nombre.
     * Si la toma es ciega ({@code blind = true}) y el usuario no tiene permiso de aprobar, oculta
     * {@code theoreticalQty} y {@code difference} devolviéndolos en {@code null}.
     */
    @Transactional(readOnly = true)
    public Page<CountLineView> listLines(
            Long storeId,
            Long countId,
            CountLineFilter filter,
            Pageable pageable,
            boolean canReadCosts,
            boolean canApprove) {
        InventoryCount count = requireCount(storeId, countId);
        List<Long> distinctProductIds = lookup.findDistinctProductIdsForCount(countId);
        if (distinctProductIds.isEmpty()) {
            return Page.empty(pageable);
        }

        Map<Long, ProductSnapshot> productsById = productCatalogApi.findByIds(distinctProductIds).stream()
                .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));

        Collection<Long> matchingProductIds = null;
        if (filter != null && filter.q() != null && !filter.q().isBlank()) {
            String query = normalizeSearch(filter.q());
            List<Long> filtered = productsById.values().stream()
                    .filter(p -> matchesSearch(p, query))
                    .map(ProductSnapshot::id)
                    .toList();
            if (filtered.isEmpty()) {
                return Page.empty(pageable);
            }
            matchingProductIds = filtered;
        }

        Page<CountLineRow> page = lookup.findCountLines(
                countId,
                filter != null ? filter.counted() : null,
                matchingProductIds,
                pageable);
        if (!page.hasContent()) {
            return Page.empty(pageable);
        }

        boolean showTheoretical = !count.isBlind() || canApprove;
        Map<Long, BigDecimal> stockByProduct = Map.of();
        Map<Long, BigDecimal> qtyByLot = Map.of();
        if (showTheoretical) {
            Set<Long> uncountedProducts = new HashSet<>();
            Set<Long> uncountedLots = new HashSet<>();
            for (CountLineRow row : page.getContent()) {
                if (row.countedAt() == null) {
                    if (row.lotId() != null) {
                        uncountedLots.add(row.lotId());
                    } else {
                        uncountedProducts.add(row.productId());
                    }
                }
            }
            stockByProduct = lookup.findStockQuantities(storeId, uncountedProducts);
            qtyByLot = lookup.findLotQuantities(storeId, uncountedLots);
        }

        final Map<Long, BigDecimal> finalStock = stockByProduct;
        final Map<Long, BigDecimal> finalLots = qtyByLot;

        List<CountLineView> views = page.getContent().stream()
                .map(row -> toLineView(row, productsById.get(row.productId()), showTheoretical, canReadCosts, finalStock, finalLots))
                .toList();

        return new PageImpl<>(views, pageable, page.getTotalElements());
    }

    /**
     * Registra o actualiza cantidades contadas en lote (1..500 líneas) cuando la toma está en estado
     * {@code OPEN}. Captura en ese instante el {@code theoretical_qty} vigente y el {@code unit_cost}.
     */
    @Transactional
    public List<CountLineView> recordLines(
            Long storeId,
            Long countId,
            List<CountLineInput> inputs,
            Long userId,
            boolean canReadCosts,
            boolean canApprove) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(countId, "countId");
        Objects.requireNonNull(userId, "userId");
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_LINES_PER_BATCH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Debe enviar entre 1 y " + MAX_LINES_PER_BATCH + " líneas por solicitud");
        }

        InventoryCount count = requireCount(storeId, countId);
        if (count.getStatus() != CountStatus.OPEN) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden registrar conteos en una toma OPEN (estado actual: " + count.getStatus() + ")");
        }

        Set<Long> requestedIds = new LinkedHashSet<>(inputs.size());
        for (CountLineInput input : inputs) {
            if (input.lineId() == null || input.countedQty() == null) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Cada línea requiere lineId y countedQty");
            }
            if (!requestedIds.add(input.lineId())) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "No se permiten identificadores lineId duplicados en la misma solicitud: " + input.lineId());
            }
            if (input.countedQty().signum() < 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "La cantidad contada no puede ser negativa");
            }
        }

        Map<Long, InventoryCountLine> linesById = lineRepo.findByCountIdAndIdIn(countId, requestedIds).stream()
                .collect(Collectors.toMap(InventoryCountLine::getId, Function.identity()));
        if (linesById.size() != requestedIds.size()) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "Una o más líneas no existen o no pertenecen a la toma " + countId);
        }

        Set<Long> productIds = linesById.values().stream()
                .map(InventoryCountLine::getProductId)
                .collect(Collectors.toSet());
        Map<Long, ProductSnapshot> productsById = productCatalogApi.findByIds(productIds).stream()
                .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));

        Set<Long> lotIds = linesById.values().stream()
                .map(InventoryCountLine::getLotId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, BigDecimal> lotQuantities = lookup.findLotQuantities(storeId, lotIds);
        Map<Long, String> lotNumbers = lookup.findLotNumbers(storeId, lotIds);

        // Cacheamos la lectura de StockApi.onHand por producto dentro de esta operación.
        Map<Long, BigDecimal> currentOnHandByProduct = new HashMap<>(productIds.size());
        Instant now = clock.now();
        List<InventoryCountLine> updatedLines = new ArrayList<>(inputs.size());

        for (CountLineInput input : inputs) {
            InventoryCountLine line = linesById.get(input.lineId());
            ProductSnapshot product = productsById.get(line.getProductId());
            if (product == null) {
                throw new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "Producto no encontrado en catálogo: " + line.getProductId());
            }
            if (product.unitOfMeasure() == UnitOfMeasure.UNIT
                    && !Quantity.of(input.countedQty()).isWholeNumber()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "El producto '" + product.name() + "' se controla en unidades enteras");
            }

            BigDecimal theoretical = line.getLotId() != null
                    ? lotQuantities.getOrDefault(line.getLotId(), Rounding.quantity(BigDecimal.ZERO))
                    : currentOnHandByProduct.computeIfAbsent(
                            line.getProductId(),
                            pid -> Rounding.quantity(stockApi.onHand(storeId, pid)));

            line.recordCount(theoretical, input.countedQty(), product.avgCost(), userId, now);
            updatedLines.add(line);
        }

        lineRepo.saveAll(updatedLines);

        boolean showTheoretical = !count.isBlind() || canApprove;
        return updatedLines.stream()
                .map(line -> toLineViewFromEntity(line, productsById.get(line.getProductId()), lotNumbers, showTheoretical, canReadCosts))
                .toList();
    }

    /**
     * Envía una toma abierta a revisión ({@code OPEN -> SUBMITTED}) validando que todas sus líneas
     * tengan conteo registrado.
     */
    @Transactional
    public CountDetailView submit(
            Long storeId,
            Long countId,
            Long userId,
            boolean canReadCosts,
            boolean canApprove) {
        InventoryCount count = requireCount(storeId, countId);
        if (count.getStatus() != CountStatus.OPEN) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se puede enviar a revisión una toma en estado OPEN (actual: " + count.getStatus() + ")");
        }
        long pendingLines = lineRepo.countByCountIdAndCountedAtIsNull(countId);
        if (pendingLines > 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Faltan " + pendingLines + " línea(s) por contar antes de enviar la toma a revisión");
        }

        count.submit(userId, clock.now());
        countRepo.save(count);
        return buildDetailView(count, canReadCosts, canApprove);
    }

    /**
     * Aprueba una toma enviada ({@code SUBMITTED -> APPROVED}), validando segregación de funciones
     * (SOD: el aprobador no puede ser {@code started_by} ni ninguno de los {@code counted_by}),
     * aplicando únicamente los deltas ({@code counted_qty - theoretical_qty}) vía {@link StockApi}
     * (RN-20) y auditando {@code INVENTORY_COUNT_APPROVED}.
     */
    @Transactional
    public CountDetailView approve(
            Long storeId,
            Long countId,
            Long approverId,
            boolean canReadCosts,
            boolean canApprove) {
        Objects.requireNonNull(approverId, "approverId");
        InventoryCount count = requireCount(storeId, countId);
        if (count.getStatus() != CountStatus.SUBMITTED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se puede aprobar una toma en estado SUBMITTED (actual: " + count.getStatus() + ")");
        }

        if (approverId.equals(count.getStartedBy())) {
            throw new BusinessException(
                    ErrorCode.SEGREGATION_OF_DUTIES,
                    "Segregación de funciones: quien aprueba la toma no puede ser quien la inició");
        }
        if (lineRepo.existsByCountIdAndCountedBy(countId, approverId)) {
            throw new BusinessException(
                    ErrorCode.SEGREGATION_OF_DUTIES,
                    "Segregación de funciones: quien aprueba la toma no puede haber registrado conteos");
        }

        List<InventoryCountLine> lines = lineRepo.findByCountIdOrderByIdAsc(countId);
        List<InventoryCountLine> positiveLines = new ArrayList<>();
        List<InventoryCountLine> negativeLines = new ArrayList<>();
        List<StockLine> inboundStockLines = new ArrayList<>();
        List<StockLine> outboundStockLines = new ArrayList<>();

        for (InventoryCountLine line : lines) {
            if (!line.isCounted()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Todas las líneas deben estar contadas antes de aprobar la toma");
            }
            BigDecimal delta = line.difference();
            if (delta.signum() > 0) {
                positiveLines.add(line);
                inboundStockLines.add(new StockLine(
                        line.getProductId(),
                        delta,
                        null,
                        line.getId(),
                        line.getLotId(),
                        null,
                        null));
            } else if (delta.signum() < 0) {
                negativeLines.add(line);
                outboundStockLines.add(new StockLine(
                        line.getProductId(),
                        delta.abs(),
                        null,
                        line.getId(),
                        line.getLotId(),
                        null,
                        null));
            }
        }

        String adjustmentReason = "Toma de inventario " + count.getNumber();
        int inboundMovements = 0;
        int outboundMovements = 0;

        if (!inboundStockLines.isEmpty()) {
            List<StockMovementResult> results = stockApi.apply(new StockCommand(
                    storeId,
                    StockMovementType.ADJUSTMENT_IN,
                    StockSourceType.INVENTORY_COUNT,
                    count.getId(),
                    approverId,
                    adjustmentReason,
                    inboundStockLines));
            inboundMovements = results.size();
            Map<Long, StockMovementResult> byProduct = results.stream()
                    .collect(Collectors.toMap(StockMovementResult::productId, Function.identity()));
            for (InventoryCountLine line : positiveLines) {
                StockMovementResult result = byProduct.get(line.getProductId());
                if (result != null) {
                    line.linkStockMovement(result.movementId(), result.unitCost());
                }
            }
        }

        if (!outboundStockLines.isEmpty()) {
            List<StockMovementResult> results = stockApi.apply(new StockCommand(
                    storeId,
                    StockMovementType.ADJUSTMENT_OUT,
                    StockSourceType.INVENTORY_COUNT,
                    count.getId(),
                    approverId,
                    adjustmentReason,
                    outboundStockLines));
            outboundMovements = results.size();
            Map<Long, StockMovementResult> byProduct = results.stream()
                    .collect(Collectors.toMap(StockMovementResult::productId, Function.identity()));
            for (InventoryCountLine line : negativeLines) {
                StockMovementResult result = byProduct.get(line.getProductId());
                if (result != null) {
                    line.linkStockMovement(result.movementId(), result.unitCost());
                }
            }
        }

        if (!positiveLines.isEmpty() || !negativeLines.isEmpty()) {
            lineRepo.saveAll(lines);
        }

        count.approve(approverId, clock.now());
        countRepo.save(count);

        auditService.record(new AuditEvent(
                "INVENTORY_COUNT_APPROVED",
                "inventory_count",
                String.valueOf(count.getId()),
                Map.of("number", count.getNumber(), "status", CountStatus.SUBMITTED.name()),
                Map.of(
                        "number", count.getNumber(),
                        "status", CountStatus.APPROVED.name(),
                        "adjustedLines", positiveLines.size() + negativeLines.size(),
                        "movementsIn", inboundMovements,
                        "movementsOut", outboundMovements),
                null,
                null));

        return buildDetailView(count, canReadCosts, canApprove);
    }

    /**
     * Rechaza una toma enviada devolviéndola a estado {@code OPEN} para reconteo, y registra
     * auditoría {@code INVENTORY_COUNT_REJECTED}.
     */
    @Transactional
    public CountDetailView reject(
            Long storeId,
            Long countId,
            String reason,
            boolean canReadCosts,
            boolean canApprove) {
        String normalizedReason = normalizeReason(reason);
        InventoryCount count = requireCount(storeId, countId);
        count.rejectToOpen(normalizedReason);
        countRepo.save(count);

        auditService.record(new AuditEvent(
                "INVENTORY_COUNT_REJECTED",
                "inventory_count",
                String.valueOf(count.getId()),
                Map.of("number", count.getNumber(), "status", CountStatus.SUBMITTED.name()),
                Map.of("number", count.getNumber(), "status", CountStatus.OPEN.name()),
                normalizedReason,
                null));

        return buildDetailView(count, canReadCosts, canApprove);
    }

    /**
     * Cancela una toma en estado {@code OPEN} o {@code SUBMITTED} liberando el alcance abierto.
     */
    @Transactional
    public void cancel(Long storeId, Long countId, String reason) {
        String normalizedReason = normalizeReason(reason);
        InventoryCount count = requireCount(storeId, countId);
        CountStatus previous = count.getStatus();
        count.cancel(normalizedReason);
        countRepo.save(count);

        auditService.record(new AuditEvent(
                "INVENTORY_COUNT_CANCELLED",
                "inventory_count",
                String.valueOf(count.getId()),
                Map.of("number", count.getNumber(), "status", previous.name()),
                Map.of("number", count.getNumber(), "status", CountStatus.CANCELLED.name()),
                normalizedReason,
                null));
    }

    private InventoryCount requireCount(Long storeId, Long countId) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(countId, "countId");
        return countRepo.findByIdAndStoreId(countId, storeId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "Toma de inventario no encontrada: " + countId));
    }

    private List<EligibleProduct> resolveFullProducts(Long storeId) {
        List<Long> positiveIds = lookup.findPositiveStockProductIds(storeId);
        if (positiveIds.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductSnapshot> byId = productCatalogApi.findByIds(positiveIds).stream()
                .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));
        List<EligibleProduct> eligible = new ArrayList<>(positiveIds.size());
        for (Long productId : positiveIds) {
            ProductSnapshot snapshot = byId.get(productId);
            if (snapshot != null && snapshot.saleType() != SaleType.PACK) {
                eligible.add(new EligibleProduct(productId, snapshot.lotControlled()));
            }
        }
        return eligible;
    }

    private List<EligibleProduct> resolveCyclicProducts(Long categoryId) {
        List<ProductExportRow> rows = productCatalogApi.findForExport(
                new ProductExportFilter(null, categoryId, null, null, true, null, null));
        return rows.stream()
                .filter(row -> row.saleType() != SaleType.PACK)
                .sorted(Comparator.comparing(ProductExportRow::id))
                .map(row -> new EligibleProduct(row.id(), row.lotControlled()))
                .toList();
    }

    private List<InventoryCountLine> buildInitialLines(
            Long storeId,
            Long countId,
            List<EligibleProduct> eligibleProducts) {
        List<Long> lotControlledIds = eligibleProducts.stream()
                .filter(EligibleProduct::lotControlled)
                .map(EligibleProduct::productId)
                .toList();
        Map<Long, List<LotStockInfo>> lotsByProduct = lookup.findActiveLotsByProduct(storeId, lotControlledIds);

        List<InventoryCountLine> lines = new ArrayList<>(eligibleProducts.size());
        for (EligibleProduct product : eligibleProducts) {
            if (product.lotControlled()) {
                List<LotStockInfo> lots = lotsByProduct.getOrDefault(product.productId(), List.of());
                if (!lots.isEmpty()) {
                    for (LotStockInfo lot : lots) {
                        lines.add(InventoryCountLine.createPending(countId, product.productId(), lot.lotId()));
                    }
                    continue;
                }
            }
            lines.add(InventoryCountLine.createPending(countId, product.productId(), null));
        }
        return lines;
    }

    private CountDetailView buildDetailView(InventoryCount count, boolean canReadCosts, boolean canApprove) {
        CategoryRefView categoryRef = null;
        if (count.getCategoryId() != null) {
            categoryRef = lookup.findCategory(count.getCategoryId())
                    .map(c -> new CategoryRefView(c.id(), c.name()))
                    .orElse(new CategoryRefView(count.getCategoryId(), "Categoría #" + count.getCategoryId()));
        }

        List<Long> userIds = new ArrayList<>(2);
        userIds.add(count.getStartedBy());
        if (count.getApprovedBy() != null) {
            userIds.add(count.getApprovedBy());
        }
        Map<Long, UserRefView> users = lookup.findUserRefs(userIds);
        UserRefView starter = users.getOrDefault(
                count.getStartedBy(),
                new UserRefView(count.getStartedBy(), "Usuario #" + count.getStartedBy()));
        UserRefView approver = count.getApprovedBy() == null
                ? null
                : users.getOrDefault(
                        count.getApprovedBy(),
                        new UserRefView(count.getApprovedBy(), "Usuario #" + count.getApprovedBy()));

        CountProgressView progress = lookup.findProgressByCountIds(List.of(count.getId()))
                .getOrDefault(count.getId(), new CountProgressView(0L, 0L));

        boolean showDiff = canReadCosts && (!count.isBlind() || canApprove);
        DifferenceSummaryView diffSummary = showDiff
                ? lookup.findDifferenceSummariesByCountIds(List.of(count.getId())).get(count.getId())
                : null;

        return new CountDetailView(
                count.getId(),
                count.getNumber(),
                count.getStoreId(),
                count.getCountType(),
                categoryRef,
                count.getStatus(),
                count.isBlind(),
                starter,
                count.getCreatedAt(),
                count.getSubmittedAt(),
                approver,
                count.getApprovedAt(),
                count.getRejectionReason(),
                count.getNotes(),
                progress,
                diffSummary,
                count.getVersion());
    }

    private static CountLineView toLineView(
            CountLineRow row,
            ProductSnapshot product,
            boolean showTheoretical,
            boolean canReadCosts,
            Map<Long, BigDecimal> uncountedStockByProduct,
            Map<Long, BigDecimal> uncountedQtyByLot) {
        BigDecimal theoretical = null;
        if (showTheoretical) {
            if (row.countedAt() != null && row.theoreticalQty() != null) {
                theoretical = Rounding.quantity(row.theoreticalQty());
            } else if (row.lotId() != null) {
                theoretical = uncountedQtyByLot.getOrDefault(row.lotId(), Rounding.quantity(BigDecimal.ZERO));
            } else {
                theoretical = uncountedStockByProduct.getOrDefault(
                        row.productId(),
                        Rounding.quantity(BigDecimal.ZERO));
            }
        }
        BigDecimal counted = row.countedQty() != null ? Rounding.quantity(row.countedQty()) : null;
        BigDecimal difference = (showTheoretical && counted != null && theoretical != null)
                ? Rounding.quantity(counted.subtract(theoretical))
                : null;
        BigDecimal unitCost = (canReadCosts && row.unitCost() != null)
                ? Rounding.unitCost(row.unitCost())
                : null;

        String sku = product != null ? product.sku() : String.valueOf(row.productId());
        String name = product != null ? product.name() : "Producto #" + row.productId();
        UnitOfMeasure uom = product != null ? product.unitOfMeasure() : UnitOfMeasure.UNIT;

        return new CountLineView(
                row.id(),
                row.productId(),
                sku,
                name,
                uom,
                row.lotId(),
                row.lotNumber(),
                theoretical,
                counted,
                difference,
                unitCost,
                row.countedAt(),
                row.stockMovementId());
    }

    private static CountLineView toLineViewFromEntity(
            InventoryCountLine line,
            ProductSnapshot product,
            Map<Long, String> lotNumbers,
            boolean showTheoretical,
            boolean canReadCosts) {
        BigDecimal theoretical = showTheoretical && line.getTheoreticalQty() != null
                ? Rounding.quantity(line.getTheoreticalQty())
                : null;
        BigDecimal counted = line.getCountedQty() != null ? Rounding.quantity(line.getCountedQty()) : null;
        BigDecimal difference = showTheoretical ? line.difference() : null;
        BigDecimal unitCost = (canReadCosts && line.getUnitCost() != null)
                ? Rounding.unitCost(line.getUnitCost())
                : null;

        return new CountLineView(
                line.getId(),
                line.getProductId(),
                product != null ? product.sku() : String.valueOf(line.getProductId()),
                product != null ? product.name() : "Producto #" + line.getProductId(),
                product != null ? product.unitOfMeasure() : UnitOfMeasure.UNIT,
                line.getLotId(),
                line.getLotId() != null ? lotNumbers.get(line.getLotId()) : null,
                theoretical,
                counted,
                difference,
                unitCost,
                line.getCountedAt(),
                line.getStockMovementId());
    }

    private static boolean matchesSearch(ProductSnapshot product, String normalizedQuery) {
        if (normalizeSearch(product.name()).contains(normalizedQuery)) {
            return true;
        }
        if (normalizeSearch(product.sku()).contains(normalizedQuery)) {
            return true;
        }
        return product.primaryBarcode() != null
                && normalizeSearch(product.primaryBarcode()).contains(normalizedQuery);
    }

    private static String normalizeSearch(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static String normalizeOptionalText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El motivo es obligatorio");
        }
        String trimmed = reason.trim();
        if (trimmed.length() < MIN_REASON_LENGTH || trimmed.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El motivo debe tener entre " + MIN_REASON_LENGTH + " y " + MAX_REASON_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private static boolean isOpenScopeConflict(DataIntegrityViolationException ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof SQLException sqlEx && "23505".equals(sqlEx.getSQLState())) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null && message.contains("uq_inventory_counts_open_scope")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private record EligibleProduct(Long productId, boolean lotControlled) {
    }
}
