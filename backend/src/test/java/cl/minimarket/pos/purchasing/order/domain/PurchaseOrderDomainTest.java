package cl.minimarket.pos.purchasing.order.domain;

import cl.minimarket.pos.purchasing.PurchaseOrderOrigin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias de la entidad {@link PurchaseOrder} y sus transiciones de estado.
 */
class PurchaseOrderDomainTest {

    @Test
    void transitionsAndTotalsWorkCorrectly() {
        PurchaseOrder order = new PurchaseOrder(
                "L01-OC-00000001",
                1L,
                10L,
                PurchaseOrderOrigin.MANUAL,
                null,
                LocalDate.of(2026, 12, 1),
                "Nota inicial");

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);

        PurchaseOrderLine line = new PurchaseOrderLine(
                5L, 20L,
                new BigDecimal("2.000"),
                new BigDecimal("12.000"),
                new BigDecimal("24.000"),
                new BigDecimal("500.0000"),
                new BigDecimal("12000"),
                null);
        order.addLine(line);
        order.applyTotals(new BigDecimal("12000"), new BigDecimal("2280"), new BigDecimal("14280"));

        assertThat(order.getLines()).hasSize(1);
        assertThat(order.getTotal()).isEqualByComparingTo("14280");

        Instant sentAt = Instant.parse("2026-11-18T12:00:00Z");
        order.markSent(sentAt, 1L);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.SENT);
        assertThat(order.getSentAt()).isEqualTo(sentAt);
        assertThat(order.getSentBy()).isEqualTo(1L);

        Instant cancelledAt = Instant.parse("2026-11-18T13:00:00Z");
        order.markCancelled(cancelledAt, "Proveedor sin stock");
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(order.getCancelReason()).isEqualTo("Proveedor sin stock");
    }
}
