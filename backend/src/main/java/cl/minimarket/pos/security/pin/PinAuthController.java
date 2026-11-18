package cl.minimarket.pos.security.pin;

import cl.minimarket.pos.security.auth.dto.TokenResponse;
import cl.minimarket.pos.security.pin.dto.ChangePinRequest;
import cl.minimarket.pos.security.pin.dto.PinLoginRequest;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.context.RequiresTerminal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador REST para gestión de PIN personal ({@code PUT /api/v1/auth/me/pin}) y cambio rápido
 * de operador en terminal POS ({@code POST /api/v1/auth/pin-login}).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Autenticación y sesión del usuario actual")
public class PinAuthController {

    private final PinService pinService;

    /**
     * @param pinService servicio de casos de uso de PIN
     */
    public PinAuthController(PinService pinService) {
        this.pinService = pinService;
    }

    /**
     * Configura o actualiza el PIN del usuario autenticado y reinicia su contador de intentos.
     */
    @PutMapping("/me/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @GlobalEndpoint
    @Operation(
            summary = "Configurar o cambiar PIN propio",
            description = "Permiso: autenticado. Errores: UNAUTHENTICATED, INVALID_CREDENTIALS, VALIDATION_ERROR."
    )
    public void changePin(@Valid @RequestBody ChangePinRequest request) {
        pinService.changePin(request);
    }

    /**
     * Inicia sesión rápida en terminal POS con PIN, exigiendo una sesión vigente ligada a la
     * terminal ({@code X-Store-Id} y {@code X-Terminal-Id} obligatorios).
     */
    @PostMapping("/pin-login")
    @RequiresTerminal
    @Operation(
            summary = "Iniciar sesión POS con PIN",
            description = "Permiso: autenticado (sesión vigente ligada a esa terminal). "
                    + "Errores: UNAUTHENTICATED, STORE_ACCESS_DENIED, TERMINAL_INVALID, TOKEN_INVALID, "
                    + "INVALID_CREDENTIALS, PIN_LOCKED, RATE_LIMITED, VALIDATION_ERROR."
    )
    public TokenResponse pinLogin(@Valid @RequestBody PinLoginRequest request, HttpServletRequest httpRequest) {
        return pinService.pinLogin(request, httpRequest);
    }
}
