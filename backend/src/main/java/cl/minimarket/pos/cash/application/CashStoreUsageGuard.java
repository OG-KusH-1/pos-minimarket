package cl.minimarket.pos.cash.application;

import cl.minimarket.pos.cash.CashSessionStatus;
import cl.minimarket.pos.cash.domain.CashSessionRepository;
import cl.minimarket.pos.organization.StoreUsageGuard;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementación real de {@link StoreUsageGuard} ({@code PLAN.md} A.4).
 *
 * <p>Impide desactivar un local mientras tenga al menos un turno de caja en estado
 * {@link CashSessionStatus#OPEN}. Reemplaza al guard por defecto de {@code organization}.
 */
@Service
public class CashStoreUsageGuard implements StoreUsageGuard {

    private final CashSessionRepository sessionRepository;

    public CashStoreUsageGuard(CashSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public void assertCanDeactivate(Long storeId) {
        if (sessionRepository.existsByStoreIdAndStatus(storeId, CashSessionStatus.OPEN)) {
            throw new BusinessException(
                    ErrorCode.ENTITY_IN_USE,
                    "No se puede desactivar el local porque tiene turnos de caja abiertos."
            );
        }
    }
}
