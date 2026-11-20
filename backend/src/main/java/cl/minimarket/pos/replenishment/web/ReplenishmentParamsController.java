package cl.minimarket.pos.replenishment.web;

import cl.minimarket.pos.replenishment.application.params.ReplenishmentParam;
import cl.minimarket.pos.replenishment.application.params.ReplenishmentParamScope;
import cl.minimarket.pos.replenishment.application.params.ReplenishmentParamsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Endpoints de parámetros de reabastecimiento ({@code API.md} F.2). Ninguno es global ni de
 * terminal: operan en el local activo ({@code X-Store-Id}); la fila GLOBAL es la única que no
 * depende de un local concreto y no se puede borrar.
 */
@RestController
@RequestMapping("/api/v1/replenishment")
@Tag(name = "Replenishment")
public class ReplenishmentParamsController {

    private final ReplenishmentParamsService paramsService;

    public ReplenishmentParamsController(ReplenishmentParamsService paramsService) {
        this.paramsService = Objects.requireNonNull(paramsService, "paramsService");
    }

    /** Lista las filas de parámetros, opcionalmente filtradas por alcance. */
    @GetMapping("/params")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPLENISHMENT_RUN_READ)")
    @Operation(summary = "Listar parámetros de reabastecimiento",
            description = "Permiso: replenishment:run:read.")
    @ApiResponse(responseCode = "200", description = "Filas de parámetros ordenadas por id")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR si el alcance no existe")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    public List<ReplenishmentParamsResponse> list(
            @Parameter(description = "Filtro por alcance (GLOBAL, STORE, CATEGORY o PRODUCT).")
            @RequestParam(required = false) ReplenishmentParamScope scope) {
        return paramsService.list(scope).stream()
                .map(ReplenishmentParamsResponse::from)
                .toList();
    }

    /** Crea o reemplaza la fila de parámetros del alcance indicado (UQ de B.11). */
    @PutMapping("/params")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPLENISHMENT_PARAMS_MANAGE)")
    @Operation(summary = "Crear o reemplazar parámetros por alcance",
            description = "Permiso: replenishment:params:manage (sensible, auditado). Un valor null hereda del nivel superior.")
    @ApiResponse(responseCode = "200", description = "Fila creada o actualizada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (rangos o coherencia de alcance)")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el local, la categoría o el producto no existen")
    public ReplenishmentParamsResponse upsert(@Valid @RequestBody ReplenishmentParamsRequest request) {
        return ReplenishmentParamsResponse.from(paramsService.upsert(request.toCommand()));
    }

    /** Borra una fila de parámetros; la GLOBAL (default del sistema) se rechaza. */
    @DeleteMapping("/params/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPLENISHMENT_PARAMS_MANAGE)")
    @Operation(summary = "Borrar parámetros por alcance",
            description = "Permiso: replenishment:params:manage (sensible, auditado). La fila GLOBAL no se puede borrar (409 ENTITY_IN_USE).")
    @ApiResponse(responseCode = "204", description = "Fila borrada")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la fila no existe")
    @ApiResponse(responseCode = "409", description = "ENTITY_IN_USE si es la fila GLOBAL")
    public void delete(@PathVariable long id) {
        paramsService.delete(id);
    }
}
