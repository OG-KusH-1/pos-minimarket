package cl.minimarket.pos.organization.web;

import cl.minimarket.pos.organization.application.StoreService;
import cl.minimarket.pos.organization.domain.Store;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints globales de locales ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/stores")
@GlobalEndpoint
@Tag(name = "Organization")
public class StoreController {

    private static final SortParser SORT = SortParser.allowing("id", "code", "name", "active")
            .withDefault(Sort.by("code"));

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    /** Lista los locales accesibles para el usuario actual. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar locales")
    public PageResponse<StoreResponse> list(@RequestParam(required = false) Boolean active,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) List<String> sort) {
        Pageable pageable = PageQuery.of(page, size).toPageable(SORT.parse(sort));
        Page<Store> result = storeService.listForCurrentUser(active, pageable);
        return PageResponse.from(result, StoreResponse::from);
    }

    /** Obtiene el detalle de un local validando acceso. */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Obtener local")
    public StoreResponse get(@PathVariable Long id) {
        return StoreResponse.from(storeService.getForCurrentUser(id));
    }

    /** Crea un local nuevo y publica {@code StoreCreatedEvent}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_STORE_MANAGE)")
    @Operation(summary = "Crear local")
    public StoreResponse create(@Valid @RequestBody StoreRequest request) {
        return StoreResponse.from(storeService.create(toCommand(request)));
    }

    /** Actualiza los datos de un local. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_STORE_MANAGE)")
    @Operation(summary = "Actualizar local")
    public StoreResponse update(@PathVariable Long id, @Valid @RequestBody StoreRequest request) {
        return StoreResponse.from(storeService.update(id, toCommand(request)));
    }

    /** Desactiva un local si no tiene turnos abiertos ({@code StoreUsageGuard}). */
    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_STORE_MANAGE)")
    @Operation(summary = "Desactivar local")
    public void deactivate(@PathVariable Long id) {
        storeService.deactivate(id);
    }

    /** Reactiva un local. */
    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_STORE_MANAGE)")
    @Operation(summary = "Activar local")
    public void activate(@PathVariable Long id) {
        storeService.activate(id);
    }

    private static StoreService.StoreCommand toCommand(StoreRequest request) {
        return new StoreService.StoreCommand(
                request.code(),
                request.name(),
                request.address(),
                request.commune(),
                request.city(),
                request.phone(),
                request.siiBranchCode()
        );
    }
}
