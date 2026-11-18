package cl.minimarket.pos.audit.application;

import cl.minimarket.pos.audit.domain.AuditHasher;
import cl.minimarket.pos.audit.domain.AuditLogEntry;
import cl.minimarket.pos.audit.infrastructure.AuditLogJdbcRepository;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.security.Permissions;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Casos de uso de consulta y verificación de integridad de la bitácora de auditoría.
 */
@Service
public class AuditQueryService {

    private static final int VERIFY_BATCH_SIZE = 500;

    private final AuditLogJdbcRepository repository;

    /**
     * @param repository repositorio JDBC de {@code audit_log}
     */
    public AuditQueryService(AuditLogJdbcRepository repository) {
        this.repository = repository;
    }

    /**
     * Consulta paginada de {@code audit_log} con filtros.
     *
     * <p>Si el usuario autenticado no posee {@link Permissions#SECURITY_STORE_ALL_ACCESS}, la
     * consulta se restringe automáticamente a sus locales asignados en {@code user_stores}.
     *
     * @param filter criterios de filtrado
     * @param pageable paginación y orden validados
     * @return página de registros de auditoría
     */
    @Transactional(readOnly = true)
    public Page<AuditLogEntry> search(AuditLogFilter filter, Pageable pageable) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(pageable, "pageable");
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El rango de fechas es inválido: 'from' no puede ser posterior a 'to'.",
                    Map.of(),
                    List.of(new FieldErrorDetail("from", "INVALID_RANGE",
                            "'from' debe ser menor o igual que 'to'.")));
        }
        Long scopedUserId = resolveScopedUserId();
        return repository.search(filter, scopedUserId, pageable);
    }

    /**
     * Obtiene un registro puntual de {@code audit_log} por su identificador.
     *
     * @param id identificador del registro
     * @return registro encontrado
     * @throws BusinessException 404 {@code NOT_FOUND} si no existe o no pertenece a los locales
     *         del usuario
     */
    @Transactional(readOnly = true)
    public AuditLogEntry findById(long id) {
        Long scopedUserId = resolveScopedUserId();
        return repository.findById(id, scopedUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Registro de auditoría no encontrado."));
    }

    /**
     * Recorre la cadena de hash SHA-256 en el rango {@code [fromId, toId]} (o toda la bitácora si
     * ambos son nulos) comprobando tanto el enlace {@code prev_hash} como el recalculo del
     * {@code hash} de cada fila.
     *
     * @param fromId cota inferior inclusiva de {@code id}, o {@code null} para iniciar desde el principio
     * @param toId cota superior inclusiva de {@code id}, o {@code null} para verificar hasta el final
     * @return resultado con cantidad verificada, validez y el primer {@code id} roto si existe
     */
    @Transactional(readOnly = true)
    public AuditVerificationResult verify(Long fromId, Long toId) {
        if (fromId != null && toId != null && fromId > toId) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El rango de verificación es inválido: 'fromId' no puede ser mayor que 'toId'.",
                    Map.of(),
                    List.of(new FieldErrorDetail("fromId", "INVALID_RANGE",
                            "'fromId' debe ser menor o igual que 'toId'.")));
        }

        String expectedPrevHash = fromId == null
                ? null
                : repository.findHashBefore(fromId).orElse(null);

        long nextMinId = fromId != null ? fromId : 1L;
        long checked = 0L;

        while (true) {
            List<AuditLogEntry> batch = repository.findBatchForVerification(nextMinId, toId, VERIFY_BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (AuditLogEntry entry : batch) {
                checked++;
                if (!Objects.equals(expectedPrevHash, entry.prevHash())) {
                    return new AuditVerificationResult(checked, false, entry.id());
                }
                String recomputedHash = AuditHasher.computeHash(entry.prevHash(), entry);
                if (!recomputedHash.equals(entry.hash())) {
                    return new AuditVerificationResult(checked, false, entry.id());
                }
                expectedPrevHash = entry.hash();
            }
            if (batch.size() < VERIFY_BATCH_SIZE) {
                break;
            }
            nextMinId = batch.getLast().id() + 1;
        }

        return new AuditVerificationResult(checked, true, null);
    }

    private Long resolveScopedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> Permissions.SECURITY_STORE_ALL_ACCESS.equals(a.getAuthority()))) {
            return null;
        }
        return CurrentUserId.find().orElseThrow(() -> new AccessDeniedException(
                "No se pudo determinar el identificador numérico del usuario autenticado."));
    }
}
