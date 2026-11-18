package cl.minimarket.pos.security.override;

import cl.minimarket.pos.security.override.dto.OverrideRequest;
import cl.minimarket.pos.security.override.dto.OverrideResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador REST para autorizaciones de supervisor (overrides) de un solo uso
 * ({@code POST /api/v1/auth/overrides}, API.md F.2).
 *
 * <p>Requiere {@code X-Store-Id} y acepta {@code X-Terminal-Id} opcional (L-02).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Autenticación y sesión del usuario actual")
public class OverrideController {

    private final SupervisorOverrideService overrideService;

    /**
     * @param overrideService servicio de autorización y consumo de overrides
     */
    public OverrideController(SupervisorOverrideService overrideService) {
        this.overrideService = overrideService;
    }

    /**
     * Valida las credenciales y permisos/límites de un supervisor y emite un token de override de un
     * solo uso ligado a la acción, contexto, solicitante, local y terminal (si se indicó).
     */
    @PostMapping("/overrides")
    @Operation(
            summary = "Solicitar autorización (override) de supervisor",
            description = "Permiso: autenticado (requiere X-Store-Id y X-Terminal-Id opcional). "
                    + "Errores: UNAUTHENTICATED, STORE_ACCESS_DENIED, INVALID_CREDENTIALS, OVERRIDE_INVALID, "
                    + "OVERRIDE_LIMIT_EXCEEDED, SEGREGATION_OF_DUTIES, PIN_LOCKED, ACCOUNT_LOCKED, "
                    + "RATE_LIMITED, VALIDATION_ERROR."
    )
    public OverrideResponse authorize(@Valid @RequestBody OverrideRequest request, HttpServletRequest httpRequest) {
        return overrideService.authorize(request, httpRequest);
    }
}
