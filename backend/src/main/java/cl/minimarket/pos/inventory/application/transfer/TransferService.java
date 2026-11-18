package cl.minimarket.pos.inventory.application.transfer;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.LotAllocation;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.inventory.TransferDifferenceHandler;
import cl.minimarket.pos.inventory.TransferDifferenceHandler.TransferDifference;
import cl.minimarket.pos.inventory.application.transfer.TransferLookupRepository.LotLookupInfo;
import cl.minimarket.pos.inventory.application.transfer.TransferQueryService.TransferDetail;
import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Quantity;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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
 * Servicio de aplicación que orquesta el ciclo de vida de transferencias entre locales ({@code
 * DRAFT -> SENT -> RECEIVED} o {@code DRAFT -> CANCELLED}).
 *
 * <p>Garantiza las reglas de dominio de inventario:
 *
 * <ul>
 *   <li>Local origen distinto al local destino y ambos activos.
 *   <li>Control estricto por {@code X-Store-Id}: crear, editar, cancelar y despachar solo desde el
 *       local de origen; recepcionar solo desde el local de destino (403 {@code ACCESS_DENIED} si
 *       lo intenta origen; 404 {@code NOT_FOUND} para terceros locales).
 *   <li>Descuento en origen ({@code TRANSFER_OUT}) al despachar y abono en destino ({@code
 *       TRANSFER_IN}) al recepcionar mediante {@link StockApi}.
 *   <li>Delegación de diferencias negativas al puerto {@link TransferDifferenceHandler} para su
 *       registro como merma sistémica en el módulo {@code shrinkage}.
 *   <li>Auditoría explícita en despacho, recepción y cancelación.
 * </ul>
 */
@Service
public class TransferService {

    private final StockTransferRepository repository;
    private final TransferLookupRepository lookup;
    private final TransferQueryService queryService;
    private final StockApi stockApi;
    private final ProductCatalogApi catalogApi;
    private final StoreApi storeApi;
    private final SequenceService sequenceService;
    private final TransferDifferenceHandler differenceHandler;
    private final AuditService auditService;
    private final BusinessClock clock;

    public TransferService(
            StockTransferRepository repository,
            TransferLookupRepository lookup,
            TransferQueryService queryService,
            StockApi stockApi,
            ProductCatalogApi catalogApi,
            StoreApi storeApi,
            SequenceService sequenceService,
            TransferDifferenceHandler differenceHandler,
            AuditService auditService,
            BusinessClock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.stockApi = Objects.requireNonNull(stockApi, "stockApi");
        this.catalogApi = Objects.requireNonNull(catalogApi, "catalogApi");
        this.storeApi = Objects.requireNonNull(storeApi, "storeApi");
        this.sequenceService = Objects.requireNonNull(sequenceService, "sequenceService");
        this.differenceHandler = Objects.requireNonNull(differenceHandler, "differenceHandler");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Línea solicitada al crear o actualizar un borrador de transferencia. */
    public record TransferLineInput(Long productId, BigDecimal qtySent, Long originLotId) {}

    /** Comando para crear un borrador de transferencia desde el local activo. */
    public record CreateTransferCommand(
            Long destinationStoreId, String notes, List<TransferLineInput> lines) {}

    /** Comando para actualizar un borrador existente desde el local de origen. */
    public record UpdateTransferCommand(
            Long destinationStoreId, String notes, List<TransferLineInput> lines, Long version) {}

    /** Línea reportada al recepcionar una transferencia en destino. */
    public record ReceiveLineInput(Long lineId, BigDecimal qtyReceived, String differenceReason) {}

    /** Comando para recepcionar una transferencia enviada en el local de destino. */
    public record ReceiveTransferCommand(List<ReceiveLineInput> lines) {}

    /**
     * Crea una transferencia nueva en estado {@link TransferStatus#DRAFT} con el local activo como
     * origen.
     *
     * @param cmd datos del destino, notas y líneas a transferir.
     * @return detalle completo de la transferencia creada.
     */
    @Transactional
    public TransferDetail create(CreateTransferCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Long originStoreId = StoreContext.require().storeId();
        requireUserId();

        validateDestinationStore(originStoreId, cmd.destinationStoreId());
        List<StockTransferLine> lines = buildValidatedLines(originStoreId, cmd.lines());

        String number = sequenceService.next(SequenceType.TRANSFER, originStoreId);
        StockTransfer transfer =
                StockTransfer.draft(
                        number,
                        originStoreId,
                        cmd.destinationStoreId(),
                        normalizeNotes(cmd.notes()));
        for (StockTransferLine line : lines) {
            transfer.addLine(line);
        }
        StockTransfer saved = repository.saveAndFlush(transfer);
        return queryService.loadDetailInternal(saved.getId());
    }

    /**
     * Actualiza un borrador de transferencia existente desde su local de origen.
     *
     * @param id identificador de la transferencia.
     * @param cmd nuevos datos del borrador.
     * @return detalle actualizado.
     */
    @Transactional
    public TransferDetail update(Long id, UpdateTransferCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Long activeStoreId = StoreContext.require().storeId();
        requireUserId();

        StockTransfer transfer = findTransferOrThrow(id);
        requireOriginAccess(transfer, activeStoreId, "modificar la transferencia");

        if (cmd.version() != null && !cmd.version().equals(transfer.getVersion())) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "La transferencia fue modificada por otro usuario");
        }
        if (transfer.getStatus() != TransferStatus.DRAFT) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden editar transferencias en estado DRAFT");
        }

        validateDestinationStore(transfer.getOriginStoreId(), cmd.destinationStoreId());
        List<StockTransferLine> newLines =
                buildValidatedLines(transfer.getOriginStoreId(), cmd.lines());

        transfer.replaceDraftContent(
                cmd.destinationStoreId(), normalizeNotes(cmd.notes()), newLines);
        repository.saveAndFlush(transfer);
        return queryService.loadDetailInternal(transfer.getId());
    }

    /**
     * Despacha una transferencia en estado {@link TransferStatus#DRAFT} desde el local de origen,
     * descontando el stock mediante {@link StockApi} ({@code TRANSFER_OUT}) y dejándola en tránsito.
     *
     * @param id identificador de la transferencia.
     * @return detalle actualizado en estado {@link TransferStatus#SENT}.
     */
    @Transactional
    public TransferDetail send(Long id) {
        Long activeStoreId = StoreContext.require().storeId();
        Long userId = requireUserId();

        StockTransfer transfer = findTransferOrThrow(id);
        requireOriginAccess(transfer, activeStoreId, "despachar la transferencia");

        if (transfer.getStatus() != TransferStatus.DRAFT) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden despachar transferencias en estado DRAFT");
        }
        validateDestinationStore(transfer.getOriginStoreId(), transfer.getDestinationStoreId());

        Map<Long, ProductSnapshot> products = loadProductsForLines(transfer.getLines());
        List<StockLine> stockLines = new ArrayList<>(transfer.getLines().size());
        for (StockTransferLine line : transfer.getLines()) {
            ProductSnapshot product = products.get(line.getProductId());
            if (!product.active()) {
                throw new BusinessException(
                        ErrorCode.PRODUCT_INACTIVE,
                        "El producto " + product.sku() + " está inactivo");
            }
            BigDecimal cost =
                    product.avgCost() != null ? product.avgCost() : line.getUnitCost();
            stockLines.add(
                    new StockLine(
                            line.getProductId(),
                            line.getQtySent(),
                            cost,
                            line.getId(),
                            line.getOriginLotId(),
                            null,
                            null));
        }

        List<StockMovementResult> movementResults =
                stockApi.apply(
                        new StockCommand(
                                transfer.getOriginStoreId(),
                                StockMovementType.TRANSFER_OUT,
                                StockSourceType.STOCK_TRANSFER,
                                transfer.getId(),
                                userId,
                                "Transferencia " + transfer.getNumber(),
                                stockLines));

        Map<Long, StockMovementResult> resultsByProduct = new HashMap<>();
        Set<Long> allocatedLotIds = new HashSet<>();
        for (StockMovementResult result : movementResults) {
            resultsByProduct.put(result.productId(), result);
            for (LotAllocation allocation : result.allocations()) {
                allocatedLotIds.add(allocation.lotId());
            }
        }
        Map<Long, LotLookupInfo> allocatedLots =
                allocatedLotIds.isEmpty()
                        ? Map.of()
                        : lookup.findLotsInStore(transfer.getOriginStoreId(), allocatedLotIds);

        applyDispatchSnapshots(transfer, resultsByProduct, allocatedLots);

        Instant sentAt = clock.now();
        transfer.markSent(userId, sentAt);
        repository.saveAndFlush(transfer);

        auditService.record(
                new AuditEvent(
                        "TRANSFER_SENT",
                        "stock_transfer",
                        String.valueOf(transfer.getId()),
                        Map.of("status", TransferStatus.DRAFT.name()),
                        auditSnapshot(transfer),
                        null,
                        null));

        return queryService.loadDetailInternal(transfer.getId());
    }

    /**
     * Recepciona en el local de destino una transferencia en estado {@link TransferStatus#SENT}.
     *
     * <p>Ingresa el stock efectivamente recibido en destino mediante {@link StockApi} ({@code
     * TRANSFER_IN}) e invoca {@link TransferDifferenceHandler#handle} por cada línea con {@code
     * qtyReceived < qtySent}.
     *
     * @param id identificador de la transferencia.
     * @param cmd cantidades recibidas por línea y motivos de diferencia.
     * @return detalle actualizado en estado {@link TransferStatus#RECEIVED}.
     */
    @Transactional
    public TransferDetail receive(Long id, ReceiveTransferCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Long activeStoreId = StoreContext.require().storeId();
        Long userId = requireUserId();

        StockTransfer transfer = findTransferOrThrow(id);
        requireDestinationAccess(transfer, activeStoreId);

        if (transfer.getStatus() != TransferStatus.SENT) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden recepcionar transferencias en estado SENT");
        }

        Map<Long, ReceiveLineInput> inputsByLineId =
                validateReceiveInputs(transfer.getLines(), cmd.lines());

        List<StockLine> inboundLines = new ArrayList<>();
        for (StockTransferLine line : transfer.getLines()) {
            ReceiveLineInput input = inputsByLineId.get(line.getId());
            BigDecimal qtyReceived = Rounding.quantity(input.qtyReceived());
            line.recordReceipt(qtyReceived, input.differenceReason());

            if (qtyReceived.signum() > 0) {
                inboundLines.add(
                        new StockLine(
                                line.getProductId(),
                                qtyReceived,
                                line.getUnitCost(),
                                line.getId(),
                                null,
                                line.getLotNumber(),
                                line.getExpiryDate()));
            }
        }

        if (!inboundLines.isEmpty()) {
            stockApi.apply(
                    new StockCommand(
                            transfer.getDestinationStoreId(),
                            StockMovementType.TRANSFER_IN,
                            StockSourceType.STOCK_TRANSFER,
                            transfer.getId(),
                            userId,
                            "Recepción transferencia " + transfer.getNumber(),
                            inboundLines));
        }

        Instant receivedAt = clock.now();
        transfer.markReceived(userId, receivedAt);
        repository.saveAndFlush(transfer);

        for (StockTransferLine line : transfer.getLines()) {
            BigDecimal missing = line.missingQuantity();
            if (missing.signum() > 0) {
                differenceHandler.handle(
                        new TransferDifference(
                                transfer.getId(),
                                transfer.getNumber(),
                                line.getId(),
                                transfer.getOriginStoreId(),
                                transfer.getDestinationStoreId(),
                                line.getProductId(),
                                line.getOriginLotId(),
                                missing,
                                line.getUnitCost(),
                                line.getDifferenceReason(),
                                userId,
                                receivedAt));
            }
        }

        auditService.record(
                new AuditEvent(
                        "TRANSFER_RECEIVED",
                        "stock_transfer",
                        String.valueOf(transfer.getId()),
                        Map.of("status", TransferStatus.SENT.name()),
                        auditSnapshot(transfer),
                        null,
                        null));

        return queryService.loadDetailInternal(transfer.getId());
    }

    /**
     * Cancela una transferencia en estado {@link TransferStatus#DRAFT} desde el local de origen.
     *
     * @param id identificador de la transferencia.
     * @param reason motivo obligatorio de cancelación (entre 3 y 250 caracteres).
     */
    @Transactional
    public void cancel(Long id, String reason) {
        Long activeStoreId = StoreContext.require().storeId();
        requireUserId();

        if (reason == null || reason.trim().length() < 3 || reason.trim().length() > 250) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El motivo de cancelación es obligatorio (3 a 250 caracteres)",
                    Map.of(),
                    List.of(
                            new FieldErrorDetail(
                                    "reason",
                                    "INVALID_LENGTH",
                                    "Debe tener entre 3 y 250 caracteres")));
        }

        StockTransfer transfer = findTransferOrThrow(id);
        requireOriginAccess(transfer, activeStoreId, "cancelar la transferencia");

        if (transfer.getStatus() != TransferStatus.DRAFT) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden cancelar transferencias en estado DRAFT");
        }

        transfer.cancel(reason);
        repository.saveAndFlush(transfer);

        auditService.record(
                new AuditEvent(
                        "TRANSFER_CANCELLED",
                        "stock_transfer",
                        String.valueOf(transfer.getId()),
                        Map.of("status", TransferStatus.DRAFT.name()),
                        auditSnapshot(transfer),
                        reason.trim(),
                        null));
    }

    private StockTransfer findTransferOrThrow(Long id) {
        return repository
                .findWithLinesById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.NOT_FOUND, "Transferencia no encontrada"));
    }

    private void requireOriginAccess(StockTransfer transfer, Long activeStoreId, String action) {
        if (!activeStoreId.equals(transfer.getOriginStoreId())
                && !activeStoreId.equals(transfer.getDestinationStoreId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Transferencia no encontrada");
        }
        if (!activeStoreId.equals(transfer.getOriginStoreId())) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "Solo el local de origen puede " + action);
        }
    }

    private void requireDestinationAccess(StockTransfer transfer, Long activeStoreId) {
        if (!activeStoreId.equals(transfer.getOriginStoreId())
                && !activeStoreId.equals(transfer.getDestinationStoreId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Transferencia no encontrada");
        }
        if (!activeStoreId.equals(transfer.getDestinationStoreId())) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "Solo el local de destino puede recepcionar la transferencia");
        }
    }

    private void validateDestinationStore(Long originStoreId, Long destinationStoreId) {
        if (destinationStoreId == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El local de destino es obligatorio",
                    Map.of(),
                    List.of(
                            new FieldErrorDetail(
                                    "destinationStoreId",
                                    "REQUIRED",
                                    "El local de destino es obligatorio")));
        }
        if (originStoreId.equals(destinationStoreId)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El local de destino debe ser distinto al local de origen",
                    Map.of(),
                    List.of(
                            new FieldErrorDetail(
                                    "destinationStoreId",
                                    "SAME_AS_ORIGIN",
                                    "No puede coincidir con el local de origen")));
        }
        StoreInfo destination =
                storeApi.find(destinationStoreId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND,
                                                "Local de destino no encontrado"));
        if (!destination.active()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El local de destino está inactivo",
                    Map.of(),
                    List.of(
                            new FieldErrorDetail(
                                    "destinationStoreId",
                                    "STORE_INACTIVE",
                                    "El local de destino está inactivo")));
        }
    }

    private List<StockTransferLine> buildValidatedLines(
            Long originStoreId, List<TransferLineInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 300) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "La transferencia debe incluir entre 1 y 300 líneas");
        }

        List<FieldErrorDetail> errors = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        Set<Long> productIds = new HashSet<>();
        Set<Long> lotIds = new HashSet<>();

        for (int i = 0; i < inputs.size(); i++) {
            TransferLineInput input = inputs.get(i);
            if (input.productId() == null) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].productId",
                                "REQUIRED",
                                "El producto es obligatorio"));
                continue;
            }
            if (input.qtySent() == null || input.qtySent().signum() <= 0) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].qtySent",
                                "POSITIVE_REQUIRED",
                                "La cantidad enviada debe ser mayor a cero"));
            }
            String key =
                    input.productId()
                            + ":"
                            + (input.originLotId() != null ? input.originLotId() : "NONE");
            if (!seenKeys.add(key)) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].productId",
                                "DUPLICATE_LINE",
                                "Producto/lote duplicado en la transferencia"));
            }
            productIds.add(input.productId());
            if (input.originLotId() != null) {
                lotIds.add(input.originLotId());
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Líneas de transferencia inválidas",
                    Map.of(),
                    errors);
        }

        Map<Long, ProductSnapshot> productsById = new HashMap<>();
        for (ProductSnapshot product : catalogApi.findByIds(productIds)) {
            productsById.put(product.id(), product);
        }
        for (Long productId : productIds) {
            if (!productsById.containsKey(productId)) {
                throw new BusinessException(
                        ErrorCode.NOT_FOUND, "Producto no encontrado: " + productId);
            }
        }

        Map<Long, LotLookupInfo> lotsById = lookup.findLotsInStore(originStoreId, lotIds);

        List<StockTransferLine> result = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            TransferLineInput input = inputs.get(i);
            ProductSnapshot product = productsById.get(input.productId());
            if (!product.active()) {
                throw new BusinessException(
                        ErrorCode.PRODUCT_INACTIVE,
                        "El producto " + product.sku() + " está inactivo");
            }
            if (product.saleType() == SaleType.PACK) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].productId",
                                "PACK_NOT_ALLOWED",
                                "No se pueden transferir productos de tipo PACK"));
            }
            BigDecimal normalizedQty = Rounding.quantity(input.qtySent());
            if (normalizedQty.signum() <= 0) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].qtySent",
                                "POSITIVE_REQUIRED",
                                "La cantidad enviada debe ser mayor a cero"));
            } else if (product.unitOfMeasure() == UnitOfMeasure.UNIT
                    && !Quantity.of(normalizedQty).isWholeNumber()) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].qtySent",
                                "INTEGER_REQUIRED",
                                "Los productos por unidad requieren cantidades enteras"));
            }

            LotLookupInfo lot = null;
            if (input.originLotId() != null) {
                lot = lotsById.get(input.originLotId());
                if (lot == null || !lot.productId().equals(product.id())) {
                    errors.add(
                            new FieldErrorDetail(
                                    "lines[" + i + "].originLotId",
                                    "INVALID_LOT",
                                    "El lote especificado no pertenece al producto o al local de origen"));
                }
            }

            BigDecimal unitCost = product.avgCost() != null ? product.avgCost() : BigDecimal.ZERO;
            result.add(
                    StockTransferLine.create(
                            product.id(),
                            lot != null ? lot.id() : null,
                            lot != null ? lot.lotNumber() : null,
                            lot != null ? lot.expiryDate() : null,
                            normalizedQty,
                            unitCost));
        }

        if (!errors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Líneas de transferencia inválidas",
                    Map.of(),
                    errors);
        }
        return result;
    }

    private Map<Long, ProductSnapshot> loadProductsForLines(List<StockTransferLine> lines) {
        Set<Long> productIds = new HashSet<>();
        for (StockTransferLine line : lines) {
            productIds.add(line.getProductId());
        }
        Map<Long, ProductSnapshot> products = new HashMap<>();
        for (ProductSnapshot snapshot : catalogApi.findByIds(productIds)) {
            products.put(snapshot.id(), snapshot);
        }
        for (Long productId : productIds) {
            if (!products.containsKey(productId)) {
                throw new BusinessException(
                        ErrorCode.NOT_FOUND, "Producto no encontrado: " + productId);
            }
        }
        return products;
    }

    private Map<Long, ReceiveLineInput> validateReceiveInputs(
            List<StockTransferLine> transferLines, List<ReceiveLineInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Debe informar las cantidades recibidas de todas las líneas");
        }

        Map<Long, StockTransferLine> linesById = new LinkedHashMap<>();
        for (StockTransferLine line : transferLines) {
            linesById.put(line.getId(), line);
        }
        Map<Long, ProductSnapshot> productsById = loadProductsForLines(transferLines);

        List<FieldErrorDetail> errors = new ArrayList<>();
        Map<Long, ReceiveLineInput> inputsByLineId = new LinkedHashMap<>();

        for (int i = 0; i < inputs.size(); i++) {
            ReceiveLineInput input = inputs.get(i);
            if (input.lineId() == null) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].lineId",
                                "REQUIRED",
                                "El identificador de línea es obligatorio"));
                continue;
            }
            StockTransferLine line = linesById.get(input.lineId());
            if (line == null) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].lineId",
                                "LINE_NOT_FOUND",
                                "La línea no pertenece a esta transferencia"));
                continue;
            }
            if (inputsByLineId.put(input.lineId(), input) != null) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].lineId",
                                "DUPLICATE_LINE",
                                "Línea duplicada en la recepción"));
                continue;
            }

            if (input.qtyReceived() == null || input.qtyReceived().signum() < 0) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].qtyReceived",
                                "NON_NEGATIVE_REQUIRED",
                                "La cantidad recibida no puede ser negativa"));
                continue;
            }

            BigDecimal normalizedReceived = Rounding.quantity(input.qtyReceived());
            if (normalizedReceived.compareTo(line.getQtySent()) > 0) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].qtyReceived",
                                "EXCEEDS_SENT",
                                "La cantidad recibida no puede superar lo enviado ("
                                        + line.getQtySent()
                                        + ")"));
            }

            ProductSnapshot product = productsById.get(line.getProductId());
            if (normalizedReceived.signum() > 0
                    && product.unitOfMeasure() == UnitOfMeasure.UNIT
                    && !Quantity.of(normalizedReceived).isWholeNumber()) {
                errors.add(
                        new FieldErrorDetail(
                                "lines[" + i + "].qtyReceived",
                                "INTEGER_REQUIRED",
                                "Los productos por unidad requieren cantidades enteras"));
            }

            if (normalizedReceived.compareTo(line.getQtySent()) < 0) {
                if (input.differenceReason() == null || input.differenceReason().isBlank()) {
                    errors.add(
                            new FieldErrorDetail(
                                    "lines[" + i + "].differenceReason",
                                    "DIFFERENCE_REASON_REQUIRED",
                                    "Debe indicar el motivo de la diferencia cuando lo recibido es menor a lo enviado"));
                } else if (input.differenceReason().trim().length() > 250) {
                    errors.add(
                            new FieldErrorDetail(
                                    "lines[" + i + "].differenceReason",
                                    "MAX_LENGTH_EXCEEDED",
                                    "El motivo de diferencia no puede superar los 250 caracteres"));
                }
            }
        }

        if (inputsByLineId.size() != linesById.size()) {
            for (Long expectedLineId : linesById.keySet()) {
                if (!inputsByLineId.containsKey(expectedLineId)) {
                    errors.add(
                            new FieldErrorDetail(
                                    "lines",
                                    "MISSING_LINE",
                                    "Falta informar la línea "
                                            + expectedLineId
                                            + " en la recepción"));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Datos de recepción inválidos",
                    Map.of(),
                    errors);
        }
        return inputsByLineId;
    }

    private void applyDispatchSnapshots(
            StockTransfer transfer,
            Map<Long, StockMovementResult> resultsByProduct,
            Map<Long, LotLookupInfo> allocatedLots) {
        Map<Long, List<StockTransferLine>> linesByProduct = new LinkedHashMap<>();
        for (StockTransferLine line : List.copyOf(transfer.getLines())) {
            linesByProduct
                    .computeIfAbsent(line.getProductId(), ignored -> new ArrayList<>())
                    .add(line);
        }

        for (Map.Entry<Long, List<StockTransferLine>> entry : linesByProduct.entrySet()) {
            StockMovementResult result = resultsByProduct.get(entry.getKey());
            if (result == null) {
                continue;
            }
            Map<Long, BigDecimal> remainingByLot = new LinkedHashMap<>();
            for (LotAllocation allocation : result.allocations()) {
                remainingByLot.merge(
                        allocation.lotId(),
                        Rounding.quantity(allocation.quantity()),
                        (a, b) -> Rounding.quantity(a.add(b)));
            }

            List<StockTransferLine> fefoLines = new ArrayList<>();
            for (StockTransferLine line : entry.getValue()) {
                if (line.getOriginLotId() != null) {
                    line.applyDispatchSnapshot(result.unitCost(), null, null, null, null);
                    deductFromRemaining(remainingByLot, line.getOriginLotId(), line.getQtySent());
                } else {
                    fefoLines.add(line);
                }
            }

            for (StockTransferLine line : fefoLines) {
                if (remainingByLot.isEmpty()) {
                    line.applyDispatchSnapshot(result.unitCost(), null, null, null, null);
                    continue;
                }
                boolean first = true;
                for (Map.Entry<Long, BigDecimal> allocEntry : remainingByLot.entrySet()) {
                    Long lotId = allocEntry.getKey();
                    BigDecimal allocQty = allocEntry.getValue();
                    LotLookupInfo lotInfo = allocatedLots.get(lotId);
                    String lotNumber = lotInfo != null ? lotInfo.lotNumber() : null;
                    LocalDate expiryDate = lotInfo != null ? lotInfo.expiryDate() : null;
                    if (first) {
                        line.applyDispatchSnapshot(
                                result.unitCost(), lotId, lotNumber, expiryDate, allocQty);
                        first = false;
                    } else {
                        transfer.addLine(
                                StockTransferLine.create(
                                        line.getProductId(),
                                        lotId,
                                        lotNumber,
                                        expiryDate,
                                        allocQty,
                                        result.unitCost()));
                    }
                }
                remainingByLot.clear();
            }
        }
    }

    private void deductFromRemaining(
            Map<Long, BigDecimal> remainingByLot, Long lotId, BigDecimal qtySent) {
        BigDecimal current = remainingByLot.get(lotId);
        if (current == null) {
            return;
        }
        BigDecimal updated = Rounding.quantity(current.subtract(qtySent));
        if (updated.signum() > 0) {
            remainingByLot.put(lotId, updated);
        } else {
            remainingByLot.remove(lotId);
        }
    }

    private Map<String, Object> auditSnapshot(StockTransfer transfer) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("number", transfer.getNumber());
        snap.put("status", transfer.getStatus().name());
        snap.put("originStoreId", transfer.getOriginStoreId());
        snap.put("destinationStoreId", transfer.getDestinationStoreId());
        snap.put("lineCount", transfer.getLines().size());
        return snap;
    }

    private Long requireUserId() {
        return CurrentUserId.find()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.UNAUTHENTICATED,
                                        "Se requiere usuario autenticado"));
    }

    private String normalizeNotes(String notes) {
        if (notes == null) {
            return null;
        }
        String trimmed = notes.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
