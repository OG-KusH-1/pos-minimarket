package cl.minimarket.pos.inventory.web.count;

import cl.minimarket.pos.inventory.application.count.CountStatus;
import cl.minimarket.pos.inventory.application.count.CountType;
import cl.minimarket.pos.inventory.application.count.InventoryCountService;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountFilter;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountLineFilter;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountLineInput;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.Objects;

/**
 * Endpoints REST para el ciclo de vida de tomas físicas de inventario en el local activo
 * ({@code API.md} F.2, {@code HU-INV-06}).
 */
@RestController
@RequestMapping("/api/v1/inventory-counts")
@Tag(name = "Inventory")
public class InventoryCountController {

    private static final SortParser COUNT_SORT = SortParser.allowing(
                    "id", "number", "countType", "status", "createdAt", "startedAt", "submittedAt", "approvedAt")
            .withDefault(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    private static final SortParser LINE_SORT = SortParser.allowing(
                    "id", "productId", "theoreticalQty", "countedQty", "countedAt")
            .withDefault(Sort.by("id"));

    private final InventoryCountService countService;

    public InventoryCountController(InventoryCountService countService) {
        this.countService = Objects.requireNonNull(countService, "countService");
    }

    /** Inicia una toma física de inventario (completa o cíclica) en el local activo. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_COUNT_MANAGE)")
    @Operation(summary = "Iniciar toma de inventario", description = "Permiso: inventory:count:manage")
    public InventoryCountResponse create(@Valid @RequestBody InventoryCountRequest request) {
        Long storeId = StoreContext.require().storeId();
        Long userId = requireUserId();
        boolean blind = request.blind() == null || request.blind();
        return InventoryCountResponse.from(countService.create(
                storeId,
                request.countType(),
                request.categoryId(),
                blind,
                request.notes(),
                userId,
                canReadCosts(),
                canApproveCounts()));
    }

    /** Lista paginada de tomas de inventario del local activo con filtros opcionales. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_COUNT_EXECUTE)")
    @Operation(summary = "Listar tomas de inventario", description = "Permiso: inventory:count:execute")
    public PageResponse<InventoryCountResponse> list(
            @RequestParam(required = false) CountStatus status,
            @RequestParam(required = false) CountType countType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort) {
        Long storeId = StoreContext.require().storeId();
        Pageable pageable = PageQuery.of(page, size).toPageable(COUNT_SORT.parse(sort));
        return PageResponse.from(
                countService.list(
                        storeId,
                        new CountFilter(status, countType),
                        pageable,
                        canReadCosts(),
                        canApproveCounts()),
                InventoryCountResponse::from);
    }

    /** Consulta el detalle de una toma de inventario perteneciente al local activo. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_COUNT_EXECUTE)")
    @Operation(summary = "Detalle de toma de inventario", description = "Permiso: inventory:count:execute")
    public InventoryCountResponse get(@PathVariable Long id) {
        Long storeId = StoreContext.require().storeId();
        return InventoryCountResponse.from(
                countService.get(storeId, id, canReadCosts(), canApproveCounts()));
    }

    /** Lista paginada de líneas de una toma, ocultando existencia teórica si es ciega sin permiso de aprobar. */
    @GetMapping("/{id}/lines")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_COUNT_EXECUTE)")
    @Operation(summary = "Listar líneas de una toma de inventario", description = "Permiso: inventory:count:execute")
    public PageResponse<CountLineResponse> listLines(
            @PathVariable Long id,
            @RequestParam(required = false) Boolean counted,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort) {
        Long storeId = StoreContext.require().storeId();
        Pageable pageable = PageQuery.of(page, size).toPageable(LINE_SORT.parse(sort));
        return PageResponse.from(
                countService.listLines(
                        storeId,
                        id,
                        new CountLineFilter(counted, q),
                        pageable,
                        canReadCosts(),
                        canApproveCounts()),
                CountLineResponse::from);
    }

    /** Registra o actualiza cantidades contadas en lote cuando la toma está abierta ({@code OPEN}). */
    @PutMapping("/{id}/lines")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_COUNT_EXECUTE)")
    @Operation(summary = "Registrar conteos por línea", description = "Permiso: inventory:count:execute")
    public List<CountLineResponse> recordLines(
            @PathVariable Long id,
            @Valid @RequestBody CountLinesRequest request) {
        Long storeId = StoreContext.require().storeId();
        Long userId = requireUserId();
        List<CountLineInput> inputs = request.lines().stream()
                .map(entry -> new CountLineInput(entry.lineId(), entry.countedQty()))
                .toList();
        return countService.recordLines(
                        storeId,
                        id,
                        inputs,
                        userId,
                        canReadCosts(),
                        canApproveCounts())
                .stream()
                .map(CountLineResponse::from)
                .toList();
    }

    /** Envía una toma completa en conteo a revisión para aprobación o rechazo. */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_COUNT_MANAGE)")
    @Operation(summary = "Enviar toma de inventario a revisión", description = "Permiso: inventory:count:manage")
    public InventoryCountResponse submit(@PathVariable Long id) {
        Long storeId = StoreContext.require().storeId();
        Long userId = requireUserId();
        return InventoryCountResponse.from(
                countService.submit(storeId, id, userId, canReadCosts(), canApproveCounts()));
    }

    /** Aprueba la toma, valida segregación de funciones y aplica deltas vía {@code StockApi}. */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_COUNT_APPROVE)")
    @Operation(summary = "Aprobar toma de inventario y aplicar diferencias", description = "Permiso: inventory:count:approve")
    public InventoryCountResponse approve(@PathVariable Long id) {
        Long storeId = StoreContext.require().storeId();
        Long approverId = requireUserId();
        return InventoryCountResponse.from(
                countService.approve(storeId, id, approverId, canReadCosts(), canApproveCounts()));
    }

    /** Rechaza una toma enviada devolviéndola a {@code OPEN} para reconteo. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_COUNT_APPROVE)")
    @Operation(summary = "Rechazar toma de inventario hacia reconteo", description = "Permiso: inventory:count:approve")
    public InventoryCountResponse reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectCountRequest request) {
        Long storeId = StoreContext.require().storeId();
        return InventoryCountResponse.from(
                countService.reject(storeId, id, request.reason(), canReadCosts(), canApproveCounts()));
    }

    /** Cancela una toma abierta o enviada a revisión, liberando el alcance abierto. */
    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_COUNT_MANAGE)")
    @Operation(summary = "Cancelar toma de inventario", description = "Permiso: inventory:count:manage")
    public void cancel(
            @PathVariable Long id,
            @Valid @RequestBody CancelCountRequest request) {
        Long storeId = StoreContext.require().storeId();
        countService.cancel(storeId, id, request.reason());
    }

    private static Long requireUserId() {
        return CurrentUserId.find()
                .orElseThrow(() -> new AccessDeniedException("Usuario autenticado requerido"));
    }

    private static boolean canReadCosts() {
        return hasAuthority(Permissions.CATALOG_COST_READ);
    }

    private static boolean canApproveCounts() {
        return hasAuthority(Permissions.INVENTORY_COUNT_APPROVE);
    }

    private static boolean hasAuthority(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> permission.equals(a.getAuthority()));
    }
}
