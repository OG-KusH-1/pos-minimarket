package cl.minimarket.pos.audit.web;

import cl.minimarket.pos.audit.application.AuditLogFilter;
import cl.minimarket.pos.audit.application.AuditQueryService;
import cl.minimarket.pos.audit.application.AuditVerificationResult;
import cl.minimarket.pos.audit.domain.AuditLogEntry;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Controlador REST del módulo de auditoría ({@code /api/v1/audit-log}).
 *
 * <p>Todos sus endpoints son globales ({@link GlobalEndpoint}, marcados [G] en F.2 de
 * {@code API.md}) y no exigen cabecera {@code X-Store-Id}.
 */
@RestController
@RequestMapping("/api/v1/audit-log")
@GlobalEndpoint
@Tag(name = "Audit", description = "Bitácora de auditoría inmutable y verificación de cadena de hash")
public class AuditController {

    private static final SortParser SORT = SortParser
            .allowing("id", "occurredAt", "action", "entityType", "username", "userId", "storeId")
            .withDefault(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));

    private final AuditQueryService queryService;

    /**
     * @param queryService servicio de consulta y verificación de {@code audit_log}
     */
    public AuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Lista registros de auditoría de forma paginada y filtrable.
     *
     * <p>Los gerentes sin {@code security:store:all-access} solo ven registros de sus locales
     * asignados.
     */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_AUDIT_READ)")
    @Operation(
            summary = "Consultar bitácora de auditoría",
            description = "Permiso: security:audit:read. Errores: VALIDATION_ERROR, ACCESS_DENIED."
    )
    public PageResponse<AuditLogResponse> list(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort
    ) {
        PageQuery pageQuery = PageQuery.of(page, size);
        Pageable pageable = pageQuery.toPageable(SORT.parse(sort));
        AuditLogFilter filter = new AuditLogFilter(from, to, action, entityType, entityId, userId, storeId);
        Page<AuditLogEntry> result = queryService.search(filter, pageable);
        return PageResponse.from(result, AuditLogResponse::from);
    }

    /**
     * Obtiene el detalle de un registro de auditoría por su identificador.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_AUDIT_READ)")
    @Operation(
            summary = "Obtener detalle de registro de auditoría",
            description = "Permiso: security:audit:read. Errores: NOT_FOUND, ACCESS_DENIED."
    )
    public AuditLogResponse getById(@PathVariable long id) {
        return AuditLogResponse.from(queryService.findById(id));
    }

    /**
     * Verifica la integridad de la cadena de hash SHA-256 en el rango indicado.
     */
    @PostMapping("/verify")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_AUDIT_VERIFY)")
    @Operation(
            summary = "Verificar cadena de hash de auditoría",
            description = "Permiso: security:audit:verify. Errores: VALIDATION_ERROR, ACCESS_DENIED."
    )
    public AuditVerifyResponse verify(@RequestBody(required = false) AuditVerifyRequest request) {
        Long fromId = request != null ? request.fromId() : null;
        Long toId = request != null ? request.toId() : null;
        AuditVerificationResult result = queryService.verify(fromId, toId);
        return AuditVerifyResponse.from(result);
    }
}
