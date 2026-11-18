package cl.minimarket.pos.shared.context.support;

import cl.minimarket.pos.shared.context.StoreAccessValidator;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * {@link StoreAccessValidator} <b>solo para pruebas</b> (la implementación real es de security, T21).
 *
 * <p>Por defecto permite todo (no-op). Se puede configurar para negar locales concretos o todo
 * acceso, y registra cada invocación para verificar que el filtro la hizo con los argumentos
 * correctos. Llamar a {@link #reset()} antes de cada prueba.
 */
public class ConfigurableStoreAccessValidator implements StoreAccessValidator {

    /** Una invocación registrada del puerto. */
    public record Call(Long userId, Long storeId, Long terminalId) {
    }

    private final Set<Long> deniedStores = new CopyOnWriteArraySet<>();
    private final Set<Long> invalidTerminals = new CopyOnWriteArraySet<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile boolean denyAll;

    @Override
    public void requireAccess(Long userId, Long storeId, Long terminalId) {
        calls.add(new Call(userId, storeId, terminalId));
        if (denyAll || deniedStores.contains(storeId)) {
            throw new BusinessException(ErrorCode.STORE_ACCESS_DENIED, "Sin acceso al local " + storeId);
        }
        if (terminalId != null && invalidTerminals.contains(terminalId)) {
            throw new BusinessException(ErrorCode.TERMINAL_INVALID, "Terminal inválida " + terminalId);
        }
    }

    /** Niega el acceso a todos los locales. */
    public void denyAll() {
        denyAll = true;
    }

    /** Niega el acceso a un local concreto. */
    public void denyStore(Long storeId) {
        deniedStores.add(storeId);
    }

    /** Declara inválida una terminal (lanza {@code TERMINAL_INVALID}). */
    public void invalidateTerminal(Long terminalId) {
        invalidTerminals.add(terminalId);
    }

    /** @return invocaciones registradas desde el último {@link #reset()} */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Vuelve al comportamiento por defecto (permitir todo) y borra el registro. */
    public void reset() {
        denyAll = false;
        deniedStores.clear();
        invalidTerminals.clear();
        calls.clear();
    }
}
