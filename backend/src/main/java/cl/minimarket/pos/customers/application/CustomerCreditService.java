package cl.minimarket.pos.customers.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.customers.CustomerCreditApi;
import cl.minimarket.pos.customers.CustomerCreditSnapshot;
import cl.minimarket.pos.customers.domain.Customer;
import cl.minimarket.pos.customers.domain.CustomerAccountMovement;
import cl.minimarket.pos.customers.domain.CustomerAccountMovementRepository;
import cl.minimarket.pos.customers.domain.CustomerMovementType;
import cl.minimarket.pos.customers.domain.CustomerRepository;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implementación de {@link CustomerCreditApi} para cargos y reversos de cuenta corriente desde
 * ventas, anulaciones y devoluciones ({@code MODELO_DATOS.md} B.7, RN-15).
 */
@Service
public class CustomerCreditService implements CustomerCreditApi {

    private final CustomerRepository customerRepository;
    private final CustomerAccountMovementRepository movementRepository;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    public CustomerCreditService(
            CustomerRepository customerRepository,
            CustomerAccountMovementRepository movementRepository,
            AuditService auditService,
            BusinessClock businessClock
    ) {
        this.customerRepository = customerRepository;
        this.movementRepository = movementRepository;
        this.auditService = auditService;
        this.businessClock = businessClock;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerCreditSnapshot creditStatus(Long customerId) {
        Objects.requireNonNull(customerId, "customerId");
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado."));
        return new CustomerCreditSnapshot(
                customer.getId(),
                customer.getName(),
                customer.isActive(),
                customer.isCreditEnabled(),
                customer.getCreditLimit(),
                customer.getCreditBalance(),
                customer.availableCredit()
        );
    }

    @Override
    @Transactional
    public void charge(Long customerId, BigDecimal amount, Long saleId, Long storeId) {
        doCharge(customerId, amount, saleId, storeId, null);
    }

    @Override
    @Transactional
    public void charge(Long customerId, BigDecimal amount, Long saleId, Long storeId, Long authorizationId) {
        doCharge(customerId, amount, saleId, storeId, authorizationId);
    }

    private void doCharge(Long customerId, BigDecimal amount, Long saleId, Long storeId, Long authorizationId) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(storeId, "storeId");
        BigDecimal normalizedAmount = requirePositiveAmount(amount);

        Customer customer = customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado."));

        if (!customer.isActive()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "El cliente está inactivo.");
        }
        if (!customer.isCreditEnabled()) {
            throw new BusinessException(
                    ErrorCode.CREDIT_NOT_ENABLED,
                    "El cliente no tiene habilitada la cuenta corriente (fiado)."
            );
        }

        BigDecimal oldBalance = customer.getCreditBalance();
        BigDecimal newBalance = oldBalance.add(normalizedAmount);
        if (newBalance.compareTo(customer.getCreditLimit()) > 0 && authorizationId == null) {
            throw new BusinessException(
                    ErrorCode.CREDIT_LIMIT_EXCEEDED,
                    "El cargo excede el cupo de crédito disponible del cliente.",
                    Map.of(
                            "creditLimit", customer.getCreditLimit(),
                            "creditBalance", oldBalance,
                            "requestedAmount", normalizedAmount
                    )
            );
        }

        customer.setCreditBalance(newBalance);
        customerRepository.save(customer);

        Long userId = CurrentUserId.find()
                .orElseThrow(() -> new IllegalStateException("Se requiere un usuario autenticado."));

        movementRepository.save(new CustomerAccountMovement(
                customerId,
                storeId,
                CustomerMovementType.CHARGE,
                normalizedAmount,
                newBalance,
                saleId,
                null,
                PaymentMethod.CUSTOMER_CREDIT,
                null,
                userId,
                null,
                authorizationId != null ? "Cargo fiado autorizado sobre el cupo" : "Cargo fiado en venta",
                businessClock.now()
        ));

        if (authorizationId != null) {
            auditService.record(new AuditEvent(
                    "CREDIT_OVER_LIMIT",
                    "customer",
                    String.valueOf(customerId),
                    Map.of("creditBalance", oldBalance, "creditLimit", customer.getCreditLimit()),
                    Map.of(
                            "creditBalance", newBalance,
                            "chargeAmount", normalizedAmount,
                            "saleId", saleId
                    ),
                    "Venta a fiado sobre el cupo autorizada",
                    authorizationId
            ));
        }
    }

    @Override
    @Transactional
    public void reverse(Long customerId, BigDecimal amount, Long saleReturnId) {
        Long storeId = StoreContext.require().storeId();
        doReverse(customerId, amount, null, saleReturnId, storeId);
    }

    @Override
    @Transactional
    public void reverse(Long customerId, BigDecimal amount, Long saleId, Long saleReturnId, Long storeId) {
        doReverse(customerId, amount, saleId, saleReturnId, storeId);
    }

    private void doReverse(Long customerId, BigDecimal amount, Long saleId, Long saleReturnId, Long storeId) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(storeId, "storeId");
        BigDecimal normalizedAmount = requirePositiveAmount(amount);

        Customer customer = customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado."));

        BigDecimal newBalance = customer.getCreditBalance().subtract(normalizedAmount);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(
                    ErrorCode.PAYMENT_EXCEEDS_DEBT,
                    "El reverso excede la deuda vigente del cliente."
            );
        }

        customer.setCreditBalance(newBalance);
        customerRepository.save(customer);

        Long userId = CurrentUserId.find()
                .orElseThrow(() -> new IllegalStateException("Se requiere un usuario autenticado."));

        movementRepository.save(new CustomerAccountMovement(
                customerId,
                storeId,
                CustomerMovementType.REVERSAL,
                normalizedAmount.negate(),
                newBalance,
                saleId,
                saleReturnId,
                PaymentMethod.CUSTOMER_CREDIT,
                null,
                userId,
                null,
                saleReturnId != null ? "Reverso por devolución" : "Reverso por anulación de venta",
                businessClock.now()
        ));
    }

    private static BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El monto es obligatorio.",
                    Map.of(),
                    List.of(new FieldErrorDetail("amount", "REQUIRED", "El monto es obligatorio."))
            );
        }
        BigDecimal normalized = Rounding.clp(amount);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El monto debe ser mayor que cero.",
                    Map.of(),
                    List.of(new FieldErrorDetail("amount", "MIN_VALUE", "El monto debe ser mayor que cero."))
            );
        }
        return normalized;
    }
}
