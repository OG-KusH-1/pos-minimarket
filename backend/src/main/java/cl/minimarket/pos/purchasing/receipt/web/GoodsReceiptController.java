package cl.minimarket.pos.purchasing.receipt.web;

import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService;
import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService.GoodsReceiptFilter;
import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService.GoodsReceiptSummaryView;
import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptService;
import cl.minimarket.pos.purchasing.receipt.domain.GoodsReceiptStatus;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.idempotency.Idempotent;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Endpoints por local de recepciones de mercadería y borrador de recepción desde OC
 * ({@code API.md} F.2, {@code HU-PRV-05}, {@code HU-PRV-06}, {@code HU-PRV-07}, {@code HU-PRV-08}).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Purchasing")
public class GoodsReceiptController {

    private static final SortParser RECEIPTS_SORT = SortParser.allowing(
                    "id", "number", "supplierName", "purchaseOrderNumber", "status", "receivedAt", "netTotal", "createdAt")
            .withDefault(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    private final GoodsReceiptQueryService queryService;
    private final GoodsReceiptService receiptService;

    public GoodsReceiptController(GoodsReceiptQueryService queryService,
                                  GoodsReceiptService receiptService) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.receiptService = Objects.requireNonNull(receiptService, "receiptService");
    }

    /** Prellena un borrador de recepción con lo pendiente de recibir de una orden de compra. */
    @GetMapping("/purchase-orders/{id}/receipt-draft")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_RECEIPT_CREATE)")
    @Operation(
            summary = "Prellenar borrador de recepción desde orden de compra",
            description = "Permiso: purchasing:receipt:create. Prellena las cantidades pendientes de recibir.")
    @ApiResponse(responseCode = "200", description = "Borrador de recepción prellenado")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    public GoodsReceiptRequest receiptDraft(@PathVariable Long id) {
        Long storeId = StoreContext.require().storeId();
        return GoodsReceiptRequest.fromDraft(queryService.buildDraftForOrder(id, storeId));
    }

    /** Lista paginada de recepciones de mercadería del local activo. */
    @GetMapping("/goods-receipts")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_ORDER_READ)")
    @Operation(
            summary = "Listar recepciones de mercadería",
            description = "Permiso: purchasing:order:read. netTotal visible solo con catalog:cost:read.")
    @ApiResponse(responseCode = "200", description = "Página de recepciones")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    public PageResponse<GoodsReceiptSummary> list(
            @Parameter(description = "Filtrar por estado de la recepción.")
            @RequestParam(required = false) GoodsReceiptStatus status,
            @Parameter(description = "Filtrar por proveedor.")
            @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Filtrar por orden de compra.")
            @RequestParam(required = false) Long purchaseOrderId,
            @Parameter(description = "Fecha desde (YYYY-MM-DD).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha hasta (YYYY-MM-DD).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest) {
        Long storeId = StoreContext.require().storeId();
        List<String> rawSort = resolveRawSort(sort, httpRequest);
        Pageable pageable = PageQuery.of(page, size).toPageable(RECEIPTS_SORT.parse(rawSort));
        Page<GoodsReceiptSummaryView> result = queryService.list(
                storeId,
                new GoodsReceiptFilter(status, supplierId, purchaseOrderId, from, to),
                pageable);
        return PageResponse.from(result, GoodsReceiptSummary::from);
    }

    /** Obtiene el detalle de una recepción de mercadería del local activo. */
    @GetMapping("/goods-receipts/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_ORDER_READ)")
    @Operation(
            summary = "Obtener recepción de mercadería",
            description = "Permiso: purchasing:order:read. Costos visibles solo con catalog:cost:read.")
    @ApiResponse(responseCode = "200", description = "Detalle de la recepción")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    public GoodsReceiptResponse getById(@PathVariable Long id) {
        Long storeId = StoreContext.require().storeId();
        return GoodsReceiptResponse.from(queryService.getById(id, storeId));
    }

    /** Crea una recepción de mercadería en estado DRAFT. */
    @PostMapping("/goods-receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_RECEIPT_CREATE)")
    @Operation(
            summary = "Crear recepción de mercadería en borrador",
            description = "Permiso: purchasing:receipt:create (+ purchasing:receipt:without-order si no hay OC).")
    @ApiResponse(responseCode = "201", description = "Recepción creada en estado DRAFT")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    public GoodsReceiptResponse create(@Valid @RequestBody GoodsReceiptRequest request) {
        return GoodsReceiptResponse.from(receiptService.create(request.toCommand()));
    }

    /** Actualiza una recepción de mercadería en estado DRAFT. */
    @PutMapping("/goods-receipts/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_RECEIPT_CREATE)")
    @Operation(
            summary = "Actualizar recepción de mercadería en borrador",
            description = "Permiso: purchasing:receipt:create. Solo editable en estado DRAFT.")
    @ApiResponse(responseCode = "200", description = "Recepción actualizada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION o CONCURRENT_MODIFICATION")
    public GoodsReceiptResponse update(@PathVariable Long id,
                                       @Valid @RequestBody GoodsReceiptRequest request) {
        return GoodsReceiptResponse.from(receiptService.update(id, request.toCommand()));
    }

    /** Confirma la recepción: mueve stock, crea/actualiza lotes, recalcula costo promedio y actualiza la OC. */
    @PostMapping("/goods-receipts/{id}/confirm")
    @Idempotent
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_RECEIPT_CONFIRM)")
    @Operation(
            summary = "Confirmar recepción de mercadería",
            description = "Permiso: purchasing:receipt:confirm. Idempotente [I]. Mueve stock, crea lotes y actualiza costo promedio.")
    @ApiResponse(responseCode = "200", description = "Recepción confirmada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION, IDEMPOTENCY_CONFLICT o IDEMPOTENCY_IN_PROGRESS")
    @ApiResponse(responseCode = "422", description = "LOT_REQUIRED, RECEIPT_EXCEEDS_ORDER o SEGREGATION_OF_DUTIES")
    public GoodsReceiptResponse confirm(@PathVariable Long id) {
        return GoodsReceiptResponse.from(receiptService.confirm(id));
    }

    /** Cancela una recepción de mercadería en estado DRAFT. */
    @PostMapping("/goods-receipts/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_RECEIPT_CREATE)")
    @Operation(
            summary = "Cancelar recepción de mercadería",
            description = "Permiso: purchasing:receipt:create. Solo cancelable en estado DRAFT.")
    @ApiResponse(responseCode = "204", description = "Recepción cancelada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    public void cancel(@PathVariable Long id,
                       @Valid @RequestBody CancelGoodsReceiptRequest request) {
        receiptService.cancel(id, request.reason());
    }

    private static List<String> resolveRawSort(List<String> sort, HttpServletRequest request) {
        String[] rawValues = request.getParameterValues("sort");
        if (rawValues != null && rawValues.length > 0) {
            return Arrays.asList(rawValues);
        }
        return sort == null ? List.of() : sort;
    }
}
