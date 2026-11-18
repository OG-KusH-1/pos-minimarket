package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Utilidades internas de seguridad y contexto compartidas por los servicios de ventas.
 */
final class SaleSecuritySupport {

    private SaleSecuritySupport() {
    }

    static Long requireCurrentUserId() {
        return CurrentUserId.find().orElseThrow(() ->
                new BusinessException(ErrorCode.UNAUTHENTICATED, "No hay usuario autenticado."));
    }

    static boolean hasFullSaleReadScope() {
        return hasAuthority(Permissions.SALES_SALE_VOID)
                || hasAuthority(Permissions.SALES_RETURN_CREATE)
                || hasAuthority(Permissions.SALES_DISCOUNT_OVERRIDE)
                || hasAuthority(Permissions.SALES_RECEIPT_REPRINT)
                || hasAuthority(Permissions.REPORT_SALES_READ);
    }

    static boolean hasAuthority(String code) {
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

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
