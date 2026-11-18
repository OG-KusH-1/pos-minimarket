package cl.minimarket.pos.organization.web;

import cl.minimarket.pos.organization.SettingScope;
import cl.minimarket.pos.organization.application.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints de parámetros de configuración ({@code API.md} F.2).
 *
 * <p>No son {@code @GlobalEndpoint} (L-02): requieren {@code X-Store-Id}.
 */
@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "Organization")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** Lista parámetros filtrando por alcance y/o prefijo. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_SETTINGS_READ)")
    @Operation(summary = "Listar parámetros de configuración")
    public List<SettingResponse> list(@RequestParam(required = false) SettingScope scope,
                                      @RequestParam(required = false) String prefix) {
        return settingsService.list(scope, prefix).stream()
                .map(SettingResponse::from)
                .toList();
    }

    /**
     * Devuelve los parámetros efectivos ({@code STORE} sobre {@code GLOBAL}) para el local activo
     * (solo claves {@code pos.*}, {@code ticket.*}, {@code alerts.*} y {@code cash.*}).
     */
    @GetMapping("/effective")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Obtener parámetros efectivos del local")
    public EffectiveSettingsResponse effective() {
        return new EffectiveSettingsResponse(settingsService.getEffectiveForCurrentStore());
    }

    /** Actualiza un parámetro global o del local activo, validando tipo, rango y permisos. */
    @PutMapping("/{key:.+}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_SETTINGS_UPDATE)")
    @Operation(summary = "Actualizar parámetro de configuración")
    public SettingResponse update(@PathVariable String key, @Valid @RequestBody SettingUpdateRequest request) {
        return SettingResponse.from(settingsService.update(key, request.scope(), request.value()));
    }
}
