package cl.minimarket.pos.sales.payment.infrastructure;

import cl.minimarket.pos.sales.payment.CardPaymentMethod;
import cl.minimarket.pos.sales.payment.PaymentRequest;
import cl.minimarket.pos.sales.payment.PaymentResult;
import cl.minimarket.pos.sales.payment.PaymentResultStatus;
import cl.minimarket.pos.sales.payment.RefundRequest;
import cl.minimarket.pos.sales.payment.RefundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias de {@link SimulatedPaymentGateway} ({@code PLAN.md} A.4, H.3 T33).
 */
class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    @Test
    void approvesNormalAmountsAndNeverExposesFullPan() {
        PaymentResult result = gateway.authorize(new PaymentRequest(
                1L, CardPaymentMethod.DEBIT, new BigDecimal("5000"), UUID.randomUUID(), "CS-1"
        ));

        assertThat(result.status()).isEqualTo(PaymentResultStatus.APPROVED);
        assertThat(result.gatewayReference()).startsWith("SIM-");
        assertThat(result.authorizationCode()).hasSize(6);
        assertThat(result.cardBrand()).isEqualTo("REDCOMPRA");
        assertThat(result.last4()).hasSize(4);
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void declinesAmountsEndingIn13() {
        PaymentResult result = gateway.authorize(new PaymentRequest(
                1L, CardPaymentMethod.CREDIT, new BigDecimal("12013"), UUID.randomUUID(), "CS-1"
        ));

        assertThat(result.status()).isEqualTo(PaymentResultStatus.DECLINED);
        assertThat(result.authorizationCode()).isNull();
        assertThat(result.errorCode()).isEqualTo("PAYMENT_DECLINED");
    }

    @Test
    void returnsErrorForAmountsEndingIn99() {
        PaymentResult result = gateway.authorize(new PaymentRequest(
                1L, CardPaymentMethod.CREDIT, new BigDecimal("99"), UUID.randomUUID(), "CS-1"
        ));

        assertThat(result.status()).isEqualTo(PaymentResultStatus.ERROR);
        assertThat(result.gatewayReference()).isNull();
        assertThat(result.errorCode()).isEqualTo("GATEWAY_ERROR");
    }

    @Test
    void refundsSuccessfully() {
        RefundResult refund = gateway.refund(new RefundRequest("SIM-1234", new BigDecimal("5000"), UUID.randomUUID()));

        assertThat(refund.success()).isTrue();
        assertThat(refund.gatewayReference()).startsWith("SIM-REF-");
        assertThat(gateway.providerCode()).isEqualTo("SIMULATED");
    }
}
