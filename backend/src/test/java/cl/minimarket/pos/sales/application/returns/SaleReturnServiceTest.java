package cl.minimarket.pos.sales.application.returns;

import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.cash.CashSessionApi;
import cl.minimarket.pos.cash.CashSessionRef;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.customers.CustomerCreditApi;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.organization.TerminalApi;
import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import cl.minimarket.pos.sales.application.returns.SaleReturnJdbcRepository.LockedSaleLineRow;
import cl.minimarket.pos.sales.application.returns.SaleReturnJdbcRepository.LockedSaleRow;
import cl.minimarket.pos.sales.application.returns.SaleReturnJdbcRepository.SalePaymentRow;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.VoidSaleCommand;
import cl.minimarket.pos.sales.application.sale.SaleStatus;
import cl.minimarket.pos.sales.payment.PaymentGateway;
import cl.minimarket.pos.sales.payment.RefundResult;
import cl.minimarket.pos.security.RoleLimitApi;
import cl.minimarket.pos.security.SupervisorOverrideApi;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.shrinkage.ShrinkageApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link SaleReturnService} para casos de borde de pasarela y contratos.
 */
class SaleReturnServiceTest {

    @AfterEach
    void cleanUpContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void voidSaleThrowsPaymentDeclinedWhenGatewayRejectsRefund() {
        SaleReturnJdbcRepository jdbcRepository = mock(SaleReturnJdbcRepository.class);
        CashSessionApi cashSessionApi = mock(CashSessionApi.class);
        TerminalApi terminalApi = mock(TerminalApi.class);
        SettingsApi settingsApi = mock(SettingsApi.class);
        StockApi stockApi = mock(StockApi.class);
        PaymentGateway paymentGateway = mock(PaymentGateway.class);
        CustomerCreditApi customerCreditApi = mock(CustomerCreditApi.class);
        ShrinkageApi shrinkageApi = mock(ShrinkageApi.class);
        SupervisorOverrideApi supervisorOverrideApi = mock(SupervisorOverrideApi.class);
        RoleLimitApi roleLimitApi = mock(RoleLimitApi.class);
        SequenceService sequenceService = mock(SequenceService.class);
        AuditService auditService = mock(AuditService.class);
        BusinessClock clock = BusinessClock.fixed(Instant.parse("2026-11-18T15:00:00Z"), ZoneId.of("America/Santiago"));
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        SaleReturnService service = new SaleReturnService(
                jdbcRepository, cashSessionApi, terminalApi, settingsApi, stockApi, paymentGateway,
                customerCreditApi, shrinkageApi, supervisorOverrideApi, roleLimitApi,
                sequenceService, auditService, clock, eventPublisher
        );

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "5", "n/a", List.of(new SimpleGrantedAuthority(Permissions.SALES_SALE_VOID))
        ));

        UUID idempKey = UUID.randomUUID();
        when(jdbcRepository.findIdByIdempotencyKey(idempKey)).thenReturn(Optional.empty());
        when(cashSessionApi.requireOpen(10L)).thenReturn(
                new CashSessionRef(100L, 1L, 10L, 5L, "TU-001", LocalDate.of(2026, 11, 18))
        );
        when(cashSessionApi.isOpen(100L)).thenReturn(true);
        when(jdbcRepository.lockSale(50L, 1L)).thenReturn(Optional.of(new LockedSaleRow(
                50L, "V-001-0001", 1L, 10L, 100L, 5L, null, SaleStatus.COMPLETED,
                LocalDate.of(2026, 11, 18), new BigDecimal("5000"), new BigDecimal("5000")
        )));
        when(jdbcRepository.hasAnyReturnForSale(50L)).thenReturn(false);
        when(jdbcRepository.lockSaleLines(50L)).thenReturn(List.of(new LockedSaleLineRow(
                501L, 50L, 1, 20L, "SKU1", "Producto 1", SaleType.UNIT,
                BigDecimal.ONE, 20L, BigDecimal.ONE, new BigDecimal("5000"),
                new BigDecimal("4202"), new BigDecimal("798"), BigDecimal.ZERO,
                new BigDecimal("2000.0000"), BigDecimal.ZERO
        )));
        when(jdbcRepository.findSalePayments(50L)).thenReturn(List.of(
                new SalePaymentRow(700L, PaymentMethod.DEBIT, new BigDecimal("5000"), "GW-REF-1")
        ));
        when(jdbcRepository.findLotAllocationsBySaleLineIds(any())).thenReturn(java.util.Map.of());
        when(sequenceService.next(SequenceType.SALE_RETURN, 1L)).thenReturn("D-001-0001");
        when(jdbcRepository.insertSaleReturn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(99L);
        when(jdbcRepository.insertSaleReturnLine(any(), any(), any(), any(), any(Boolean.class), any(), any(), any(), any()))
                .thenReturn(991L);
        when(paymentGateway.refund(any())).thenReturn(new RefundResult(false, null, "GW_ERR", "Reversa rechazada"));

        try (var ignored = StoreContext.bind(new StoreContext(1L, 10L))) {
            assertThatThrownBy(() -> service.voidSale(new VoidSaleCommand(
                    50L, SaleReturnReasonCode.CASHIER_ERROR, "Error", idempKey, null
            )))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getCode()).isEqualTo(ErrorCode.PAYMENT_DECLINED);
                    });
        }
    }
}
