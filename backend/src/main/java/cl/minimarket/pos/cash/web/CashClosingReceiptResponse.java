package cl.minimarket.pos.cash.web;

import cl.minimarket.pos.cash.application.CashSessionService.ClosingReceiptResult;
import cl.minimarket.pos.cash.domain.CashLookupJdbcRepository.CompanyRefInfo;
import cl.minimarket.pos.organization.StoreInfo;

import java.time.Instant;

/**
 * Respuesta JSON del comprobante de cierre de caja ({@code GET /cash-sessions/{id}/closing-receipt?format=json}).
 */
public record CashClosingReceiptResponse(
        CashSessionResponse session,
        CashSessionSummaryResponse summary,
        CompanyRef company,
        StoreDetailRef store,
        Instant printedAt
) {
    /** Datos de la empresa emisora. */
    public record CompanyRef(
            String rut,
            String legalName,
            String tradeName,
            String address,
            String commune,
            String city,
            String phone,
            String email
    ) {
    }

    /** Datos del local donde operó el turno. */
    public record StoreDetailRef(
            Long id,
            String code,
            String name,
            String address,
            String commune,
            String city,
            String phone
    ) {
    }

    /** Convierte el resultado de aplicación a DTO de respuesta. */
    public static CashClosingReceiptResponse from(ClosingReceiptResult result) {
        CompanyRefInfo c = result.company();
        StoreInfo s = result.store();
        return new CashClosingReceiptResponse(
                CashSessionResponse.from(result.session()),
                CashSessionSummaryResponse.from(result.summary()),
                c != null ? new CompanyRef(c.rut(), c.legalName(), c.tradeName(), c.address(),
                        c.commune(), c.city(), c.phone(), c.email()) : null,
                s != null ? new StoreDetailRef(s.id(), s.code(), s.name(), s.address(),
                        s.commune(), s.city(), s.phone()) : null,
                result.printedAt()
        );
    }
}
