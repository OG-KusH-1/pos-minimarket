package cl.minimarket.pos.cash.web;

import cl.minimarket.pos.cash.CashSessionStatus;
import cl.minimarket.pos.cash.application.CashSessionService.SessionDetail;
import cl.minimarket.pos.cash.domain.CashLookupJdbcRepository.TerminalRefInfo;
import cl.minimarket.pos.cash.domain.CashLookupJdbcRepository.UserInfo;
import cl.minimarket.pos.cash.domain.CashSession;
import cl.minimarket.pos.organization.StoreInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Representación pública de un turno de caja ({@code API.md} F.3).
 */
public record CashSessionResponse(
        Long id,
        String number,
        StoreRef store,
        TerminalRef terminal,
        UserRef cashier,
        CashSessionStatus status,
        LocalDate businessDate,
        Instant openedAt,
        BigDecimal openingAmount,
        Instant closedAt,
        UserRef closedBy,
        BigDecimal expectedTotal,
        BigDecimal countedTotal,
        BigDecimal differenceTotal,
        boolean requiresDifferenceApproval,
        UserRef differenceApprovedBy,
        List<CashCountResponse> counts
) {
    /** Referencia compacta de local. */
    public record StoreRef(Long id, String code, String name) {
    }

    /** Referencia compacta de terminal. */
    public record TerminalRef(Long id, String code, String name) {
    }

    /** Referencia compacta de usuario. */
    public record UserRef(Long id, String username, String fullName) {
    }

    /** Convierte el detalle de aplicación a DTO de respuesta. */
    public static CashSessionResponse from(SessionDetail detail) {
        CashSession s = detail.session();
        StoreInfo st = detail.store();
        return new CashSessionResponse(
                s.getId(),
                s.getNumber(),
                st != null ? new StoreRef(st.id(), st.code(), st.name()) : new StoreRef(s.getStoreId(), null, null),
                toTerminalRef(detail.terminal(), s.getTerminalId()),
                toUserRef(detail.cashier(), s.getCashierUserId()),
                s.getStatus(),
                s.getBusinessDate(),
                s.getOpenedAt(),
                s.getOpeningAmount(),
                s.getClosedAt(),
                s.getClosedBy() != null ? toUserRef(detail.closedBy(), s.getClosedBy()) : null,
                s.getExpectedTotal(),
                s.getCountedTotal(),
                s.getDifferenceTotal(),
                detail.requiresDifferenceApproval(),
                s.getDifferenceApprovedBy() != null
                        ? toUserRef(detail.differenceApprovedBy(), s.getDifferenceApprovedBy())
                        : null,
                detail.counts().stream().map(CashCountResponse::from).toList()
        );
    }

    private static TerminalRef toTerminalRef(TerminalRefInfo info, Long fallbackId) {
        if (info == null) {
            return new TerminalRef(fallbackId, null, null);
        }
        return new TerminalRef(info.id(), info.code(), info.name());
    }

    private static UserRef toUserRef(UserInfo info, Long fallbackId) {
        if (info == null) {
            return new UserRef(fallbackId, null, null);
        }
        return new UserRef(info.id(), info.username(), info.fullName());
    }
}
