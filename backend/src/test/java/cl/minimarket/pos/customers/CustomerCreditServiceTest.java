package cl.minimarket.pos.customers;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.customers.application.CustomerCreditService;
import cl.minimarket.pos.customers.domain.Customer;
import cl.minimarket.pos.customers.domain.CustomerAccountMovement;
import cl.minimarket.pos.customers.domain.CustomerAccountMovementRepository;
import cl.minimarket.pos.customers.domain.CustomerMovementType;
import cl.minimarket.pos.customers.domain.CustomerRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link CustomerCreditService} (RN-15, {@code CREDIT_NOT_ENABLED},
 * {@code CREDIT_LIMIT_EXCEEDED}, override {@code CREDIT_OVER_LIMIT} y {@code reverse}).
 */
@ExtendWith(MockitoExtension.class)
class CustomerCreditServiceTest {

    @Mock
    CustomerRepository customerRepository;

    @Mock
    CustomerAccountMovementRepository movementRepository;

    @Mock
    AuditService auditService;

    private CustomerCreditService service;

    @BeforeEach
    void setUp() {
        BusinessClock clock = BusinessClock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneId.of("America/Santiago"));
        service = new CustomerCreditService(customerRepository, movementRepository, auditService, clock);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", "N/A", List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void chargeSucceedsWithinLimit() {
        Customer customer = activeCustomer(true, "50000", "40000");
        when(customerRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(customer));
        when(customerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(movementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.charge(10L, new BigDecimal("10000"), 100L, 1L);

        assertThat(customer.getCreditBalance()).isEqualByComparingTo("50000");
        ArgumentCaptor<CustomerAccountMovement> captor = ArgumentCaptor.forClass(CustomerAccountMovement.class);
        verify(movementRepository).save(captor.capture());
        assertThat(captor.getValue().getMovementType()).isEqualTo(CustomerMovementType.CHARGE);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("10000");
        assertThat(captor.getValue().getBalanceAfter()).isEqualByComparingTo("50000");
        verify(auditService, never()).record(any());
    }

    @Test
    void chargeFailsWhenCreditNotEnabled() {
        Customer customer = activeCustomer(false, "0", "0");
        when(customerRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.charge(10L, new BigDecimal("5000"), 100L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.CREDIT_NOT_ENABLED);
    }

    @Test
    void chargeFailsWhenExceedingLimitWithoutOverride() {
        Customer customer = activeCustomer(true, "50000", "45000");
        when(customerRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.charge(10L, new BigDecimal("10000"), 100L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.CREDIT_LIMIT_EXCEEDED);
    }

    @Test
    void chargeSucceedsOverLimitWithOverrideAndAudits() {
        Customer customer = activeCustomer(true, "50000", "45000");
        when(customerRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(customer));
        when(customerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(movementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.charge(10L, new BigDecimal("10000"), 100L, 1L, 999L);

        assertThat(customer.getCreditBalance()).isEqualByComparingTo("55000");
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("CREDIT_OVER_LIMIT");
        assertThat(auditCaptor.getValue().authorizationId()).isEqualTo(999L);
    }

    @Test
    void reverseReducesDebtAndRejectsOverReversal() {
        Customer customer = activeCustomer(true, "50000", "20000");
        when(customerRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(customer));
        when(customerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(movementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reverse(10L, new BigDecimal("8000"), 100L, 55L, 1L);
        assertThat(customer.getCreditBalance()).isEqualByComparingTo("12000");

        assertThatThrownBy(() -> service.reverse(10L, new BigDecimal("15000"), 100L, 56L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.PAYMENT_EXCEEDS_DEBT);
    }

    private static Customer activeCustomer(boolean creditEnabled, String limit, String balance) {
        Customer c = new Customer("11111111-1", "Juan Pérez", null, null, null, null);
        c.setCreditEnabled(creditEnabled);
        c.setCreditLimit(new BigDecimal(limit));
        c.setCreditBalance(new BigDecimal(balance));
        return c;
    }
}
