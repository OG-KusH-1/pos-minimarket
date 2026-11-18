package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.cash.CashSessionApi;
import cl.minimarket.pos.cash.CashSessionRef;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.PackInfo;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.ProductCatalogApi.TaxRate;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.inventory.LotAllocation;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.organization.TerminalApi;
import cl.minimarket.pos.sales.SaleCompletedEvent;
import cl.minimarket.pos.sales.application.pricing.DiscountKind;
import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import cl.minimarket.pos.sales.application.pricing.PricingModels.DiscountSpec;
import cl.minimarket.pos.sales.application.pricing.PricingModels.LineInput;
import cl.minimarket.pos.sales.application.pricing.PricingModels.PaymentInput;
import cl.minimarket.pos.sales.application.pricing.PricingModels.QuoteCommand;
import cl.minimarket.pos.sales.application.pricing.PricingModels.QuoteResult;
import cl.minimarket.pos.sales.application.pricing.PricingModels.QuotedLine;
import cl.minimarket.pos.sales.application.pricing.PricingModels.RequiredOverride;
import cl.minimarket.pos.sales.application.pricing.PricingService;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.CustomerInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.OverrideCandidate;
import cl.minimarket.pos.sales.payment.CardAuthorizationStatus;
import cl.minimarket.pos.sales.payment.domain.CardAuthorization;
import cl.minimarket.pos.sales.payment.domain.CardAuthorizationRepository;
import cl.minimarket.pos.security.AuthorizationRef;
import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.SupervisorOverrideApi;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Money;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.money.TaxBreakdownCalculator;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Caso de uso de creación de ventas en el POS ({@code POST /sales}, {@code API.md} F.2/F.4).
 *
 * <p>Ejecuta en una sola transacción ACID: verificación de terminal y turno abierto, cotización
 * autoritativa con {@link PricingService}, validación de edad y overrides, reconciliación de pagos
 * y captura de autorizaciones de tarjeta, descuento de stock (con resolución pack → base) vía
 * {@link StockApi}, persistencia inmutable de cabecera/líneas/impuestos/descuentos/pagos,
 * registro de auditoría y publicación de {@link SaleCompletedEvent}.
 */
@Service
public class SaleService {

    private final PricingService pricingService;
    private final ProductCatalogApi productCatalogApi;
    private final StockApi stockApi;
    private final CashSessionApi cashSessionApi;
    private final TerminalApi terminalApi;
    private final SupervisorOverrideApi supervisorOverrideApi;
    private final SequenceService sequenceService;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final ApplicationEventPublisher eventPublisher;
    private final CardAuthorizationRepository cardAuthorizationRepository;
    private final SaleRepository saleRepository;
    private final SaleLineRepository saleLineRepository;
    private final SaleLineTaxRepository saleLineTaxRepository;
    private final SaleDiscountRepository saleDiscountRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final SaleJdbcRepository jdbcRepository;
    private final SaleDetailAssembler detailAssembler;
    private final SaleLotAllocationWriter saleLotAllocationWriter;

    public SaleService(
            PricingService pricingService,
            ProductCatalogApi productCatalogApi,
            StockApi stockApi,
            CashSessionApi cashSessionApi,
            TerminalApi terminalApi,
            SupervisorOverrideApi supervisorOverrideApi,
            SequenceService sequenceService,
            AuditService auditService,
            BusinessClock businessClock,
            ApplicationEventPublisher eventPublisher,
            CardAuthorizationRepository cardAuthorizationRepository,
            SaleRepository saleRepository,
            SaleLineRepository saleLineRepository,
            SaleLineTaxRepository saleLineTaxRepository,
            SaleDiscountRepository saleDiscountRepository,
            SalePaymentRepository salePaymentRepository,
            SaleJdbcRepository jdbcRepository,
            SaleDetailAssembler detailAssembler,
            SaleLotAllocationWriter saleLotAllocationWriter
    ) {
        this.pricingService = pricingService;
        this.productCatalogApi = productCatalogApi;
        this.stockApi = stockApi;
        this.cashSessionApi = cashSessionApi;
        this.terminalApi = terminalApi;
        this.supervisorOverrideApi = supervisorOverrideApi;
        this.sequenceService = sequenceService;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.eventPublisher = eventPublisher;
        this.cardAuthorizationRepository = cardAuthorizationRepository;
        this.saleRepository = saleRepository;
        this.saleLineRepository = saleLineRepository;
        this.saleLineTaxRepository = saleLineTaxRepository;
        this.saleDiscountRepository = saleDiscountRepository;
        this.salePaymentRepository = salePaymentRepository;
        this.jdbcRepository = jdbcRepository;
        this.detailAssembler = detailAssembler;
        this.saleLotAllocationWriter = saleLotAllocationWriter;
    }

    /** Entrada de medio de pago para crear una venta. */
    public record PaymentCommand(
            PaymentMethod method,
            BigDecimal amount,
            BigDecimal tenderedAmount,
            Long cardAuthorizationId,
            String transferReference
    ) {
    }

    /** Comando completo para crear una venta. */
    public record CreateSaleCommand(
            List<LineInput> lines,
            DiscountSpec totalDiscount,
            Long customerId,
            List<PaymentCommand> payments,
            Boolean ageConfirmed,
            String emailTo,
            UUID idempotencyKey,
            String overrideTokenHeader
    ) {
    }

    /**
     * Registra una nueva venta de forma idempotente y atómica.
     */
    @Transactional
    public SaleDetail create(CreateSaleCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.idempotencyKey(), "idempotencyKey");

        StoreContext ctx = StoreContext.require();
        Long storeId = ctx.storeId();
        Long terminalId = requireTerminalId(ctx);
        Long userId = SaleSecuritySupport.requireCurrentUserId();

        terminalApi.requireActive(terminalId, storeId);
        CashSessionRef session = cashSessionApi.requireOpen(terminalId);

        validatePaymentsInputPhase1(command.payments());
        validateCustomerIfPresent(command.customerId());

        Optional<Sale> existingByKey = saleRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existingByKey.isPresent()) {
            return verifyAndReturnIdempotentSale(existingByKey.get(), storeId, session.id());
        }

        List<PaymentInput> pricingPayments = command.payments().stream()
                .map(p -> new PaymentInput(p.method(), p.amount(), p.tenderedAmount()))
                .toList();

        QuoteResult quote = pricingService.quote(new QuoteCommand(
                storeId,
                userId,
                SaleSecuritySupport.hasAuthority(Permissions.SALES_DISCOUNT_APPLY),
                command.lines(),
                command.totalDiscount(),
                command.customerId(),
                pricingPayments
        ));

        if (quote.requiresAgeConfirmation() && !Boolean.TRUE.equals(command.ageConfirmed())) {
            throw new BusinessException(
                    ErrorCode.AGE_CONFIRMATION_REQUIRED,
                    "La venta contiene productos restringidos y requiere confirmar la mayoría de edad del cliente."
            );
        }

        List<OverrideAssignment> overrideAssignments = pairRequiredOverrides(
                quote.requiredOverrides(),
                command.overrideTokenHeader()
        );

        ReconciledPayments reconciledPayments = reconcileAndValidatePayments(
                command.payments(),
                quote,
                storeId,
                terminalId,
                session.id()
        );

        Map<Long, ProductSnapshot> productsById = loadProductSnapshots(quote.lines());
        Map<Long, PackInfo> packsByProductId = loadPackInfos(productsById.values());

        Instant now = businessClock.now();
        String saleNumber = sequenceService.next(SequenceType.SALE, storeId);

        Sale sale = buildSaleHeader(
                command,
                saleNumber,
                storeId,
                terminalId,
                session,
                userId,
                quote,
                reconciledPayments,
                now
        );

        Sale savedSale;
        try {
            savedSale = saleRepository.saveAndFlush(sale);
        } catch (DataIntegrityViolationException ex) {
            Optional<Sale> concurrent = saleRepository.findByIdempotencyKey(command.idempotencyKey());
            if (concurrent.isPresent()) {
                return verifyAndReturnIdempotentSale(concurrent.get(), storeId, session.id());
            }
            throw ex;
        }

        ConsumedOverrides consumedOverrides = consumeOverrides(
                overrideAssignments,
                userId,
                savedSale.getId()
        );

        StockApplicationResult stockApplication = applyStockMovements(
                savedSale,
                userId,
                quote.lines(),
                productsById,
                packsByProductId
        );

        PersistedChildren children = persistLinesTaxesDiscountsAndPayments(
                savedSale,
                command,
                quote,
                productsById,
                packsByProductId,
                stockApplication.avgCostByStockProduct(),
                consumedOverrides,
                reconciledPayments,
                userId,
                now
        );

        saleLotAllocationWriter.persistAllocations(
                children.lines(),
                stockApplication.allocationsByStockProduct(),
                now
        );

        recordSaleAudits(
                savedSale,
                quote,
                productsById,
                consumedOverrides
        );

        publishSaleCompletedEvent(savedSale, children.lines());

        return detailAssembler.assemble(
                savedSale,
                children.lines(),
                children.lineTaxes(),
                children.discounts(),
                children.payments()
        );
    }

    private void validatePaymentsInputPhase1(List<PaymentCommand> payments) {
        if (payments == null || payments.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.PAYMENT_MISMATCH,
                    "Debe informar al menos un medio de pago."
            );
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (int i = 0; i < payments.size(); i++) {
            PaymentCommand payment = payments.get(i);
            if (payment.method() == PaymentMethod.CUSTOMER_CREDIT) {
                errors.add(new FieldErrorDetail(
                        "payments[" + i + "].method",
                        "VALIDATION_ERROR",
                        "El medio de pago CUSTOMER_CREDIT (fiado) no está habilitado en Fase 1."
                ));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El medio de pago CUSTOMER_CREDIT no está habilitado en esta fase.",
                    Map.of(),
                    errors
            );
        }
    }

    private void validateCustomerIfPresent(Long customerId) {
        if (customerId == null) {
            return;
        }
        CustomerInfo customer = jdbcRepository.findCustomerById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado."));
        if (!customer.active()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El cliente seleccionado se encuentra inactivo.",
                    Map.of(),
                    List.of(new FieldErrorDetail("customerId", "VALIDATION_ERROR", "Cliente inactivo."))
            );
        }
    }

    private SaleDetail verifyAndReturnIdempotentSale(Sale existing, Long storeId, Long cashSessionId) {
        if (!existing.getStoreId().equals(storeId) || !existing.getCashSessionId().equals(cashSessionId)) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "La clave de idempotencia ya fue utilizada con otro contexto."
            );
        }
        return detailAssembler.load(existing);
    }

    private List<OverrideAssignment> pairRequiredOverrides(
            List<RequiredOverride> requiredOverrides,
            String overrideTokenHeader
    ) {
        if (requiredOverrides == null || requiredOverrides.isEmpty()) {
            return List.of();
        }
        List<String> tokens = parseOverrideTokens(overrideTokenHeader);
        if (tokens.isEmpty()) {
            throw overrideRequiredException(requiredOverrides.getFirst(), requiredOverrides);
        }

        if (requiredOverrides.size() == 1 && tokens.size() == 1) {
            RequiredOverride req = requiredOverrides.getFirst();
            return List.of(new OverrideAssignment(
                    req,
                    tokens.getFirst(),
                    supervisorOverrideApi.canonicalContextHash(req.context())
            ));
        }

        Map<String, String> hashToRawToken = new LinkedHashMap<>();
        for (String rawToken : tokens) {
            hashToRawToken.putIfAbsent(sha256Hex(rawToken), rawToken);
        }
        Map<String, OverrideCandidate> candidates =
                jdbcRepository.findOverrideCandidatesByTokenHashes(hashToRawToken.keySet());

        List<OverrideAssignment> assignments = new ArrayList<>(requiredOverrides.size());
        Set<String> usedTokenHashes = new HashSet<>();

        for (RequiredOverride req : requiredOverrides) {
            String expectedHash = supervisorOverrideApi.canonicalContextHash(req.context());
            String matchedHash = null;
            for (Map.Entry<String, String> entry : hashToRawToken.entrySet()) {
                String tokenHash = entry.getKey();
                if (usedTokenHashes.contains(tokenHash)) {
                    continue;
                }
                OverrideCandidate candidate = candidates.get(tokenHash);
                if (candidate != null
                        && req.action().name().equals(candidate.action())
                        && expectedHash.equals(candidate.contextHash())) {
                    matchedHash = tokenHash;
                    break;
                }
            }
            if (matchedHash == null) {
                if (usedTokenHashes.size() == hashToRawToken.size()) {
                    throw overrideRequiredException(req, requiredOverrides);
                }
                throw new BusinessException(
                        ErrorCode.OVERRIDE_INVALID,
                        "Uno o más tokens de autorización son inválidos, expiraron o no coinciden con la venta."
                );
            }
            usedTokenHashes.add(matchedHash);
            assignments.add(new OverrideAssignment(req, hashToRawToken.get(matchedHash), expectedHash));
        }

        return assignments;
    }

    private static BusinessException overrideRequiredException(
            RequiredOverride primary,
            List<RequiredOverride> allRequired
    ) {
        return new BusinessException(
                ErrorCode.OVERRIDE_REQUIRED,
                "La venta supera los límites permitidos y requiere autorización de supervisor.",
                Map.of(
                        "action", primary.action().name(),
                        "context", primary.context(),
                        "requiredOverrides", allRequired
                )
        );
    }

    private ReconciledPayments reconcileAndValidatePayments(
            List<PaymentCommand> commands,
            QuoteResult quote,
            Long storeId,
            Long terminalId,
            Long cashSessionId
    ) {
        PaymentCommand cashCommand = null;
        List<ResolvedPayment> resolved = new ArrayList<>(commands.size());
        Money nonCashTotal = Money.ZERO;
        Set<Long> seenCardAuthIds = new HashSet<>();

        for (int i = 0; i < commands.size(); i++) {
            PaymentCommand cmd = commands.get(i);
            if (cmd.method() == PaymentMethod.CASH) {
                if (cashCommand != null) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Solo se admite un pago en efectivo por venta."
                    );
                }
                cashCommand = cmd;
                continue;
            }

            if (cmd.amount() == null || Rounding.clp(cmd.amount()).compareTo(BigDecimal.ONE) < 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Los pagos distintos de efectivo deben indicar un monto mayor o igual a $1.",
                        Map.of(),
                        List.of(new FieldErrorDetail("payments[" + i + "].amount", "VALIDATION_ERROR", "Monto obligatorio >= 1."))
                );
            }
            Money amount = Money.of(cmd.amount());
            nonCashTotal = nonCashTotal.plus(amount);

            if (cmd.method() == PaymentMethod.TRANSFER) {
                String ref = SaleSecuritySupport.trimToNull(cmd.transferReference());
                if (ref == null) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "El pago con transferencia debe indicar transferReference.",
                            Map.of(),
                            List.of(new FieldErrorDetail(
                                    "payments[" + i + "].transferReference",
                                    "NOT_BLANK",
                                    "La referencia de transferencia es obligatoria."
                            ))
                    );
                }
                resolved.add(new ResolvedPayment(cmd.method(), amount, null, BigDecimal.ZERO, null, ref));
            } else {
                // DEBIT o CREDIT
                if (cmd.cardAuthorizationId() == null) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Los pagos con tarjeta deben indicar cardAuthorizationId.",
                            Map.of(),
                            List.of(new FieldErrorDetail(
                                    "payments[" + i + "].cardAuthorizationId",
                                    "NOT_NULL",
                                    "Debe informar la autorización de tarjeta."
                            ))
                    );
                }
                if (!seenCardAuthIds.add(cmd.cardAuthorizationId())) {
                    throw new BusinessException(
                            ErrorCode.INVALID_STATE_TRANSITION,
                            "No se puede reutilizar la misma autorización de tarjeta dentro de la misma venta."
                    );
                }
                CardAuthorization auth = cardAuthorizationRepository
                        .findByIdAndStoreId(cmd.cardAuthorizationId(), storeId)
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.NOT_FOUND,
                                "Autorización de tarjeta no encontrada."
                        ));

                if (auth.getStatus() != CardAuthorizationStatus.APPROVED || auth.getSaleId() != null) {
                    throw new BusinessException(
                            ErrorCode.INVALID_STATE_TRANSITION,
                            "La autorización de tarjeta ya fue utilizada o anulada."
                    );
                }
                if (!auth.getTerminalId().equals(terminalId) || !auth.getCashSessionId().equals(cashSessionId)) {
                    throw new BusinessException(
                            ErrorCode.INVALID_STATE_TRANSITION,
                            "La autorización de tarjeta no corresponde a la terminal o turno de caja activo."
                    );
                }
                if (!auth.getMethod().name().equals(cmd.method().name())) {
                    throw new BusinessException(
                            ErrorCode.PAYMENT_MISMATCH,
                            "El medio de pago de la autorización de tarjeta no coincide con el pago informado."
                    );
                }
                if (Money.of(auth.getAmount()).compareTo(amount) != 0) {
                    throw new BusinessException(
                            ErrorCode.PAYMENT_MISMATCH,
                            "El monto de la autorización de tarjeta no coincide con el monto del pago."
                    );
                }
                resolved.add(new ResolvedPayment(cmd.method(), amount, null, BigDecimal.ZERO, auth, null));
            }
        }

        Money totalGross = Money.of(quote.totalGross());
        Money roundingAdjustment = Money.of(quote.roundingAdjustment());
        Money totalPayable = Money.of(quote.totalPayable());
        Money paidTotal;
        Money changeTotal;

        if (cashCommand == null) {
            if (nonCashTotal.compareTo(totalPayable) != 0) {
                throw new BusinessException(
                        ErrorCode.PAYMENT_MISMATCH,
                        "La suma de los pagos (" + nonCashTotal.amount() + ") no coincide con el total a pagar (" + totalPayable.amount() + ")."
                );
            }
            paidTotal = nonCashTotal;
            changeTotal = Money.ZERO;
        } else {
            Money cashDue = totalGross.minus(nonCashTotal);
            if (cashDue.signum() <= 0) {
                throw new BusinessException(
                        ErrorCode.PAYMENT_MISMATCH,
                        "Los pagos distintos de efectivo ya cubren o superan el total de la venta."
                );
            }
            Money cashApplied = cashDue.plus(roundingAdjustment);
            if (cashApplied.signum() <= 0) {
                throw new BusinessException(
                        ErrorCode.PAYMENT_MISMATCH,
                        "El saldo en efectivo resultante tras redondeo debe ser mayor que 0."
                );
            }
            if (cashCommand.amount() != null && Money.of(cashCommand.amount()).compareTo(cashApplied) != 0) {
                throw new BusinessException(
                        ErrorCode.PAYMENT_MISMATCH,
                        "El monto informado para efectivo no coincide con el saldo adeudado redondeado."
                );
            }
            if (cashCommand.tenderedAmount() == null) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Debe informar el monto entregado en efectivo (tenderedAmount).",
                        Map.of(),
                        List.of(new FieldErrorDetail("payments.tenderedAmount", "NOT_NULL", "Es obligatorio para efectivo."))
                );
            }
            Money tendered = Money.of(cashCommand.tenderedAmount());
            if (tendered.compareTo(cashApplied) < 0) {
                throw new BusinessException(
                        ErrorCode.PAYMENT_MISMATCH,
                        "El efectivo entregado (" + tendered.amount() + ") es menor al monto a pagar (" + cashApplied.amount() + ")."
                );
            }
            Money cashChange = tendered.minus(cashApplied);
            resolved.add(new ResolvedPayment(
                    PaymentMethod.CASH,
                    cashApplied,
                    tendered.amount(),
                    cashChange.amount(),
                    null,
                    null
            ));
            paidTotal = nonCashTotal.plus(tendered);
            changeTotal = cashChange;
        }

        if (paidTotal.minus(changeTotal).compareTo(totalPayable) != 0) {
            throw new BusinessException(
                    ErrorCode.PAYMENT_MISMATCH,
                    "Los montos de pago y vuelto no cuadran con el total a pagar."
            );
        }

        return new ReconciledPayments(resolved, paidTotal.amount(), changeTotal.amount());
    }

    private Map<Long, ProductSnapshot> loadProductSnapshots(List<QuotedLine> lines) {
        Set<Long> productIds = new HashSet<>(lines.size());
        for (QuotedLine line : lines) {
            productIds.add(line.productId());
        }
        List<ProductSnapshot> snapshots = productCatalogApi.findByIds(productIds);
        Map<Long, ProductSnapshot> byId = new HashMap<>(snapshots.size());
        for (ProductSnapshot snapshot : snapshots) {
            byId.put(snapshot.id(), snapshot);
        }
        return byId;
    }

    private Map<Long, PackInfo> loadPackInfos(Iterable<ProductSnapshot> snapshots) {
        Map<Long, PackInfo> packs = new HashMap<>();
        for (ProductSnapshot snapshot : snapshots) {
            if (snapshot.saleType() == SaleType.PACK) {
                PackInfo info = productCatalogApi.packOf(snapshot.id())
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.INTERNAL_ERROR,
                                "El producto pack " + snapshot.sku() + " no tiene definición de producto base."
                        ));
                packs.put(snapshot.id(), info);
            }
        }
        return packs;
    }

    private Sale buildSaleHeader(
            CreateSaleCommand command,
            String saleNumber,
            Long storeId,
            Long terminalId,
            CashSessionRef session,
            Long userId,
            QuoteResult quote,
            ReconciledPayments reconciledPayments,
            Instant now
    ) {
        Sale sale = new Sale();
        sale.setNumber(saleNumber);
        sale.setStoreId(storeId);
        sale.setTerminalId(terminalId);
        sale.setCashSessionId(session.id());
        sale.setCashierUserId(userId);
        sale.setCustomerId(command.customerId());
        sale.setStatus(SaleStatus.COMPLETED);
        sale.setSoldAt(now);
        sale.setBusinessDate(session.businessDate());
        sale.setIdempotencyKey(command.idempotencyKey());
        sale.setSubtotalGross(quote.subtotalGross());
        sale.setDiscountTotal(quote.discountTotal());
        sale.setTotalGross(quote.totalGross());
        sale.setNetTotal(quote.netTotal());
        sale.setTaxTotal(quote.taxTotal());
        sale.setExemptTotal(quote.exemptTotal());
        sale.setRoundingAdjustment(quote.roundingAdjustment());
        sale.setTotalPayable(quote.totalPayable());
        sale.setPaidTotal(reconciledPayments.paidTotal());
        sale.setChangeTotal(reconciledPayments.changeTotal());
        sale.setAgeConfirmed(quote.requiresAgeConfirmation() && Boolean.TRUE.equals(command.ageConfirmed()));
        sale.setEmailTo(SaleSecuritySupport.trimToNull(command.emailTo()));
        return sale;
    }

    private ConsumedOverrides consumeOverrides(
            List<OverrideAssignment> assignments,
            Long userId,
            Long saleId
    ) {
        Map<Integer, ConsumedOverrideDetail> lineOverrides = new HashMap<>();
        ConsumedOverrideDetail totalOverride = null;
        String saleIdText = String.valueOf(saleId);

        for (OverrideAssignment assignment : assignments) {
            RequiredOverride req = assignment.required();
            AuthorizationRef authRef = supervisorOverrideApi.consume(
                    assignment.rawToken(),
                    req.action(),
                    assignment.contextHash(),
                    userId,
                    "sale",
                    saleIdText
            );
            ConsumedOverrideDetail detail = new ConsumedOverrideDetail(
                    authRef.authorizationId(),
                    req.action(),
                    req.context()
            );
            if (req.action() == OverrideAction.DISCOUNT_ITEM_OVER_LIMIT) {
                Integer lineIndex = ((Number) req.context().get("lineIndex")).intValue();
                lineOverrides.put(lineIndex, detail);
            } else if (req.action() == OverrideAction.DISCOUNT_TOTAL_OVER_LIMIT) {
                totalOverride = detail;
            }
        }

        return new ConsumedOverrides(lineOverrides, totalOverride);
    }

    private StockApplicationResult applyStockMovements(
            Sale sale,
            Long userId,
            List<QuotedLine> quotedLines,
            Map<Long, ProductSnapshot> productsById,
            Map<Long, PackInfo> packsByProductId
    ) {
        List<StockLine> stockLines = new ArrayList<>(quotedLines.size());
        for (QuotedLine ql : quotedLines) {
            ProductSnapshot product = productsById.get(ql.productId());
            Long stockProductId;
            BigDecimal stockQuantity;
            if (product.saleType() == SaleType.PACK) {
                PackInfo pack = packsByProductId.get(product.id());
                stockProductId = pack.baseProductId();
                stockQuantity = Rounding.quantity(ql.quantity().multiply(BigDecimal.valueOf(pack.factor())));
            } else {
                stockProductId = product.id();
                stockQuantity = Rounding.quantity(ql.quantity());
            }
            stockLines.add(StockLine.of(stockProductId, stockQuantity));
        }

        List<StockMovementResult> results = stockApi.apply(new StockCommand(
                sale.getStoreId(),
                StockMovementType.SALE,
                StockSourceType.SALE,
                sale.getId(),
                userId,
                "Venta " + sale.getNumber(),
                stockLines
        ));

        Map<Long, BigDecimal> unitCosts = new HashMap<>(results.size());
        Map<Long, List<LotAllocation>> allocationsByProduct = new HashMap<>(results.size());
        for (StockMovementResult result : results) {
            unitCosts.put(result.productId(), result.unitCost());
            if (result.allocations() != null && !result.allocations().isEmpty()) {
                allocationsByProduct.put(result.productId(), result.allocations());
            }
        }
        return new StockApplicationResult(unitCosts, allocationsByProduct);
    }

    private record StockApplicationResult(
            Map<Long, BigDecimal> avgCostByStockProduct,
            Map<Long, List<LotAllocation>> allocationsByStockProduct
    ) {
    }

    private PersistedChildren persistLinesTaxesDiscountsAndPayments(
            Sale savedSale,
            CreateSaleCommand command,
            QuoteResult quote,
            Map<Long, ProductSnapshot> productsById,
            Map<Long, PackInfo> packsByProductId,
            Map<Long, BigDecimal> avgCostByStockProduct,
            ConsumedOverrides consumedOverrides,
            ReconciledPayments reconciledPayments,
            Long userId,
            Instant now
    ) {
        List<SaleLine> persistedLines = new ArrayList<>(quote.lines().size());
        List<SaleLineTax> persistedTaxes = new ArrayList<>();
        List<SaleDiscount> persistedDiscounts = new ArrayList<>();

        for (int i = 0; i < quote.lines().size(); i++) {
            QuotedLine ql = quote.lines().get(i);
            LineInput inputLine = command.lines().get(i);
            ProductSnapshot product = productsById.get(ql.productId());

            Long stockProductId;
            BigDecimal stockQuantity;
            int packFactor = 1;
            if (product.saleType() == SaleType.PACK) {
                PackInfo pack = packsByProductId.get(product.id());
                stockProductId = pack.baseProductId();
                packFactor = pack.factor();
                stockQuantity = Rounding.quantity(ql.quantity().multiply(BigDecimal.valueOf(packFactor)));
            } else {
                stockProductId = product.id();
                stockQuantity = Rounding.quantity(ql.quantity());
            }

            BigDecimal baseUnitCost = avgCostByStockProduct.getOrDefault(
                    stockProductId,
                    product.avgCost() != null ? product.avgCost() : BigDecimal.ZERO
            );
            BigDecimal lineUnitCost = product.saleType() == SaleType.PACK
                    ? Rounding.unitCost(baseUnitCost.multiply(BigDecimal.valueOf(packFactor)))
                    : Rounding.unitCost(baseUnitCost);
            BigDecimal lineCost = Rounding.costTotal(baseUnitCost.multiply(stockQuantity));

            BigDecimal totalLineDiscount = ql.promotionDiscount()
                    .add(ql.manualDiscount())
                    .add(ql.proratedDiscount());

            SaleLine saleLine = new SaleLine();
            saleLine.setSaleId(savedSale.getId());
            saleLine.setLineNumber(ql.lineNumber());
            saleLine.setStoreId(savedSale.getStoreId());
            saleLine.setBusinessDate(savedSale.getBusinessDate());
            saleLine.setProductId(product.id());
            saleLine.setSku(ql.sku());
            saleLine.setProductName(ql.name());
            saleLine.setScannedCode(SaleSecuritySupport.trimToNull(inputLine.scannedCode()));
            saleLine.setSaleType(product.saleType());
            saleLine.setQuantity(Rounding.quantity(ql.quantity()));
            saleLine.setStockProductId(stockProductId);
            saleLine.setStockQuantity(stockQuantity);
            saleLine.setUnitPriceGross(ql.unitPriceGross());
            saleLine.setLineSubtotalGross(ql.lineSubtotalGross());
            saleLine.setDiscountAmount(totalLineDiscount);
            saleLine.setLineTotalGross(ql.lineTotalGross());
            saleLine.setLineNet(ql.lineNet());
            saleLine.setLineTax(ql.lineTax());
            saleLine.setLineExempt(ql.lineExempt());
            saleLine.setUnitCost(lineUnitCost);
            saleLine.setLineCost(lineCost);
            saleLine.setRestricted(ql.restricted());
            saleLine.setReturnedQuantity(Rounding.quantity(BigDecimal.ZERO));
            saleLine.setCreatedAt(now);

            SaleLine savedLine = saleLineRepository.save(saleLine);
            persistedLines.add(savedLine);

            List<TaxRate> productTaxes = productCatalogApi.taxesFor(product.id());
            if (!productTaxes.isEmpty()) {
                Map<Long, TaxRate> taxInfoById = new HashMap<>(productTaxes.size());
                List<TaxBreakdownCalculator.TaxRate> calcRates = new ArrayList<>(productTaxes.size());
                for (TaxRate taxRate : productTaxes) {
                    taxInfoById.put(taxRate.taxId(), taxRate);
                    calcRates.add(new TaxBreakdownCalculator.TaxRate(taxRate.taxId(), taxRate.rate()));
                }
                TaxBreakdownCalculator.Breakdown breakdown = TaxBreakdownCalculator.calculate(
                        Money.of(ql.lineTotalGross()),
                        calcRates,
                        product.vatExempt()
                );
                for (TaxBreakdownCalculator.TaxAmount taxAmount : breakdown.taxes()) {
                    TaxRate rateMeta = taxInfoById.get(taxAmount.taxId());
                    SaleLineTax lineTax = new SaleLineTax();
                    lineTax.setSaleLineId(savedLine.getId());
                    lineTax.setTaxId(taxAmount.taxId());
                    lineTax.setTaxCode(rateMeta.code());
                    lineTax.setRate(Rounding.percentage(rateMeta.rate()));
                    lineTax.setBaseAmount(breakdown.net().amount());
                    lineTax.setTaxAmount(taxAmount.amount().amount());
                    lineTax.setCreatedAt(now);
                    persistedTaxes.add(saleLineTaxRepository.save(lineTax));
                }
            }

            if (ql.promotionDiscount().signum() > 0 && ql.appliedPromotion() != null) {
                SaleDiscount promoDiscount = new SaleDiscount();
                promoDiscount.setSaleId(savedSale.getId());
                promoDiscount.setSaleLineId(savedLine.getId());
                promoDiscount.setSource(SaleDiscountSource.PROMOTION);
                promoDiscount.setPromotionId(ql.appliedPromotion().id());
                promoDiscount.setDiscountKind(DiscountKind.AMOUNT);
                promoDiscount.setValue(Rounding.percentage(ql.promotionDiscount()));
                promoDiscount.setAmount(ql.promotionDiscount());
                promoDiscount.setReason(ql.appliedPromotion().name());
                promoDiscount.setAppliedBy(userId);
                promoDiscount.setAuthorizationId(null);
                promoDiscount.setCreatedAt(now);
                persistedDiscounts.add(saleDiscountRepository.save(promoDiscount));
            }

            if (ql.manualDiscount().signum() > 0 && inputLine.manualDiscount() != null) {
                ConsumedOverrideDetail lineOverride = consumedOverrides.lineOverrides().get(i);
                SaleDiscount manualDiscount = new SaleDiscount();
                manualDiscount.setSaleId(savedSale.getId());
                manualDiscount.setSaleLineId(savedLine.getId());
                manualDiscount.setSource(SaleDiscountSource.MANUAL);
                manualDiscount.setPromotionId(null);
                manualDiscount.setDiscountKind(inputLine.manualDiscount().kind());
                manualDiscount.setValue(Rounding.percentage(inputLine.manualDiscount().value()));
                manualDiscount.setAmount(ql.manualDiscount());
                manualDiscount.setReason(SaleSecuritySupport.trimToNull(inputLine.manualDiscount().reason()));
                manualDiscount.setAppliedBy(userId);
                manualDiscount.setAuthorizationId(lineOverride != null ? lineOverride.authorizationId() : null);
                manualDiscount.setCreatedAt(now);
                persistedDiscounts.add(saleDiscountRepository.save(manualDiscount));
            }
        }

        if (quote.totalDiscountAmount().signum() > 0 && command.totalDiscount() != null) {
            ConsumedOverrideDetail totalOverride = consumedOverrides.totalOverride();
            SaleDiscount totalDiscount = new SaleDiscount();
            totalDiscount.setSaleId(savedSale.getId());
            totalDiscount.setSaleLineId(null);
            totalDiscount.setSource(SaleDiscountSource.MANUAL);
            totalDiscount.setPromotionId(null);
            totalDiscount.setDiscountKind(command.totalDiscount().kind());
            totalDiscount.setValue(Rounding.percentage(command.totalDiscount().value()));
            totalDiscount.setAmount(quote.totalDiscountAmount());
            totalDiscount.setReason(SaleSecuritySupport.trimToNull(command.totalDiscount().reason()));
            totalDiscount.setAppliedBy(userId);
            totalDiscount.setAuthorizationId(totalOverride != null ? totalOverride.authorizationId() : null);
            totalDiscount.setCreatedAt(now);
            persistedDiscounts.add(saleDiscountRepository.save(totalDiscount));
        }

        List<SalePayment> persistedPayments = new ArrayList<>(reconciledPayments.payments().size());
        for (ResolvedPayment rp : reconciledPayments.payments()) {
            SalePayment sp = new SalePayment();
            sp.setSaleId(savedSale.getId());
            sp.setMethod(rp.method());
            sp.setAmount(rp.amount().amount());
            sp.setTenderedAmount(rp.tenderedAmount());
            sp.setChangeAmount(rp.changeAmount());
            sp.setStatus(SalePaymentStatus.APPROVED);
            sp.setTransferReference(rp.transferReference());
            if (rp.cardAuthorization() != null) {
                CardAuthorization auth = rp.cardAuthorization();
                sp.setGatewayReference(auth.getGatewayReference());
                sp.setAuthorizationCode(auth.getAuthorizationCode());
                sp.setCardBrand(auth.getCardBrand());
                sp.setCardLast4(auth.getCardLast4());
                sp.setCardAuthorizationId(auth.getId());
                auth.markCaptured(savedSale.getId());
                cardAuthorizationRepository.save(auth);
            }
            sp.setCreatedAt(now);
            persistedPayments.add(salePaymentRepository.save(sp));
        }

        return new PersistedChildren(persistedLines, persistedTaxes, persistedDiscounts, persistedPayments);
    }

    private void recordSaleAudits(
            Sale sale,
            QuoteResult quote,
            Map<Long, ProductSnapshot> productsById,
            ConsumedOverrides consumedOverrides
    ) {
        String saleIdText = String.valueOf(sale.getId());

        List<ConsumedOverrideDetail> discountOverrides = new ArrayList<>(consumedOverrides.lineOverrides().values());
        if (consumedOverrides.totalOverride() != null) {
            discountOverrides.add(consumedOverrides.totalOverride());
        }
        for (ConsumedOverrideDetail detail : discountOverrides) {
            auditService.record(new AuditEvent(
                    "SALE_DISCOUNT_OVER_LIMIT",
                    "sale",
                    saleIdText,
                    null,
                    Map.of(
                            "saleNumber", sale.getNumber(),
                            "overrideAction", detail.action().name(),
                            "context", detail.context()
                    ),
                    null,
                    detail.authorizationId()
            ));
        }

        if (sale.isAgeConfirmed()) {
            List<Map<String, Object>> restrictedProducts = quote.lines().stream()
                    .filter(QuotedLine::restricted)
                    .map(ql -> {
                        ProductSnapshot product = productsById.get(ql.productId());
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("productId", ql.productId());
                        item.put("sku", ql.sku());
                        item.put("name", ql.name());
                        item.put("restrictedType", product != null && product.restrictedType() != null
                                ? product.restrictedType().name()
                                : null);
                        return item;
                    })
                    .toList();

            auditService.record(new AuditEvent(
                    "SALE_RESTRICTED_AGE_CONFIRMED",
                    "sale",
                    saleIdText,
                    null,
                    Map.of(
                            "saleNumber", sale.getNumber(),
                            "ageConfirmed", true,
                            "restrictedProducts", restrictedProducts
                    ),
                    "Confirmación de mayoría de edad en POS",
                    null
            ));
        }
    }

    private void publishSaleCompletedEvent(Sale sale, List<SaleLine> lines) {
        List<SaleCompletedEvent.Line> eventLines = lines.stream()
                .map(line -> new SaleCompletedEvent.Line(
                        line.getLineNumber(),
                        line.getProductId(),
                        line.getSku(),
                        line.getProductName(),
                        line.getQuantity(),
                        line.getUnitPriceGross(),
                        line.getLineTotalGross(),
                        line.getLineNet(),
                        line.getLineTax(),
                        line.getLineExempt()
                ))
                .toList();

        eventPublisher.publishEvent(new SaleCompletedEvent(
                sale.getId(),
                sale.getNumber(),
                sale.getStoreId(),
                sale.getTerminalId(),
                sale.getCashSessionId(),
                sale.getCashierUserId(),
                sale.getCustomerId(),
                sale.getSoldAt(),
                sale.getBusinessDate(),
                sale.getSubtotalGross(),
                sale.getDiscountTotal(),
                sale.getTotalGross(),
                sale.getNetTotal(),
                sale.getTaxTotal(),
                sale.getExemptTotal(),
                sale.getRoundingAdjustment(),
                sale.getTotalPayable(),
                eventLines
        ));
    }

    private static List<String> parseOverrideTokens(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return List.of();
        }
        String[] parts = headerValue.split(",");
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo SHA-256 no disponible", e);
        }
    }

    private static Long requireTerminalId(StoreContext ctx) {
        if (ctx.terminalId() == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Falta el header X-Terminal-Id.",
                    Map.of(),
                    List.of(new FieldErrorDetail("X-Terminal-Id", "VALIDATION_ERROR", "Es obligatorio."))
            );
        }
        return ctx.terminalId();
    }

    private record OverrideAssignment(
            RequiredOverride required,
            String rawToken,
            String contextHash
    ) {
    }

    private record ConsumedOverrideDetail(
            Long authorizationId,
            OverrideAction action,
            Map<String, Object> context
    ) {
    }

    private record ConsumedOverrides(
            Map<Integer, ConsumedOverrideDetail> lineOverrides,
            ConsumedOverrideDetail totalOverride
    ) {
    }

    private record ResolvedPayment(
            PaymentMethod method,
            Money amount,
            BigDecimal tenderedAmount,
            BigDecimal changeAmount,
            CardAuthorization cardAuthorization,
            String transferReference
    ) {
    }

    private record ReconciledPayments(
            List<ResolvedPayment> payments,
            BigDecimal paidTotal,
            BigDecimal changeTotal
    ) {
    }

    private record PersistedChildren(
            List<SaleLine> lines,
            List<SaleLineTax> lineTaxes,
            List<SaleDiscount> discounts,
            List<SalePayment> payments
    ) {
    }
}
