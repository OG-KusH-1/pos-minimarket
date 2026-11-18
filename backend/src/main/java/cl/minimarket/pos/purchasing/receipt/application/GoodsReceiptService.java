package cl.minimarket.pos.purchasing.receipt.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.PriceChangeType;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.LotAllocation;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderStatus;
import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService.CostChangeView;
import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService.GoodsReceiptDetail;
import cl.minimarket.pos.purchasing.receipt.domain.AverageCostCalculator;
import cl.minimarket.pos.purchasing.receipt.domain.GoodsReceipt;
import cl.minimarket.pos.purchasing.receipt.domain.GoodsReceiptLine;
import cl.minimarket.pos.purchasing.receipt.domain.GoodsReceiptRepository;
import cl.minimarket.pos.purchasing.receipt.domain.GoodsReceiptStatus;
import cl.minimarket.pos.purchasing.receipt.domain.SupplierDocumentType;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Quantity;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Casos de uso de escritura para recepciones de mercadería ({@code goods_receipts},
 * {@code HU-PRV-05}, {@code HU-PRV-06}, {@code HU-PRV-07}, {@code HU-PRV-08}).
 */
@Service
public class GoodsReceiptService {

    private static final String ACTION_CONFIRMED = "GOODS_RECEIPT_CONFIRMED";
    private static final String ENTITY_TYPE = "goods_receipt";
    private static final BigDecimal SOD_DIFFERENCE_THRESHOLD = new BigDecimal("0.10");

    private final GoodsReceiptRepository repository;
    private final GoodsReceiptQueryService queryService;
    private final ProductCatalogApi productCatalogApi;
    private final ProductSupplierApi productSupplierApi;
    private final StockApi stockApi;
    private final SequenceService sequenceService;
    private final AuditService auditService;
    private final BusinessClock clock;
    private final JdbcClient jdbc;

    public GoodsReceiptService(GoodsReceiptRepository repository,
                               GoodsReceiptQueryService queryService,
                               ProductCatalogApi productCatalogApi,
                               ProductSupplierApi productSupplierApi,
                               StockApi stockApi,
                               SequenceService sequenceService,
                               AuditService auditService,
                               BusinessClock clock,
                               JdbcClient jdbc) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.productSupplierApi = Objects.requireNonNull(productSupplierApi, "productSupplierApi");
        this.stockApi = Objects.requireNonNull(stockApi, "stockApi");
        this.sequenceService = Objects.requireNonNull(sequenceService, "sequenceService");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Línea de entrada para crear o actualizar una recepción en borrador. */
    public record SaveLineCommand(
            Long purchaseOrderLineId,
            Long productId,
            BigDecimal qtyReceived,
            BigDecimal qtyRejected,
            String differenceReason,
            BigDecimal unitCostNet,
            String lotNumber,
            LocalDate expiryDate
    ) {
    }

    /** Comando de creación o actualización de recepción en estado {@code DRAFT}. */
    public record SaveCommand(
            Long purchaseOrderId,
            Long supplierId,
            SupplierDocumentType supplierDocumentType,
            String supplierDocumentNumber,
            String notes,
            List<SaveLineCommand> lines,
            Long version
    ) {
    }

    /**
     * Crea una recepción de mercadería en estado {@code DRAFT}.
     *
     * <p>Si no se asocia una orden de compra ({@code purchaseOrderId == null}), exige el permiso
     * {@code purchasing:receipt:without-order} ({@code HU-PRV-08}).
     */
    @Transactional
    public GoodsReceiptDetail create(SaveCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Long storeId = StoreContext.require().storeId();

        ValidatedReceiptInput validated = validateAndBuildInput(cmd, storeId);
        String number = sequenceService.next(SequenceType.GOODS_RECEIPT, storeId);

        GoodsReceipt receipt = new GoodsReceipt(
                number,
                validated.purchaseOrderId(),
                storeId,
                validated.supplierId(),
                validated.documentType(),
                validated.documentNumber(),
                validated.netTotal(),
                validated.notes());
        for (GoodsReceiptLine line : validated.lines()) {
            receipt.addLine(line);
        }

        GoodsReceipt saved = repository.saveAndFlush(receipt);
        return queryService.getById(saved.getId(), storeId);
    }

    /**
     * Actualiza una recepción en estado {@code DRAFT} aplicando control de concurrencia optimista.
     */
    @Transactional
    public GoodsReceiptDetail update(Long id, SaveCommand cmd) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cmd, "cmd");
        Long storeId = StoreContext.require().storeId();

        GoodsReceipt receipt = repository.findWithLinesByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Recepción de mercadería no encontrada: " + id));

        if (receipt.getStatus() != GoodsReceiptStatus.DRAFT) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden editar recepciones en estado DRAFT.");
        }
        if (cmd.version() != null && !cmd.version().equals(receipt.getVersion())) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "La recepción fue modificada por otro usuario.");
        }

        ValidatedReceiptInput validated = validateAndBuildInput(cmd, storeId);
        receipt.setPurchaseOrderId(validated.purchaseOrderId());
        receipt.setSupplierId(validated.supplierId());
        receipt.setSupplierDocumentType(validated.documentType());
        receipt.setSupplierDocumentNumber(validated.documentNumber());
        receipt.setNetTotal(validated.netTotal());
        receipt.setNotes(validated.notes());

        receipt.clearLines();
        repository.flush();
        for (GoodsReceiptLine line : validated.lines()) {
            receipt.addLine(line);
        }

        GoodsReceipt saved = repository.saveAndFlush(receipt);
        return queryService.getById(saved.getId(), storeId);
    }

    /**
     * Cancela una recepción que aún se encuentra en estado {@code DRAFT}.
     */
    @Transactional
    public void cancel(Long id, String reason) {
        Objects.requireNonNull(id, "id");
        Long storeId = StoreContext.require().storeId();

        GoodsReceipt receipt = repository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Recepción de mercadería no encontrada: " + id));

        if (receipt.getStatus() != GoodsReceiptStatus.DRAFT) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden cancelar recepciones en estado DRAFT.");
        }

        receipt.markCancelled(reason);
        repository.saveAndFlush(receipt);
    }

    /**
     * Confirma una recepción en estado {@code DRAFT}:
     * <ol>
     *   <li>Valida estado de la OC, sobre-recepción ({@code RECEIPT_EXCEEDS_ORDER}), segregación de
     *       funciones para diferencias &gt; 10 % y lotes obligatorios ({@code LOT_REQUIRED}).</li>
     *   <li>Ordena las líneas por {@code product_id} ascendente (ADR-13).</li>
     *   <li>Calcula y aplica el costo promedio ponderado ({@code ProductCatalogApi.applyAverageCost})
     *       usando {@code Q = companyQuantity} previo a la entrada y {@code q = qtyReceived - qtyRejected}.</li>
     *   <li>Ejecuta {@code StockApi.apply(PURCHASE_RECEIPT)} creando/actualizando lotes y asignando {@code lot_id}.</li>
     *   <li>Actualiza {@code purchase_order_lines.qty_received} y transiciona la OC a
     *       {@code PARTIALLY_RECEIVED} o {@code RECEIVED}.</li>
     *   <li>Registra/actualiza la asociación producto-proveedor y audita {@code GOODS_RECEIPT_CONFIRMED}.</li>
     * </ol>
     */
    @Transactional
    public GoodsReceiptDetail confirm(Long id) {
        Objects.requireNonNull(id, "id");
        Long storeId = StoreContext.require().storeId();
        Long currentUserId = CurrentUserId.find()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Usuario no autenticado."));
        Instant now = clock.now();

        GoodsReceipt receipt = repository.findWithLinesByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Recepción de mercadería no encontrada: " + id));

        if (receipt.getStatus() != GoodsReceiptStatus.DRAFT) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden confirmar recepciones en estado DRAFT.");
        }
        if (receipt.getLines().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "La recepción debe tener al menos una línea para confirmarse.");
        }

        boolean withoutOrder = receipt.getPurchaseOrderId() == null;
        if (withoutOrder && !hasAuthority(Permissions.PURCHASING_RECEIPT_WITHOUT_ORDER)) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "Se requiere el permiso purchasing:receipt:without-order para confirmar sin orden de compra.");
        }

        PoLockContext poContext = null;
        if (!withoutOrder) {
            poContext = lockAndValidateOrderForConfirm(receipt, currentUserId);
        }

        Map<Long, ProductCatalogApi.ProductSnapshot> products = loadProductsMap(
                receipt.getLines().stream().map(GoodsReceiptLine::getProductId).distinct().toList());

        validateLotsForConfirm(receipt.getLines(), products);

        List<GoodsReceiptLine> sortedLines = receipt.getLines().stream()
                .sorted(Comparator.comparing(GoodsReceiptLine::getProductId)
                        .thenComparing(GoodsReceiptLine::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();

        List<CostChangeView> costChanges = applyWeightedAverageCosts(receipt.getId(), sortedLines, products);

        List<StockLine> stockLines = new ArrayList<>();
        List<GoodsReceiptLine> acceptedLines = new ArrayList<>();
        for (GoodsReceiptLine line : sortedLines) {
            BigDecimal qtyAccepted = line.qtyAccepted();
            if (qtyAccepted.compareTo(BigDecimal.ZERO) > 0) {
                acceptedLines.add(line);
                stockLines.add(new StockLine(
                        line.getProductId(),
                        qtyAccepted,
                        line.getUnitCostNet(),
                        line.getId(),
                        null,
                        line.getLotNumber(),
                        line.getExpiryDate()));
            }
        }

        if (!stockLines.isEmpty()) {
            List<StockMovementResult> movementResults = stockApi.apply(new StockCommand(
                    storeId,
                    StockMovementType.PURCHASE_RECEIPT,
                    StockSourceType.GOODS_RECEIPT,
                    receipt.getId(),
                    currentUserId,
                    "Recepción " + receipt.getNumber(),
                    stockLines));
            assignLotIds(acceptedLines, movementResults);
        }

        boolean overReceived = false;
        if (poContext != null) {
            overReceived = poContext.overReceived();
            updatePurchaseOrderProgress(poContext, acceptedLines, currentUserId, now);
        }

        syncProductSuppliers(receipt.getSupplierId(), acceptedLines, now);

        BigDecimal netTotal = computeNetTotal(receipt.getLines());
        receipt.markConfirmed(now, currentUserId, netTotal);
        repository.saveAndFlush(receipt);

        auditService.record(new AuditEvent(
                ACTION_CONFIRMED,
                ENTITY_TYPE,
                receipt.getId().toString(),
                null,
                buildConfirmAuditPayload(receipt, withoutOrder, overReceived, costChanges),
                null,
                null));

        return queryService.getByIdWithCostChanges(receipt.getId(), storeId, costChanges);
    }

    private PoLockContext lockAndValidateOrderForConfirm(GoodsReceipt receipt, Long currentUserId) {
        PoLockedRow po = jdbc.sql("""
                SELECT id, store_id, supplier_id, status, created_by
                FROM purchase_orders
                WHERE id = :id AND store_id = :storeId
                FOR UPDATE
                """)
                .param("id", receipt.getPurchaseOrderId())
                .param("storeId", receipt.getStoreId())
                .query((rs, rowNum) -> {
                    long cb = rs.getLong("created_by");
                    Long createdBy = rs.wasNull() ? null : cb;
                    return new PoLockedRow(
                            rs.getLong("id"),
                            rs.getLong("store_id"),
                            rs.getLong("supplier_id"),
                            PurchaseOrderStatus.valueOf(rs.getString("status")),
                            createdBy);
                })
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Orden de compra no encontrada: " + receipt.getPurchaseOrderId()));

        if (po.status() != PurchaseOrderStatus.SENT
                && po.status() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "La orden de compra debe estar en estado SENT o PARTIALLY_RECEIVED para confirmar la recepción.");
        }

        Map<Long, PoLineRow> poLinesById = jdbc.sql("""
                SELECT id, product_id, qty_ordered, qty_received
                FROM purchase_order_lines
                WHERE purchase_order_id = :poId
                """)
                .param("poId", po.id())
                .query((rs, rowNum) -> new PoLineRow(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        Rounding.quantity(rs.getBigDecimal("qty_ordered")),
                        Rounding.quantity(rs.getBigDecimal("qty_received"))))
                .list()
                .stream()
                .collect(Collectors.toMap(PoLineRow::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Map<Long, BigDecimal> acceptedByPoLine = new LinkedHashMap<>();
        boolean significantDifference = false;

        for (GoodsReceiptLine line : receipt.getLines()) {
            BigDecimal qtyAccepted = line.qtyAccepted();
            if (line.getPurchaseOrderLineId() != null) {
                PoLineRow poLine = poLinesById.get(line.getPurchaseOrderLineId());
                if (poLine == null || !poLine.productId().equals(line.getProductId())) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "La línea de recepción no corresponde a la orden de compra.");
                }
                acceptedByPoLine.merge(poLine.id(), qtyAccepted, (a, b) -> Rounding.quantity(a.add(b)));

                BigDecimal expected = line.getQtyExpected() != null
                        ? line.getQtyExpected()
                        : Rounding.quantity(poLine.qtyOrdered().subtract(poLine.qtyReceived()).max(BigDecimal.ZERO));
                if (isDifferenceAboveThreshold(expected, qtyAccepted)) {
                    significantDifference = true;
                }
            }
        }

        boolean overReceived = false;
        for (Map.Entry<Long, BigDecimal> entry : acceptedByPoLine.entrySet()) {
            PoLineRow poLine = poLinesById.get(entry.getKey());
            BigDecimal newTotalReceived = poLine.qtyReceived().add(entry.getValue());
            if (newTotalReceived.compareTo(poLine.qtyOrdered()) > 0) {
                overReceived = true;
                break;
            }
        }

        if (overReceived && !hasAuthority(Permissions.PURCHASING_RECEIPT_OVER_RECEIVE)) {
            throw new BusinessException(
                    ErrorCode.RECEIPT_EXCEEDS_ORDER,
                    "La cantidad recibida excede lo pendiente de la orden de compra.");
        }

        if (significantDifference && po.createdBy() != null && po.createdBy().equals(currentUserId)) {
            throw new BusinessException(
                    ErrorCode.SEGREGATION_OF_DUTIES,
                    "Una recepción con diferencias superiores al 10 % debe ser confirmada por un usuario distinto de quien creó la orden de compra.");
        }

        return new PoLockContext(po.id(), poLinesById, acceptedByPoLine, overReceived);
    }

    private static boolean isDifferenceAboveThreshold(BigDecimal expected, BigDecimal accepted) {
        if (expected.compareTo(BigDecimal.ZERO) <= 0) {
            return accepted.compareTo(BigDecimal.ZERO) > 0;
        }
        BigDecimal diff = accepted.subtract(expected).abs();
        BigDecimal ratio = diff.divide(expected, 6, RoundingMode.HALF_UP);
        return ratio.compareTo(SOD_DIFFERENCE_THRESHOLD) > 0;
    }

    private void validateLotsForConfirm(List<GoodsReceiptLine> lines,
                                        Map<Long, ProductCatalogApi.ProductSnapshot> products) {
        for (GoodsReceiptLine line : lines) {
            if (line.qtyAccepted().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            ProductCatalogApi.ProductSnapshot product = products.get(line.getProductId());
            if (product != null && product.lotControlled()) {
                if (line.getLotNumber() == null || line.getLotNumber().isBlank()) {
                    throw new BusinessException(
                            ErrorCode.LOT_REQUIRED,
                            "El producto " + product.sku() + " tiene control de lotes y requiere número de lote.");
                }
                if (product.shelfLifeDays() != null && line.getExpiryDate() == null) {
                    throw new BusinessException(
                            ErrorCode.LOT_REQUIRED,
                            "El producto " + product.sku() + " tiene vida útil configurada y requiere fecha de vencimiento.");
                }
            }
        }
    }

    private List<CostChangeView> applyWeightedAverageCosts(Long receiptId,
                                                           List<GoodsReceiptLine> sortedLines,
                                                           Map<Long, ProductCatalogApi.ProductSnapshot> products) {
        Map<Long, List<GoodsReceiptLine>> byProduct = new LinkedHashMap<>();
        for (GoodsReceiptLine line : sortedLines) {
            if (line.qtyAccepted().compareTo(BigDecimal.ZERO) > 0) {
                byProduct.computeIfAbsent(line.getProductId(), k -> new ArrayList<>()).add(line);
            }
        }

        List<CostChangeView> changes = new ArrayList<>();
        String sourceRef = "GOODS_RECEIPT:" + receiptId;

        for (Map.Entry<Long, List<GoodsReceiptLine>> entry : byProduct.entrySet()) {
            Long productId = entry.getKey();
            ProductCatalogApi.ProductSnapshot product = products.get(productId);
            BigDecimal initialAvg = Rounding.unitCost(
                    product.avgCost() != null ? product.avgCost() : BigDecimal.ZERO);
            BigDecimal runningQty = stockApi.companyQuantity(productId);
            BigDecimal runningAvg = initialAvg;

            for (GoodsReceiptLine line : entry.getValue()) {
                BigDecimal qIn = line.qtyAccepted();
                runningAvg = AverageCostCalculator.calculate(
                        runningQty,
                        runningAvg,
                        line.getQtyReceived(),
                        line.getQtyRejected(),
                        line.getUnitCostNet());
                runningQty = runningQty.add(qIn);
            }

            productCatalogApi.applyAverageCost(
                    productId, runningAvg, PriceChangeType.COST_RECEIPT, sourceRef);
            changes.add(new CostChangeView(productId, initialAvg, runningAvg));
        }

        return changes;
    }

    private void assignLotIds(List<GoodsReceiptLine> acceptedLines, List<StockMovementResult> results) {
        Map<Long, List<LotAllocation>> allocationsByProduct = new LinkedHashMap<>();
        for (StockMovementResult result : results) {
            if (!result.allocations().isEmpty()) {
                allocationsByProduct.put(result.productId(), result.allocations());
            }
        }
        Map<Long, Integer> indexByProduct = new LinkedHashMap<>();
        for (GoodsReceiptLine line : acceptedLines) {
            List<LotAllocation> allocations = allocationsByProduct.get(line.getProductId());
            if (allocations != null && !allocations.isEmpty()) {
                int idx = indexByProduct.getOrDefault(line.getProductId(), 0);
                if (idx < allocations.size()) {
                    line.setLotId(allocations.get(idx).lotId());
                    indexByProduct.put(line.getProductId(), idx + 1);
                } else {
                    line.setLotId(allocations.getFirst().lotId());
                }
            }
        }
    }

    private void updatePurchaseOrderProgress(PoLockContext poContext,
                                             List<GoodsReceiptLine> acceptedLines,
                                             Long userId,
                                             Instant now) {
        Timestamp ts = Timestamp.from(now);
        for (Map.Entry<Long, BigDecimal> entry : poContext.acceptedByPoLine().entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                jdbc.sql("""
                        UPDATE purchase_order_lines
                        SET qty_received = qty_received + :qtyAccepted,
                            updated_at = :now,
                            updated_by = :userId
                        WHERE id = :lineId
                        """)
                        .param("qtyAccepted", entry.getValue())
                        .param("now", ts)
                        .param("userId", userId)
                        .param("lineId", entry.getKey())
                        .update();
            }
        }

        boolean allReceived = true;
        for (PoLineRow poLine : poContext.poLinesById().values()) {
            BigDecimal added = poContext.acceptedByPoLine().getOrDefault(poLine.id(), BigDecimal.ZERO);
            BigDecimal updatedReceived = poLine.qtyReceived().add(added);
            if (updatedReceived.compareTo(poLine.qtyOrdered()) < 0) {
                allReceived = false;
                break;
            }
        }

        PurchaseOrderStatus nextStatus = allReceived
                ? PurchaseOrderStatus.RECEIVED
                : PurchaseOrderStatus.PARTIALLY_RECEIVED;

        jdbc.sql("""
                UPDATE purchase_orders
                SET status = :status,
                    updated_at = :now,
                    updated_by = :userId,
                    version = version + 1
                WHERE id = :poId
                """)
                .param("status", nextStatus.name())
                .param("now", ts)
                .param("userId", userId)
                .param("poId", poContext.poId())
                .update();
    }

    private void syncProductSuppliers(Long supplierId, List<GoodsReceiptLine> acceptedLines, Instant now) {
        Timestamp ts = Timestamp.from(now);
        for (GoodsReceiptLine line : acceptedLines) {
            var existing = productSupplierApi.find(line.getProductId(), supplierId);
            if (existing.isEmpty()) {
                productSupplierApi.upsert(new ProductSupplierApi.ProductSupplierCommand(
                        line.getProductId(),
                        supplierId,
                        null,
                        "Unidad",
                        BigDecimal.ONE,
                        line.getUnitCostNet(),
                        BigDecimal.ONE,
                        null,
                        null));
            }
            jdbc.sql("""
                    UPDATE product_suppliers
                    SET last_purchase_at = :now
                    WHERE product_id = :productId AND supplier_id = :supplierId
                    """)
                    .param("now", ts)
                    .param("productId", line.getProductId())
                    .param("supplierId", supplierId)
                    .update();
        }
    }

    private ValidatedReceiptInput validateAndBuildInput(SaveCommand cmd, Long storeId) {
        List<FieldErrorDetail> fieldErrors = new ArrayList<>();
        if (cmd.supplierDocumentType() == null) {
            fieldErrors.add(new FieldErrorDetail("supplierDocumentType", "REQUIRED", "Tipo de documento requerido."));
        }
        String docNumber = trimToNull(cmd.supplierDocumentNumber());
        if (cmd.supplierDocumentType() != null && cmd.supplierDocumentType() != SupplierDocumentType.NONE) {
            if (docNumber == null) {
                fieldErrors.add(new FieldErrorDetail(
                        "supplierDocumentNumber", "REQUIRED", "Número de documento requerido."));
            } else if (docNumber.length() > 30) {
                fieldErrors.add(new FieldErrorDetail(
                        "supplierDocumentNumber", "MAX_LENGTH", "Máximo 30 caracteres."));
            }
        }
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            fieldErrors.add(new FieldErrorDetail("lines", "REQUIRED", "Debe incluir al menos una línea."));
        }
        if (!fieldErrors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Datos de recepción inválidos.", Map.of(), fieldErrors);
        }

        Long purchaseOrderId = cmd.purchaseOrderId();
        Long supplierId;
        Map<Long, PoLineRow> poLinesById = Map.of();
        Map<Long, PoLineRow> poLinesByProduct = Map.of();

        if (purchaseOrderId == null) {
            if (!hasAuthority(Permissions.PURCHASING_RECEIPT_WITHOUT_ORDER)) {
                throw new BusinessException(
                        ErrorCode.ACCESS_DENIED,
                        "Se requiere el permiso purchasing:receipt:without-order para recepcionar sin orden de compra.");
            }
            if (cmd.supplierId() == null) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "El proveedor es obligatorio cuando la recepción no tiene orden de compra.",
                        Map.of(),
                        List.of(new FieldErrorDetail("supplierId", "REQUIRED", "Proveedor obligatorio.")));
            }
            ProductSupplierApi.SupplierRef supplier = productSupplierApi.findSupplierById(cmd.supplierId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.NOT_FOUND, "Proveedor no encontrado: " + cmd.supplierId()));
            if (!supplier.active()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "El proveedor se encuentra inactivo: " + supplier.id());
            }
            supplierId = supplier.id();
        } else {
            PoLockedRow po = jdbc.sql("""
                    SELECT id, store_id, supplier_id, status, created_by
                    FROM purchase_orders
                    WHERE id = :id AND store_id = :storeId
                    """)
                    .param("id", purchaseOrderId)
                    .param("storeId", storeId)
                    .query((rs, rowNum) -> new PoLockedRow(
                            rs.getLong("id"),
                            rs.getLong("store_id"),
                            rs.getLong("supplier_id"),
                            PurchaseOrderStatus.valueOf(rs.getString("status")),
                            null))
                    .optional()
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.NOT_FOUND, "Orden de compra no encontrada: " + purchaseOrderId));

            if (po.status() != PurchaseOrderStatus.SENT
                    && po.status() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
                throw new BusinessException(
                        ErrorCode.INVALID_STATE_TRANSITION,
                        "Solo se puede recepcionar una orden en estado SENT o PARTIALLY_RECEIVED.");
            }
            if (cmd.supplierId() != null && !cmd.supplierId().equals(po.supplierId())) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "El proveedor no coincide con el de la orden de compra.");
            }
            supplierId = po.supplierId();

            List<PoLineRow> poLines = jdbc.sql("""
                    SELECT id, product_id, qty_ordered, qty_received
                    FROM purchase_order_lines
                    WHERE purchase_order_id = :poId
                    """)
                    .param("poId", purchaseOrderId)
                    .query((rs, rowNum) -> new PoLineRow(
                            rs.getLong("id"),
                            rs.getLong("product_id"),
                            Rounding.quantity(rs.getBigDecimal("qty_ordered")),
                            Rounding.quantity(rs.getBigDecimal("qty_received"))))
                    .list();
            poLinesById = poLines.stream()
                    .collect(Collectors.toMap(PoLineRow::id, Function.identity()));
            poLinesByProduct = poLines.stream()
                    .collect(Collectors.toMap(PoLineRow::productId, Function.identity(), (a, b) -> a));
        }

        List<Long> productIds = cmd.lines().stream()
                .map(SaveLineCommand::productId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ProductCatalogApi.ProductSnapshot> products = loadProductsMap(productIds);

        List<GoodsReceiptLine> builtLines = new ArrayList<>(cmd.lines().size());
        for (int i = 0; i < cmd.lines().size(); i++) {
            SaveLineCommand lineCmd = cmd.lines().get(i);
            if (lineCmd.productId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Producto obligatorio en línea " + (i + 1));
            }
            ProductCatalogApi.ProductSnapshot product = products.get(lineCmd.productId());
            if (product == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Producto no encontrado: " + lineCmd.productId());
            }
            if (product.saleType() == SaleType.PACK) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Un producto PACK no puede recepcionarse directamente: " + product.sku());
            }

            BigDecimal qtyReceived = Rounding.quantity(
                    lineCmd.qtyReceived() != null ? lineCmd.qtyReceived() : BigDecimal.ZERO);
            BigDecimal qtyRejected = Rounding.quantity(
                    lineCmd.qtyRejected() != null ? lineCmd.qtyRejected() : BigDecimal.ZERO);
            BigDecimal unitCostNet = Rounding.unitCost(
                    lineCmd.unitCostNet() != null ? lineCmd.unitCostNet() : BigDecimal.ZERO);

            if (qtyReceived.compareTo(BigDecimal.ZERO) < 0
                    || qtyRejected.compareTo(BigDecimal.ZERO) < 0
                    || qtyRejected.compareTo(qtyReceived) > 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Cantidades inválidas en línea de producto " + product.sku()
                                + ": debe cumplirse 0 <= qtyRejected <= qtyReceived.");
            }
            if (unitCostNet.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "El costo unitario neto no puede ser negativo en línea " + product.sku());
            }
            if (product.unitOfMeasure() == UnitOfMeasure.UNIT) {
                if (!Quantity.of(qtyReceived).isWholeNumber() || !Quantity.of(qtyRejected).isWholeNumber()) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Las cantidades deben ser enteras para el producto por unidad " + product.sku());
                }
            }

            Long poLineId = null;
            BigDecimal qtyExpected = null;
            String diffReason = trimToNull(lineCmd.differenceReason());
            if (purchaseOrderId != null) {
                PoLineRow poLine = lineCmd.purchaseOrderLineId() != null
                        ? poLinesById.get(lineCmd.purchaseOrderLineId())
                        : poLinesByProduct.get(lineCmd.productId());
                if (poLine == null || !poLine.productId().equals(lineCmd.productId())) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "El producto " + product.sku() + " no corresponde a la orden de compra.");
                }
                poLineId = poLine.id();
                qtyExpected = Rounding.quantity(poLine.qtyOrdered().subtract(poLine.qtyReceived()).max(BigDecimal.ZERO));
                if (qtyReceived.compareTo(qtyExpected) != 0 && diffReason == null) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Debe indicar el motivo de diferencia para el producto " + product.sku()
                                    + " cuando qtyReceived difiere de lo pendiente (" + qtyExpected + ").");
                }
            }

            String lotNumber = trimToNull(lineCmd.lotNumber());
            if (lotNumber != null && lotNumber.length() > 40) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "El número de lote no puede superar 40 caracteres.");
            }
            if (diffReason != null && diffReason.length() > 250) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "El motivo de diferencia no puede superar 250 caracteres.");
            }

            builtLines.add(new GoodsReceiptLine(
                    poLineId,
                    product.id(),
                    qtyExpected,
                    qtyReceived,
                    qtyRejected,
                    diffReason,
                    unitCostNet,
                    lotNumber,
                    lineCmd.expiryDate()));
        }

        BigDecimal netTotal = computeNetTotal(builtLines);
        return new ValidatedReceiptInput(
                purchaseOrderId,
                supplierId,
                cmd.supplierDocumentType(),
                docNumber,
                netTotal,
                trimToNull(cmd.notes()),
                builtLines);
    }

    private Map<Long, ProductCatalogApi.ProductSnapshot> loadProductsMap(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productCatalogApi.findByIds(productIds).stream()
                .collect(Collectors.toMap(ProductCatalogApi.ProductSnapshot::id, Function.identity()));
    }

    private static BigDecimal computeNetTotal(List<GoodsReceiptLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (GoodsReceiptLine line : lines) {
            BigDecimal lineNet = line.qtyAccepted().multiply(line.getUnitCostNet(), MathContext.DECIMAL64);
            total = total.add(Rounding.clp(lineNet));
        }
        return Rounding.clp(total);
    }

    private static Map<String, Object> buildConfirmAuditPayload(GoodsReceipt receipt,
                                                                boolean withoutOrder,
                                                                boolean overReceived,
                                                                List<CostChangeView> costChanges) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("number", receipt.getNumber());
        payload.put("storeId", receipt.getStoreId());
        payload.put("supplierId", receipt.getSupplierId());
        payload.put("purchaseOrderId", receipt.getPurchaseOrderId());
        payload.put("netTotal", receipt.getNetTotal());
        payload.put("withoutOrder", withoutOrder);
        payload.put("overReceived", overReceived);

        List<Map<String, Object>> differences = new ArrayList<>();
        for (GoodsReceiptLine line : receipt.getLines()) {
            boolean qtyDiffers = line.getQtyExpected() != null
                    && line.getQtyReceived().compareTo(line.getQtyExpected()) != 0;
            boolean hasRejected = line.getQtyRejected().compareTo(BigDecimal.ZERO) > 0;
            if (qtyDiffers || hasRejected) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("productId", line.getProductId());
                diff.put("qtyExpected", line.getQtyExpected());
                diff.put("qtyReceived", line.getQtyReceived());
                diff.put("qtyRejected", line.getQtyRejected());
                diff.put("differenceReason", line.getDifferenceReason());
                differences.add(diff);
            }
        }
        payload.put("differences", differences);
        payload.put("costChanges", costChanges);
        return payload;
    }

    private static boolean hasAuthority(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> permission.equals(a.getAuthority()));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PoLockedRow(Long id, Long storeId, Long supplierId, PurchaseOrderStatus status, Long createdBy) {
    }

    private record PoLineRow(Long id, Long productId, BigDecimal qtyOrdered, BigDecimal qtyReceived) {
    }

    private record PoLockContext(
            Long poId,
            Map<Long, PoLineRow> poLinesById,
            Map<Long, BigDecimal> acceptedByPoLine,
            boolean overReceived
    ) {
    }

    private record ValidatedReceiptInput(
            Long purchaseOrderId,
            Long supplierId,
            SupplierDocumentType documentType,
            String documentNumber,
            BigDecimal netTotal,
            String notes,
            List<GoodsReceiptLine> lines
    ) {
    }
}
