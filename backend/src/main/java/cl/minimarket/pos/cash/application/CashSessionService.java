package cl.minimarket.pos.cash.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.cash.CashFlowContributor;
import cl.minimarket.pos.cash.CashMovementType;
import cl.minimarket.pos.cash.CashSessionApi;
import cl.minimarket.pos.cash.CashSessionMetricsContributor;
import cl.minimarket.pos.cash.CashSessionMetricsContributor.SessionMetricsSnapshot;
import cl.minimarket.pos.cash.CashSessionRef;
import cl.minimarket.pos.cash.CashSessionStatus;
import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.cash.domain.CashExpectedCalculator;
import cl.minimarket.pos.cash.domain.CashLookupJdbcRepository;
import cl.minimarket.pos.cash.domain.CashLookupJdbcRepository.CompanyRefInfo;
import cl.minimarket.pos.cash.domain.CashLookupJdbcRepository.TerminalRefInfo;
import cl.minimarket.pos.cash.domain.CashLookupJdbcRepository.UserInfo;
import cl.minimarket.pos.cash.domain.CashMovement;
import cl.minimarket.pos.cash.domain.CashMovementRepository;
import cl.minimarket.pos.cash.domain.CashSession;
import cl.minimarket.pos.cash.domain.CashSessionCount;
import cl.minimarket.pos.cash.domain.CashSessionCountRepository;
import cl.minimarket.pos.cash.domain.CashSessionRepository;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.organization.TerminalApi;
import cl.minimarket.pos.security.AuthorizationRef;
import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.RoleLimit;
import cl.minimarket.pos.security.RoleLimitApi;
import cl.minimarket.pos.security.RoleLimitCode;
import cl.minimarket.pos.security.SupervisorOverrideApi;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.export.PdfRenderer;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import jakarta.persistence.criteria.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Casos de uso del módulo de caja ({@code cash}) e implementación de {@link CashSessionApi}.
 *
 * <p>Gestiona apertura y consulta de turnos (RN-09, RN-10), ingresos/retiros de efectivo con
 * límite por rol y override (RN-24), resumen con {@link CashFlowContributor} (B.8), cierre con
 * arqueo por medio de pago, aprobación de diferencias con segregación de funciones (RN-23) y
 * comprobante de cierre PDF/JSON.
 */
@Service
public class CashSessionService implements CashSessionApi {

    private static final String SETTING_DIFFERENCE_TOLERANCE = "cash.difference_tolerance";
    private static final Locale CL_LOCALE = Locale.of("es", "CL");
    private static final DateTimeFormatter PDF_DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final DateTimeFormatter PDF_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final CashSessionRepository sessionRepository;
    private final CashMovementRepository movementRepository;
    private final CashSessionCountRepository countRepository;
    private final CashLookupJdbcRepository lookupRepository;
    private final List<CashFlowContributor> contributors;
    private final List<CashSessionMetricsContributor> metricsContributors;
    private final StoreApi storeApi;
    private final TerminalApi terminalApi;
    private final SettingsApi settingsApi;
    private final RoleLimitApi roleLimitApi;
    private final SupervisorOverrideApi supervisorOverrideApi;
    private final SequenceService sequenceService;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final PdfRenderer pdfRenderer;
    private final ObjectMapper objectMapper;

    public CashSessionService(
            CashSessionRepository sessionRepository,
            CashMovementRepository movementRepository,
            CashSessionCountRepository countRepository,
            CashLookupJdbcRepository lookupRepository,
            List<CashFlowContributor> contributors,
            List<CashSessionMetricsContributor> metricsContributors,
            StoreApi storeApi,
            TerminalApi terminalApi,
            SettingsApi settingsApi,
            RoleLimitApi roleLimitApi,
            SupervisorOverrideApi supervisorOverrideApi,
            SequenceService sequenceService,
            AuditService auditService,
            BusinessClock businessClock,
            PdfRenderer pdfRenderer,
            ObjectMapper objectMapper
    ) {
        this.sessionRepository = sessionRepository;
        this.movementRepository = movementRepository;
        this.countRepository = countRepository;
        this.lookupRepository = lookupRepository;
        this.contributors = contributors != null ? contributors : List.of();
        this.metricsContributors = metricsContributors != null ? metricsContributors : List.of();
        this.storeApi = storeApi;
        this.terminalApi = terminalApi;
        this.settingsApi = settingsApi;
        this.roleLimitApi = roleLimitApi;
        this.supervisorOverrideApi = supervisorOverrideApi;
        this.sequenceService = sequenceService;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.pdfRenderer = pdfRenderer;
        this.objectMapper = objectMapper;
    }

    /** Entrada de arqueo por medio de pago al cerrar un turno. */
    public record CloseCountCommand(
            PaymentMethod paymentMethod,
            BigDecimal countedAmount,
            Map<Integer, Integer> denominationDetail,
            String notes
    ) {
    }

    /** Vista completa de un turno de caja para la capa web. */
    public record SessionDetail(
            CashSession session,
            StoreInfo store,
            TerminalRefInfo terminal,
            UserInfo cashier,
            UserInfo closedBy,
            UserInfo differenceApprovedBy,
            boolean requiresDifferenceApproval,
            List<CashSessionCount> counts
    ) {
    }

    /** Vista enriquecida de un movimiento de efectivo. */
    public record MovementDetail(
            CashMovement movement,
            String username
    ) {
    }

    /** Línea de resumen por medio de pago. */
    public record MethodSummaryDetail(
            PaymentMethod method,
            BigDecimal sales,
            BigDecimal refunds,
            BigDecimal creditPayments,
            BigDecimal expected
    ) {
    }

    /** Resumen financiero completo de un turno. */
    public record SummaryResult(
            Long sessionId,
            BigDecimal openingAmount,
            BigDecimal cashIn,
            BigDecimal cashOut,
            List<MethodSummaryDetail> byMethod,
            BigDecimal expectedCash,
            int salesCount,
            int voidCount,
            int returnCount
    ) {
    }

    /** Datos completos para el comprobante de cierre (JSON o PDF). */
    public record ClosingReceiptResult(
            SessionDetail session,
            SummaryResult summary,
            CompanyRefInfo company,
            StoreInfo store,
            Instant printedAt
    ) {
    }

    @Override
    @Transactional(readOnly = true)
    public CashSessionRef requireOpen(Long terminalId) {
        if (terminalId == null) {
            throw new BusinessException(ErrorCode.CASH_SESSION_NOT_OPEN, "No hay una terminal activa.");
        }
        CashSession session = sessionRepository.findByTerminalIdAndStatus(terminalId, CashSessionStatus.OPEN)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CASH_SESSION_NOT_OPEN,
                        "No existe un turno de caja abierto en esta terminal."
                ));
        return toRef(session);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOpen(Long cashSessionId) {
        if (cashSessionId == null) {
            return false;
        }
        return sessionRepository.existsByIdAndStatus(cashSessionId, CashSessionStatus.OPEN);
    }

    /**
     * Abre un nuevo turno de caja en la terminal activa del {@link StoreContext} (HU-CAJ-01, RN-10).
     */
    @Transactional
    public SessionDetail open(BigDecimal openingAmount) {
        StoreContext ctx = StoreContext.require();
        Long storeId = ctx.storeId();
        Long terminalId = requireTerminalId(ctx);
        Long cashierUserId = requireCurrentUserId();

        terminalApi.requireActive(terminalId, storeId);

        if (sessionRepository.existsByTerminalIdAndStatus(terminalId, CashSessionStatus.OPEN)) {
            throw new BusinessException(
                    ErrorCode.CASH_SESSION_ALREADY_OPEN,
                    "La terminal ya tiene un turno de caja abierto."
            );
        }
        if (sessionRepository.existsByCashierUserIdAndStatus(cashierUserId, CashSessionStatus.OPEN)) {
            throw new BusinessException(
                    ErrorCode.CASH_SESSION_ALREADY_OPEN,
                    "El cajero ya tiene un turno de caja abierto."
            );
        }

        BigDecimal normalizedOpening = Rounding.clp(Objects.requireNonNull(openingAmount, "openingAmount"));
        if (normalizedOpening.signum() < 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El monto de apertura no puede ser negativo.",
                    Map.of(),
                    List.of(new FieldErrorDetail("openingAmount", "VALIDATION_ERROR", "Debe ser mayor o igual a 0."))
            );
        }

        String number = sequenceService.next(SequenceType.CASH_SESSION, storeId);
        Instant now = businessClock.now();

        CashSession session = new CashSession();
        session.setNumber(number);
        session.setStoreId(storeId);
        session.setTerminalId(terminalId);
        session.setCashierUserId(cashierUserId);
        session.setStatus(CashSessionStatus.OPEN);
        session.setBusinessDate(businessClock.businessDate(now));
        session.setOpenedAt(now);
        session.setOpeningAmount(normalizedOpening);

        CashSession saved;
        try {
            saved = sessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(
                    ErrorCode.CASH_SESSION_ALREADY_OPEN,
                    "Ya existe un turno de caja abierto para la terminal o el cajero."
            );
        }

        auditService.record(new AuditEvent(
                "CASH_SESSION_OPENED",
                "cash_session",
                String.valueOf(saved.getId()),
                null,
                Map.of(
                        "number", saved.getNumber(),
                        "terminalId", terminalId,
                        "cashierUserId", cashierUserId,
                        "openingAmount", saved.getOpeningAmount()
                ),
                null,
                null
        ));

        return toDetail(saved, List.of());
    }

    /**
     * Obtiene el turno actualmente abierto en la terminal del contexto, si existe.
     */
    @Transactional(readOnly = true)
    public Optional<SessionDetail> findCurrent() {
        StoreContext ctx = StoreContext.require();
        Long terminalId = requireTerminalId(ctx);
        return sessionRepository.findByTerminalIdAndStatus(terminalId, CashSessionStatus.OPEN)
                .filter(session -> session.getStoreId().equals(ctx.storeId()))
                .map(session -> toDetail(session, List.of()));
    }

    /**
     * Lista el historial de turnos del local activo aplicando filtros y alcance P (HU-CAJ-06).
     */
    @Transactional(readOnly = true)
    public Page<SessionDetail> list(CashSessionFilter filter, Pageable pageable) {
        Long storeId = StoreContext.require().storeId();
        Long currentUserId = requireCurrentUserId();
        boolean fullScope = hasFullReadScope();

        Long effectiveCashierId = fullScope ? filter.cashierId() : currentUserId;

        Specification<CashSession> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("storeId"), storeId));
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("businessDate"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("businessDate"), filter.to()));
            }
            if (effectiveCashierId != null) {
                predicates.add(cb.equal(root.get("cashierUserId"), effectiveCashierId));
            }
            if (filter.terminalId() != null) {
                predicates.add(cb.equal(root.get("terminalId"), filter.terminalId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (Boolean.TRUE.equals(filter.withDifference())) {
                predicates.add(cb.isNotNull(root.get("differenceTotal")));
                predicates.add(cb.notEqual(root.get("differenceTotal"), BigDecimal.ZERO));
            } else if (Boolean.FALSE.equals(filter.withDifference())) {
                predicates.add(cb.or(
                        cb.isNull(root.get("differenceTotal")),
                        cb.equal(root.get("differenceTotal"), BigDecimal.ZERO)
                ));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Page<CashSession> page = sessionRepository.findAll(spec, pageable);
        if (page.isEmpty()) {
            return Page.empty(pageable);
        }

        List<CashSession> sessions = page.getContent();
        StoreInfo store = storeApi.get(storeId);
        BigDecimal tolerance = resolveTolerance(storeId);

        Set<Long> sessionIds = new HashSet<>();
        Set<Long> terminalIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (CashSession s : sessions) {
            sessionIds.add(s.getId());
            terminalIds.add(s.getTerminalId());
            userIds.add(s.getCashierUserId());
            if (s.getClosedBy() != null) {
                userIds.add(s.getClosedBy());
            }
            if (s.getDifferenceApprovedBy() != null) {
                userIds.add(s.getDifferenceApprovedBy());
            }
        }

        Map<Long, TerminalRefInfo> terminals = lookupRepository.findTerminalsByIds(terminalIds);
        Map<Long, UserInfo> users = lookupRepository.findUsersByIds(userIds);
        Map<Long, List<CashSessionCount>> countsBySession = new HashMap<>();
        for (CashSessionCount count : countRepository.findByCashSessionIdInOrderByIdAsc(sessionIds)) {
            countsBySession.computeIfAbsent(count.getCashSessionId(), ignored -> new ArrayList<>()).add(count);
        }

        List<SessionDetail> content = sessions.stream()
                .map(s -> new SessionDetail(
                        s,
                        store,
                        terminals.get(s.getTerminalId()),
                        users.get(s.getCashierUserId()),
                        s.getClosedBy() != null ? users.get(s.getClosedBy()) : null,
                        s.getDifferenceApprovedBy() != null ? users.get(s.getDifferenceApprovedBy()) : null,
                        requiresDifferenceApproval(s, tolerance),
                        countsBySession.getOrDefault(s.getId(), List.of())
                ))
                .toList();

        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    /**
     * Obtiene el detalle de un turno de caja validando local activo y alcance P.
     */
    @Transactional(readOnly = true)
    public SessionDetail get(Long id) {
        CashSession session = findAccessibleSession(id);
        List<CashSessionCount> counts = countRepository.findByCashSessionIdOrderByIdAsc(id);
        return toDetail(session, counts);
    }

    /**
     * Calcula el resumen financiero y de operaciones del turno (B.8).
     */
    @Transactional(readOnly = true)
    public SummaryResult summary(Long id) {
        CashSession session = findAccessibleSession(id);
        return computeSummary(session);
    }

    /**
     * Lista los movimientos manuales de efectivo de un turno.
     */
    @Transactional(readOnly = true)
    public List<MovementDetail> listMovements(Long id) {
        CashSession session = findAccessibleSession(id);
        List<CashMovement> movements = movementRepository.findByCashSessionIdOrderByOccurredAtAscIdAsc(session.getId());
        if (movements.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new HashSet<>();
        for (CashMovement m : movements) {
            userIds.add(m.getUserId());
        }
        Map<Long, UserInfo> users = lookupRepository.findUsersByIds(userIds);
        return movements.stream()
                .map(m -> {
                    UserInfo user = users.get(m.getUserId());
                    return new MovementDetail(m, user != null ? user.username() : String.valueOf(m.getUserId()));
                })
                .toList();
    }

    /**
     * Registra un ingreso ({@code CASH_IN}) o retiro ({@code CASH_OUT}) de efectivo en un turno
     * abierto (HU-CAJ-02, RN-24).
     */
    @Transactional
    public MovementDetail registerMovement(
            Long sessionId,
            CashMovementType type,
            BigDecimal amount,
            String reason,
            UUID idempotencyKey,
            String overrideToken
    ) {
        requirePermissionForMovement(type);
        Long currentUserId = requireCurrentUserId();
        CashSession session = findSessionInActiveStore(sessionId);

        if (session.getStatus() != CashSessionStatus.OPEN) {
            throw new BusinessException(
                    ErrorCode.CASH_SESSION_NOT_OPEN,
                    "No se pueden registrar movimientos en un turno cerrado."
            );
        }

        BigDecimal normalizedAmount = Rounding.clp(Objects.requireNonNull(amount, "amount"));
        if (normalizedAmount.compareTo(BigDecimal.ONE) < 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El monto del movimiento debe ser al menos $1.",
                    Map.of(),
                    List.of(new FieldErrorDetail("amount", "VALIDATION_ERROR", "Debe ser mayor o igual a 1."))
            );
        }

        Long authorizationId = null;
        if (type == CashMovementType.CASH_OUT) {
            SummaryResult currentSummary = computeSummary(session);
            if (normalizedAmount.compareTo(currentSummary.expectedCash()) > 0) {
                throw new BusinessException(
                        ErrorCode.CASH_OUT_EXCEEDS_AVAILABLE,
                        "El retiro excede el efectivo disponible en caja."
                );
            }

            RoleLimit limit = roleLimitApi.limitFor(currentUserId, RoleLimitCode.CASH_OUT_MAX_AMOUNT);
            if (!limit.allows(normalizedAmount)) {
                Map<String, Object> context = Map.of(
                        "cashSessionId", session.getId(),
                        "amount", normalizedAmount
                );
                if (overrideToken == null || overrideToken.isBlank()) {
                    throw new BusinessException(
                            ErrorCode.OVERRIDE_REQUIRED,
                            "El retiro supera el límite permitido para su rol y requiere autorización de supervisor.",
                            Map.of(
                                    "action", OverrideAction.CASH_OUT_OVER_LIMIT.name(),
                                    "context", context
                            )
                    );
                }
                String contextHash = supervisorOverrideApi.canonicalContextHash(context);
                AuthorizationRef authRef = supervisorOverrideApi.consume(
                        overrideToken,
                        OverrideAction.CASH_OUT_OVER_LIMIT,
                        contextHash,
                        currentUserId
                );
                authorizationId = authRef.authorizationId();
            }
        }

        String trimmedReason = reason.trim();
        CashMovement movement = new CashMovement();
        movement.setCashSessionId(session.getId());
        movement.setMovementType(type);
        movement.setAmount(normalizedAmount);
        movement.setReason(trimmedReason);
        movement.setAuthorizationId(authorizationId);
        movement.setUserId(currentUserId);
        movement.setIdempotencyKey(Objects.requireNonNull(idempotencyKey, "idempotencyKey"));
        movement.setOccurredAt(businessClock.now());

        CashMovement saved;
        try {
            saved = movementRepository.saveAndFlush(movement);
        } catch (DataIntegrityViolationException ex) {
            Optional<CashMovement> existing = movementRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                CashMovement found = existing.get();
                Map<Long, UserInfo> users = lookupRepository.findUsersByIds(List.of(found.getUserId()));
                UserInfo user = users.get(found.getUserId());
                return new MovementDetail(found, user != null ? user.username() : String.valueOf(found.getUserId()));
            }
            throw ex;
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("cashSessionId", session.getId());
        auditPayload.put("movementType", type.name());
        auditPayload.put("amount", saved.getAmount());
        if (authorizationId != null) {
            auditPayload.put("authorizationId", authorizationId);
        }

        auditService.record(new AuditEvent(
                type == CashMovementType.CASH_IN ? "CASH_IN" : "CASH_OUT",
                "cash_movement",
                String.valueOf(saved.getId()),
                null,
                auditPayload,
                trimmedReason,
                authorizationId
        ));

        Map<Long, UserInfo> users = lookupRepository.findUsersByIds(List.of(currentUserId));
        UserInfo user = users.get(currentUserId);
        return new MovementDetail(saved, user != null ? user.username() : String.valueOf(currentUserId));
    }

    /**
     * Cierra un turno de caja registrando el arqueo por medio de pago (HU-CAJ-03, HU-CAJ-07, RN-23).
     */
    @Transactional
    public SessionDetail close(
            Long sessionId,
            List<CloseCountCommand> counts,
            String notes,
            String overrideToken
    ) {
        Long currentUserId = requireCurrentUserId();
        CashSession session = findSessionInActiveStore(sessionId);

        if (!session.getCashierUserId().equals(currentUserId)) {
            if (!hasAuthority(Permissions.CASH_SESSION_CLOSE_OTHERS)) {
                throw new BusinessException(
                        ErrorCode.ACCESS_DENIED,
                        "No tiene permiso para cerrar el turno de otro cajero."
                );
            }
        } else if (!hasAuthority(Permissions.CASH_SESSION_CLOSE)
                && !hasAuthority(Permissions.CASH_SESSION_CLOSE_OTHERS)) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "No tiene permiso para cerrar el turno de caja."
            );
        }

        if (session.getStatus() != CashSessionStatus.OPEN) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "El turno de caja ya se encuentra cerrado."
            );
        }

        SummaryResult summary = computeSummary(session);
        Map<PaymentMethod, BigDecimal> expectedByMethod = new HashMap<>();
        Set<PaymentMethod> methodsWithActivity = EnumSet.noneOf(PaymentMethod.class);
        methodsWithActivity.add(PaymentMethod.CASH);

        for (MethodSummaryDetail line : summary.byMethod()) {
            expectedByMethod.put(line.method(), line.expected());
            if (line.expected().signum() != 0
                    || line.sales().signum() != 0
                    || line.refunds().signum() != 0
                    || line.creditPayments().signum() != 0) {
                methodsWithActivity.add(line.method());
            }
        }

        validateCloseCounts(counts, methodsWithActivity);

        Instant now = businessClock.now();
        BigDecimal expectedTotal = BigDecimal.ZERO;
        BigDecimal countedTotal = BigDecimal.ZERO;
        List<CashSessionCount> entities = new ArrayList<>();

        for (CloseCountCommand input : counts) {
            BigDecimal expected = expectedByMethod.getOrDefault(input.paymentMethod(), BigDecimal.ZERO);
            BigDecimal counted = Rounding.clp(input.countedAmount());
            BigDecimal difference = counted.subtract(expected);

            expectedTotal = expectedTotal.add(expected);
            countedTotal = countedTotal.add(counted);

            CashSessionCount count = new CashSessionCount();
            count.setCashSessionId(session.getId());
            count.setPaymentMethod(input.paymentMethod());
            count.setExpectedAmount(expected);
            count.setCountedAmount(counted);
            count.setDifference(difference);
            count.setDenominationDetail(serializeDenominations(input.denominationDetail()));
            count.setNotes(trimToNull(input.notes()));
            count.setCreatedAt(now);
            entities.add(count);
        }

        BigDecimal differenceTotal = countedTotal.subtract(expectedTotal);
        BigDecimal tolerance = resolveTolerance(session.getStoreId());
        boolean exceedsTolerance = differenceTotal.abs().compareTo(tolerance) > 0;

        Long differenceApprovedBy = null;
        Instant differenceApprovedAt = null;
        Long authorizationId = null;

        if (exceedsTolerance && overrideToken != null && !overrideToken.isBlank()) {
            Map<String, Object> context = Map.of(
                    "cashSessionId", session.getId(),
                    "differenceTotal", differenceTotal
            );
            String contextHash = supervisorOverrideApi.canonicalContextHash(context);
            AuthorizationRef authRef = supervisorOverrideApi.consume(
                    overrideToken,
                    OverrideAction.CASH_DIFFERENCE_APPROVAL,
                    contextHash,
                    currentUserId,
                    "cash_session",
                    String.valueOf(session.getId())
            );
            if (authRef.authorizedBy().equals(session.getCashierUserId())) {
                throw new BusinessException(
                        ErrorCode.SEGREGATION_OF_DUTIES,
                        "Quien aprueba la diferencia debe ser distinto del cajero del turno."
                );
            }
            differenceApprovedBy = authRef.authorizedBy();
            differenceApprovedAt = now;
            authorizationId = authRef.authorizationId();
        }

        session.setStatus(CashSessionStatus.CLOSED);
        session.setClosedAt(now);
        session.setClosedBy(currentUserId);
        session.setExpectedTotal(expectedTotal);
        session.setCountedTotal(countedTotal);
        session.setDifferenceTotal(differenceTotal);
        session.setDifferenceApprovedBy(differenceApprovedBy);
        session.setDifferenceApprovedAt(differenceApprovedAt);
        session.setClosingNotes(trimToNull(notes));

        CashSession saved = sessionRepository.save(session);
        List<CashSessionCount> savedCounts = countRepository.saveAll(entities);

        Map<String, Object> auditValue = new LinkedHashMap<>();
        auditValue.put("number", saved.getNumber());
        auditValue.put("closedBy", currentUserId);
        auditValue.put("cashierUserId", saved.getCashierUserId());
        auditValue.put("expectedTotal", expectedTotal);
        auditValue.put("countedTotal", countedTotal);
        auditValue.put("differenceTotal", differenceTotal);
        auditValue.put("requiresDifferenceApproval", requiresDifferenceApproval(saved, tolerance));

        auditService.record(new AuditEvent(
                "CASH_SESSION_CLOSED",
                "cash_session",
                String.valueOf(saved.getId()),
                Map.of("status", CashSessionStatus.OPEN.name()),
                auditValue,
                trimToNull(notes),
                authorizationId
        ));

        return toDetail(saved, savedCounts);
    }

    /**
     * Aprueba la diferencia de cierre de un turno cerrado (HU-CAJ-04, RN-23).
     *
     * <p>Exige segregación de funciones: quien aprueba debe ser distinto del cajero dueño del turno.
     */
    @Transactional
    public SessionDetail approveDifference(Long sessionId) {
        Long currentUserId = requireCurrentUserId();
        CashSession session = findSessionInActiveStore(sessionId);

        if (session.getStatus() != CashSessionStatus.CLOSED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se puede aprobar la diferencia de un turno cerrado."
            );
        }
        if (session.getDifferenceApprovedBy() != null) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "La diferencia de cierre ya fue aprobada."
            );
        }

        BigDecimal tolerance = resolveTolerance(session.getStoreId());
        if (!requiresDifferenceApproval(session, tolerance)) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "La diferencia del turno no supera la tolerancia configurada."
            );
        }

        if (currentUserId.equals(session.getCashierUserId())) {
            throw new BusinessException(
                    ErrorCode.SEGREGATION_OF_DUTIES,
                    "El cajero del turno no puede aprobar su propia diferencia de cierre."
            );
        }

        Instant now = businessClock.now();
        session.setDifferenceApprovedBy(currentUserId);
        session.setDifferenceApprovedAt(now);

        CashSession saved = sessionRepository.save(session);
        List<CashSessionCount> counts = countRepository.findByCashSessionIdOrderByIdAsc(saved.getId());

        auditService.record(new AuditEvent(
                "CASH_DIFFERENCE_APPROVED",
                "cash_session",
                String.valueOf(saved.getId()),
                Map.of("differenceApprovedBy", "null"),
                Map.of(
                        "differenceTotal", saved.getDifferenceTotal(),
                        "differenceApprovedBy", currentUserId,
                        "differenceApprovedAt", now.toString()
                ),
                null,
                null
        ));

        return toDetail(saved, counts);
    }

    /**
     * Construye los datos del comprobante de cierre (HU-CAJ-05).
     */
    @Transactional(readOnly = true)
    public ClosingReceiptResult closingReceipt(Long sessionId) {
        CashSession session = findAccessibleSession(sessionId);
        if (session.getStatus() != CashSessionStatus.CLOSED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "El turno aún está abierto; ciérrelo para emitir el comprobante."
            );
        }
        List<CashSessionCount> counts = countRepository.findByCashSessionIdOrderByIdAsc(session.getId());
        SessionDetail detail = toDetail(session, counts);
        SummaryResult summary = computeSummary(session);
        CompanyRefInfo company = lookupRepository.findCompanyInfo().orElse(null);
        return new ClosingReceiptResult(detail, summary, company, detail.store(), businessClock.now());
    }

    /**
     * Renderiza el comprobante de cierre en PDF usando la plantilla {@code cash-closing.html}.
     */
    @Transactional(readOnly = true)
    public byte[] renderClosingReceiptPdf(Long sessionId) {
        ClosingReceiptResult receipt = closingReceipt(sessionId);
        SessionDetail detail = receipt.session();
        CashSession session = detail.session();
        ZoneId zone = businessClock.zone();

        String companyName = receipt.company() != null
                ? (receipt.company().tradeName() != null && !receipt.company().tradeName().isBlank()
                ? receipt.company().tradeName()
                : receipt.company().legalName())
                : "Minimarket";

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("title", "Comprobante de Cierre de Caja " + session.getNumber());
        model.put("company", companyName);
        model.put("companyRut", receipt.company() != null ? receipt.company().rut() : "");
        model.put("storeName", detail.store().code() + " - " + detail.store().name());
        model.put("storeAddress", detail.store().address() != null ? detail.store().address() : "");
        model.put("terminalName", detail.terminal() != null
                ? detail.terminal().code() + " - " + detail.terminal().name()
                : String.valueOf(session.getTerminalId()));
        model.put("sessionNumber", session.getNumber());
        model.put("businessDate", PDF_DATE.format(session.getBusinessDate()));
        model.put("cashierName", formatUserName(detail.cashier(), session.getCashierUserId()));
        model.put("closedByName", detail.closedBy() != null
                ? formatUserName(detail.closedBy(), session.getClosedBy())
                : formatUserName(detail.cashier(), session.getCashierUserId()));
        model.put("openedAt", formatInstant(session.getOpenedAt(), zone));
        model.put("closedAt", formatInstant(session.getClosedAt(), zone));
        model.put("printedAt", formatInstant(receipt.printedAt(), zone));
        model.put("openingAmount", formatClp(receipt.summary().openingAmount()));
        model.put("cashIn", formatClp(receipt.summary().cashIn()));
        model.put("cashOut", formatClp(receipt.summary().cashOut()));
        model.put("expectedCash", formatClp(receipt.summary().expectedCash()));
        model.put("expectedTotal", formatClp(session.getExpectedTotal()));
        model.put("countedTotal", formatClp(session.getCountedTotal()));
        model.put("differenceTotal", formatClp(session.getDifferenceTotal()));
        model.put("requiresDifferenceApproval", detail.requiresDifferenceApproval());
        model.put("differenceApprovedByName", detail.differenceApprovedBy() != null
                ? formatUserName(detail.differenceApprovedBy(), session.getDifferenceApprovedBy())
                : null);
        model.put("closingNotes", session.getClosingNotes());
        model.put("salesCount", receipt.summary().salesCount());
        model.put("voidCount", receipt.summary().voidCount());
        model.put("returnCount", receipt.summary().returnCount());

        List<Map<String, String>> countRows = detail.counts().stream()
                .map(c -> Map.of(
                        "method", paymentMethodLabel(c.getPaymentMethod()),
                        "expected", formatClp(c.getExpectedAmount()),
                        "counted", formatClp(c.getCountedAmount()),
                        "difference", formatClp(c.getDifference())
                ))
                .toList();
        model.put("counts", countRows);

        return pdfRenderer.render("cash-closing", model);
    }

    private SummaryResult computeSummary(CashSession session) {
        Long sessionId = session.getId();
        BigDecimal opening = Rounding.clp(session.getOpeningAmount());
        BigDecimal cashIn = Rounding.clp(movementRepository.sumAmountBySessionAndType(sessionId, CashMovementType.CASH_IN));
        BigDecimal cashOut = Rounding.clp(movementRepository.sumAmountBySessionAndType(sessionId, CashMovementType.CASH_OUT));

        List<Map<PaymentMethod, BigDecimal>> contributorMaps = new ArrayList<>(contributors.size());
        for (CashFlowContributor contributor : contributors) {
            contributorMaps.add(contributor.netAmountsBySession(sessionId));
        }

        CashExpectedCalculator.ExpectedTotals expectedTotals = CashExpectedCalculator.calculate(
                opening, cashIn, cashOut, contributorMaps
        );

        int salesCount = 0;
        int voidCount = 0;
        int returnCount = 0;
        EnumMap<PaymentMethod, BigDecimal> salesByMethod = emptyMethodMap();
        EnumMap<PaymentMethod, BigDecimal> refundsByMethod = emptyMethodMap();
        EnumMap<PaymentMethod, BigDecimal> creditPaymentsByMethod = emptyMethodMap();

        for (CashSessionMetricsContributor contributor : metricsContributors) {
            SessionMetricsSnapshot snapshot = contributor.contribute(sessionId);
            if (snapshot == null) {
                continue;
            }
            salesCount += snapshot.salesCount();
            voidCount += snapshot.voidCount();
            returnCount += snapshot.returnCount();
            mergeMethodAmounts(salesByMethod, snapshot.salesByMethod());
            mergeMethodAmounts(refundsByMethod, snapshot.refundsByMethod());
            mergeMethodAmounts(creditPaymentsByMethod, snapshot.creditPaymentsByMethod());
        }

        List<MethodSummaryDetail> byMethod = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            byMethod.add(new MethodSummaryDetail(
                    method,
                    Rounding.clp(salesByMethod.get(method)),
                    Rounding.clp(refundsByMethod.get(method)),
                    Rounding.clp(creditPaymentsByMethod.get(method)),
                    expectedTotals.expectedByMethod().getOrDefault(method, BigDecimal.ZERO)
            ));
        }

        return new SummaryResult(
                sessionId,
                opening,
                cashIn,
                cashOut,
                byMethod,
                expectedTotals.expectedCash(),
                salesCount,
                voidCount,
                returnCount
        );
    }

    private static EnumMap<PaymentMethod, BigDecimal> emptyMethodMap() {
        EnumMap<PaymentMethod, BigDecimal> map = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod method : PaymentMethod.values()) {
            map.put(method, BigDecimal.ZERO);
        }
        return map;
    }

    private static void mergeMethodAmounts(
            EnumMap<PaymentMethod, BigDecimal> target,
            Map<PaymentMethod, BigDecimal> source
    ) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<PaymentMethod, BigDecimal> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                target.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
            }
        }
    }

    private CashSession findSessionInActiveStore(Long id) {
        Long storeId = StoreContext.require().storeId();
        return sessionRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Turno de caja no encontrado."));
    }

    private CashSession findAccessibleSession(Long id) {
        CashSession session = findSessionInActiveStore(id);
        if (!hasFullReadScope()) {
            Long currentUserId = requireCurrentUserId();
            if (!session.getCashierUserId().equals(currentUserId)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Turno de caja no encontrado.");
            }
        }
        return session;
    }

    private SessionDetail toDetail(CashSession session, List<CashSessionCount> counts) {
        StoreInfo store = storeApi.get(session.getStoreId());
        Set<Long> userIds = new HashSet<>();
        userIds.add(session.getCashierUserId());
        if (session.getClosedBy() != null) {
            userIds.add(session.getClosedBy());
        }
        if (session.getDifferenceApprovedBy() != null) {
            userIds.add(session.getDifferenceApprovedBy());
        }
        Map<Long, TerminalRefInfo> terminals = lookupRepository.findTerminalsByIds(List.of(session.getTerminalId()));
        Map<Long, UserInfo> users = lookupRepository.findUsersByIds(userIds);
        BigDecimal tolerance = resolveTolerance(session.getStoreId());

        return new SessionDetail(
                session,
                store,
                terminals.get(session.getTerminalId()),
                users.get(session.getCashierUserId()),
                session.getClosedBy() != null ? users.get(session.getClosedBy()) : null,
                session.getDifferenceApprovedBy() != null ? users.get(session.getDifferenceApprovedBy()) : null,
                requiresDifferenceApproval(session, tolerance),
                counts
        );
    }

    private boolean requiresDifferenceApproval(CashSession session, BigDecimal tolerance) {
        if (session.getStatus() != CashSessionStatus.CLOSED
                || session.getDifferenceApprovedBy() != null
                || session.getDifferenceTotal() == null) {
            return false;
        }
        return session.getDifferenceTotal().abs().compareTo(tolerance) > 0;
    }

    private BigDecimal resolveTolerance(Long storeId) {
        BigDecimal value = settingsApi.get(SETTING_DIFFERENCE_TOLERANCE, storeId, BigDecimal.class);
        return value != null ? Rounding.clp(value) : BigDecimal.ZERO;
    }

    private void validateCloseCounts(List<CloseCountCommand> counts, Set<PaymentMethod> requiredMethods) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (counts == null || counts.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Debe informar el arqueo de caja.",
                    Map.of(),
                    List.of(new FieldErrorDetail("counts", "VALIDATION_ERROR", "Debe incluir al menos CASH."))
            );
        }

        Set<PaymentMethod> seen = EnumSet.noneOf(PaymentMethod.class);
        for (int i = 0; i < counts.size(); i++) {
            CloseCountCommand item = counts.get(i);
            if (item.paymentMethod() == null) {
                errors.add(new FieldErrorDetail("counts[" + i + "].paymentMethod", "VALIDATION_ERROR",
                        "El medio de pago es obligatorio."));
                continue;
            }
            if (!seen.add(item.paymentMethod())) {
                errors.add(new FieldErrorDetail("counts[" + i + "].paymentMethod", "VALIDATION_ERROR",
                        "Medio de pago duplicado: " + item.paymentMethod()));
            }
            if (item.countedAmount() == null || item.countedAmount().signum() < 0) {
                errors.add(new FieldErrorDetail("counts[" + i + "].countedAmount", "VALIDATION_ERROR",
                        "El monto contado debe ser mayor o igual a 0."));
            }
            if (item.denominationDetail() != null && !item.denominationDetail().isEmpty()) {
                if (item.paymentMethod() != PaymentMethod.CASH) {
                    errors.add(new FieldErrorDetail("counts[" + i + "].denominationDetail", "VALIDATION_ERROR",
                            "El detalle de denominaciones solo aplica para CASH."));
                } else {
                    for (Map.Entry<Integer, Integer> entry : item.denominationDetail().entrySet()) {
                        if (entry.getKey() == null || entry.getKey() <= 0
                                || entry.getValue() == null || entry.getValue() < 0) {
                            errors.add(new FieldErrorDetail("counts[" + i + "].denominationDetail",
                                    "VALIDATION_ERROR",
                                    "Las denominaciones deben ser positivas y sus cantidades >= 0."));
                            break;
                        }
                    }
                }
            }
        }

        for (PaymentMethod required : requiredMethods) {
            if (!seen.contains(required)) {
                errors.add(new FieldErrorDetail("counts", "VALIDATION_ERROR",
                        "Falta informar el arqueo para el medio de pago " + required + "."));
            }
        }

        if (!errors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Arqueo de cierre inválido.",
                    Map.of(),
                    errors
            );
        }
    }

    private String serializeDenominations(Map<Integer, Integer> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JacksonException e) {
            throw new IllegalStateException("No se pudo serializar denominationDetail", e);
        }
    }

    private void requirePermissionForMovement(CashMovementType type) {
        String required = type == CashMovementType.CASH_IN
                ? Permissions.CASH_MOVEMENT_CASH_IN
                : Permissions.CASH_MOVEMENT_CASH_OUT;
        if (!hasAuthority(required)) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "No tiene permiso para registrar movimientos de tipo " + type + "."
            );
        }
    }

    private boolean hasFullReadScope() {
        return hasAuthority(Permissions.CASH_SESSION_CLOSE_OTHERS)
                || hasAuthority(Permissions.CASH_DIFFERENCE_APPROVE)
                || hasAuthority(Permissions.REPORT_CASH_READ);
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

    private static Long requireCurrentUserId() {
        return CurrentUserId.find().orElseThrow(() ->
                new BusinessException(ErrorCode.UNAUTHENTICATED, "No hay usuario autenticado."));
    }

    private static CashSessionRef toRef(CashSession session) {
        return new CashSessionRef(
                session.getId(),
                session.getStoreId(),
                session.getTerminalId(),
                session.getCashierUserId(),
                session.getNumber(),
                session.getBusinessDate()
        );
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String formatUserName(UserInfo user, Long fallbackId) {
        if (user == null) {
            return fallbackId != null ? String.valueOf(fallbackId) : "";
        }
        if (user.fullName() != null && !user.fullName().isBlank()) {
            return user.fullName() + " (" + user.username() + ")";
        }
        return user.username();
    }

    private static String formatInstant(Instant instant, ZoneId zone) {
        return instant == null ? "-" : PDF_DATE_TIME.format(instant.atZone(zone));
    }

    private static String formatClp(BigDecimal amount) {
        if (amount == null) {
            return "$0";
        }
        NumberFormat nf = NumberFormat.getIntegerInstance(CL_LOCALE);
        long value = amount.longValue();
        if (value < 0) {
            return "-$" + nf.format(Math.abs(value));
        }
        return "$" + nf.format(value);
    }

    private static String paymentMethodLabel(PaymentMethod method) {
        return switch (method) {
            case CASH -> "Efectivo";
            case DEBIT -> "Tarjeta de débito";
            case CREDIT -> "Tarjeta de crédito";
            case TRANSFER -> "Transferencia";
            case CUSTOMER_CREDIT -> "Cuenta corriente (fiado)";
        };
    }
}
