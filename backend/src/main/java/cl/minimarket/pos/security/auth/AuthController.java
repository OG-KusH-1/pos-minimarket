package cl.minimarket.pos.security.auth;

import cl.minimarket.pos.security.auth.dto.ChangePasswordRequest;
import cl.minimarket.pos.security.auth.dto.LoginRequest;
import cl.minimarket.pos.security.auth.dto.MeResponse;
import cl.minimarket.pos.security.auth.dto.TokenResponse;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador REST de autenticación núcleo ({@code /api/v1/auth}).
 *
 * <p>Todos los endpoints de este controlador son globales ([G]): no requieren {@code X-Store-Id}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Autenticación y sesión del usuario actual")
public class AuthController {

    private final AuthService authService;

    /**
     * @param authService servicio de casos de uso de autenticación
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Inicia sesión con usuario y contraseña, devolviendo el access token JWT y el perfil del usuario.
     */
    @PostMapping("/login")
    @GlobalEndpoint
    @Operation(
            summary = "Iniciar sesión",
            description = "Permiso: público. Errores: INVALID_CREDENTIALS, ACCOUNT_LOCKED, RATE_LIMITED, VALIDATION_ERROR."
    )
    public TokenResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest,
                               HttpServletResponse httpResponse) {
        return authService.login(request, httpRequest, httpResponse);
    }

    /**
     * Devuelve la información del usuario autenticado, sus roles, permisos, locales y límites.
     */
    @GetMapping("/me")
    @GlobalEndpoint
    @Operation(
            summary = "Obtener perfil actual",
            description = "Permiso: autenticado. Errores: UNAUTHENTICATED."
    )
    public MeResponse me() {
        return authService.me();
    }

    /**
     * Cambia la contraseña del usuario autenticado y limpia el indicador {@code mustChangePassword}.
     */
    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @GlobalEndpoint
    @Operation(
            summary = "Cambiar contraseña propia",
            description = "Permiso: autenticado. Errores: UNAUTHENTICATED, INVALID_CREDENTIALS, PASSWORD_POLICY, VALIDATION_ERROR."
    )
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
    }
}
