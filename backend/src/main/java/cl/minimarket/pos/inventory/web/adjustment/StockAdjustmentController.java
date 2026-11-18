package cl.minimarket.pos.inventory.web.adjustment;

import cl.minimarket.pos.inventory.application.adjustment.AdjustmentStatus;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentFilter;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentService;
import cl.minimarket.pos.shared.context.CurrentUserId;
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
 * Endpoints REST para solicitar, consultar, aprobar y rechazar ajustes manuales de stock en el
 * local activo ({@code API.md} F.2, {@code HU-INV-05}).
 */
@RestController
@RequestMapping("/api/v1/stock-adjustments")
@Tag(name = "Inventory")
public class StockAdjustmentController {

    private static final SortParser ADJUSTMENT_SORT = SortParser.allowing(
                    "id", "number", "status", "reasonCode", "totalCostImpact", "createdAt", "approvedAt")
            .withDefault(Sort.by(Sort.Direction.DESC, "createdAt"));

    private final StockAdjustmentService adjustmentService;

    public StockAdjustmentController(StockAdjustmentService adjustmentService) {
        this.adjustmentService = Objects.requireNonNull(adjustmentService, "adjustmentService");
    }

    /** Lista paginada de ajustes de stock del local activo. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_ADJUSTMENT_CREATE)")
    @Operation(
            summary = "Listar ajustes de stock",
            description = "Permiso: inventory:adjustment:create. "
                    + "Filtros opcionales por status, from y to en zona horaria de negocio.")
    @ApiResponse(responseCode = "200", description = "Listado paginado de ajustes de stock")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por rango de fechas, paginación u orden inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public PageResponse<StockAdjustmentResponse> list(
            @Parameter(description = "Filtrar por estado del ajuste.")
            @RequestParam(required = false) AdjustmentStatus status,
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
        Pageable pageable = PageQuery.of(page, size).toPageable(ADJUSTMENT_SORT.parse(rawSort));
        StockAdjustmentFilter filter = new StockAdjustmentFilter(status, from, to);
        return PageResponse.from(
                adjustmentService.list(storeId, filter, pageable, canReadCosts()),
                StockAdjustmentResponse::from);
    }

    /** Obtiene el detalle de un ajuste de stock del local activo. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_ADJUSTMENT_CREATE)")
    @Operation(
            summary = "Obtener ajuste de stock por id",
            description = "Permiso: inventory:adjustment:create.")
    @ApiResponse(responseCode = "200", description = "Detalle del ajuste")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Ajuste no encontrado en el local activo")
    public StockAdjustmentResponse getById(@PathVariable Long id) {
        Long storeId = StoreContext.require().storeId();
        return StockAdjustmentResponse.from(adjustmentService.getById(storeId, id, canReadCosts()));
    }

    /** Solicita un nuevo ajuste de stock en estado PENDING_APPROVAL (sin mover existencias). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_ADJUSTMENT_CREATE)")
    @Operation(
            summary = "Crear solicitud de ajuste de stock",
            description = "Permiso: inventory:adjustment:create. Requiere Idempotency-Key ([I]). "
                    + "Nace en PENDING_APPROVAL y no modifica el stock hasta ser aprobado.")
    @ApiResponse(responseCode = "201", description = "Ajuste creado en estado PENDING_APPROVAL")
    @ApiResponse(responseCode = "400", description = "Datos inválidos o Idempotency-Key faltante")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Producto o lote no encontrado en el local activo")
    @ApiResponse(responseCode = "422", description = "Regla de negocio incumplida (p. ej. LOT_REQUIRED)")
    public StockAdjustmentResponse create(@Valid @RequestBody StockAdjustmentRequest request) {
        Long storeId = StoreContext.require().storeId();
        Long userId = requireCurrentUserId();
        return StockAdjustmentResponse.from(
                adjustmentService.create(storeId, userId, request.toCommand(), canReadCosts()));
    }

    /** Aprueba un ajuste pendiente aplicando los movimientos reales de stock y kardex. */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_ADJUSTMENT_APPROVE)")
    @Operation(
            summary = "Aprobar ajuste de stock",
            description = "Permiso: inventory:adjustment:approve. Aplica segregación de funciones (RN-22), "
                    + "límite ADJUSTMENT_APPROVE_MAX_AMOUNT ([O:STOCK_ADJUSTMENT_APPROVAL] vía X-Override-Token) "
                    + "y validación de stock suficiente (RN-01).")
    @ApiResponse(responseCode = "200", description = "Ajuste aprobado y stock actualizado")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Ajuste no encontrado en el local activo")
    @ApiResponse(responseCode = "409", description = "El ajuste ya no está en estado PENDING_APPROVAL")
    @ApiResponse(responseCode = "422", description = "SEGREGATION_OF_DUTIES, OVERRIDE_REQUIRED, OVERRIDE_INVALID o STOCK_INSUFFICIENT")
    public StockAdjustmentResponse approve(
            @PathVariable Long id,
            @Parameter(description = "Token de autorización de supervisor cuando supera ADJUSTMENT_APPROVE_MAX_AMOUNT.")
            @RequestHeader(value = "X-Override-Token", required = false) String overrideToken) {
        Long storeId = StoreContext.require().storeId();
        Long userId = requireCurrentUserId();
        return StockAdjustmentResponse.from(
                adjustmentService.approve(storeId, id, userId, overrideToken, canReadCosts()));
    }

    /** Rechaza un ajuste pendiente sin alterar el stock. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_ADJUSTMENT_APPROVE)")
    @Operation(
            summary = "Rechazar ajuste de stock",
            description = "Permiso: inventory:adjustment:approve. Deja el ajuste en REJECTED sin mover stock.")
    @ApiResponse(responseCode = "200", description = "Ajuste rechazado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR si el motivo de rechazo es inválido")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "Ajuste no encontrado en el local activo")
    @ApiResponse(responseCode = "409", description = "El ajuste ya no está en estado PENDING_APPROVAL")
    @ApiResponse(responseCode = "422", description = "SEGREGATION_OF_DUTIES si el solicitante intenta rechazarlo")
    public StockAdjustmentResponse reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectRequest request) {
        Long storeId = StoreContext.require().storeId();
        Long userId = requireCurrentUserId();
        return StockAdjustmentResponse.from(
                adjustmentService.reject(storeId, id, userId, request.reason(), canReadCosts()));
    }

    private static List<String> resolveRawSort(List<String> boundSort, HttpServletRequest httpRequest) {
        // Spring MVC divide ?sort=campo,dir por coma al enlazar List<String>; leer los valores
        // crudos de HttpServletRequest conserva cada criterio "campo,dir" completo.
        if (httpRequest != null) {
            String[] values = httpRequest.getParameterValues("sort");
            return values == null ? null : Arrays.asList(values);
        }
        return boundSort;
    }

    /**
     * Determina si el usuario actual puede ver costos del ajuste: requiere
     * {@code catalog:cost:read} o {@code inventory:adjustment:approve} ({@code ROLES_PERMISOS.md} D.2).
     */
    private static boolean canReadCosts() {
        return hasAuthority(Permissions.CATALOG_COST_READ)
                || hasAuthority(Permissions.INVENTORY_ADJUSTMENT_APPROVE);
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
