package cl.minimarket.pos.cash.application;

import cl.minimarket.pos.cash.CashSessionStatus;
import cl.minimarket.pos.cash.domain.CashSessionRepository;
import cl.minimarket.pos.organization.TerminalUsageGuard;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementación real de {@link TerminalUsageGuard} ({@code PLAN.md} A.4).
 *
 * <p>Impide desactivar una terminal POS mientras tenga un turno de caja en estado
 * {@link CashSessionStatus#OPEN}. Reemplaza al guard por defecto de {@code organization}.
 */
@Service
public class CashTerminalUsageGuard implements TerminalUsageGuard {

    private final CashSessionRepository sessionRepository;

    public CashTerminalUsageGuard(CashSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public void assertCanDeactivate(Long terminalId) {
        if (sessionRepository.existsByTerminalIdAndStatus(terminalId, CashSessionStatus.OPEN)) {
            throw new BusinessException(
                    ErrorCode.ENTITY_IN_USE,
                    "No se puede desactivar la terminal porque tiene un turno de caja abierto."
            );
        }
    }
}
