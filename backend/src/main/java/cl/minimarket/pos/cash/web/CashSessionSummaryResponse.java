package cl.minimarket.pos.cash.web;

import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.cash.application.CashSessionService.SummaryResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resumen financiero y operativo de un turno de caja ({@code API.md} F.3).
 */
public record CashSessionSummaryResponse(
        Long sessionId,
        BigDecimal openingAmount,
        BigDecimal cashIn,
        BigDecimal cashOut,
        List<MethodSummary> byMethod,
        BigDecimal expectedCash,
        int salesCount,
        int voidCount,
        int returnCount
) {
    /** Detalle por medio de pago. */
    public record MethodSummary(
            PaymentMethod method,
            BigDecimal sales,
            BigDecimal refunds,
            BigDecimal creditPayments,
            BigDecimal expected
    ) {
    }

    /** Convierte el resultado de aplicación a DTO de respuesta. */
    public static CashSessionSummaryResponse from(SummaryResult result) {
        return new CashSessionSummaryResponse(
                result.sessionId(),
                result.openingAmount(),
                result.cashIn(),
                result.cashOut(),
                result.byMethod().stream()
                        .map(m -> new MethodSummary(
                                m.method(),
                                m.sales(),
                                m.refunds(),
                                m.creditPayments(),
                                m.expected()
                        ))
                        .toList(),
                result.expectedCash(),
                result.salesCount(),
                result.voidCount(),
                result.returnCount()
        );
    }
}
