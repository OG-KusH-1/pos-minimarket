package cl.minimarket.pos.purchasing.order.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.ProductCatalogApi.TaxRate;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileMetadata;
import cl.minimarket.pos.notification.EmailOutboxApi;
import cl.minimarket.pos.notification.EmailOutboxApi.EmailMessage;
import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.purchasing.ProductSupplierApi.ProductSupplierInfo;
import cl.minimarket.pos.purchasing.PurchaseOrderOrigin;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderQueryService.PurchaseOrderDetail;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrder;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderLine;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderRepository;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderStatus;
import cl.minimarket.pos.purchasing.supplier.domain.Supplier;
import cl.minimarket.pos.purchasing.supplier.domain.SupplierRepository;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Casos de uso de órdenes de compra ({@code purchase_orders}, {@code HU-PRV-03}, {@code HU-PRV-04}).
 */
@Service
public class PurchaseOrderService {

    private static final int MAX_LINES = 500;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String ENTITY_TYPE = "purchase_order";

    private final PurchaseOrderRepository orderRepository;
    private final SupplierRepository supplierRepository;
    private final ProductSupplierApi productSupplierApi;
    private final ProductCatalogApi productCatalogApi;
    private final SequenceService sequenceService;
    private final BusinessClock clock;
    private final AuditService auditService;
    private final EmailOutboxApi emailOutboxApi;
    private final FileApi fileApi;
    private final PurchaseOrderQueryService queryService;
    private final JdbcClient jdbc;

    public PurchaseOrderService(PurchaseOrderRepository orderRepository,
                                SupplierRepository supplierRepository,
                                ProductSupplierApi productSupplierApi,
                                ProductCatalogApi productCatalogApi,
                                SequenceService sequenceService,
                                BusinessClock clock,
                                AuditService auditService,
                                EmailOutboxApi emailOutboxApi,
                                FileApi fileApi,
                                PurchaseOrderQueryService queryService,
                                JdbcClient jdbc) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
        this.supplierRepository = Objects.requireNonNull(supplierRepository, "supplierRepository");
        this.productSupplierApi = Objects.requireNonNull(productSupplierApi, "productSupplierApi");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.sequenceService = Objects.requireNonNull(sequenceService, "sequenceService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.emailOutboxApi = Objects.requireNonNull(emailOutboxApi, "emailOutboxApi");
        this.fileApi = Objects.requireNonNull(fileApi, "fileApi");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Línea de entrada para crear o actualizar una orden de compra. */
    public record LineCommand(
            Long productId,
            BigDecimal qtyPurchaseUnits,
            BigDecimal unitCostNet,
            Long replenishmentLineId
    ) {

        /**
         * Constructor de conveniencia sin línea de reabastecimiento: es el que usa la
         * creación y edición manual desde la web, donde el enlace
         * {@code replenishment_line_id} no existe.
         */
        public LineCommand(Long productId, BigDecimal qtyPurchaseUnits, BigDecimal unitCostNet) {
            this(productId, qtyPurchaseUnits, unitCostNet, null);
        }
    }

    /** Comando de creación o actualización de una orden de compra. */
    public record SaveCommand(
            Long supplierId,
            LocalDate expectedDate,
            String notes,
            List<LineCommand> lines,
            Long version
    ) {
    }

    /**
     * Crea una orden de compra en estado {@code DRAFT} para el local activo, de origen
     * {@code MANUAL} (creación manual desde la web, {@code HU-PRV-03}).
     */
    @Transactional
    public PurchaseOrderDetail create(SaveCommand command) {
        Objects.requireNonNull(command, "command");
        Long storeId = StoreContext.require().storeId();
        Long id = createDraft(storeId, PurchaseOrderOrigin.MANUAL, null, command);
        return queryService.getById(id, storeId);
    }

    /**
     * Crea una orden de compra en estado {@code DRAFT} para el local y el origen
     * indicados, sin depender de {@link StoreContext}.
     *
     * <p>Es el camino de creación que usa la API pública del módulo
     * ({@code PurchaseOrderApi.createDrafts}, llamada por {@code replenishment} con
     * {@code origin=REPLENISHMENT}); la creación manual delega aquí con
     * {@code origin=MANUAL}. No audita, igual que la creación manual: la bitácora del
     * módulo registra el envío y la cancelación, no el borrador.
     *
     * @param storeId            local para el que se crea la orden
     * @param origin             origen de la orden (manual o reabastecimiento)
     * @param replenishmentRunId corrida que originó el borrador (opcional; solo tiene
     *                           sentido con {@code origin=REPLENISHMENT})
     * @param command            datos de la orden (proveedor, fecha, notas y líneas)
     * @return identificador de la orden creada
     */
    @Transactional
    public Long createDraft(Long storeId,
                            PurchaseOrderOrigin origin,
                            Long replenishmentRunId,
                            SaveCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(origin, "origin");

        Supplier supplier = requireActiveSupplier(command.supplierId());
        validateExpectedDate(command.expectedDate());

        String number = sequenceService.next(SequenceType.PURCHASE_ORDER, storeId);
        PurchaseOrder order = new PurchaseOrder(
                number,
                storeId,
                supplier.getId(),
                origin,
                replenishmentRunId,
                command.expectedDate(),
                normalizeNotes(command.notes()));

        populateLinesAndTotals(order, supplier.getId(), command.lines());
        return orderRepository.saveAndFlush(order).getId();
    }

    /** Actualiza una orden de compra existente; solo permitido en estado {@code DRAFT} (RN-18). */
    @Transactional
    public PurchaseOrderDetail update(Long id, SaveCommand command) {
        Objects.requireNonNull(command, "command");
        Long storeId = StoreContext.require().storeId();

        PurchaseOrder order = orderRepository.findWithLinesByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Orden de compra no encontrada."));

        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden editar órdenes de compra en estado DRAFT.");
        }
        requireMatchingVersion(command.version(), order.getVersion());

        Supplier supplier = requireActiveSupplier(command.supplierId());
        validateExpectedDate(command.expectedDate());

        order.setSupplierId(supplier.getId());
        order.setExpectedDate(command.expectedDate());
        order.setNotes(normalizeNotes(command.notes()));
        order.clearLines();
        orderRepository.flush();

        populateLinesAndTotals(order, supplier.getId(), command.lines());
        orderRepository.saveAndFlush(order);
        return queryService.getById(order.getId(), storeId);
    }

    /**
     * Aprueba y envía la orden de compra ({@code DRAFT -> SENT}). Si {@code emailToSupplier} es
     * {@code true}, genera el PDF, lo registra en {@code stored_files} y encola el correo en la
     * outbox ({@link EmailOutboxApi}).
     */
    @Transactional
    public PurchaseOrderDetail send(Long id, boolean emailToSupplier, Long version) {
        Long storeId = StoreContext.require().storeId();
        PurchaseOrder order = orderRepository.findWithLinesByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Orden de compra no encontrada."));

        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden enviar órdenes de compra en estado DRAFT.");
        }
        requireMatchingVersion(version, order.getVersion());

        Supplier supplier = supplierRepository.findById(order.getSupplierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Proveedor no encontrado."));

        if (emailToSupplier && (supplier.getEmail() == null || supplier.getEmail().isBlank())) {
            throw validationError(
                    "El proveedor no tiene correo electrónico configurado.",
                    "emailToSupplier", "EMAIL_MISSING", "El proveedor no posee correo electrónico.");
        }

        PurchaseOrderStatus previousStatus = order.getStatus();
        order.markSent(clock.now(), CurrentUserId.find().orElse(null));
        orderRepository.saveAndFlush(order);

        if (emailToSupplier) {
            byte[] pdfBytes = queryService.renderPdfForEmail(order.getId(), storeId);
            FileMetadata fileMeta = fileApi.register(
                    new ByteArrayInputStream(pdfBytes),
                    "OC-" + order.getNumber() + ".pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    pdfBytes.length,
                    FileCategory.EXPORT);

            String storeName = loadStoreName(storeId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", order.getId());
            payload.put("orderNumber", order.getNumber());
            payload.put("storeName", storeName);
            payload.put("supplierName", supplier.getLegalName());
            payload.put("expectedDate", order.getExpectedDate() != null ? order.getExpectedDate().toString() : "");
            payload.put("notes", order.getNotes() != null ? order.getNotes() : "");

            emailOutboxApi.enqueue(new EmailMessage(
                    supplier.getEmail().trim(),
                    "Orden de Compra " + order.getNumber(),
                    "purchase-order",
                    payload,
                    fileMeta.id()));
        }

        auditService.record(new AuditEvent(
                "PURCHASE_ORDER_SENT",
                ENTITY_TYPE,
                String.valueOf(order.getId()),
                Map.of("status", previousStatus.name(), "total", order.getTotal()),
                Map.of("status", order.getStatus().name(), "total", order.getTotal(),
                        "emailToSupplier", emailToSupplier),
                null,
                null));

        return queryService.getById(order.getId(), storeId);
    }

    /**
     * Cancela una orden de compra en estado {@code DRAFT} o {@code SENT} sin recepciones
     * confirmadas.
     */
    @Transactional
    public PurchaseOrderDetail cancel(Long id, String reason) {
        Long storeId = StoreContext.require().storeId();
        PurchaseOrder order = orderRepository.findWithLinesByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Orden de compra no encontrada."));

        if (order.getStatus() != PurchaseOrderStatus.DRAFT && order.getStatus() != PurchaseOrderStatus.SENT) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "No se puede cancelar una orden en estado " + order.getStatus().name() + ".");
        }
        if (hasConfirmedReceipt(order.getId())) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "No se puede cancelar una orden de compra con recepciones confirmadas.");
        }

        String trimmedReason = reason == null ? "" : reason.trim();
        PurchaseOrderStatus previousStatus = order.getStatus();
        order.markCancelled(clock.now(), trimmedReason);
        orderRepository.saveAndFlush(order);

        auditService.record(new AuditEvent(
                "PURCHASE_ORDER_CANCELLED",
                ENTITY_TYPE,
                String.valueOf(order.getId()),
                Map.of("status", previousStatus.name(), "total", order.getTotal()),
                Map.of("status", order.getStatus().name(), "total", order.getTotal()),
                trimmedReason,
                null));

        return queryService.getById(order.getId(), storeId);
    }

    private void populateLinesAndTotals(PurchaseOrder order, Long supplierId, List<LineCommand> lineCommands) {
        if (lineCommands == null || lineCommands.isEmpty() || lineCommands.size() > MAX_LINES) {
            throw validationError(
                    "La orden debe tener entre 1 y " + MAX_LINES + " líneas.",
                    "lines", "INVALID_SIZE", "Cantidad de líneas inválida.");
        }

        Set<Long> productIds = new HashSet<>();
        for (LineCommand line : lineCommands) {
            if (line.productId() == null) {
                throw validationError(
                        "Cada línea debe indicar un producto.",
                        "lines.productId", "NOT_NULL", "El producto es obligatorio.");
            }
            if (!productIds.add(line.productId())) {
                throw validationError(
                        "No se permiten productos repetidos en la misma orden.",
                        "lines", "DUPLICATED_PRODUCT", "Producto duplicado: " + line.productId());
            }
        }

        Map<Long, ProductSnapshot> productsById = productCatalogApi.findByIds(productIds).stream()
                .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));

        BigDecimal defaultVatRate = loadDefaultVatRate();
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;

        for (LineCommand cmdLine : lineCommands) {
            ProductSnapshot product = productsById.get(cmdLine.productId());
            if (product == null) {
                throw new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "Producto no encontrado: " + cmdLine.productId());
            }
            if (!product.active()) {
                throw new BusinessException(
                        ErrorCode.PRODUCT_INACTIVE,
                        "El producto está inactivo: " + product.name());
            }
            if (product.saleType() == SaleType.PACK) {
                throw validationError(
                        "Un producto PACK no puede incluirse en una orden de compra.",
                        "lines.productId", "PACK_NOT_ALLOWED", "Producto PACK no permitido: " + product.sku());
            }
            if (cmdLine.qtyPurchaseUnits() == null || cmdLine.qtyPurchaseUnits().signum() <= 0) {
                throw validationError(
                        "La cantidad a comprar debe ser mayor a cero.",
                        "lines.qtyPurchaseUnits", "MIN_VALUE", "Debe ser mayor a 0.");
            }

            Optional<ProductSupplierInfo> supplierLink = productSupplierApi.find(product.id(), supplierId);
            Long productSupplierId = supplierLink.map(ProductSupplierInfo::id).orElse(null);
            BigDecimal unitsPerPurchaseUnit = supplierLink
                    .map(ProductSupplierInfo::unitsPerPurchaseUnit)
                    .orElse(BigDecimal.ONE);

            BigDecimal unitCostNet;
            if (cmdLine.unitCostNet() != null) {
                if (cmdLine.unitCostNet().signum() < 0) {
                    throw validationError(
                            "El costo unitario neto no puede ser negativo.",
                            "lines.unitCostNet", "MIN_VALUE", "Debe ser >= 0.");
                }
                unitCostNet = Rounding.unitCost(cmdLine.unitCostNet());
            } else {
                unitCostNet = supplierLink
                        .map(ProductSupplierInfo::unitCostNet)
                        .orElseThrow(() -> validationError(
                                "Debe indicar unitCostNet para el producto sin asociación producto-proveedor.",
                                "lines.unitCostNet", "REQUIRED", "Costo requerido para producto " + product.sku()));
            }

            BigDecimal qtyPurchaseUnits = Rounding.quantity(cmdLine.qtyPurchaseUnits());
            BigDecimal qtyOrdered = Rounding.quantity(qtyPurchaseUnits.multiply(unitsPerPurchaseUnit));
            BigDecimal lineNetTotal = Rounding.clp(qtyOrdered.multiply(unitCostNet));

            BigDecimal vatRate = resolveVatRate(product, defaultVatRate);
            BigDecimal lineTax = Rounding.clp(lineNetTotal.multiply(vatRate).divide(HUNDRED, 0, RoundingMode.HALF_UP));

            netTotal = netTotal.add(lineNetTotal);
            taxTotal = taxTotal.add(lineTax);

            order.addLine(new PurchaseOrderLine(
                    product.id(),
                    productSupplierId,
                    qtyPurchaseUnits,
                    unitsPerPurchaseUnit,
                    qtyOrdered,
                    unitCostNet,
                    lineNetTotal,
                    cmdLine.replenishmentLineId()));
        }

        order.applyTotals(netTotal, taxTotal, netTotal.add(taxTotal));
    }

    private BigDecimal resolveVatRate(ProductSnapshot product, BigDecimal defaultVatRate) {
        if (product.vatExempt()) {
            return BigDecimal.ZERO;
        }
        return productCatalogApi.taxesFor(product.id()).stream()
                .filter(t -> "VAT".equalsIgnoreCase(t.taxType()))
                .map(TaxRate::rate)
                .findFirst()
                .orElse(defaultVatRate);
    }

    private BigDecimal loadDefaultVatRate() {
        return jdbc.sql("SELECT rate FROM taxes WHERE tax_type = 'VAT' AND active = true ORDER BY id ASC LIMIT 1")
                .query(BigDecimal.class)
                .optional()
                .orElse(BigDecimal.ZERO);
    }

    private Supplier requireActiveSupplier(Long supplierId) {
        if (supplierId == null) {
            throw validationError("El proveedor es obligatorio.", "supplierId", "NOT_NULL", "Obligatorio.");
        }
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Proveedor no encontrado."));
        if (!supplier.isActive()) {
            throw validationError("El proveedor está inactivo.", "supplierId", "INACTIVE", "Proveedor inactivo.");
        }
        return supplier;
    }

    private void validateExpectedDate(LocalDate expectedDate) {
        if (expectedDate != null && expectedDate.isBefore(clock.businessDate())) {
            throw validationError(
                    "La fecha esperada no puede ser anterior a hoy.",
                    "expectedDate", "DATE_IN_PAST", "Debe ser igual o posterior a hoy.");
        }
    }

    private boolean hasConfirmedReceipt(Long orderId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*)
                FROM goods_receipts
                WHERE purchase_order_id = :orderId AND status = 'CONFIRMED'
                """)
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    private String loadStoreName(Long storeId) {
        return jdbc.sql("SELECT name FROM stores WHERE id = :storeId")
                .param("storeId", storeId)
                .query(String.class)
                .optional()
                .orElse("Minimarket");
    }

    private static void requireMatchingVersion(Long requestVersion, Long currentVersion) {
        if (requestVersion == null) {
            throw validationError(
                    "La versión es obligatoria para modificar la orden.",
                    "version", "NOT_NULL", "La versión es obligatoria.");
        }
        if (!requestVersion.equals(currentVersion)) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "La orden de compra fue modificada por otro usuario.");
        }
    }

    private static BusinessException validationError(String detail,
                                                     String field,
                                                     String fieldCode,
                                                     String fieldMessage) {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                detail,
                Map.of(),
                List.of(new FieldErrorDetail(field, fieldCode, fieldMessage)));
    }

    private static String normalizeNotes(String notes) {
        if (notes == null) {
            return null;
        }
        String trimmed = notes.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
