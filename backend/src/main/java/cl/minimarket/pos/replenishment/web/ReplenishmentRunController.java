package cl.minimarket.pos.replenishment.web;

import cl.minimarket.pos.replenishment.application.run.ReplenishmentDraftService;
import cl.minimarket.pos.replenishment.application.run.ReplenishmentLine;
import cl.minimarket.pos.replenishment.application.run.ReplenishmentLineService;
import cl.minimarket.pos.replenishment.application.run.ReplenishmentQueryService;
import cl.minimarket.pos.replenishment.application.run.ReplenishmentRun;
import cl.minimarket.pos.replenishment.application.run.ReplenishmentRunService;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.idempotency.Idempotent;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Endpoints de corridas, líneas y borradores de reabastecimiento ({@code API.md} F.2, flujo 8
 * de F.4). Todos operan en el local activo ({@code X-Store-Id}).
 */
@RestController
@RequestMapping("/api/v1/replenishment")
@Tag(name = "Replenishment")
public class ReplenishmentRunController {

    /** Lista blanca de orden del listado de corridas. */
    private static final SortParser RUNS_SORT = SortParser.allowing("id", "asOfDate", "startedAt", "status")
            .withDefault(Sort.by(Sort.Order.desc("id")));

    /** Lista blanca de orden del listado de líneas (nombres públicos, ver el servicio). */
    private static final SortParser LINES_SORT = SortParser.allowing(
                    "id", "productId", "avgDailyDemand", "onHand", "suggestedQty",
                    "suggestedPurchaseUnits", "status")
            .withDefault(Sort.by(Sort.Order.asc("id")));

    private final ReplenishmentRunService runService;
    private final ReplenishmentLineService lineService;
    private final ReplenishmentDraftService draftService;
    private final ReplenishmentQueryService queryService;

    public ReplenishmentRunController(ReplenishmentRunService runService,
                                      ReplenishmentLineService lineService,
                                      ReplenishmentDraftService draftService,
                                      ReplenishmentQueryService queryService) {
        this.runService = Objects.requireNonNull(runService, "runService");
        this.lineService = Objects.requireNonNull(lineService, "lineService");
        this.draftService = Objects.requireNonNull(draftService, "draftService");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
    }

    /** Ejecuta la corrida síncrona: refresca el agregado, calcula y persiste las líneas. */
    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPLENISHMENT_RUN_EXECUTE)")
    @Operation(summary = "Ejecutar corrida de reabastecimiento",
            description = "Permiso: replenishment:run:execute. [I] Idempotency-Key obligatoria; síncrono en el MVP: "
                    + "refresca el agregado diario del rango y luego calcula C.5.")
    @ApiResponse(responseCode = "201", description = "Corrida completada con sus líneas")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (fecha futura, Idempotency-Key)")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT / IDEMPOTENCY_IN_PROGRESS")
    public ReplenishmentRunResponse run(@Valid @RequestBody(required = false) ReplenishmentRunRequest request) {
        Long storeId = StoreContext.require().storeId();
        ReplenishmentRun run = runService.execute(storeId, request == null ? null : request.asOfDate());
        return ReplenishmentRunResponse.from(queryService.getRun(storeId, run.getId()));
    }

    /** Lista las corridas del local (de la más reciente a la más antigua). */
    @GetMapping("/runs")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPLENISHMENT_RUN_READ)")
    @Operation(summary = "Listar corridas", description = "Permiso: replenishment:run:read.")
    @ApiResponse(responseCode = "200", description = "Página de corridas")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por paginación u orden inválido")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    public PageResponse<ReplenishmentRunResponse> listRuns(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest) {
        Long storeId = StoreContext.require().storeId();
        var pageable = PageQuery.of(page, size)
                .toPageable(RUNS_SORT.parse(resolveRawSort(sort, httpRequest)));
        return mapPage(queryService.listRuns(storeId, pageable), ReplenishmentRunResponse::from);
    }

    /** Detalle de una corrida del local. */
    @GetMapping("/runs/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPLENISHMENT_RUN_READ)")
    @Operation(summary = "Obtener corrida", description = "Permiso: replenishment:run:read.")
    @ApiResponse(responseCode = "200", description = "Corrida con conteo por estado")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la corrida no pertenece al local")
    public ReplenishmentRunResponse getRun(@PathVariable long id) {
        return ReplenishmentRunResponse.from(queryService.getRun(StoreContext.require().storeId(), id));
    }

    /** Lista las líneas de una corrida con filtros por estado, proveedor, flag y categoría. */
    @GetMapping("/runs/{id}/lines")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPLENISHMENT_RUN_READ)")
    @Operation(summary = "Listar líneas de la corrida",
            description = "Permiso: replenishment:run:read. Costos visibles solo con catalog:cost:read.")
    @ApiResponse(responseCode = "200", description = "Página de líneas")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por paginación, orden o filtros inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la corrida no pertenece al local")
    public PageResponse<ReplenishmentLineResponse> listLines(
            @PathVariable long id,
            @Parameter(description = "Filtro por estado de la línea.")
            @RequestParam(required = false) cl.minimarket.pos.replenishment.domain.ReplenishmentStatus status,
            @Parameter(description = "Filtro por proveedor elegido.")
            @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Filtro por flag del cálculo (una sola).")
            @RequestParam(required = false) cl.minimarket.pos.replenishment.domain.ReplenishmentFlag flag,
            @Parameter(description = "Filtro por categoría del producto (directa o padre).")
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest) {
        Long storeId = StoreContext.require().storeId();
        var pageable = PageQuery.of(page, size)
                .toPageable(LINES_SORT.parse(resolveRawSort(sort, httpRequest)));
        var filter = new ReplenishmentQueryService.LineFilter(status, supplierId, flag, categoryId);
        return mapPage(
                queryService.listLines(storeId, id, filter, orderClause(pageable), pageable),
                line -> ReplenishmentLineResponse.from(line, canReadCosts()));
    }

    /** Edita las unidades finales, el estado o el proveedor de una línea de sugerencia. */
    @PatchMapping("/lines/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPLENISHMENT_RUN_EXECUTE)")
    @Operation(summary = "Editar línea de sugerencia",
            description = "Permiso: replenishment:run:execute. Una línea ya incluida en una OC responde 409 INVALID_STATE_TRANSITION.")
    @ApiResponse(responseCode = "200", description = "Línea actualizada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (comando vacío, MOQ, proveedor no candidato)")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la línea no pertenece al local")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    public ReplenishmentLineResponse updateLine(@PathVariable long id,
                                                @Valid @RequestBody ReplenishmentLineUpdateRequest request) {
        Long storeId = StoreContext.require().storeId();
        ReplenishmentLine updated = lineService.update(storeId, id, request.toCommand());
        return ReplenishmentLineResponse.from(queryService.lineView(storeId, updated), canReadCosts());
    }

    /** Genera los borradores de OC por proveedor desde las líneas sugeridas o aceptadas. */
    @PostMapping("/runs/{id}/purchase-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPLENISHMENT_RUN_EXECUTE) "
            + "and hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_ORDER_CREATE)")
    @Operation(summary = "Generar borradores de OC desde la corrida",
            description = "Permisos: replenishment:run:execute + purchasing:order:create. [I] "
                    + "Idempotency-Key obligatoria. Una OC DRAFT por proveedor con origin=REPLENISHMENT "
                    + "y trazabilidad de la corrida.")
    @ApiResponse(responseCode = "201", description = "Borradores creados")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (líneas inválidas, Idempotency-Key)")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la corrida no pertenece al local")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION / IDEMPOTENCY_CONFLICT / IDEMPOTENCY_IN_PROGRESS")
    public GeneratedOrdersResponse generateDrafts(
            @PathVariable long id,
            @Valid @RequestBody(required = false) GenerateDraftsRequest request) {
        Long storeId = StoreContext.require().storeId();
        return GeneratedOrdersResponse.from(draftService.generate(
                storeId, id, request == null ? null : request.lineIds()));
    }

    /** Orden de líneas desde el Sort ya validado (nombres públicos → columnas del SQL nativo). */
    private static String orderClause(org.springframework.data.domain.Pageable pageable) {
        List<Map.Entry<String, Boolean>> orders = new ArrayList<>();
        pageable.getSort().forEach(order ->
                orders.add(new AbstractMap.SimpleImmutableEntry<>(
                        order.getProperty(), order.isAscending())));
        return ReplenishmentQueryService.orderClause(orders);
    }

    /** Convierte el contenido de una página de servicio a su DTO de respuesta. */
    private static <S, T> PageResponse<T> mapPage(PageResponse<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.content().stream().map(mapper).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.sort());
    }

    /** Repite los valores del parámetro repetible {@code sort} que perdió el binding. */
    private static List<String> resolveRawSort(List<String> boundSort, HttpServletRequest httpRequest) {
        if (httpRequest != null) {
            String[] values = httpRequest.getParameterValues("sort");
            return values == null ? null : Arrays.asList(values);
        }
        return boundSort;
    }

    /** Indica si el usuario autenticado posee permiso {@code catalog:cost:read}. */
    private static boolean canReadCosts() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication() != null
                && org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> Permissions.CATALOG_COST_READ.equals(a.getAuthority()));
    }
}
