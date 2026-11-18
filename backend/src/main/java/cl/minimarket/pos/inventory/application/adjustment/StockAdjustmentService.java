package cl.minimarket.pos.inventory.application.adjustment;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.LotAllocation;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.inventory.application.adjustment.CreateAdjustmentCommand.LineCommand;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentJdbcRepository.AdjustmentLineForExecution;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentJdbcRepository.LockedAdjustment;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentJdbcRepository.LotRow;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentJdbcRepository.ProductRow;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Servicio de aplicación para los ajustes manuales de stock con flujo de aprobación
 * ({@code HU-INV-05}, {@code API.md} F.2, {@code MODELO_DATOS.md} B.5 y B.15).
 *
 * <p>Reglas clave implementadas:
 * <ul>
 *   <li>Todo ajuste nace en {@link AdjustmentStatus#PENDING_APPROVAL} y <b>no</b> modifica
 *       {@code stock} ni {@code stock_movements} hasta que se aprueba explícitamente.</li>
 *   <li><b>Segregación de funciones (RN-22):</b> quien solicita un ajuste no puede aprobarlo ni
 *       rechazarlo ({@code 422 SEGREGATION_OF_DUTIES}).</li>
 *   <li><b>Límite de rol y override:</b> la aprobación valida el impacto monetario bruto contra
 *       {@link RoleLimitCode#ADJUSTMENT_APPROVE_MAX_AMOUNT}; si excede el tope, exige y consume un
 *       token para {@link OverrideAction#STOCK_ADJUSTMENT_APPROVAL}.</li>
 *   <li><b>Trazabilidad y kardex (RN-01..RN-03):</b> al aprobar se invoca {@link StockApi#apply}
 *       con {@link StockMovementType#ADJUSTMENT_IN} / {@link StockMovementType#ADJUSTMENT_OUT} y
 *       {@link StockSourceType#STOCK_ADJUSTMENT}, enlazando cada línea con su movimiento.</li>
 *   <li><b>Auditoría (D.5):</b> registra {@code STOCK_ADJUSTMENT_REQUESTED},
 *       {@code STOCK_ADJUSTMENT_APPROVED} y {@code STOCK_ADJUSTMENT_REJECTED}.</li>
 * </ul>
 */
@Service
public class StockAdjustmentService {

    private static final int MAX_LINES = 200;
    private static final int MAX_NOTES_LENGTH = 500;
    private static final int MAX_LOT_NUMBER_LENGTH = 40;
    private static final int MIN_REJECTION_REASON_LENGTH = 3;
    private static final int MAX_REJECTION_REASON_LENGTH = 250;
    private static final int MAX_MOVEMENT_REASON_LENGTH = 250;
    private static final String ENTITY_TYPE = "stock_adjustment";

    private static final String AUDIT_REQUESTED = "STOCK_ADJUSTMENT_REQUESTED";
    private static final String AUDIT_APPROVED = "STOCK_ADJUSTMENT_APPROVED";
    private static final String AUDIT_REJECTED = "STOCK_ADJUSTMENT_REJECTED";

    private final StockAdjustmentJdbcRepository repository;
    private final StockApi stockApi;
    private final SequenceService sequenceService;
    private final RoleLimitApi roleLimitApi;
    private final SupervisorOverrideApi supervisorOverrideApi;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    public StockAdjustmentService(
            StockAdjustmentJdbcRepository repository,
            StockApi stockApi,
            SequenceService sequenceService,
            RoleLimitApi roleLimitApi,
            SupervisorOverrideApi supervisorOverrideApi,
            AuditService auditService,
            BusinessClock businessClock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.stockApi = Objects.requireNonNull(stockApi, "stockApi");
        this.sequenceService = Objects.requireNonNull(sequenceService, "sequenceService");
        this.roleLimitApi = Objects.requireNonNull(roleLimitApi, "roleLimitApi");
        this.supervisorOverrideApi = Objects.requireNonNull(supervisorOverrideApi, "supervisorOverrideApi");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.businessClock = Objects.requireNonNull(businessClock, "businessClock");
    }

    /**
     * Lista los ajustes de stock del local activo aplicando filtros y paginación.
     *
     * @param storeId     identificador del local activo
     * @param filter      filtros opcionales por estado y rango de fechas de negocio
     * @param pageable    paginación y ordenamiento
     * @param includeCost {@code true} si el consultante puede ver costos del ajuste
     * @return página de ajustes de stock
     */
    @Transactional(readOnly = true)
    public Page<StockAdjustmentView> list(
            Long storeId,
            StockAdjustmentFilter filter,
            Pageable pageable,
            boolean includeCost) {
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
     * Obtiene el detalle de un ajuste de stock del local activo.
     *
     * @param storeId      identificador del local activo
     * @param adjustmentId identificador del ajuste
     * @param includeCost  {@code true} si el consultante puede ver costos del ajuste
     * @return proyección del ajuste con sus líneas
     */
    @Transactional(readOnly = true)
    public StockAdjustmentView getById(Long storeId, Long adjustmentId, boolean includeCost) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(adjustmentId, "adjustmentId");
        return repository.findById(storeId, adjustmentId, includeCost)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "Ajuste de stock no encontrado."));
    }

    /**
     * Crea una solicitud de ajuste manual en estado {@link AdjustmentStatus#PENDING_APPROVAL} sin
     * modificar las existencias ni generar movimientos de kardex.
     *
     * @param storeId     identificador del local activo
     * @param userId      usuario solicitante
     * @param command     datos del ajuste y sus líneas
     * @param includeCost {@code true} si la respuesta debe incluir los costos valorizados
     * @return ajuste recién creado en estado pendiente
     */
    @Transactional
    public StockAdjustmentView create(
            Long storeId,
            Long userId,
            CreateAdjustmentCommand command,
            boolean includeCost) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(command, "command");

        List<LineCommand> rawLines = command.lines();
        if (rawLines.isEmpty() || rawLines.size() > MAX_LINES) {
            throw validationError(
                    "El ajuste debe contener entre 1 y " + MAX_LINES + " líneas.",
                    "lines",
                    "INVALID_SIZE",
                    "Debe incluir entre 1 y " + MAX_LINES + " líneas.");
        }

        String notes = trimToNull(command.notes());
        if (notes != null && notes.length() > MAX_NOTES_LENGTH) {
            throw validationError(
                    "Las observaciones no pueden superar los " + MAX_NOTES_LENGTH + " caracteres.",
                    "notes",
                    "MAX_LENGTH",
                    "Máximo " + MAX_NOTES_LENGTH + " caracteres.");
        }

        Set<Long> productIds = new HashSet<>();
        Set<Long> lotIds = new HashSet<>();
        for (int i = 0; i < rawLines.size(); i++) {
            LineCommand line = rawLines.get(i);
            if (line == null || line.productId() == null) {
                throw validationError(
                        "El producto de la línea es obligatorio.",
                        "lines[" + i + "].productId",
                        "NOT_NULL",
                        "Debe indicar un productId.");
            }
            productIds.add(line.productId());
            if (line.lotId() != null) {
                lotIds.add(line.lotId());
            }
        }

        Map<Long, ProductRow> products = repository.findProductsByIds(productIds);
        Set<Long> stockProductIds = repository.findStockProductIds(storeId, productIds);
        Map<Long, LotRow> lots = repository.findLotsByIds(storeId, lotIds);

        Instant now = businessClock.now();
        List<PreparedLine> preparedLines = new ArrayList<>(rawLines.size());
        BigDecimal netImpactSum = BigDecimal.ZERO;

        for (int i = 0; i < rawLines.size(); i++) {
            LineCommand line = rawLines.get(i);
            PreparedLine prepared = validateAndPrepareLine(
                    i, storeId, userId, now, line, products, stockProductIds, lots);
            preparedLines.add(prepared);
            netImpactSum = netImpactSum.add(prepared.quantityDelta().multiply(prepared.unitCost()));
        }

        BigDecimal totalCostImpact = Rounding.costTotal(netImpactSum);
        String number = sequenceService.next(SequenceType.STOCK_ADJUSTMENT, storeId);

        Long adjustmentId = repository.insertAdjustment(
                number,
                storeId,
                command.reasonCode(),
                notes,
                AdjustmentStatus.PENDING_APPROVAL,
                totalCostImpact,
                userId,
                now);

        for (PreparedLine line : preparedLines) {
            repository.insertLine(
                    adjustmentId,
                    line.productId(),
                    line.lotId(),
                    line.quantityDelta(),
                    line.unitCost(),
                    now);
        }

        Map<String, Object> auditNewValue = new LinkedHashMap<>();
        auditNewValue.put("id", adjustmentId);
        auditNewValue.put("number", number);
        auditNewValue.put("status", AdjustmentStatus.PENDING_APPROVAL.name());
        auditNewValue.put("reasonCode", command.reasonCode().name());
        auditNewValue.put("totalCostImpact", totalCostImpact);
        auditNewValue.put("lineCount", preparedLines.size());
        auditNewValue.put("requestedBy", userId);

        auditService.record(new AuditEvent(
                AUDIT_REQUESTED,
                ENTITY_TYPE,
                String.valueOf(adjustmentId),
                null,
                auditNewValue,
                buildAuditReason(notes, command.reasonCode()),
                null));

        return getById(storeId, adjustmentId, includeCost);
    }

    /**
     * Aprueba un ajuste pendiente, validando segregación de funciones (RN-22), tope del rol
     * ({@link RoleLimitCode#ADJUSTMENT_APPROVE_MAX_AMOUNT}) o token de autorización de supervisor,
     * aplicando el movimiento real vía {@link StockApi#apply(StockCommand)} y registrando auditoría.
     *
     * @param storeId       identificador del local activo
     * @param adjustmentId  identificador del ajuste a aprobar
     * @param approverId    usuario aprobador
     * @param overrideToken token opcional {@code X-Override-Token} cuando supera el límite del rol
     * @param includeCost   {@code true} si la respuesta debe incluir los costos valorizados
     * @return ajuste aprobado con sus líneas enlazadas
     */
    @Transactional
    public StockAdjustmentView approve(
            Long storeId,
            Long adjustmentId,
            Long approverId,
            String overrideToken,
            boolean includeCost) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(adjustmentId, "adjustmentId");
        Objects.requireNonNull(approverId, "approverId");

        LockedAdjustment header = lockPendingAdjustment(storeId, adjustmentId);

        // RN-22: segregación de funciones (quien solicita no puede aprobar su propio ajuste).
        if (approverId.equals(header.requestedBy())) {
            throw new BusinessException(
                    ErrorCode.SEGREGATION_OF_DUTIES,
                    "Quien solicita un ajuste de stock no puede aprobarlo.");
        }

        List<AdjustmentLineForExecution> lines = repository.findLinesForExecution(adjustmentId);
        if (lines.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El ajuste no tiene líneas para aplicar.");
        }

        // Magnitud monetaria bruta: evita que entradas y salidas opuestas anulen el monto sujeto a límite.
        BigDecimal approvalAmount = computeApprovalAmount(lines);
        Long authorizationId = checkApprovalLimitAndConsumeOverride(
                header.id(), approverId, approvalAmount, overrideToken);

        applyStockMovements(storeId, approverId, header, lines);

        Instant now = businessClock.now();
        repository.markApproved(header.id(), approverId, now);

        Map<String, Object> oldValue = Map.of("status", AdjustmentStatus.PENDING_APPROVAL.name());
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("number", header.number());
        newValue.put("status", AdjustmentStatus.APPROVED.name());
        newValue.put("approvedBy", approverId);
        newValue.put("totalCostImpact", header.totalCostImpact());
        newValue.put("approvalAmount", approvalAmount);

        auditService.record(new AuditEvent(
                AUDIT_APPROVED,
                ENTITY_TYPE,
                String.valueOf(header.id()),
                oldValue,
                newValue,
                buildAuditReason(header.notes(), header.reasonCode()),
                authorizationId));

        return getById(storeId, header.id(), includeCost);
    }

    /**
     * Rechaza un ajuste pendiente sin generar movimientos de stock y registra el motivo en
     * auditoría.
     *
     * @param storeId      identificador del local activo
     * @param adjustmentId identificador del ajuste a rechazar
     * @param approverId   usuario que rechaza
     * @param reason       motivo del rechazo (3 a 250 caracteres)
     * @param includeCost  {@code true} si la respuesta debe incluir los costos valorizados
     * @return ajuste rechazado
     */
    @Transactional
    public StockAdjustmentView reject(
            Long storeId,
            Long adjustmentId,
            Long approverId,
            String reason,
            boolean includeCost) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(adjustmentId, "adjustmentId");
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

        LockedAdjustment header = lockPendingAdjustment(storeId, adjustmentId);

        if (approverId.equals(header.requestedBy())) {
            throw new BusinessException(
                    ErrorCode.SEGREGATION_OF_DUTIES,
                    "Quien solicita un ajuste de stock no puede rechazarlo.");
        }

        Instant now = businessClock.now();
        repository.markRejected(header.id(), approverId, now, trimmedReason);

        Map<String, Object> oldValue = Map.of("status", AdjustmentStatus.PENDING_APPROVAL.name());
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("number", header.number());
        newValue.put("status", AdjustmentStatus.REJECTED.name());
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

    private PreparedLine validateAndPrepareLine(
            int index,
            Long storeId,
            Long userId,
            Instant now,
            LineCommand line,
            Map<Long, ProductRow> products,
            Set<Long> stockProductIds,
            Map<Long, LotRow> lots) {
        ProductRow product = products.get(line.productId());
        if (product == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "Producto no encontrado: " + line.productId());
        }
        if (product.saleType() == SaleType.PACK) {
            throw validationError(
                    "Los productos PACK no admiten ajuste directo; ajuste el producto base.",
                    "lines[" + index + "].productId",
                    "PACK_NOT_ALLOWED",
                    "No se puede ajustar un producto PACK directamente.");
        }
        if (!stockProductIds.contains(product.id())) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "El producto " + product.sku() + " no tiene registro de stock en el local activo.");
        }

        if (line.quantityDelta() == null) {
            throw validationError(
                    "La variación de cantidad es obligatoria.",
                    "lines[" + index + "].quantityDelta",
                    "NOT_NULL",
                    "Debe indicar quantityDelta.");
        }

        BigDecimal delta = Rounding.quantity(line.quantityDelta());
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            throw validationError(
                    "La variación de cantidad debe ser distinta de cero.",
                    "lines[" + index + "].quantityDelta",
                    "NON_ZERO_REQUIRED",
                    "La cantidad ajustada no puede ser cero.");
        }

        if (product.unitOfMeasure() == UnitOfMeasure.UNIT && !Quantity.of(delta).isWholeNumber()) {
            throw validationError(
                    "Los productos vendidos por unidad solo admiten ajustes en cantidades enteras.",
                    "lines[" + index + "].quantityDelta",
                    "INTEGER_QUANTITY_REQUIRED",
                    "Debe ser un número entero para productos UNIT.");
        }

        Long resolvedLotId = resolveLotId(index, storeId, userId, now, line, product, delta, lots);
        return new PreparedLine(product.id(), resolvedLotId, delta, product.avgCost());
    }

    private Long resolveLotId(
            int index,
            Long storeId,
            Long userId,
            Instant now,
            LineCommand line,
            ProductRow product,
            BigDecimal delta,
            Map<Long, LotRow> lots) {
        String lotNumber = trimToNull(line.lotNumber());
        if (!product.lotControlled()) {
            if (line.lotId() != null || lotNumber != null || line.expiryDate() != null) {
                throw validationError(
                        "El producto " + product.sku() + " no tiene control de lotes.",
                        "lines[" + index + "].lotId",
                        "LOT_NOT_SUPPORTED",
                        "El producto no maneja lotes.");
            }
            return null;
        }

        if (delta.signum() < 0) {
            if (line.lotId() == null) {
                throw new BusinessException(
                        ErrorCode.LOT_REQUIRED,
                        "El producto " + product.sku()
                                + " controla lotes y requiere lotId para ajustes negativos.");
            }
            LotRow lot = requireLotForProduct(line.lotId(), product, lots);
            return lot.id();
        }

        if (line.lotId() != null) {
            LotRow lot = requireLotForProduct(line.lotId(), product, lots);
            return lot.id();
        }

        if (lotNumber == null) {
            throw new BusinessException(
                    ErrorCode.LOT_REQUIRED,
                    "El producto " + product.sku()
                            + " controla lotes y requiere lotId o lotNumber para ajustes positivos.");
        }
        if (lotNumber.length() > MAX_LOT_NUMBER_LENGTH) {
            throw validationError(
                    "El número de lote no puede superar los " + MAX_LOT_NUMBER_LENGTH + " caracteres.",
                    "lines[" + index + "].lotNumber",
                    "MAX_LENGTH",
                    "Máximo " + MAX_LOT_NUMBER_LENGTH + " caracteres.");
        }
        if (product.shelfLifeDays() != null && line.expiryDate() == null) {
            throw new BusinessException(
                    ErrorCode.LOT_REQUIRED,
                    "El producto " + product.sku()
                            + " requiere fecha de vencimiento (expiryDate) para el lote.");
        }

        return repository.findOrCreatePlaceholderLot(
                storeId,
                product.id(),
                lotNumber,
                line.expiryDate(),
                product.avgCost(),
                userId,
                now);
    }

    private static LotRow requireLotForProduct(Long lotId, ProductRow product, Map<Long, LotRow> lots) {
        LotRow lot = lots.get(lotId);
        if (lot == null || !lot.productId().equals(product.id())) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "Lote no encontrado en el local activo para el producto " + product.sku() + ".");
        }
        return lot;
    }

    private LockedAdjustment lockPendingAdjustment(Long storeId, Long adjustmentId) {
        LockedAdjustment header = repository.lockHeaderForUpdate(storeId, adjustmentId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "Ajuste de stock no encontrado."));
        if (header.status() != AdjustmentStatus.PENDING_APPROVAL) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "El ajuste " + header.number() + " ya fue resuelto en estado " + header.status() + ".");
        }
        return header;
    }

    private Long checkApprovalLimitAndConsumeOverride(
            Long adjustmentId,
            Long approverId,
            BigDecimal approvalAmount,
            String overrideToken) {
        RoleLimit limit = roleLimitApi.limitFor(approverId, RoleLimitCode.ADJUSTMENT_APPROVE_MAX_AMOUNT);
        if (limit.allows(approvalAmount)) {
            return null;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("entityId", adjustmentId);
        context.put("amount", approvalAmount);

        if (overrideToken == null || overrideToken.isBlank()) {
            throw new BusinessException(
                    ErrorCode.OVERRIDE_REQUIRED,
                    "El impacto del ajuste supera el límite de aprobación permitido para su rol y requiere autorización de supervisor.",
                    Map.of(
                            "action", OverrideAction.STOCK_ADJUSTMENT_APPROVAL.name(),
                            "context", context));
        }

        String contextHash = supervisorOverrideApi.canonicalContextHash(context);
        AuthorizationRef authRef = supervisorOverrideApi.consume(
                overrideToken.trim(),
                OverrideAction.STOCK_ADJUSTMENT_APPROVAL,
                contextHash,
                approverId,
                ENTITY_TYPE,
                String.valueOf(adjustmentId));
        return authRef.authorizationId();
    }

    private void applyStockMovements(
            Long storeId,
            Long approverId,
            LockedAdjustment header,
            List<AdjustmentLineForExecution> lines) {
        List<AdjustmentLineForExecution> outbound = new ArrayList<>();
        List<AdjustmentLineForExecution> inbound = new ArrayList<>();
        for (AdjustmentLineForExecution line : lines) {
            if (line.quantityDelta().signum() < 0) {
                outbound.add(line);
            } else {
                inbound.add(line);
            }
        }

        String movementReason = buildMovementReason(header.number(), header.reasonCode(), header.notes());

        if (!outbound.isEmpty()) {
            List<StockLine> stockLines = outbound.stream()
                    .map(line -> new StockLine(
                            line.productId(),
                            line.quantityDelta().abs(),
                            line.unitCost(),
                            line.id(),
                            line.lotId(),
                            null,
                            null))
                    .toList();
            List<StockMovementResult> results = stockApi.apply(new StockCommand(
                    storeId,
                    StockMovementType.ADJUSTMENT_OUT,
                    StockSourceType.STOCK_ADJUSTMENT,
                    header.id(),
                    approverId,
                    movementReason,
                    stockLines));
            linkLineMovements(outbound, results);
        }

        if (!inbound.isEmpty()) {
            List<StockLine> stockLines = inbound.stream()
                    .map(line -> new StockLine(
                            line.productId(),
                            line.quantityDelta(),
                            line.unitCost(),
                            line.id(),
                            line.lotId(),
                            line.lotNumber(),
                            line.expiryDate()))
                    .toList();
            List<StockMovementResult> results = stockApi.apply(new StockCommand(
                    storeId,
                    StockMovementType.ADJUSTMENT_IN,
                    StockSourceType.STOCK_ADJUSTMENT,
                    header.id(),
                    approverId,
                    movementReason,
                    stockLines));
            linkLineMovements(inbound, results);
        }
    }

    private void linkLineMovements(
            List<AdjustmentLineForExecution> lines,
            List<StockMovementResult> results) {
        Map<Long, List<StockMovementResult>> resultsByProduct = new HashMap<>();
        for (StockMovementResult result : results) {
            resultsByProduct.computeIfAbsent(result.productId(), ignored -> new ArrayList<>()).add(result);
        }

        for (AdjustmentLineForExecution line : lines) {
            List<StockMovementResult> productResults = resultsByProduct.getOrDefault(line.productId(), List.of());
            if (productResults.isEmpty()) {
                continue;
            }
            Long movementId = resolveMovementIdForLine(line, productResults);
            repository.updateLineMovementId(line.id(), movementId);
        }
    }

    private static Long resolveMovementIdForLine(
            AdjustmentLineForExecution line,
            List<StockMovementResult> productResults) {
        if (line.lotId() != null) {
            for (StockMovementResult result : productResults) {
                for (LotAllocation allocation : result.allocations()) {
                    if (Objects.equals(line.lotId(), allocation.lotId())) {
                        return result.movementId();
                    }
                }
            }
        }
        return productResults.getFirst().movementId();
    }

    /**
     * Calcula el monto absoluto total sujeto al límite de aprobación del rol
     * ({@link RoleLimitCode#ADJUSTMENT_APPROVE_MAX_AMOUNT}).
     */
    static BigDecimal computeApprovalAmount(List<AdjustmentLineForExecution> lines) {
        BigDecimal sum = BigDecimal.ZERO;
        for (AdjustmentLineForExecution line : lines) {
            BigDecimal absLineImpact = line.quantityDelta().abs().multiply(line.unitCost());
            sum = sum.add(absLineImpact);
        }
        return Rounding.costTotal(sum);
    }

    private static String buildMovementReason(String number, AdjustmentReason reasonCode, String notes) {
        String base = "Ajuste " + number + " (" + reasonCode.name() + ")";
        String raw = notes != null ? base + ": " + notes : base;
        return raw.length() > MAX_MOVEMENT_REASON_LENGTH
                ? raw.substring(0, MAX_MOVEMENT_REASON_LENGTH)
                : raw;
    }

    private static String buildAuditReason(String notes, AdjustmentReason reasonCode) {
        String raw = notes != null ? notes : reasonCode.name();
        return raw.length() > MAX_MOVEMENT_REASON_LENGTH
                ? raw.substring(0, MAX_MOVEMENT_REASON_LENGTH)
                : raw;
    }

    private static BusinessException validationError(
            String detail,
            String field,
            String fieldCode,
            String fieldMessage) {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                detail,
                Map.of(),
                List.of(new FieldErrorDetail(field, fieldCode, fieldMessage)));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PreparedLine(
            Long productId,
            Long lotId,
            BigDecimal quantityDelta,
            BigDecimal unitCost
    ) {
    }
}
