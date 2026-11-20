package cl.minimarket.pos.sales.application.returns;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.cash.CashSessionApi;
import cl.minimarket.pos.cash.CashSessionRef;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.customers.CustomerCreditApi;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.organization.TerminalApi;
import cl.minimarket.pos.sales.SaleReturnedEvent;
import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import cl.minimarket.pos.sales.application.returns.SaleReturnJdbcRepository.LockedSaleLineRow;
import cl.minimarket.pos.sales.application.returns.SaleReturnJdbcRepository.LockedSaleRow;
import cl.minimarket.pos.sales.application.returns.SaleReturnJdbcRepository.LotAllocationRow;
import cl.minimarket.pos.sales.application.returns.SaleReturnJdbcRepository.PriorLineReturnTotals;
import cl.minimarket.pos.sales.application.returns.SaleReturnJdbcRepository.SalePaymentRow;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.RefundCommand;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.ReturnLineCommand;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.ReturnSaleCommand;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.ReturnableLineDetail;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.ReturnableLinesDetail;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.SaleReturnDetail;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.VoidSaleCommand;
import cl.minimarket.pos.sales.application.sale.SaleStatus;
import cl.minimarket.pos.sales.payment.PaymentGateway;
import cl.minimarket.pos.sales.payment.RefundRequest;
import cl.minimarket.pos.sales.payment.RefundResult;
import cl.minimarket.pos.security.AuthorizationRef;
import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.RoleLimitApi;
import cl.minimarket.pos.security.RoleLimitCode;
import cl.minimarket.pos.security.SupervisorOverrideApi;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.shrinkage.ShrinkageApi;
import cl.minimarket.pos.shrinkage.ShrinkageApi.SaleReturnShrinkageCommand;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Casos de uso de anulación y devolución de ventas ({@code API.md} F.2/F.4, {@code MODELO_DATOS.md}
 * B.9, RN-12, RN-13, RN-14).
 */
@Service
public class SaleReturnService {

    private static final String RETURN_MAX_DAYS_KEY = "pos.return.max_days";
    private static final int DEFAULT_RETURN_MAX_DAYS = 30;

    private final SaleReturnJdbcRepository jdbcRepository;
    private final CashSessionApi cashSessionApi;
    private final TerminalApi terminalApi;
    private final SettingsApi settingsApi;
    private final StockApi stockApi;
    private final PaymentGateway paymentGateway;
    private final CustomerCreditApi customerCreditApi;
    private final ShrinkageApi shrinkageApi;
    private final SupervisorOverrideApi supervisorOverrideApi;
    private final RoleLimitApi roleLimitApi;
    private final SequenceService sequenceService;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final ApplicationEventPublisher eventPublisher;

    public SaleReturnService(
            SaleReturnJdbcRepository jdbcRepository,
            CashSessionApi cashSessionApi,
            TerminalApi terminalApi,
            SettingsApi settingsApi,
            StockApi stockApi,
            PaymentGateway paymentGateway,
            CustomerCreditApi customerCreditApi,
            ShrinkageApi shrinkageApi,
            SupervisorOverrideApi supervisorOverrideApi,
            RoleLimitApi roleLimitApi,
            SequenceService sequenceService,
            AuditService auditService,
            BusinessClock businessClock,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcRepository = jdbcRepository;
        this.cashSessionApi = cashSessionApi;
        this.terminalApi = terminalApi;
        this.settingsApi = settingsApi;
        this.stockApi = stockApi;
        this.paymentGateway = paymentGateway;
        this.customerCreditApi = customerCreditApi;
        this.shrinkageApi = shrinkageApi;
        this.supervisorOverrideApi = supervisorOverrideApi;
        this.roleLimitApi = roleLimitApi;
        this.sequenceService = sequenceService;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Anula una venta completa en el mismo turno de caja abierto (RN-12, {@code POST /sales/{id}/void}).
     *
     * <p>Reingresa el stock (respetando lotes originales), reversa pagos con tarjeta vía
     * {@link PaymentGateway#refund} y fiado vía {@link CustomerCreditApi#reverse}, audita
     * {@code SALE_VOIDED} y publica {@link SaleReturnedEvent}.
     */
    @Transactional
    public SaleReturnDetail voidSale(VoidSaleCommand cmd) {
        StoreContext ctx = StoreContext.require();
        Long storeId = ctx.storeId();
        Long terminalId = ctx.terminalId();
        Long currentUserId = requireCurrentUserId();

        Optional<SaleReturnDetail> existing = findExistingByIdempotencyKey(cmd.idempotencyKey(), storeId);
        if (existing.isPresent()) {
            return existing.get();
        }

        terminalApi.requireActive(terminalId, storeId);
        CashSessionRef activeSession = cashSessionApi.requireOpen(terminalId);

        LockedSaleRow sale = jdbcRepository.lockSale(cmd.saleId(), storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Venta no encontrada."));

        if (sale.status() == SaleStatus.VOIDED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "La venta ya se encuentra anulada."
            );
        }
        if (sale.status() != SaleStatus.COMPLETED || jdbcRepository.hasAnyReturnForSale(sale.id())) {
            throw new BusinessException(
                    ErrorCode.VOID_NOT_ALLOWED,
                    "No es posible anular una venta que ya tiene devoluciones."
            );
        }
        if (!cashSessionApi.isOpen(sale.cashSessionId())) {
            throw new BusinessException(
                    ErrorCode.VOID_NOT_ALLOWED,
                    "La anulación solo está permitida mientras el turno original esté abierto (RN-12)."
            );
        }

        Long authorizationId = resolveOverride(
                Permissions.SALES_SALE_VOID,
                true,
                OverrideAction.SALE_VOID,
                Map.of("saleId", sale.id(), "number", sale.number()),
                cmd.overrideToken(),
                currentUserId,
                "sale",
                String.valueOf(sale.id()),
                "La anulación de ventas requiere autorización de supervisor."
        );

        List<LockedSaleLineRow> saleLines = jdbcRepository.findSaleLines(sale.id());
        List<SalePaymentRow> salePayments = jdbcRepository.findSalePayments(sale.id());
        Map<Long, List<LotAllocationRow>> allocationsByLine = jdbcRepository.findLotAllocationsBySaleLineIds(
                saleLines.stream().map(LockedSaleLineRow::id).toList()
        );

        Instant now = businessClock.now();
        String returnNumber = sequenceService.next(SequenceType.SALE_RETURN, storeId);
        String reasonText = trimToNull(cmd.reasonText());

        Long saleReturnId;
        try {
            saleReturnId = jdbcRepository.insertSaleReturn(
                    returnNumber,
                    sale.id(),
                    SaleReturnType.VOID,
                    storeId,
                    terminalId,
                    activeSession.id(),
                    cmd.reasonCode(),
                    reasonText,
                    sale.totalPayable(),
                    authorizationId,
                    cmd.idempotencyKey(),
                    currentUserId,
                    now
            );
        } catch (DataIntegrityViolationException ex) {
            return findExistingByIdempotencyKey(cmd.idempotencyKey(), storeId)
                    .orElseThrow(() -> ex);
        }

        List<StockLine> restockLines = new ArrayList<>();
        List<SaleReturnedEvent.Line> eventLines = new ArrayList<>(saleLines.size());

        for (LockedSaleLineRow line : saleLines) {
            Long returnLineId = jdbcRepository.insertSaleReturnLine(
                    saleReturnId,
                    line.id(),
                    line.quantity(),
                    line.stockQuantity(),
                    true,
                    line.lineTotalGross(),
                    line.lineNet(),
                    line.lineTax(),
                    now
            );
            jdbcRepository.updateSaleLineReturnedQuantity(line.id(), line.quantity());

            addRestockLines(restockLines, line, line.stockQuantity(), returnLineId, allocationsByLine);

            eventLines.add(new SaleReturnedEvent.Line(
                    line.id(),
                    line.productId(),
                    line.sku(),
                    line.productName(),
                    line.quantity(),
                    line.lineTotalGross()
            ));
        }

        if (!restockLines.isEmpty()) {
            stockApi.apply(new StockCommand(
                    storeId,
                    StockMovementType.SALE_VOID,
                    StockSourceType.SALE,
                    sale.id(),
                    currentUserId,
                    buildStockReason(returnNumber, cmd.reasonCode(), reasonText),
                    restockLines
            ));
        }

        List<RefundAllocation> plannedRefunds = salePayments.stream()
                .map(p -> new RefundAllocation(p.method(), p.amount(), p.gatewayReference()))
                .toList();
        executeAndPersistRefunds(
                saleReturnId,
                sale,
                salePayments,
                plannedRefunds,
                cmd.idempotencyKey(),
                storeId,
                now
        );

        jdbcRepository.markSalePaymentsReversed(sale.id());
        jdbcRepository.updateSaleStatus(sale.id(), SaleStatus.VOIDED, currentUserId, now);

        auditService.record(new AuditEvent(
                "SALE_VOIDED",
                "sale",
                String.valueOf(sale.id()),
                Map.of("status", sale.status().name()),
                Map.of(
                        "status", SaleStatus.VOIDED.name(),
                        "saleReturnId", saleReturnId,
                        "saleReturnNumber", returnNumber,
                        "refundTotal", sale.totalPayable()
                ),
                reasonText != null ? reasonText : cmd.reasonCode().name(),
                authorizationId
        ));

        eventPublisher.publishEvent(new SaleReturnedEvent(
                saleReturnId,
                returnNumber,
                sale.id(),
                sale.number(),
                SaleReturnType.VOID.name(),
                storeId,
                terminalId,
                activeSession.id(),
                currentUserId,
                now,
                activeSession.businessDate(),
                sale.totalPayable(),
                eventLines
        ));

        return jdbcRepository.findDetailById(saleReturnId, storeId, null)
                .orElseThrow(() -> new IllegalStateException("No se pudo recuperar la anulación recién creada."));
    }

    /**
     * Registra una devolución parcial o total de una venta ({@code POST /sales/{id}/returns},
     * RN-13, RN-14).
     */
    @Transactional
    public SaleReturnDetail returnSale(ReturnSaleCommand cmd) {
        StoreContext ctx = StoreContext.require();
        Long storeId = ctx.storeId();
        Long terminalId = ctx.terminalId();
        Long currentUserId = requireCurrentUserId();

        Optional<SaleReturnDetail> existing = findExistingByIdempotencyKey(cmd.idempotencyKey(), storeId);
        if (existing.isPresent()) {
            return existing.get();
        }

        terminalApi.requireActive(terminalId, storeId);
        CashSessionRef activeSession = cashSessionApi.requireOpen(terminalId);

        LockedSaleRow sale = jdbcRepository.lockSale(cmd.saleId(), storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Venta no encontrada."));

        if (sale.status() == SaleStatus.VOIDED || sale.status() == SaleStatus.RETURNED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "La venta no admite devoluciones en estado " + sale.status() + "."
            );
        }

        int maxDays = resolveReturnMaxDays(storeId);
        long daysSinceSale = ChronoUnit.DAYS.between(sale.businessDate(), businessClock.businessDate());
        if (daysSinceSale > maxDays) {
            throw new BusinessException(
                    ErrorCode.RETURN_WINDOW_EXPIRED,
                    "La venta excede el plazo máximo de " + maxDays + " días para devoluciones."
            );
        }

        List<LockedSaleLineRow> saleLines = jdbcRepository.findSaleLines(sale.id());
        Map<Long, LockedSaleLineRow> linesById = new LinkedHashMap<>(saleLines.size());
        for (LockedSaleLineRow line : saleLines) {
            linesById.put(line.id(), line);
        }

        Map<Long, PriorLineReturnTotals> priorTotalsByLine = jdbcRepository.findPriorReturnTotalsBySaleId(sale.id());
        Map<Long, List<LotAllocationRow>> allocationsByLine = jdbcRepository.findLotAllocationsBySaleLineIds(
                saleLines.stream().map(LockedSaleLineRow::id).toList()
        );

        List<ComputedReturnLine> computedLines = computeAndValidateReturnLines(
                cmd.lines(),
                linesById,
                priorTotalsByLine
        );

        BigDecimal refundTotal = BigDecimal.ZERO;
        for (ComputedReturnLine line : computedLines) {
            refundTotal = refundTotal.add(line.refundAmount());
        }
        refundTotal = Rounding.clp(refundTotal);

        boolean withinUserLimit = hasAuthority(Permissions.SALES_RETURN_CREATE)
                && roleLimitApi.limitFor(currentUserId, RoleLimitCode.RETURN_MAX_AMOUNT).allows(refundTotal);

        Long authorizationId = resolveOverride(
                Permissions.SALES_RETURN_CREATE,
                withinUserLimit,
                OverrideAction.SALE_RETURN,
                Map.of("saleId", sale.id(), "number", sale.number(), "amount", refundTotal),
                cmd.overrideToken(),
                currentUserId,
                "sale",
                String.valueOf(sale.id()),
                "La devolución requiere autorización de supervisor o supera el límite del rol."
        );

        List<SalePaymentRow> salePayments = jdbcRepository.findSalePayments(sale.id());
        List<RefundAllocation> plannedRefunds = planRefunds(cmd.refunds(), salePayments, refundTotal, sale.customerId());

        Instant now = businessClock.now();
        String returnNumber = sequenceService.next(SequenceType.SALE_RETURN, storeId);
        String reasonText = trimToNull(cmd.reasonText());

        Long saleReturnId;
        try {
            saleReturnId = jdbcRepository.insertSaleReturn(
                    returnNumber,
                    sale.id(),
                    SaleReturnType.RETURN,
                    storeId,
                    terminalId,
                    activeSession.id(),
                    cmd.reasonCode(),
                    reasonText,
                    refundTotal,
                    authorizationId,
                    cmd.idempotencyKey(),
                    currentUserId,
                    now
            );
        } catch (DataIntegrityViolationException ex) {
            return findExistingByIdempotencyKey(cmd.idempotencyKey(), storeId)
                    .orElseThrow(() -> ex);
        }

        List<StockLine> restockLines = new ArrayList<>();
        List<SaleReturnedEvent.Line> eventLines = new ArrayList<>(computedLines.size());
        Map<Long, BigDecimal> newReturnedByLineId = new LinkedHashMap<>();

        for (ComputedReturnLine item : computedLines) {
            LockedSaleLineRow saleLine = item.saleLine();
            Long returnLineId = jdbcRepository.insertSaleReturnLine(
                    saleReturnId,
                    saleLine.id(),
                    item.quantity(),
                    item.stockQuantity(),
                    item.restock(),
                    item.refundAmount(),
                    item.refundNet(),
                    item.refundTax(),
                    now
            );

            BigDecimal newReturnedQty = Rounding.quantity(saleLine.returnedQuantity().add(item.quantity()));
            jdbcRepository.updateSaleLineReturnedQuantity(saleLine.id(), newReturnedQty);
            newReturnedByLineId.put(saleLine.id(), newReturnedQty);

            if (item.restock()) {
                addRestockLines(restockLines, saleLine, item.stockQuantity(), returnLineId, allocationsByLine);
            } else {
                Long primaryLotId = firstLotId(allocationsByLine.get(saleLine.id()));
                shrinkageApi.createFromSaleReturn(new SaleReturnShrinkageCommand(
                        storeId,
                        saleLine.stockProductId(),
                        primaryLotId,
                        returnLineId,
                        item.stockQuantity(),
                        saleLine.unitCost(),
                        reasonText != null ? reasonText : cmd.reasonCode().name(),
                        currentUserId,
                        now
                ));
            }

            eventLines.add(new SaleReturnedEvent.Line(
                    saleLine.id(),
                    saleLine.productId(),
                    saleLine.sku(),
                    saleLine.productName(),
                    item.quantity(),
                    item.refundAmount()
            ));
        }

        if (!restockLines.isEmpty()) {
            stockApi.apply(new StockCommand(
                    storeId,
                    StockMovementType.SALE_RETURN,
                    StockSourceType.SALE_RETURN,
                    saleReturnId,
                    currentUserId,
                    buildStockReason(returnNumber, cmd.reasonCode(), reasonText),
                    restockLines
            ));
        }

        executeAndPersistRefunds(
                saleReturnId,
                sale,
                salePayments,
                plannedRefunds,
                cmd.idempotencyKey(),
                storeId,
                now
        );

        boolean allReturned = true;
        for (LockedSaleLineRow line : saleLines) {
            BigDecimal effectiveReturned = newReturnedByLineId.getOrDefault(line.id(), line.returnedQuantity());
            if (effectiveReturned.compareTo(line.quantity()) < 0) {
                allReturned = false;
                break;
            }
        }
        SaleStatus targetStatus = allReturned ? SaleStatus.RETURNED : SaleStatus.PARTIALLY_RETURNED;
        jdbcRepository.updateSaleStatus(sale.id(), targetStatus, currentUserId, now);

        auditService.record(new AuditEvent(
                "SALE_RETURNED",
                "sale_return",
                String.valueOf(saleReturnId),
                null,
                Map.of(
                        "saleId", sale.id(),
                        "saleReturnNumber", returnNumber,
                        "refundTotal", refundTotal,
                        "linesCount", computedLines.size(),
                        "saleStatus", targetStatus.name()
                ),
                reasonText != null ? reasonText : cmd.reasonCode().name(),
                authorizationId
        ));

        eventPublisher.publishEvent(new SaleReturnedEvent(
                saleReturnId,
                returnNumber,
                sale.id(),
                sale.number(),
                SaleReturnType.RETURN.name(),
                storeId,
                terminalId,
                activeSession.id(),
                currentUserId,
                now,
                activeSession.businessDate(),
                refundTotal,
                eventLines
        ));

        return jdbcRepository.findDetailById(saleReturnId, storeId, null)
                .orElseThrow(() -> new IllegalStateException("No se pudo recuperar la devolución recién creada."));
    }

    /**
     * Consulta qué líneas de una venta son aún devolvibles y si la venta es anulable (alcance P).
     */
    @Transactional(readOnly = true)
    public ReturnableLinesDetail getReturnable(Long saleId) {
        Long storeId = StoreContext.require().storeId();
        LockedSaleRow sale = jdbcRepository.findSale(saleId, storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Venta no encontrada."));

        enforceScopeP(sale.cashierUserId());

        int maxDays = resolveReturnMaxDays(storeId);
        LocalDate returnDeadline = sale.businessDate().plusDays(maxDays);
        boolean voidAllowed = sale.status() == SaleStatus.COMPLETED
                && cashSessionApi.isOpen(sale.cashSessionId())
                && !jdbcRepository.hasAnyReturnForSale(sale.id());

        List<LockedSaleLineRow> saleLines = jdbcRepository.findSaleLines(sale.id());
        List<ReturnableLineDetail> lineDetails = new ArrayList<>(saleLines.size());
        for (LockedSaleLineRow line : saleLines) {
            BigDecimal returnable = Rounding.quantity(line.quantity().subtract(line.returnedQuantity()));
            if (returnable.signum() < 0) {
                returnable = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
            }
            BigDecimal unitRefund = line.quantity().signum() > 0
                    ? Rounding.clp(line.lineTotalGross().divide(line.quantity(), 0, RoundingMode.HALF_UP))
                    : BigDecimal.ZERO;
            lineDetails.add(new ReturnableLineDetail(
                    line.id(),
                    line.productName(),
                    line.quantity(),
                    line.returnedQuantity(),
                    returnable,
                    unitRefund
            ));
        }

        return new ReturnableLinesDetail(sale.id(), voidAllowed, returnDeadline, lineDetails);
    }

    /** Lista anulaciones y devoluciones del local activo con alcance P para cajeros. */
    @Transactional(readOnly = true)
    public Page<SaleReturnDetail> listReturns(SaleReturnFilter filter, Pageable pageable) {
        Long storeId = StoreContext.require().storeId();
        Long restrictCashierId = hasFullSaleReadScope() ? null : requireCurrentUserId();
        return jdbcRepository.list(storeId, restrictCashierId, filter, pageable);
    }

    /** Obtiene una anulación o devolución por su id con alcance P para cajeros. */
    @Transactional(readOnly = true)
    public SaleReturnDetail getReturnById(Long id) {
        Long storeId = StoreContext.require().storeId();
        Long restrictCashierId = hasFullSaleReadScope() ? null : requireCurrentUserId();
        return jdbcRepository.findDetailById(id, storeId, restrictCashierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Devolución no encontrada."));
    }

    private Optional<SaleReturnDetail> findExistingByIdempotencyKey(UUID idempotencyKey, Long storeId) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return jdbcRepository.findIdByIdempotencyKey(idempotencyKey)
                .flatMap(id -> jdbcRepository.findDetailById(id, storeId, null));
    }

    private Long resolveOverride(
            String requiredPermission,
            boolean conditionMet,
            OverrideAction action,
            Map<String, Object> overrideContext,
            String overrideToken,
            Long currentUserId,
            String entityType,
            String entityId,
            String requiredMessage
    ) {
        String trimmedToken = trimToNull(overrideToken);
        boolean authorized = hasAuthority(requiredPermission) && conditionMet;
        if (!authorized) {
            if (trimmedToken == null) {
                throw new BusinessException(
                        ErrorCode.OVERRIDE_REQUIRED,
                        requiredMessage,
                        Map.of("action", action.name(), "context", overrideContext)
                );
            }
            AuthorizationRef authRef = supervisorOverrideApi.consume(
                    trimmedToken,
                    action,
                    supervisorOverrideApi.canonicalContextHash(overrideContext),
                    currentUserId,
                    entityType,
                    entityId
            );
            return authRef.authorizationId();
        }
        if (trimmedToken != null) {
            AuthorizationRef authRef = supervisorOverrideApi.consume(
                    trimmedToken,
                    action,
                    supervisorOverrideApi.canonicalContextHash(overrideContext),
                    currentUserId,
                    entityType,
                    entityId
            );
            return authRef.authorizationId();
        }
        return null;
    }

    private List<ComputedReturnLine> computeAndValidateReturnLines(
            List<ReturnLineCommand> requestedLines,
            Map<Long, LockedSaleLineRow> linesById,
            Map<Long, PriorLineReturnTotals> priorTotalsByLine
    ) {
        Set<Long> seenLineIds = new HashSet<>();
        List<ComputedReturnLine> result = new ArrayList<>(requestedLines.size());

        for (ReturnLineCommand req : requestedLines) {
            if (!seenLineIds.add(req.saleLineId())) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "No se puede repetir la línea de venta " + req.saleLineId() + " en la misma devolución."
                );
            }
            LockedSaleLineRow saleLine = linesById.get(req.saleLineId());
            if (saleLine == null) {
                throw new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "Línea de venta no encontrada: " + req.saleLineId()
                );
            }
            BigDecimal qty = Rounding.quantity(req.quantity());
            if (qty.signum() <= 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "La cantidad a devolver debe ser mayor que cero."
                );
            }
            if (saleLine.saleType() == SaleType.UNIT && qty.stripTrailingZeros().scale() > 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "El producto por unidad requiere cantidad entera: " + saleLine.productName()
                );
            }

            BigDecimal returnable = Rounding.quantity(saleLine.quantity().subtract(saleLine.returnedQuantity()));
            if (qty.compareTo(returnable) > 0) {
                throw new BusinessException(
                        ErrorCode.RETURN_QUANTITY_EXCEEDED,
                        "La cantidad a devolver (" + qty + ") excede lo devolvible (" + returnable
                                + ") para " + saleLine.productName() + "."
                );
            }

            PriorLineReturnTotals prior = priorTotalsByLine.getOrDefault(
                    saleLine.id(),
                    new PriorLineReturnTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
            );
            boolean isFinalReturn = qty.compareTo(returnable) == 0;
            boolean purelyExempt = saleLine.lineExempt().signum() > 0
                    && saleLine.lineTax().signum() == 0
                    && saleLine.lineNet().signum() == 0;

            BigDecimal refundAmount;
            BigDecimal refundNet;
            BigDecimal refundTax;
            BigDecimal stockQuantity;

            if (isFinalReturn) {
                refundAmount = Rounding.clp(saleLine.lineTotalGross().subtract(prior.refundAmount()).max(BigDecimal.ZERO));
                stockQuantity = Rounding.quantity(saleLine.stockQuantity().subtract(prior.stockQuantity()));
                if (purelyExempt) {
                    refundNet = BigDecimal.ZERO;
                    refundTax = BigDecimal.ZERO;
                } else {
                    refundNet = Rounding.clp(saleLine.lineNet().subtract(prior.refundNet()).max(BigDecimal.ZERO));
                    refundTax = Rounding.clp(refundAmount.subtract(refundNet).max(BigDecimal.ZERO));
                }
            } else {
                refundAmount = Rounding.clp(
                        saleLine.lineTotalGross().multiply(qty).divide(saleLine.quantity(), 0, RoundingMode.HALF_UP)
                );
                stockQuantity = Rounding.quantity(
                        saleLine.stockQuantity().multiply(qty).divide(saleLine.quantity(), 3, RoundingMode.HALF_UP)
                );
                if (purelyExempt) {
                    refundNet = BigDecimal.ZERO;
                    refundTax = BigDecimal.ZERO;
                } else {
                    refundNet = Rounding.clp(
                            saleLine.lineNet().multiply(qty).divide(saleLine.quantity(), 0, RoundingMode.HALF_UP)
                    );
                    refundTax = Rounding.clp(refundAmount.subtract(refundNet).max(BigDecimal.ZERO));
                }
            }

            result.add(new ComputedReturnLine(
                    saleLine,
                    qty,
                    stockQuantity,
                    req.restock(),
                    refundAmount,
                    refundNet,
                    refundTax
            ));
        }

        return result;
    }

    private List<RefundAllocation> planRefunds(
            List<RefundCommand> requestedRefunds,
            List<SalePaymentRow> salePayments,
            BigDecimal refundTotal,
            Long customerId
    ) {
        if (refundTotal.signum() == 0) {
            if (requestedRefunds != null && !requestedRefunds.isEmpty()) {
                BigDecimal requestedSum = requestedRefunds.stream()
                        .map(RefundCommand::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (requestedSum.compareTo(BigDecimal.ZERO) != 0) {
                    throw new BusinessException(
                            ErrorCode.PAYMENT_MISMATCH,
                            "El monto de los reembolsos no coincide con el total calculado."
                    );
                }
            }
            return List.of();
        }

        if (requestedRefunds != null && !requestedRefunds.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            List<RefundAllocation> explicit = new ArrayList<>(requestedRefunds.size());
            for (RefundCommand r : requestedRefunds) {
                BigDecimal amount = Rounding.clp(r.amount());
                if (amount.signum() <= 0) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Cada reembolso debe tener monto mayor que cero."
                    );
                }
                if (r.method() == PaymentMethod.CUSTOMER_CREDIT && customerId == null) {
                    throw new BusinessException(
                            ErrorCode.PAYMENT_MISMATCH,
                            "No es posible reembolsar a cuenta corriente en una venta sin cliente asociado."
                    );
                }
                sum = sum.add(amount);
                explicit.add(new RefundAllocation(r.method(), amount, null));
            }
            if (sum.compareTo(refundTotal) != 0) {
                throw new BusinessException(
                        ErrorCode.PAYMENT_MISMATCH,
                        "La suma de los reembolsos (" + sum + ") no coincide con el total a reembolsar ("
                                + refundTotal + ")."
                );
            }
            return explicit;
        }

        // Por defecto: proporcional a los medios de pago de la venta original (RN-14).
        BigDecimal paymentsTotal = salePayments.stream()
                .map(SalePaymentRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (paymentsTotal.signum() <= 0) {
            return List.of(new RefundAllocation(PaymentMethod.CASH, refundTotal, null));
        }

        List<RefundAllocation> proportional = new ArrayList<>(salePayments.size());
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < salePayments.size(); i++) {
            SalePaymentRow payment = salePayments.get(i);
            BigDecimal share;
            if (i == salePayments.size() - 1) {
                share = refundTotal.subtract(assigned);
            } else {
                share = Rounding.clp(
                        refundTotal.multiply(payment.amount()).divide(paymentsTotal, 0, RoundingMode.HALF_UP)
                );
                BigDecimal maxRemaining = refundTotal.subtract(assigned);
                if (share.compareTo(maxRemaining) > 0) {
                    share = maxRemaining;
                }
                assigned = assigned.add(share);
            }
            if (share.signum() > 0) {
                proportional.add(new RefundAllocation(payment.method(), share, payment.gatewayReference()));
            }
        }
        return proportional;
    }

    private void executeAndPersistRefunds(
            Long saleReturnId,
            LockedSaleRow sale,
            List<SalePaymentRow> salePayments,
            List<RefundAllocation> plannedRefunds,
            UUID idempotencyKey,
            Long storeId,
            Instant now
    ) {
        for (int i = 0; i < plannedRefunds.size(); i++) {
            RefundAllocation refund = plannedRefunds.get(i);
            String gatewayRef = refund.gatewayReference();

            if (refund.method() == PaymentMethod.DEBIT || refund.method() == PaymentMethod.CREDIT) {
                String originalGatewayRef = gatewayRef != null
                        ? gatewayRef
                        : findOriginalGatewayReference(salePayments, refund.method());
                if (originalGatewayRef != null) {
                    UUID refundKey = UUID.nameUUIDFromBytes(
                            (idempotencyKey + ":refund:" + i).getBytes(StandardCharsets.UTF_8)
                    );
                    RefundResult gwResult = paymentGateway.refund(
                            new RefundRequest(originalGatewayRef, refund.amount(), refundKey)
                    );
                    if (!gwResult.success()) {
                        throw new BusinessException(
                                ErrorCode.PAYMENT_DECLINED,
                                gwResult.message() != null
                                        ? gwResult.message()
                                        : "La pasarela rechazó la reversa del pago."
                        );
                    }
                    gatewayRef = gwResult.gatewayReference();
                }
            } else if (refund.method() == PaymentMethod.CUSTOMER_CREDIT) {
                if (sale.customerId() == null) {
                    throw new BusinessException(
                            ErrorCode.PAYMENT_MISMATCH,
                            "No es posible reversar fiado sin cliente asociado."
                    );
                }
                customerCreditApi.reverse(sale.customerId(), refund.amount(), sale.id(), saleReturnId, storeId);
            }

            jdbcRepository.insertSaleReturnRefund(
                    saleReturnId,
                    refund.method(),
                    refund.amount(),
                    gatewayRef,
                    now
            );
        }
    }

    private static String findOriginalGatewayReference(List<SalePaymentRow> salePayments, PaymentMethod method) {
        for (SalePaymentRow p : salePayments) {
            if (p.method() == method && p.gatewayReference() != null) {
                return p.gatewayReference();
            }
        }
        for (SalePaymentRow p : salePayments) {
            if ((p.method() == PaymentMethod.DEBIT || p.method() == PaymentMethod.CREDIT)
                    && p.gatewayReference() != null) {
                return p.gatewayReference();
            }
        }
        return null;
    }

    private static void addRestockLines(
            List<StockLine> target,
            LockedSaleLineRow saleLine,
            BigDecimal stockQuantity,
            Long sourceLineId,
            Map<Long, List<LotAllocationRow>> allocationsByLine
    ) {
        List<LotAllocationRow> allocations = allocationsByLine.get(saleLine.id());
        if (allocations == null || allocations.isEmpty()) {
            target.add(new StockLine(
                    saleLine.stockProductId(),
                    stockQuantity,
                    saleLine.unitCost(),
                    sourceLineId,
                    null,
                    null,
                    null
            ));
            return;
        }

        BigDecimal remaining = stockQuantity;
        for (LotAllocationRow allocation : allocations) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = Rounding.quantity(remaining.min(allocation.quantity()));
            if (take.signum() > 0) {
                target.add(new StockLine(
                        saleLine.stockProductId(),
                        take,
                        saleLine.unitCost(),
                        sourceLineId,
                        allocation.lotId(),
                        null,
                        null
                ));
                remaining = Rounding.quantity(remaining.subtract(take));
            }
        }
        if (remaining.signum() > 0) {
            target.add(new StockLine(
                    saleLine.stockProductId(),
                    remaining,
                    saleLine.unitCost(),
                    sourceLineId,
                    allocations.getFirst().lotId(),
                    null,
                    null
            ));
        }
    }

    private static Long firstLotId(List<LotAllocationRow> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            return null;
        }
        return allocations.getFirst().lotId();
    }

    private int resolveReturnMaxDays(Long storeId) {
        Integer configured = settingsApi.get(RETURN_MAX_DAYS_KEY, storeId, Integer.class);
        return configured != null && configured > 0 ? configured : DEFAULT_RETURN_MAX_DAYS;
    }

    private static String buildStockReason(String returnNumber, SaleReturnReasonCode code, String reasonText) {
        String base = returnNumber + " (" + code.name() + ")";
        if (reasonText == null) {
            return base;
        }
        String combined = base + ": " + reasonText;
        return combined.length() <= 250 ? combined : combined.substring(0, 250);
    }

    private static void enforceScopeP(Long saleCashierUserId) {
        if (hasFullSaleReadScope()) {
            return;
        }
        Long currentUserId = requireCurrentUserId();
        if (!currentUserId.equals(saleCashierUserId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Venta no encontrada.");
        }
    }

    private static Long requireCurrentUserId() {
        return CurrentUserId.find().orElseThrow(() ->
                new BusinessException(ErrorCode.UNAUTHENTICATED, "No hay usuario autenticado."));
    }

    private static boolean hasFullSaleReadScope() {
        return hasAuthority(Permissions.SALES_SALE_VOID)
                || hasAuthority(Permissions.SALES_RETURN_CREATE)
                || hasAuthority(Permissions.SALES_DISCOUNT_OVERRIDE)
                || hasAuthority(Permissions.SALES_RECEIPT_REPRINT)
                || hasAuthority(Permissions.REPORT_SALES_READ);
    }

    private static boolean hasAuthority(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (code.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ComputedReturnLine(
            LockedSaleLineRow saleLine,
            BigDecimal quantity,
            BigDecimal stockQuantity,
            boolean restock,
            BigDecimal refundAmount,
            BigDecimal refundNet,
            BigDecimal refundTax
    ) {
    }

    private record RefundAllocation(
            PaymentMethod method,
            BigDecimal amount,
            String gatewayReference
    ) {
    }
}
