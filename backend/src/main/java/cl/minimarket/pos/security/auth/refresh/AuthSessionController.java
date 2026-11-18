package cl.minimarket.pos.security.auth.refresh;

import cl.minimarket.pos.security.auth.dto.TokenResponse;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador REST para rotación de refresh token, cierre de sesión y asociación de terminal POS
 * ({@code /api/v1/auth}).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Autenticación y sesión del usuario actual")
public class AuthSessionController {

    private final RefreshTokenService refreshTokenService;

    /**
     * @param refreshTokenService servicio de ciclo de vida de refresh tokens
     */
    public AuthSessionController(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Rota el refresh token de la cookie {@code pos_refresh} y devuelve un nuevo access token JWT.
     */
    @PostMapping("/refresh")
    @GlobalEndpoint
    @Operation(
            summary = "Refrescar access token",
            description = "Permiso: cookie pos_refresh. Errores: TOKEN_INVALID."
    )
    public TokenResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        return refreshTokenService.refresh(request, response);
    }

    /**
     * Cierra la sesión actual revocando la familia del refresh token y borrando la cookie.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @GlobalEndpoint
    @Operation(
            summary = "Cerrar sesión",
            description = "Permiso: autenticado. Errores: UNAUTHENTICATED."
    )
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        refreshTokenService.logout(request, response);
    }

    /**
     * Asocia el refresh token vigente de la sesión a una terminal activa del local indicado en
     * {@code X-Store-Id} (no es {@code @GlobalEndpoint}, L-02).
     */
    @PostMapping("/session/terminal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Ligar sesión a terminal POS",
            description = "Permiso: autenticado (requiere X-Store-Id). Errores: UNAUTHENTICATED, STORE_ACCESS_DENIED, TERMINAL_INVALID, TOKEN_INVALID, VALIDATION_ERROR."
    )
    public void bindSessionTerminal(
            @Valid @RequestBody SessionTerminalRequest request,
            HttpServletRequest httpRequest
    ) {
        refreshTokenService.bindSessionTerminal(request, httpRequest);
    }
}
