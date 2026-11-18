package cl.minimarket.pos.shrinkage.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileMetadata;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.inventory.TransferDifferenceHandler;
import cl.minimarket.pos.security.AuthorizationRef;
import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.RoleLimit;
import cl.minimarket.pos.security.RoleLimitApi;
import cl.minimarket.pos.security.RoleLimitCode;
import cl.minimarket.pos.security.SupervisorOverrideApi;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Quantity;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.shrinkage.ShrinkageApi;
import cl.minimarket.pos.shrinkage.ShrinkageStatus;
import cl.minimarket.pos.shrinkage.ShrinkageType;
import cl.minimarket.pos.shrinkage.application.ShrinkageJdbcRepository.LockedShrinkage;
import cl.minimarket.pos.shrinkage.application.ShrinkageJdbcRepository.LotRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Servicio de aplicación del módulo de Mermas ({@code HU-MER-01}, {@code HU-MER-02},
 * {@code HU-MER-03}, {@code MODELO_DATOS.md} B.6, RN-19 y RN-21).
 *
 * <p>Gestiona:
 * <ul>
 *   <li>Mermas manuales con autoaprobación cuando {@code 0 < total_cost <= SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT},
 *       dejando {@code approved_by = NULL} y {@code auto_approved = true} (RN-21).</li>
 *   <li>Aprobación/rechazo por un usuario distinto de quien reportó ({@code SEGREGATION_OF_DUTIES}),
 *       con validación de {@code SHRINKAGE_APPROVE_MAX_AMOUNT} y override {@code SHRINKAGE_APPROVAL}.</li>
 *   <li>Mermas sistémicas ({@code affects_stock = false}) originadas por diferencias en recepción
 *       de transferencias ({@link TransferDifferenceHandler}) o devoluciones de venta sin reingreso
 *       ({@link ShrinkageApi#createFromSaleReturn}), que nacen siempre en {@code PENDING_APPROVAL}
 *       y nunca generan movimiento de stock al aprobarse.</li>
 * </ul>
 */
@Service
public class ShrinkageService implements ShrinkageApi, TransferDifferenceHandler {

    private static final int MAX_REASON_LENGTH = 500;
    private static final int MIN_REJECTION_REASON_LENGTH = 3;
    private static final int MAX_REJECTION_REASON_LENGTH = 250;
    private static final int MAX_MOVEMENT_REASON_LENGTH = 250;

    private static final String ENTITY_TYPE = "shrinkage";
    private static final String AUDIT_CREATED = "SHRINKAGE_CREATED";
    private static final String AUDIT_APPROVED = "SHRINKAGE_APPROVED";
    private static final String AUDIT_REJECTED = "SHRINKAGE_REJECTED";

    private final ShrinkageJdbcRepository repository;
    private final StockApi stockApi;
    private final ProductCatalogApi productCatalogApi;
    private final FileApi fileApi;
    private final SequenceService sequenceService;
    private final RoleLimitApi roleLimitApi;
    private final SupervisorOverrideApi supervisorOverrideApi;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    public ShrinkageService(
            ShrinkageJdbcRepository repository,
            StockApi stockApi,
            ProductCatalogApi productCatalogApi,
            FileApi fileApi,
            SequenceService sequenceService,
            RoleLimitApi roleLimitApi,
            SupervisorOverrideApi supervisorOverrideApi,
            AuditService auditService,
            BusinessClock businessClock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.stockApi = Objects.requireNonNull(stockApi, "stockApi");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.fileApi = Objects.requireNonNull(fileApi, "fileApi");
        this.sequenceService = Objects.requireNonNull(sequenceService, "sequenceService");
        this.roleLimitApi = Objects.requireNonNull(roleLimitApi, "roleLimitApi");
        this.supervisorOverrideApi = Objects.requireNonNull(supervisorOverrideApi, "supervisorOverrideApi");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.businessClock = Objects.requireNonNull(businessClock, "businessClock");
    }

    /**
     * Lista paginada de mermas del local activo con filtros opcionales.
     *
     * @param storeId identificador del local activo
     * @param filter filtros opcionales
     * @param pageable paginación y ordenamiento
     * @param includeCost {@code true} si el usuario puede ver costos de merma
     * @return página de mermas
     */
    @Transactional(readOnly = true)
    public Page<ShrinkageView> list(
            Long storeId, ShrinkageFilter filter, Pageable pageable, boolean includeCost) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(pageable, "pageable");

        if (filter != null && filter.from() != null && filter.to() != null
                && filter.from().isAfter(filter.to())) {
            throw validationError(
                    "La fecha 'from' no puede ser posterior a 'to'.",
                    "from",
                    "INVALID_DATE_RANGE",
                    "Debe ser menor o igual a 'to'.");
        }

        Instant fromInstant = filter != null && filter.from() != null
                ? businessClock.startOfDay(filter.from())
                : null;
        Instant toExclusiveInstant = filter != null && filter.to() != null
                ? businessClock.startOfDay(filter.to().plusDays(1))
                : null;

        return repository.findPage(storeId, filter, fromInstant, toExclusiveInstant, pageable, includeCost);
    }

    /**
     * Obtiene el detalle de una merma del local activo.
     *
     * @param storeId identificador del local activo
     * @param shrinkageId identificador de la merma
     * @param includeCost {@code true} si el usuario puede ver costos de merma
     * @return detalle de la merma
     */
    @Transactional(readOnly = true)
    public ShrinkageView getById(Long storeId, Long shrinkageId, boolean includeCost) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(shrinkageId, "shrinkageId");
        return repository.findById(storeId, shrinkageId, includeCost)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Merma no encontrada."));
    }

    /**
     * Registra una merma manual en el local activo.
     *
     * <p>Si {@code 0 < total_cost <= SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT} del reportante, se
     * autoaprueba inmediatamente ({@code autoApproved = true}, {@code approvedBy = null}) y
     * descuenta stock en el acto; de lo contrario queda en {@link ShrinkageStatus#PENDING_APPROVAL}.
     *
     * @param storeId identificador del local activo
     * @param reportedBy usuario que reporta la merma
     * @param command datos de la merma
     * @param includeCost {@code true} si la respuesta debe incluir costos
     * @return merma creada
     */
    @Transactional
    public ShrinkageView create(
            Long storeId,
            Long reportedBy,
            CreateShrinkageCommand command,
            boolean includeCost) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(reportedBy, "reportedBy");
        Objects.requireNonNull(command, "command");

        Instant now = businessClock.now();
        validateManualRequest(command, now);

        ProductSnapshot product = repository.findProductById(command.productId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Producto no encontrado: " + command.productId()));

        if (product.saleType() == SaleType.PACK) {
            throw validationError(
                    "Los productos PACK no admiten merma directa; registre la merma sobre el producto base.",
                    "productId",
                    "PACK_NOT_ALLOWED",
                    "No se puede registrar merma sobre un producto PACK.");
        }
        if (!repository.hasStockRow(storeId, product.id())) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "El producto " + product.sku() + " no tiene registro de stock en el local activo.");
        }

        BigDecimal qty = Rounding.quantity(command.quantity());
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw validationError(
                    "La cantidad de la merma debe ser mayor que cero.",
                    "quantity",
                    "POSITIVE_REQUIRED",
                    "Debe ser mayor que 0.");
        }
        if (product.unitOfMeasure() == UnitOfMeasure.UNIT && !Quantity.of(qty).isWholeNumber()) {
            throw validationError(
                    "Los productos vendidos por unidad solo admiten mermas en cantidades enteras.",
                    "quantity",
                    "INTEGER_QUANTITY_REQUIRED",
                    "Debe ser un número entero para productos UNIT.");
        }

        Long resolvedLotId = resolveAndValidateLot(storeId, product, command.lotId());
        String reason = trimToNull(command.reason());
        validateEvidenceFile(command.evidenceFileId());

        if (!repository.userExists(command.responsibleUserId())) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "Usuario responsable no encontrado: " + command.responsibleUserId());
        }

        BigDecimal unitCost = Rounding.unitCost(product.avgCost());
        BigDecimal totalCost = Rounding.costTotal(unitCost.multiply(qty));

        RoleLimit autoLimit = roleLimitApi.limitFor(reportedBy, RoleLimitCode.SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT);
        boolean autoApprove = totalCost.compareTo(BigDecimal.ZERO) > 0 && autoLimit.allows(totalCost);

        String number = sequenceService.next(SequenceType.SHRINKAGE, storeId);
        ShrinkageStatus status = autoApprove ? ShrinkageStatus.APPROVED : ShrinkageStatus.PENDING_APPROVAL;
        Instant approvedAt = autoApprove ? now : null;

        Long shrinkageId = repository.insertShrinkage(
                number,
                storeId,
                product.id(),
                resolvedLotId,
                command.shrinkageType(),
                qty,
                unitCost,
                totalCost,
                reason,
                command.evidenceFileId(),
                command.responsibleUserId(),
                reportedBy,
                status,
                null,
                approvedAt,
                null,
                null,
                autoApprove,
                true,
                null,
                command.occurredAt(),
                now);

        Long stockMovementId = null;
        if (autoApprove) {
            stockMovementId = applyStockMovement(
                    storeId,
                    shrinkageId,
                    number,
                    product.id(),
                    resolvedLotId,
                    qty,
                    unitCost,
                    reportedBy,
                    reason);
            repository.updateStockMovement(shrinkageId, stockMovementId, reportedBy, now);
        }

        Map<String, Object> auditNewValue = new LinkedHashMap<>();
        auditNewValue.put("id", shrinkageId);
        auditNewValue.put("number", number);
        auditNewValue.put("shrinkageType", command.shrinkageType().name());
        auditNewValue.put("productId", product.id());
        auditNewValue.put("quantity", qty);
        auditNewValue.put("totalCost", totalCost);
        auditNewValue.put("status", status.name());
        auditNewValue.put("autoApproved", autoApprove);
        auditNewValue.put("affectsStock", true);
        if (command.evidenceFileId() != null) {
            auditNewValue.put("evidenceFileId", command.evidenceFileId());
        }
        if (stockMovementId != null) {
            auditNewValue.put("stockMovementId", stockMovementId);
        }

        auditService.record(new AuditEvent(
                AUDIT_CREATED,
                ENTITY_TYPE,
                String.valueOf(shrinkageId),
                null,
                auditNewValue,
                reason,
                null));

        return getById(storeId, shrinkageId, includeCost);
    }

    /**
     * Aprueba una merma pendiente dentro del local activo.
     *
     * <p>Valida segregación de funciones ({@code approverId != reportedBy}), límite
     * {@link RoleLimitCode#SHRINKAGE_APPROVE_MAX_AMOUNT} (o token {@link OverrideAction#SHRINKAGE_APPROVAL})
     * y solo descuenta stock cuando {@code affects_stock = true}.
     *
     * @param storeId identificador del local activo (debe coincidir con el {@code store_id} de la merma)
     * @param shrinkageId identificador de la merma
     * @param approverId identificador del usuario aprobador
     * @param overrideToken token opcional de supervisor
     * @param includeCost {@code true} si la respuesta debe incluir costos
     * @return merma aprobada
     */
    @Transactional
    public ShrinkageView approve(
            Long storeId,
            Long shrinkageId,
            Long approverId,
            String overrideToken,
            boolean includeCost) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(shrinkageId, "shrinkageId");
        Objects.requireNonNull(approverId, "approverId");

        LockedShrinkage header = lockPending(storeId, shrinkageId);

        if (approverId.equals(header.reportedBy())) {
            throw new BusinessException(
                    ErrorCode.SEGREGATION_OF_DUTIES,
                    "Quien reporta una merma no puede aprobarla.");
        }

        Long authorizationId = checkApprovalLimitAndConsumeOverride(
                header.id(), approverId, header.totalCost(), overrideToken);

        Long stockMovementId = null;
        if (header.affectsStock()) {
            stockMovementId = applyStockMovement(
                    storeId,
                    header.id(),
                    header.number(),
                    header.productId(),
                    header.lotId(),
                    header.quantity(),
                    header.unitCost(),
                    approverId,
                    header.reason());
        }

        Instant now = businessClock.now();
        repository.markApproved(header.id(), approverId, now, stockMovementId);

        Map<String, Object> oldValue = Map.of("status", ShrinkageStatus.PENDING_APPROVAL.name());
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("number", header.number());
        newValue.put("status", ShrinkageStatus.APPROVED.name());
        newValue.put("shrinkageType", header.shrinkageType().name());
        newValue.put("quantity", header.quantity());
        newValue.put("totalCost", header.totalCost());
        newValue.put("approvedBy", approverId);
        newValue.put("affectsStock", header.affectsStock());
        if (stockMovementId != null) {
            newValue.put("stockMovementId", stockMovementId);
        }

        auditService.record(new AuditEvent(
                AUDIT_APPROVED,
                ENTITY_TYPE,
                String.valueOf(header.id()),
                oldValue,
                newValue,
                header.reason(),
                authorizationId));

        return getById(storeId, header.id(), includeCost);
    }

    /**
     * Rechaza una merma pendiente sin alterar el stock ni contar en reportes de merma.
     *
     * @param storeId identificador del local activo
     * @param shrinkageId identificador de la merma
     * @param approverId identificador del usuario que rechaza
     * @param reason motivo del rechazo (3 a 250 caracteres)
     * @param includeCost {@code true} si la respuesta debe incluir costos
     * @return merma rechazada
     */
    @Transactional
    public ShrinkageView reject(
            Long storeId,
            Long shrinkageId,
            Long approverId,
            String reason,
            boolean includeCost) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(shrinkageId, "shrinkageId");
        Objects.requireNonNull(approverId, "approverId");

        String trimmedReason = trimToNull(reason);
        if (trimmedReason == null
                || trimmedReason.length() < MIN_REJECTION_REASON_LENGTH
                || trimmedReason.length() > MAX_REJECTION_REASON_LENGTH) {
            throw validationError(
                    "El motivo de rechazo debe tener entre " + MIN_REJECTION_REASON_LENGTH
                            + " y " + MAX_REJECTION_REASON_LENGTH + " caracteres.",
                    "reason",
                    "INVALID_LENGTH",
                    "Debe tener entre 3 y 250 caracteres.");
        }

        LockedShrinkage header = lockPending(storeId, shrinkageId);

        if (approverId.equals(header.reportedBy())) {
            throw new BusinessException(
                    ErrorCode.SEGREGATION_OF_DUTIES,
                    "Quien reporta una merma no puede rechazarla.");
        }

        Instant now = businessClock.now();
        repository.markRejected(header.id(), approverId, now, trimmedReason);

        Map<String, Object> oldValue = Map.of("status", ShrinkageStatus.PENDING_APPROVAL.name());
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("number", header.number());
        newValue.put("status", ShrinkageStatus.REJECTED.name());
        newValue.put("shrinkageType", header.shrinkageType().name());
        newValue.put("approvedBy", approverId);
        newValue.put("rejectionReason", trimmedReason);

        auditService.record(new AuditEvent(
                AUDIT_REJECTED,
                ENTITY_TYPE,
                String.valueOf(header.id()),
                oldValue,
                newValue,
                trimmedReason,
                null));

        return getById(storeId, header.id(), includeCost);
    }

    /**
     * Crea una merma sistémica {@link ShrinkageType#TRANSFER_DIFFERENCE} en el local de origen de
     * la transferencia cuando la recepción registra faltantes ({@code qtyReceived < qtySent}).
     *
     * @param difference detalle de la diferencia detectada en recepción
     */
    @Override
    @Transactional
    public void handle(TransferDifference difference) {
        Objects.requireNonNull(difference, "difference");
        Objects.requireNonNull(difference.originStoreId(), "originStoreId");
        Objects.requireNonNull(difference.transferLineId(), "transferLineId");
        Objects.requireNonNull(difference.productId(), "productId");
        Objects.requireNonNull(difference.differenceQuantity(), "differenceQuantity");
        Objects.requireNonNull(difference.unitCost(), "unitCost");
        Objects.requireNonNull(difference.reportedByUserId(), "reportedByUserId");

        BigDecimal qty = Rounding.quantity(difference.differenceQuantity());
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("differenceQuantity debe ser positiva: " + qty);
        }

        BigDecimal unitCost = Rounding.unitCost(difference.unitCost());
        BigDecimal totalCost = Rounding.costTotal(unitCost.multiply(qty));
        Instant now = businessClock.now();
        Instant occurredAt = difference.occurredAt() != null ? difference.occurredAt() : now;
        String reason = truncate(trimToNull(difference.reason()), MAX_REASON_LENGTH);
        String number = sequenceService.next(SequenceType.SHRINKAGE, difference.originStoreId());

        Long shrinkageId = repository.insertShrinkage(
                number,
                difference.originStoreId(),
                difference.productId(),
                difference.originLotId(),
                ShrinkageType.TRANSFER_DIFFERENCE,
                qty,
                unitCost,
                totalCost,
                reason,
                null,
                difference.reportedByUserId(),
                difference.reportedByUserId(),
                ShrinkageStatus.PENDING_APPROVAL,
                null,
                null,
                difference.transferLineId(),
                null,
                false,
                false,
                null,
                occurredAt,
                now);

        Map<String, Object> auditNewValue = new LinkedHashMap<>();
        auditNewValue.put("id", shrinkageId);
        auditNewValue.put("number", number);
        auditNewValue.put("shrinkageType", ShrinkageType.TRANSFER_DIFFERENCE.name());
        auditNewValue.put("storeId", difference.originStoreId());
        auditNewValue.put("transferId", difference.transferId());
        auditNewValue.put("transferLineId", difference.transferLineId());
        auditNewValue.put("productId", difference.productId());
        auditNewValue.put("quantity", qty);
        auditNewValue.put("totalCost", totalCost);
        auditNewValue.put("status", ShrinkageStatus.PENDING_APPROVAL.name());
        auditNewValue.put("affectsStock", false);

        auditService.record(new AuditEvent(
                AUDIT_CREATED,
                ENTITY_TYPE,
                String.valueOf(shrinkageId),
                null,
                auditNewValue,
                reason,
                null));
    }

    /**
     * Crea una merma sistémica {@link ShrinkageType#DAMAGED} en estado
     * {@link ShrinkageStatus#PENDING_APPROVAL} desde una devolución de venta sin reingreso.
     *
     * @param command datos de la línea de devolución
     * @return identificador de la merma sistémica creada
     */
    @Override
    @Transactional
    public Long createFromSaleReturn(SaleReturnShrinkageCommand command) {
        Objects.requireNonNull(command, "command");

        BigDecimal qty = Rounding.quantity(command.quantity());
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw validationError(
                    "La cantidad de merma debe ser mayor que cero.",
                    "quantity",
                    "POSITIVE_REQUIRED",
                    "Debe ser mayor que 0.");
        }

        BigDecimal rawUnitCost = command.unitCost() != null
                ? command.unitCost()
                : productCatalogApi.getForSale(command.productId()).avgCost();
        BigDecimal unitCost = Rounding.unitCost(rawUnitCost);
        BigDecimal totalCost = Rounding.costTotal(unitCost.multiply(qty));
        Instant now = businessClock.now();
        Instant occurredAt = command.occurredAt() != null ? command.occurredAt() : now;
        String reason = truncate(trimToNull(command.reason()), MAX_REASON_LENGTH);
        String number = sequenceService.next(SequenceType.SHRINKAGE, command.storeId());

        Long shrinkageId = repository.insertShrinkage(
                number,
                command.storeId(),
                command.productId(),
                command.lotId(),
                ShrinkageType.DAMAGED,
                qty,
                unitCost,
                totalCost,
                reason,
                null,
                command.reportedByUserId(),
                command.reportedByUserId(),
                ShrinkageStatus.PENDING_APPROVAL,
                null,
                null,
                null,
                command.saleReturnLineId(),
                false,
                false,
                null,
                occurredAt,
                now);

        Map<String, Object> auditNewValue = new LinkedHashMap<>();
        auditNewValue.put("id", shrinkageId);
        auditNewValue.put("number", number);
        auditNewValue.put("shrinkageType", ShrinkageType.DAMAGED.name());
        auditNewValue.put("storeId", command.storeId());
        auditNewValue.put("saleReturnLineId", command.saleReturnLineId());
        auditNewValue.put("productId", command.productId());
        auditNewValue.put("quantity", qty);
        auditNewValue.put("totalCost", totalCost);
        auditNewValue.put("status", ShrinkageStatus.PENDING_APPROVAL.name());
        auditNewValue.put("affectsStock", false);

        auditService.record(new AuditEvent(
                AUDIT_CREATED,
                ENTITY_TYPE,
                String.valueOf(shrinkageId),
                null,
                auditNewValue,
                reason,
                null));

        return shrinkageId;
    }

    private void validateManualRequest(CreateShrinkageCommand command, Instant now) {
        if (command.productId() == null) {
            throw validationError("El producto es obligatorio.", "productId", "NOT_NULL", "Debe indicar productId.");
        }
        if (command.shrinkageType() == null) {
            throw validationError(
                    "El tipo de merma es obligatorio.", "shrinkageType", "NOT_NULL", "Debe indicar shrinkageType.");
        }
        if (command.shrinkageType() == ShrinkageType.TRANSFER_DIFFERENCE) {
            throw validationError(
                    "El tipo TRANSFER_DIFFERENCE es exclusivamente sistémico.",
                    "shrinkageType",
                    "SYSTEMIC_TYPE_NOT_ALLOWED",
                    "No se puede registrar manualmente TRANSFER_DIFFERENCE.");
        }
        if (command.quantity() == null) {
            throw validationError(
                    "La cantidad es obligatoria.", "quantity", "NOT_NULL", "Debe indicar quantity.");
        }
        if (command.responsibleUserId() == null) {
            throw validationError(
                    "El usuario responsable es obligatorio.",
                    "responsibleUserId",
                    "NOT_NULL",
                    "Debe indicar responsibleUserId.");
        }
        if (command.occurredAt() == null) {
            throw validationError(
                    "La fecha de ocurrencia es obligatoria.",
                    "occurredAt",
                    "NOT_NULL",
                    "Debe indicar occurredAt.");
        }
        if (command.occurredAt().isAfter(now)) {
            throw validationError(
                    "La fecha de ocurrencia no puede ser futura.",
                    "occurredAt",
                    "FUTURE_DATE_NOT_ALLOWED",
                    "Debe ser menor o igual al instante actual.");
        }
        String reason = trimToNull(command.reason());
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw validationError(
                    "El motivo no puede superar los " + MAX_REASON_LENGTH + " caracteres.",
                    "reason",
                    "MAX_LENGTH",
                    "Máximo " + MAX_REASON_LENGTH + " caracteres.");
        }
        if ((command.shrinkageType() == ShrinkageType.THEFT_LOSS
                || command.shrinkageType() == ShrinkageType.INTERNAL_CONSUMPTION)
                && reason == null) {
            throw validationError(
                    "El motivo es obligatorio para mermas de tipo " + command.shrinkageType() + ".",
                    "reason",
                    "REASON_REQUIRED",
                    "Debe indicar un motivo para este tipo de merma.");
        }
    }

    private Long resolveAndValidateLot(Long storeId, ProductSnapshot product, Long lotId) {
        if (!product.lotControlled()) {
            if (lotId != null) {
                throw validationError(
                        "El producto " + product.sku() + " no tiene control de lotes.",
                        "lotId",
                        "LOT_NOT_SUPPORTED",
                        "El producto no maneja lotes.");
            }
            return null;
        }
        if (lotId == null) {
            throw new BusinessException(
                    ErrorCode.LOT_REQUIRED,
                    "El producto " + product.sku() + " controla lotes y requiere informar lotId.");
        }
        LotRow lot = repository.findLot(storeId, lotId)
                .filter(l -> l.productId().equals(product.id()))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "Lote no encontrado en el local activo para el producto " + product.sku() + "."));
        return lot.id();
    }

    private void validateEvidenceFile(Long evidenceFileId) {
        if (evidenceFileId == null) {
            return;
        }
        FileMetadata meta = fileApi.get(evidenceFileId);
        if (meta.category() != FileCategory.SHRINKAGE_EVIDENCE) {
            throw validationError(
                    "El archivo adjunto debe tener categoría SHRINKAGE_EVIDENCE.",
                    "evidenceFileId",
                    "INVALID_FILE_CATEGORY",
                    "Categoría de archivo inválida para evidencia de merma.");
        }
    }

    private LockedShrinkage lockPending(Long storeId, Long shrinkageId) {
        LockedShrinkage header = repository.lockForUpdate(storeId, shrinkageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Merma no encontrada."));
        if (header.status() != ShrinkageStatus.PENDING_APPROVAL) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "La merma " + header.number() + " ya fue resuelta en estado " + header.status() + ".");
        }
        return header;
    }

    private Long checkApprovalLimitAndConsumeOverride(
            Long shrinkageId,
            Long approverId,
            BigDecimal totalCost,
            String overrideToken) {
        RoleLimit limit = roleLimitApi.limitFor(approverId, RoleLimitCode.SHRINKAGE_APPROVE_MAX_AMOUNT);
        if (limit.allows(totalCost)) {
            return null;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("entityId", shrinkageId);
        context.put("amount", totalCost);

        if (overrideToken == null || overrideToken.isBlank()) {
            throw new BusinessException(
                    ErrorCode.OVERRIDE_REQUIRED,
                    "El monto de la merma supera el límite de aprobación permitido para su rol y requiere autorización de supervisor.",
                    Map.of(
                            "action", OverrideAction.SHRINKAGE_APPROVAL.name(),
                            "context", context));
        }

        String contextHash = supervisorOverrideApi.canonicalContextHash(context);
        AuthorizationRef authRef = supervisorOverrideApi.consume(
                overrideToken.trim(),
                OverrideAction.SHRINKAGE_APPROVAL,
                contextHash,
                approverId,
                ENTITY_TYPE,
                String.valueOf(shrinkageId));
        return authRef.authorizationId();
    }

    private Long applyStockMovement(
            Long storeId,
            Long shrinkageId,
            String number,
            Long productId,
            Long lotId,
            BigDecimal quantity,
            BigDecimal unitCost,
            Long userId,
            String reason) {
        String movementReason = truncate(
                reason == null ? "Merma " + number : "Merma " + number + ": " + reason,
                MAX_MOVEMENT_REASON_LENGTH);
        StockLine line = new StockLine(productId, quantity, unitCost, null, lotId, null, null);
        List<StockMovementResult> results = stockApi.apply(new StockCommand(
                storeId,
                StockMovementType.SHRINKAGE,
                StockSourceType.SHRINKAGE,
                shrinkageId,
                userId,
                movementReason,
                List.of(line)));
        return results.getFirst().movementId();
    }

    private static BusinessException validationError(
            String detail, String field, String code, String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                detail,
                Map.of(),
                List.of(new FieldErrorDetail(field, code, message)));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
