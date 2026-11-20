package cl.minimarket.pos.promotions.web;

import cl.minimarket.pos.promotions.PromotionType;
import cl.minimarket.pos.promotions.application.PromotionService;
import cl.minimarket.pos.promotions.application.PromotionService.PromotionFilter;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Endpoints globales de administración de promociones ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/promotions")
@GlobalEndpoint
@Tag(name = "Promotions")
public class PromotionController {

    private static final SortParser PROMOTION_SORT = SortParser.allowing(
                    "id", "name", "promotionType", "priority", "validFrom", "validTo", "active", "createdAt")
            .withDefault(Sort.by(Sort.Direction.ASC, "priority").and(Sort.by(Sort.Direction.ASC, "id")));

    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = Objects.requireNonNull(promotionService, "promotionService");
    }

    /** Lista paginada de promociones con filtros opcionales. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PROMOTIONS_PROMOTION_READ)")
    @Operation(summary = "Listar promociones", description = "Permiso: promotions:promotion:read.")
    @ApiResponse(responseCode = "200", description = "Listado paginado de promociones")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por paginación u orden inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    public PageResponse<PromotionResponse> list(
            @Parameter(description = "Filtrar por estado activo.")
            @RequestParam(required = false) Boolean active,
            @Parameter(description = "Filtrar por tipo de promoción.")
            @RequestParam(required = false) PromotionType type,
            @Parameter(description = "Filtrar promociones vigentes en este instante UTC.")
            @RequestParam(required = false) Instant vigentAt,
            @Parameter(description = "Filtrar promociones aplicables a un producto (directo, categoría o marca).")
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest
    ) {
        List<String> rawSort = resolveRawSort(sort, httpRequest);
        Pageable pageable = PageQuery.of(page, size).toPageable(PROMOTION_SORT.parse(rawSort));
        PromotionFilter filter = new PromotionFilter(active, type, vigentAt, productId);
        return PageResponse.from(promotionService.list(filter, pageable), PromotionResponse::from);
    }

    /** Obtiene el detalle de una promoción por id. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PROMOTIONS_PROMOTION_READ)")
    @Operation(summary = "Obtener promoción", description = "Permiso: promotions:promotion:read.")
    @ApiResponse(responseCode = "200", description = "Detalle de la promoción")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Promoción no encontrada")
    public PromotionResponse get(@PathVariable Long id) {
        return PromotionResponse.from(promotionService.get(id));
    }

    /** Crea una nueva promoción y audita {@code PROMOTION_CHANGED}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PROMOTIONS_PROMOTION_MANAGE)")
    @Operation(summary = "Crear promoción", description = "Permiso: promotions:promotion:manage.")
    @ApiResponse(responseCode = "201", description = "Promoción creada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    public PromotionResponse create(@Valid @RequestBody PromotionRequest request) {
        return PromotionResponse.from(promotionService.create(request.toCommand()));
    }

    /** Actualiza una promoción existente y audita {@code PROMOTION_CHANGED}. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PROMOTIONS_PROMOTION_MANAGE)")
    @Operation(summary = "Actualizar promoción", description = "Permiso: promotions:promotion:manage.")
    @ApiResponse(responseCode = "200", description = "Promoción actualizada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Promoción no encontrada")
    @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION")
    public PromotionResponse update(@PathVariable Long id, @Valid @RequestBody PromotionRequest request) {
        return PromotionResponse.from(promotionService.update(id, request.toCommand()));
    }

    /** Desactiva una promoción y audita {@code PROMOTION_CHANGED}. */
    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PROMOTIONS_PROMOTION_MANAGE)")
    @Operation(summary = "Desactivar promoción", description = "Permiso: promotions:promotion:manage.")
    @ApiResponse(responseCode = "204", description = "Promoción desactivada")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Promoción no encontrada")
    public void deactivate(@PathVariable Long id) {
        promotionService.deactivate(id);
    }

    /** Reactiva una promoción y audita {@code PROMOTION_CHANGED}. */
    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PROMOTIONS_PROMOTION_MANAGE)")
    @Operation(summary = "Activar promoción", description = "Permiso: promotions:promotion:manage.")
    @ApiResponse(responseCode = "204", description = "Promoción activada")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Promoción no encontrada")
    public void activate(@PathVariable Long id) {
        promotionService.activate(id);
    }

    private static List<String> resolveRawSort(List<String> boundSort, HttpServletRequest httpRequest) {
        if (httpRequest != null) {
            String[] values = httpRequest.getParameterValues("sort");
            return values == null ? null : Arrays.asList(values);
        }
        return boundSort;
    }
}
