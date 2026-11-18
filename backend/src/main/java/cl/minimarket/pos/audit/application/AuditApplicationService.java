package cl.minimarket.pos.audit.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.audit.domain.AuditHasher;
import cl.minimarket.pos.audit.domain.AuditLogEntry;
import cl.minimarket.pos.audit.infrastructure.AuditLogJdbcRepository;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.shared.web.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Implementación de {@link AuditService} que persiste eventos en {@code audit_log} dentro de la
 * transacción del llamador ({@link Propagation#MANDATORY}) con hash SHA-256 encadenado.
 *
 * <p>Serializa la escritura mediante {@code pg_advisory_xact_lock} para garantizar una cadena lineal
 * sin bifurcaciones bajo concurrencia (ADR-18, RN-25).
 */
@Service
public class AuditApplicationService implements AuditService {

    private static final String SYSTEM_USERNAME = "system";
    private static final int MAX_USERNAME_LENGTH = 50;
    private static final int MAX_TRACE_ID_LENGTH = 40;
    private static final int MAX_REASON_LENGTH = 500;
    private static final Pattern IP_LITERAL_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private final AuditLogJdbcRepository repository;
    private final BusinessClock clock;

    /**
     * @param repository repositorio JDBC de {@code audit_log}
     * @param clock reloj de negocio inyectable
     */
    public AuditApplicationService(AuditLogJdbcRepository repository, BusinessClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Registra un evento en {@code audit_log} encadenando su hash al último registro existente.
     *
     * @param event evento a auditar; nunca nulo
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        Objects.requireNonNull(event, "event");

        // Serializa los inserts concurrentes hasta el commit/rollback de la transacción actual.
        repository.acquireChainLock();

        String prevHash = repository.findLastHash().orElse(null);
        Instant occurredAt = clock.now().truncatedTo(ChronoUnit.MICROS);
        Long userId = CurrentUserId.find().orElse(null);
        String username = resolveUsername(userId);
        StoreContext storeContext = StoreContext.current().orElse(null);
        Long storeId = storeContext != null ? storeContext.storeId() : null;
        Long terminalId = storeContext != null ? storeContext.terminalId() : null;
        String ip = repository.canonicalizeIp(resolveClientIp());
        String traceId = truncate(MDC.get(TraceIdFilter.MDC_KEY), MAX_TRACE_ID_LENGTH);
        String reason = truncate(event.reason(), MAX_REASON_LENGTH);

        JsonNode oldValue = repository.canonicalizeJson(AuditHasher.toJsonNode(event.oldValue()));
        JsonNode newValue = repository.canonicalizeJson(AuditHasher.toJsonNode(event.newValue()));

        AuditLogEntry unsignedEntry = new AuditLogEntry(
                null,
                occurredAt,
                userId,
                username,
                storeId,
                terminalId,
                ip,
                event.action(),
                event.entityType(),
                event.entityId(),
                oldValue,
                newValue,
                reason,
                event.authorizationId(),
                traceId,
                prevHash,
                null
        );

        String hash = AuditHasher.computeHash(prevHash, unsignedEntry);
        AuditLogEntry signedEntry = new AuditLogEntry(
                null,
                occurredAt,
                userId,
                username,
                storeId,
                terminalId,
                ip,
                event.action(),
                event.entityType(),
                event.entityId(),
                oldValue,
                newValue,
                reason,
                event.authorizationId(),
                traceId,
                prevHash,
                hash
        );

        repository.insert(signedEntry);
    }

    private String resolveUsername(Long userId) {
        if (userId != null) {
            String fromDb = repository.findUsernameById(userId).orElse(null);
            if (fromDb != null && !fromDb.isBlank()) {
                return truncate(fromDb, MAX_USERNAME_LENGTH);
            }
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            String name = auth.getName();
            if (name != null && !name.isBlank()) {
                return truncate(name, MAX_USERNAME_LENGTH);
            }
        }
        return SYSTEM_USERNAME;
    }

    /**
     * Obtiene la IP del cliente desde {@code request.getRemoteAddr()} sin confiar en cabeceras
     * manipulables como {@code X-Forwarded-For}; Spring/el contenedor ya reescribe {@code remoteAddr}
     * cuando existe un proxy de confianza configurado.
     */
    private static String resolveClientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        String remote = attrs.getRequest().getRemoteAddr();
        return isValidIpLiteral(remote) ? remote : null;
    }

    private static boolean isValidIpLiteral(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (!IP_LITERAL_CHARS.matcher(candidate).matches()) {
            return false;
        }
        if (!candidate.contains(".") && !candidate.contains(":")) {
            return false;
        }
        try {
            InetAddress.getByName(candidate);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
