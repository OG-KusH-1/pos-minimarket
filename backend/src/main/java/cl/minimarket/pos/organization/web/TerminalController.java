package cl.minimarket.pos.organization.web;

import cl.minimarket.pos.organization.application.TerminalService;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints de terminales POS ({@code API.md} F.2).
 *
 * <p>Solo {@code GET /stores/{storeId}/terminals} es {@code @GlobalEndpoint} (L-02).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Organization")
public class TerminalController {

    private final TerminalService terminalService;

    public TerminalController(TerminalService terminalService) {
        this.terminalService = terminalService;
    }

    /** Lista las terminales de un local (endpoint global; valida acceso al local). */
    @GetMapping("/stores/{storeId}/terminals")
    @GlobalEndpoint
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar terminales de un local")
    public List<TerminalResponse> listByStore(@PathVariable Long storeId) {
        return terminalService.listByStore(storeId).stream()
                .map(TerminalResponse::from)
                .toList();
    }

    /** Crea una terminal en el local activo (requiere {@code X-Store-Id}). */
    @PostMapping("/stores/{storeId}/terminals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_TERMINAL_MANAGE)")
    @Operation(summary = "Crear terminal")
    public TerminalResponse create(@PathVariable Long storeId, @Valid @RequestBody TerminalRequest request) {
        return TerminalResponse.from(terminalService.create(
                storeId, new TerminalService.TerminalCommand(request.code(), request.name())));
    }

    /** Actualiza una terminal del local activo. */
    @PutMapping("/terminals/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_TERMINAL_MANAGE)")
    @Operation(summary = "Actualizar terminal")
    public TerminalResponse update(@PathVariable Long id, @Valid @RequestBody TerminalRequest request) {
        return TerminalResponse.from(terminalService.update(
                id, new TerminalService.TerminalCommand(request.code(), request.name())));
    }

    /** Desactiva una terminal si no tiene turno abierto ({@code TerminalUsageGuard}). */
    @PostMapping("/terminals/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_TERMINAL_MANAGE)")
    @Operation(summary = "Desactivar terminal")
    public void deactivate(@PathVariable Long id) {
        terminalService.deactivate(id);
    }

    /** Reactiva una terminal del local activo. */
    @PostMapping("/terminals/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_TERMINAL_MANAGE)")
    @Operation(summary = "Activar terminal")
    public void activate(@PathVariable Long id) {
        terminalService.activate(id);
    }
}
