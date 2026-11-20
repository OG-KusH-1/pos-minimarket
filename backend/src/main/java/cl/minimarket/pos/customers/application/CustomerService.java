package cl.minimarket.pos.customers.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.cash.CashSessionApi;
import cl.minimarket.pos.cash.CashSessionRef;
import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.customers.domain.Customer;
import cl.minimarket.pos.customers.domain.CustomerAccountMovement;
import cl.minimarket.pos.customers.domain.CustomerAccountMovementRepository;
import cl.minimarket.pos.customers.domain.CustomerJdbcRepository;
import cl.minimarket.pos.customers.domain.CustomerJdbcRepository.AccountMovementRow;
import cl.minimarket.pos.customers.domain.CustomerJdbcRepository.CustomerRow;
import cl.minimarket.pos.customers.domain.CustomerMovementType;
import cl.minimarket.pos.customers.domain.CustomerRepository;
import cl.minimarket.pos.security.RoleLimit;
import cl.minimarket.pos.security.RoleLimitApi;
import cl.minimarket.pos.security.RoleLimitCode;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.rut.Rut;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Servicio de aplicación para gestión de clientes y cuenta corriente (CRUD, cupo de crédito,
 * abonos, ajustes y estado de cuenta).
 */
@Service
public class CustomerService {

    private static final int MAX_MOVEMENT_NOTES_LENGTH = 250;

    private final CustomerRepository customerRepository;
    private final CustomerAccountMovementRepository movementRepository;
    private final CustomerJdbcRepository jdbcRepository;
    private final CashSessionApi cashSessionApi;
    private final RoleLimitApi roleLimitApi;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    public CustomerService(
            CustomerRepository customerRepository,
            CustomerAccountMovementRepository movementRepository,
            CustomerJdbcRepository jdbcRepository,
            CashSessionApi cashSessionApi,
            RoleLimitApi roleLimitApi,
            AuditService auditService,
            BusinessClock businessClock
    ) {
        this.customerRepository = customerRepository;
        this.movementRepository = movementRepository;
        this.jdbcRepository = jdbcRepository;
        this.cashSessionApi = cashSessionApi;
        this.roleLimitApi = roleLimitApi;
        this.auditService = auditService;
        this.businessClock = businessClock;
    }

    /** Datos para crear o actualizar un cliente. */
    public record CustomerCommand(
            String rut,
            String name,
            String email,
            String phone,
            String address,
            String notes,
            Long version
    ) {
    }

    /** Resultado de estado de cuenta (cliente y movimientos paginados). */
    public record AccountStatement(Customer customer, Page<AccountMovementRow> movements) {
    }

    /** Lista clientes de forma paginada aplicando los filtros opcionales. */
    @Transactional(readOnly = true)
    public Page<CustomerRow> list(
            String q,
            Boolean withDebt,
            Boolean creditEnabled,
            Boolean active,
            Pageable pageable
    ) {
        return jdbcRepository.searchCustomers(q, withDebt, creditEnabled, active, pageable);
    }

    /** Obtiene un cliente por su identificador. */
    @Transactional(readOnly = true)
    public Customer get(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado."));
    }

    /** Crea un cliente nuevo validando RUT (RN-30) y unicidad. */
    @Transactional
    public Customer create(CustomerCommand command) {
        String normalizedRut = normalizeRut(command.rut());
        if (normalizedRut != null && customerRepository.existsByRut(normalizedRut)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE, "Ya existe un cliente con el RUT ingresado.");
        }

        Customer customer = new Customer(
                normalizedRut,
                command.name().trim(),
                trimToNull(command.email()),
                trimToNull(command.phone()),
                trimToNull(command.address()),
                trimToNull(command.notes())
        );

        try {
            return customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE, "Ya existe un cliente con el RUT ingresado.");
        }
    }

    /** Actualiza los datos de un cliente existente respetando bloqueo optimista. */
    @Transactional
    public Customer update(Long id, CustomerCommand command) {
        Customer customer = get(id);
        if (command.version() != null && !command.version().equals(customer.getVersion())) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "El cliente fue modificado por otro usuario."
            );
        }

        String normalizedRut = normalizeRut(command.rut());
        if (normalizedRut != null && customerRepository.existsByRutAndIdNot(normalizedRut, id)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE, "Ya existe otro cliente con el RUT ingresado.");
        }

        customer.setRut(normalizedRut);
        customer.setName(command.name().trim());
        customer.setEmail(trimToNull(command.email()));
        customer.setPhone(trimToNull(command.phone()));
        customer.setAddress(trimToNull(command.address()));
        customer.setNotes(trimToNull(command.notes()));

        try {
            return customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE, "Ya existe otro cliente con el RUT ingresado.");
        }
    }

    /** Desactiva un cliente siempre que no tenga deuda vigente. */
    @Transactional
    public void deactivate(Long id) {
        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado."));
        if (customer.getCreditBalance().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(
                    ErrorCode.ENTITY_IN_USE,
                    "No se puede desactivar un cliente con deuda vigente."
            );
        }
        customer.setActive(false);
        customerRepository.save(customer);
    }

    /** Reactiva un cliente desactivado. */
    @Transactional
    public void activate(Long id) {
        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado."));
        customer.setActive(true);
        customerRepository.save(customer);
    }

    /**
     * Habilita o edita el cupo de crédito de un cliente respetando {@code CREDIT_LIMIT_MAX_ASSIGN}
     * del rol y auditando {@code CUSTOMER_CREDIT_LIMIT_CHANGED}.
     */
    @Transactional
    public Customer updateCreditSettings(Long id, boolean creditEnabled, BigDecimal creditLimit, String reason) {
        BigDecimal normalizedLimit = Rounding.clp(creditLimit);
        if (normalizedLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El cupo de crédito no puede ser negativo.",
                    Map.of(),
                    List.of(new FieldErrorDetail("creditLimit", "MIN_VALUE", "El cupo no puede ser negativo."))
            );
        }

        Long currentUserId = requireCurrentUserId();
        RoleLimit roleLimit = roleLimitApi.limitFor(currentUserId, RoleLimitCode.CREDIT_LIMIT_MAX_ASSIGN);
        if (!roleLimit.allows(normalizedLimit)) {
            throw new BusinessException(
                    ErrorCode.CREDIT_LIMIT_EXCEEDED,
                    "El cupo solicitado excede el máximo asignable para su rol."
            );
        }

        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado."));

        Map<String, Object> oldValue = Map.of(
                "creditEnabled", customer.isCreditEnabled(),
                "creditLimit", customer.getCreditLimit(),
                "creditBalance", customer.getCreditBalance()
        );

        customer.setCreditEnabled(creditEnabled);
        customer.setCreditLimit(normalizedLimit);
        Customer saved = customerRepository.save(customer);

        Map<String, Object> newValue = Map.of(
                "creditEnabled", saved.isCreditEnabled(),
                "creditLimit", saved.getCreditLimit(),
                "creditBalance", saved.getCreditBalance()
        );

        auditService.record(new AuditEvent(
                "CUSTOMER_CREDIT_LIMIT_CHANGED",
                "customer",
                String.valueOf(saved.getId()),
                oldValue,
                newValue,
                reason.trim(),
                null
        ));

        return saved;
    }

    /** Obtiene la cartola paginada de movimientos de cuenta corriente de un cliente. */
    @Transactional(readOnly = true)
    public AccountStatement accountStatement(Long id, LocalDate from, LocalDate to, Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El rango de fechas es inválido.",
                    Map.of(),
                    List.of(new FieldErrorDetail("from", "INVALID_RANGE", "'from' no puede ser posterior a 'to'."))
            );
        }
        Customer customer = get(id);
        Instant fromInstant = from != null ? businessClock.startOfDay(from) : null;
        Instant toInstant = to != null ? businessClock.startOfDay(to.plusDays(1)) : null;
        Page<AccountMovementRow> movements = jdbcRepository.findAccountMovements(id, fromInstant, toInstant, pageable);
        return new AccountStatement(customer, movements);
    }

    /**
     * Registra un abono a la deuda del cliente (solo {@code CASH} o {@code TRANSFER}, RN-15).
     * Los pagos en efectivo exigen terminal y turno abierto.
     */
    @Transactional
    public AccountMovementRow registerPayment(
            Long id,
            BigDecimal amount,
            PaymentMethod paymentMethod,
            String reference,
            String notes,
            UUID idempotencyKey
    ) {
        if (idempotencyKey != null) {
            Optional<CustomerAccountMovement> existing = movementRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return jdbcRepository.findMovementById(existing.get().getId()).orElseThrow();
            }
        }

        if (paymentMethod != PaymentMethod.CASH && paymentMethod != PaymentMethod.TRANSFER) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Medio de pago no permitido para abonos.",
                    Map.of(),
                    List.of(new FieldErrorDetail(
                            "paymentMethod",
                            "INVALID_PAYMENT_METHOD",
                            "Solo se admiten abonos en efectivo (CASH) o transferencia (TRANSFER)."
                    ))
            );
        }

        BigDecimal normalizedAmount = Rounding.clp(amount);
        if (normalizedAmount.compareTo(BigDecimal.ONE) < 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El monto del abono debe ser al menos 1.",
                    Map.of(),
                    List.of(new FieldErrorDetail("amount", "MIN_VALUE", "El monto debe ser mayor o igual a 1."))
            );
        }

        StoreContext ctx = StoreContext.require();
        Long cashSessionId = null;
        if (paymentMethod == PaymentMethod.CASH) {
            if (ctx.terminalId() == null) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "El abono en efectivo requiere una terminal activa (header X-Terminal-Id).",
                        Map.of(),
                        List.of(new FieldErrorDetail(
                                "terminalId",
                                "REQUIRED",
                                "Se requiere X-Terminal-Id para pagos en efectivo."
                        ))
                );
            }
            CashSessionRef session = cashSessionApi.requireOpen(ctx.terminalId());
            cashSessionId = session.id();
        }

        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado."));

        if (normalizedAmount.compareTo(customer.getCreditBalance()) > 0) {
            throw new BusinessException(
                    ErrorCode.PAYMENT_EXCEEDS_DEBT,
                    "El abono excede la deuda vigente del cliente."
            );
        }

        BigDecimal newBalance = customer.getCreditBalance().subtract(normalizedAmount);
        customer.setCreditBalance(newBalance);
        customerRepository.save(customer);

        Long currentUserId = requireCurrentUserId();
        CustomerAccountMovement movement = new CustomerAccountMovement(
                customer.getId(),
                ctx.storeId(),
                CustomerMovementType.PAYMENT,
                normalizedAmount.negate(),
                newBalance,
                null,
                null,
                paymentMethod,
                cashSessionId,
                currentUserId,
                idempotencyKey,
                combineNotes(reference, notes),
                businessClock.now()
        );

        try {
            CustomerAccountMovement saved = movementRepository.saveAndFlush(movement);
            return jdbcRepository.findMovementById(saved.getId()).orElseThrow();
        } catch (DataIntegrityViolationException ex) {
            if (idempotencyKey != null) {
                Optional<CustomerAccountMovement> existing = movementRepository.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    return jdbcRepository.findMovementById(existing.get().getId()).orElseThrow();
                }
            }
            throw ex;
        }
    }

    /**
     * Registra un ajuste manual de cuenta corriente respetando {@code CREDIT_ADJUSTMENT_MAX_AMOUNT}
     * y auditando {@code CUSTOMER_ACCOUNT_ADJUSTED}.
     */
    @Transactional
    public AccountMovementRow registerAdjustment(
            Long id,
            BigDecimal amount,
            String reason,
            UUID idempotencyKey
    ) {
        if (idempotencyKey != null) {
            Optional<CustomerAccountMovement> existing = movementRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return jdbcRepository.findMovementById(existing.get().getId()).orElseThrow();
            }
        }

        BigDecimal normalizedAmount = Rounding.clp(amount);
        if (normalizedAmount.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El monto del ajuste debe ser distinto de cero.",
                    Map.of(),
                    List.of(new FieldErrorDetail("amount", "NON_ZERO", "El monto debe ser distinto de cero."))
            );
        }

        Long currentUserId = requireCurrentUserId();
        RoleLimit limit = roleLimitApi.limitFor(currentUserId, RoleLimitCode.CREDIT_ADJUSTMENT_MAX_AMOUNT);
        if (!limit.allows(normalizedAmount.abs())) {
            throw new BusinessException(
                    ErrorCode.CREDIT_LIMIT_EXCEEDED,
                    "El ajuste excede el monto máximo permitido para su rol."
            );
        }

        StoreContext ctx = StoreContext.require();
        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado."));

        BigDecimal oldBalance = customer.getCreditBalance();
        BigDecimal newBalance = oldBalance.add(normalizedAmount);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(
                    ErrorCode.PAYMENT_EXCEEDS_DEBT,
                    "El ajuste no puede dejar la deuda del cliente en negativo."
            );
        }

        customer.setCreditBalance(newBalance);
        customerRepository.save(customer);

        String trimmedReason = reason.trim();
        CustomerAccountMovement movement = new CustomerAccountMovement(
                customer.getId(),
                ctx.storeId(),
                CustomerMovementType.ADJUSTMENT,
                normalizedAmount,
                newBalance,
                null,
                null,
                null,
                null,
                currentUserId,
                idempotencyKey,
                trimmedReason,
                businessClock.now()
        );

        CustomerAccountMovement saved;
        try {
            saved = movementRepository.saveAndFlush(movement);
        } catch (DataIntegrityViolationException ex) {
            if (idempotencyKey != null) {
                Optional<CustomerAccountMovement> existing = movementRepository.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    return jdbcRepository.findMovementById(existing.get().getId()).orElseThrow();
                }
            }
            throw ex;
        }

        auditService.record(new AuditEvent(
                "CUSTOMER_ACCOUNT_ADJUSTED",
                "customer",
                String.valueOf(customer.getId()),
                Map.of("creditBalance", oldBalance),
                Map.of(
                        "creditBalance", newBalance,
                        "amount", normalizedAmount,
                        "movementId", saved.getId()
                ),
                trimmedReason,
                null
        ));

        return jdbcRepository.findMovementById(saved.getId()).orElseThrow();
    }

    private static String normalizeRut(String rawRut) {
        if (rawRut == null || rawRut.isBlank()) {
            return null;
        }
        return Rut.of(rawRut).normalized();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String combineNotes(String reference, String notes) {
        String ref = trimToNull(reference);
        String n = trimToNull(notes);
        String combined;
        if (ref != null && n != null) {
            combined = "Ref: " + ref + " — " + n;
        } else if (ref != null) {
            combined = "Ref: " + ref;
        } else {
            combined = n;
        }
        if (combined == null) {
            return null;
        }
        return combined.length() > MAX_MOVEMENT_NOTES_LENGTH
                ? combined.substring(0, MAX_MOVEMENT_NOTES_LENGTH)
                : combined;
    }

    private static Long requireCurrentUserId() {
        return CurrentUserId.find()
                .orElseThrow(() -> new IllegalStateException("Se requiere un usuario autenticado."));
    }
}
