package cl.minimarket.pos.shrinkage.web;

import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.idempotency.Idempotent;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import cl.minimarket.pos.shrinkage.ShrinkageStatus;
import cl.minimarket.pos.shrinkage.ShrinkageType;
import cl.minimarket.pos.shrinkage.application.ShrinkageFilter;
import cl.minimarket.pos.shrinkage.application.ShrinkageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Endpoints REST del módulo de Mermas ({@code API.md} F.2, {@code HU-MER-01..03}).
 */
@RestController
@RequestMapping("/api/v1/shrinkages")
@Tag(name = "Shrinkage")
public class ShrinkageController {

    private static final SortParser SHRINKAGE_SORT = SortParser.allowing(
                    "id",
                    "number",
                    "status",
                    "shrinkageType",
                    "quantity",
                    "totalCost",
                    "occurredAt",
                    "approvedAt",
                    "createdAt")
            .withDefault(Sort.by(Sort.Direction.DESC, "occurredAt"));

    private final ShrinkageService shrinkageService;

    public ShrinkageController(ShrinkageService shrinkageService) {
        this.shrinkageService = Objects.requireNonNull(shrinkageService, "shrinkageService");
    }

    /** Lista paginada de mermas del local activo. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SHRINKAGE_RECORD_READ)")
    @Operation(
            summary = "Listar mermas",
            description = "Permiso: shrinkage:record:read. "
                    + "Filtros opcionales por status, shrinkageType, productId, from y to.")
    @ApiResponse(responseCode = "200", description = "Listado paginado de mermas")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por filtros, paginación u orden inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public PageResponse<ShrinkageResponse> list(
            @Parameter(description = "Filtrar por estado.")
            @RequestParam(required = false) ShrinkageStatus status,
            @Parameter(description = "Filtrar por tipo de merma.")
            @RequestParam(required = false) ShrinkageType shrinkageType,
            @Parameter(description = "Filtrar por producto.")
            @RequestParam(required = false) Long productId,
            @Parameter(description = "Fecha inicial inclusive (YYYY-MM-DD, zona de negocio).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha final inclusive (YYYY-MM-DD, zona de negocio).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest) {
        Long storeId = StoreContext.require().storeId();
        List<String> rawSort = resolveRawSort(sort, httpRequest);
        Pageable pageable = PageQuery.of(page, size).toPageable(SHRINKAGE_SORT.parse(rawSort));
        ShrinkageFilter filter = new ShrinkageFilter(status, shrinkageType, productId, from, to);
        return PageResponse.from(
                shrinkageService.list(storeId, filter, pageable, canReadCosts()),
                ShrinkageResponse::from);
    }

    /** Obtiene el detalle de una merma del local activo. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SHRINKAGE_RECORD_READ)")
    @Operation(
            summary = "Obtener merma por id",
            description = "Permiso: shrinkage:record:read.")
    @ApiResponse(responseCode = "200", description = "Detalle de la merma")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Merma no encontrada en el local activo")
    public ShrinkageResponse getById(@PathVariable Long id) {
        Long storeId = StoreContext.require().storeId();
        return ShrinkageResponse.from(shrinkageService.getById(storeId, id, canReadCosts()));
    }

    /** Registra una merma manual; se autoaprueba si {@code 0 < total_cost <= límite}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SHRINKAGE_RECORD_CREATE)")
    @Operation(
            summary = "Registrar merma",
            description = "Permiso: shrinkage:record:create. Requiere Idempotency-Key ([I]). "
                    + "Se autoaprueba si 0 < totalCost <= SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT (RN-21).")
    @ApiResponse(responseCode = "201", description = "Merma registrada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR o Idempotency-Key faltante")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Producto, lote, archivo o usuario no encontrado")
    @ApiResponse(responseCode = "422", description = "LOT_REQUIRED o STOCK_INSUFFICIENT")
    public ShrinkageResponse create(@Valid @RequestBody ShrinkageRequest request) {
        Long storeId = StoreContext.require().storeId();
        Long userId = requireCurrentUserId();
        return ShrinkageResponse.from(
                shrinkageService.create(storeId, userId, request.toCommand(), canReadCosts()));
    }

    /** Aprueba una merma pendiente (manual o sistémica). */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SHRINKAGE_RECORD_APPROVE)")
    @Operation(
            summary = "Aprobar merma",
            description = "Permiso: shrinkage:record:approve. Aplica segregación de funciones, "
                    + "límite SHRINKAGE_APPROVE_MAX_AMOUNT ([O:SHRINKAGE_APPROVAL]) y descuento de stock "
                    + "solo si affectsStock = true.")
    @ApiResponse(responseCode = "200", description = "Merma aprobada")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Merma no encontrada en el local activo")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    @ApiResponse(responseCode = "422", description = "SEGREGATION_OF_DUTIES, OVERRIDE_REQUIRED, OVERRIDE_INVALID o STOCK_INSUFFICIENT")
    public ShrinkageResponse approve(
            @PathVariable Long id,
            @Parameter(description = "Token de autorización de supervisor cuando supera SHRINKAGE_APPROVE_MAX_AMOUNT.")
            @RequestHeader(value = "X-Override-Token", required = false) String overrideToken) {
        Long storeId = StoreContext.require().storeId();
        Long userId = requireCurrentUserId();
        return ShrinkageResponse.from(
                shrinkageService.approve(storeId, id, userId, overrideToken, canReadCosts()));
    }

    /** Rechaza una merma pendiente como simple anotación sin efecto en stock. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SHRINKAGE_RECORD_APPROVE)")
    @Operation(
            summary = "Rechazar merma",
            description = "Permiso: shrinkage:record:approve. Deja la merma en REJECTED sin mover stock.")
    @ApiResponse(responseCode = "200", description = "Merma rechazada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Merma no encontrada en el local activo")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    @ApiResponse(responseCode = "422", description = "SEGREGATION_OF_DUTIES")
    public ShrinkageResponse reject(
            @PathVariable Long id,
            @Valid @RequestBody ShrinkageRejectRequest request) {
        Long storeId = StoreContext.require().storeId();
        Long userId = requireCurrentUserId();
        return ShrinkageResponse.from(
                shrinkageService.reject(storeId, id, userId, request.reason(), canReadCosts()));
    }

    private static List<String> resolveRawSort(List<String> boundSort, HttpServletRequest httpRequest) {
        if (httpRequest != null) {
            String[] values = httpRequest.getParameterValues("sort");
            return values == null ? null : Arrays.asList(values);
        }
        return boundSort;
    }

    /**
     * Determina si el usuario actual puede ver los costos de merma: requiere
     * {@code catalog:cost:read} o {@code shrinkage:record:approve} ({@code API.md} F.3).
     */
    private static boolean canReadCosts() {
        return hasAuthority(Permissions.CATALOG_COST_READ)
                || hasAuthority(Permissions.SHRINKAGE_RECORD_APPROVE);
    }

    private static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }

    private static Long requireCurrentUserId() {
        return CurrentUserId.find().orElseThrow(() -> new AccessDeniedException(
                "No se pudo determinar el identificador numérico del usuario autenticado."));
    }
}
