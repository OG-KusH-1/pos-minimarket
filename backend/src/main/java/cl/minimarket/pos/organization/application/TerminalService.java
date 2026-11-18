package cl.minimarket.pos.organization.application;

import cl.minimarket.pos.organization.TerminalApi;
import cl.minimarket.pos.organization.TerminalInfo;
import cl.minimarket.pos.organization.TerminalUsageGuard;
import cl.minimarket.pos.organization.domain.StoreRepository;
import cl.minimarket.pos.organization.domain.Terminal;
import cl.minimarket.pos.organization.domain.TerminalRepository;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Casos de uso de terminales POS ({@code terminals}) e implementación de {@link TerminalApi}.
 */
@Service
public class TerminalService implements TerminalApi {

    private final TerminalRepository terminalRepository;
    private final StoreRepository storeRepository;
    private final StoreService storeService;
    private final TerminalUsageGuard terminalUsageGuard;

    public TerminalService(TerminalRepository terminalRepository,
                           StoreRepository storeRepository,
                           StoreService storeService,
                           TerminalUsageGuard terminalUsageGuard) {
        this.terminalRepository = terminalRepository;
        this.storeRepository = storeRepository;
        this.storeService = storeService;
        this.terminalUsageGuard = terminalUsageGuard;
    }

    /** Comando de creación o edición de una terminal. */
    public record TerminalCommand(String code, String name) {
    }

    @Override
    @Transactional(readOnly = true)
    public TerminalInfo requireActive(Long terminalId, Long storeId) {
        if (terminalId == null || storeId == null) {
            throw new BusinessException(ErrorCode.TERMINAL_INVALID, "Terminal inválida.");
        }
        Terminal terminal = terminalRepository.findByIdAndStoreId(terminalId, storeId)
                .filter(Terminal::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.TERMINAL_INVALID,
                        "La terminal no existe, está inactiva o no pertenece al local."));
        return new TerminalInfo(
                terminal.getId(),
                terminal.getStoreId(),
                terminal.getCode(),
                terminal.getName(),
                terminal.isActive(),
                terminal.getLastSeenAt()
        );
    }

    /**
     * Lista las terminales de un local, validando que el local exista y que el usuario tenga acceso.
     */
    @Transactional(readOnly = true)
    public List<Terminal> listByStore(Long storeId) {
        if (!storeRepository.existsById(storeId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Local no encontrado.");
        }
        storeService.requireStoreAccess(storeId);
        return terminalRepository.findByStoreIdOrderByCodeAsc(storeId);
    }

    /** Crea una terminal en el local indicado (debe coincidir con {@code StoreContext}). */
    @Transactional
    public Terminal create(Long storeId, TerminalCommand cmd) {
        Long activeStoreId = StoreContext.require().storeId();
        if (!activeStoreId.equals(storeId)) {
            throw new BusinessException(ErrorCode.STORE_ACCESS_DENIED,
                    "El local de la ruta no coincide con X-Store-Id.");
        }
        if (!storeRepository.existsById(storeId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Local no encontrado.");
        }
        String code = cmd.code().trim();
        if (terminalRepository.existsByStoreIdAndCode(storeId, code)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe una terminal con el código " + code + " en este local.");
        }
        Terminal terminal = new Terminal();
        terminal.setStoreId(storeId);
        terminal.setCode(code);
        terminal.setName(cmd.name().trim());
        terminal.setActive(true);
        return terminalRepository.save(terminal);
    }

    /** Edita una terminal perteneciente al local activo. */
    @Transactional
    public Terminal update(Long id, TerminalCommand cmd) {
        Terminal terminal = requireOwnedTerminal(id);
        String code = cmd.code().trim();
        if (terminalRepository.existsByStoreIdAndCodeAndIdNot(terminal.getStoreId(), code, id)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe una terminal con el código " + code + " en este local.");
        }
        terminal.setCode(code);
        terminal.setName(cmd.name().trim());
        return terminalRepository.save(terminal);
    }

    /** Desactiva una terminal previa validación con {@link TerminalUsageGuard}. */
    @Transactional
    public void deactivate(Long id) {
        Terminal terminal = requireOwnedTerminal(id);
        terminalUsageGuard.assertCanDeactivate(id);
        if (terminal.isActive()) {
            terminal.setActive(false);
            terminalRepository.save(terminal);
        }
    }

    /** Reactiva una terminal perteneciente al local activo. */
    @Transactional
    public void activate(Long id) {
        Terminal terminal = requireOwnedTerminal(id);
        if (!terminal.isActive()) {
            terminal.setActive(true);
            terminalRepository.save(terminal);
        }
    }

    private Terminal requireOwnedTerminal(Long id) {
        Terminal terminal = terminalRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Terminal no encontrada."));
        Long activeStoreId = StoreContext.require().storeId();
        if (!activeStoreId.equals(terminal.getStoreId())) {
            throw new BusinessException(ErrorCode.STORE_ACCESS_DENIED,
                    "La terminal no pertenece al local activo.");
        }
        return terminal;
    }
}
