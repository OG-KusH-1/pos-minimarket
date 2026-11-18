package cl.minimarket.pos.inventory.web.lot;

import cl.minimarket.pos.inventory.application.lot.LotQueryService;
import cl.minimarket.pos.inventory.application.lot.LotQueryService.FefoSuggestion;
import cl.minimarket.pos.inventory.application.lot.LotUpdateService;
import cl.minimarket.pos.inventory.web.lot.FefoSuggestionResponse.AllocationResponse;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Endpoints REST para consultar lotes del local activo, obtener sugerencias de salida FEFO y
 * corregir metadatos de lotes ({@code API.md} F.2, {@code HU-INV-09}).
 */
@RestController
@RequestMapping("/api/v1/lots")
@Tag(name = "Inventory")
public class LotController {

    private static final String LOT_STATUS_ACTIVE = "ACTIVE";
    private static final String LOT_STATUS_DEPLETED = "DEPLETED";

    private static final SortParser LOT_SORT = SortParser.of(Map.of(
                    "id", "id",
                    "lotNumber", "lotNumber",
                    "expiryDate", "expiryDate",
                    "quantity", "quantity",
                    "receivedAt", "receivedAt",
                    "status", "status",
                    "sku", "sku",
                    "name", "productName",
                    "productName", "productName"))
            .withDefault(Sort.by(Sort.Order.asc("expiryDate"), Sort.Order.asc("id")));

    private final LotQueryService lotQueryService;
    private final LotUpdateService lotUpdateService;

    public LotController(LotQueryService lotQueryService, LotUpdateService lotUpdateService) {
        this.lotQueryService = Objects.requireNonNull(lotQueryService, "lotQueryService");
        this.lotUpdateService = Objects.requireNonNull(lotUpdateService, "lotUpdateService");
    }

    /** Lista paginada de lotes del local activo con filtros opcionales. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_STOCK_READ)")
    @Operation(
            summary = "Listar lotes del local",
            description = "Permiso: inventory:stock:read. Permite filtrar por producto, días hasta el "
                    + "vencimiento, disponibilidad de stock (withStock), estado y texto libre.")
    @ApiResponse(responseCode = "200", description = "Página de lotes del local activo")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por parámetros de filtro o orden inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public PageResponse<LotResponse> list(
            @Parameter(description = "Filtrar por identificador de producto.")
            @RequestParam(required = false) Long productId,
            @Parameter(description = "Filtrar lotes cuyo vencimiento sea menor o igual a hoy + días indicados.")
            @RequestParam(required = false) Integer expiringWithinDays,
            @Parameter(description = "Filtrar lotes con saldo positivo (true = ACTIVE) o agotados (false = DEPLETED).")
            @RequestParam(required = false) Boolean withStock,
            @Parameter(description = "Filtrar por estado explícito del lote (ACTIVE o DEPLETED).")
            @RequestParam(required = false) String status,
            @Parameter(description = "Búsqueda libre por número de lote, SKU o nombre de producto.")
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest) {
        Long storeId = StoreContext.require().storeId();
        String effectiveStatus = resolveStatusFilter(withStock, status);
        List<String> rawSort = resolveRawSort(sort, httpRequest);
        Pageable pageable = PageQuery.of(page, size).toPageable(LOT_SORT.parse(rawSort));
        return PageResponse.from(
                lotQueryService.findLots(storeId, productId, effectiveStatus, expiringWithinDays, q, pageable),
                LotResponse::from);
    }

    /**
     * Calcula la sugerencia de consumo FEFO para un producto y cantidad en el local activo,
     * informando las asignaciones por lote y el eventual faltante ({@code HU-INV-09}).
     */
    @GetMapping("/fefo")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_STOCK_READ)")
    @Operation(
            summary = "Sugerir asignación FEFO por producto",
            description = "Permiso: inventory:stock:read. Calcula la distribución sugerida entre lotes "
                    + "disponibles ordenados por vencimiento más próximo (NULLS LAST) e informa el faltante.")
    @ApiResponse(responseCode = "200", description = "Sugerencia FEFO calculada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR si la cantidad es inválida o el producto es PACK")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el producto no existe")
    public FefoSuggestionResponse suggestFefo(
            @Parameter(description = "Identificador del producto base.", required = true)
            @RequestParam Long productId,
            @Parameter(description = "Cantidad solicitada en unidad base (> 0).", required = true)
            @RequestParam BigDecimal quantity) {
        Long storeId = StoreContext.require().storeId();
        FefoSuggestion suggestion = lotQueryService.suggestFefo(storeId, productId, quantity);
        List<AllocationResponse> allocations = suggestion.allocations().stream()
                .map(s -> new AllocationResponse(
                        s.lotId(),
                        s.lotNumber(),
                        s.expiryDate(),
                        s.suggestedQuantity()))
                .toList();
        return new FefoSuggestionResponse(
                suggestion.productId(),
                suggestion.requestedQuantity(),
                allocations,
                suggestion.shortage());
    }

    /**
     * Corrige el número y/o fecha de vencimiento de un lote del local activo, validando versión
     * optimista y dejando traza {@code LOT_UPDATED} en auditoría.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_LOT_UPDATE)")
    @Operation(
            summary = "Corregir datos de un lote",
            description = "Permiso: inventory:lot:update (sensible, auditado como LOT_UPDATED). "
                    + "Exige versión optimista y pertenencia del lote al local activo X-Store-Id.")
    @ApiResponse(responseCode = "200", description = "Lote actualizado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por datos inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el lote no existe en el local activo")
    @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION o DUPLICATED_VALUE")
    public LotResponse update(
            @Parameter(description = "Identificador del lote.", required = true)
            @PathVariable Long id,
            @Valid @RequestBody LotUpdateRequest request) {
        Long storeId = StoreContext.require().storeId();
        return LotResponse.from(lotUpdateService.updateLot(
                storeId,
                id,
                request.lotNumber(),
                request.expiryDate(),
                request.reason(),
                request.version()));
    }

    static List<String> resolveRawSort(List<String> boundSort, HttpServletRequest httpRequest) {
        // Spring MVC divide ?sort=campo,dir por coma al enlazar List<String>; leer los valores
        // crudos de HttpServletRequest conserva cada criterio "campo,dir" completo.
        if (httpRequest != null) {
            String[] values = httpRequest.getParameterValues("sort");
            return values == null ? null : Arrays.asList(values);
        }
        return boundSort;
    }

    private static String resolveStatusFilter(Boolean withStock, String status) {
        if (status == null || status.isBlank()) {
            if (withStock == null) {
                return null;
            }
            return withStock ? LOT_STATUS_ACTIVE : LOT_STATUS_DEPLETED;
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        if (withStock != null) {
            String expected = withStock ? LOT_STATUS_ACTIVE : LOT_STATUS_DEPLETED;
            if (!expected.equals(normalizedStatus)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Los filtros withStock=" + withStock + " y status=" + normalizedStatus
                                + " son incompatibles.");
            }
        }
        return normalizedStatus;
    }
}
