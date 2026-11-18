package cl.minimarket.pos.purchasing.order.web;

import cl.minimarket.pos.purchasing.order.application.PurchaseOrderQueryService;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderQueryService.PurchaseOrderDetail;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderQueryService.PurchaseOrderFilter;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderQueryService.PurchaseOrderSummaryView;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderService;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderOrigin;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderStatus;
import cl.minimarket.pos.shared.context.StoreContext;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Endpoints por local de órdenes de compra ({@code API.md} F.2, {@code HU-PRV-03}, {@code HU-PRV-04}).
 */
@RestController
@RequestMapping("/api/v1/purchase-orders")
@Tag(name = "Purchasing")
public class PurchaseOrderController {

    private static final SortParser ORDERS_SORT = SortParser.allowing(
                    "id", "number", "supplierName", "status", "origin", "total", "createdAt", "expectedDate")
            .withDefault(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    private final PurchaseOrderQueryService queryService;
    private final PurchaseOrderService orderService;

    public PurchaseOrderController(PurchaseOrderQueryService queryService,
                                   PurchaseOrderService orderService) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.orderService = Objects.requireNonNull(orderService, "orderService");
    }

    /** Lista paginada de órdenes de compra del local activo. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_ORDER_READ)")
    @Operation(
            summary = "Listar órdenes de compra",
            description = "Permiso: purchasing:order:read. Total visible solo con catalog:cost:read.")
    @ApiResponse(responseCode = "200", description = "Página de órdenes de compra")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    public PageResponse<PurchaseOrderSummary> list(
            @Parameter(description = "Filtrar por estado de la orden.")
            @RequestParam(required = false) PurchaseOrderStatus status,
            @Parameter(description = "Filtrar por proveedor.")
            @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Filtrar por origen (MANUAL o REPLENISHMENT).")
            @RequestParam(required = false) PurchaseOrderOrigin origin,
            @Parameter(description = "Fecha de creación desde (YYYY-MM-DD).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Fecha de creación hasta (YYYY-MM-DD).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Búsqueda por número de orden o proveedor.")
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest) {
        Long storeId = StoreContext.require().storeId();
        List<String> rawSort = resolveRawSort(sort, httpRequest);
        Pageable pageable = PageQuery.of(page, size).toPageable(ORDERS_SORT.parse(rawSort));
        Page<PurchaseOrderSummaryView> result = queryService.list(
                storeId,
                new PurchaseOrderFilter(status, supplierId, origin, from, to, q),
                pageable);
        return PageResponse.from(result, PurchaseOrderSummary::from);
    }

    /** Obtiene el detalle de una orden de compra del local activo. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_ORDER_READ)")
    @Operation(
            summary = "Obtener orden de compra",
            description = "Permiso: purchasing:order:read. Costos visibles solo con catalog:cost:read.")
    @ApiResponse(responseCode = "200", description = "Detalle de la orden de compra")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    public PurchaseOrderResponse getById(@PathVariable Long id) {
        Long storeId = StoreContext.require().storeId();
        return PurchaseOrderResponse.from(queryService.getById(id, storeId));
    }

    /** Crea una orden de compra en estado DRAFT. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_ORDER_CREATE)")
    @Operation(
            summary = "Crear orden de compra en borrador",
            description = "Permiso: purchasing:order:create.")
    @ApiResponse(responseCode = "201", description = "Orden de compra creada en estado DRAFT")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    public PurchaseOrderResponse create(@Valid @RequestBody PurchaseOrderRequest request) {
        return PurchaseOrderResponse.from(orderService.create(request.toCommand()));
    }

    /** Actualiza una orden de compra en estado DRAFT (RN-18). */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_ORDER_CREATE)")
    @Operation(
            summary = "Actualizar orden de compra en borrador",
            description = "Permiso: purchasing:order:create. Solo editable en estado DRAFT.")
    @ApiResponse(responseCode = "200", description = "Orden de compra actualizada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION o CONCURRENT_MODIFICATION")
    public PurchaseOrderResponse update(@PathVariable Long id,
                                        @Valid @RequestBody PurchaseOrderRequest request) {
        return PurchaseOrderResponse.from(orderService.update(id, request.toCommand()));
    }

    /** Aprueba y envía la orden de compra (DRAFT -> SENT), opcionalmente encolando PDF por correo. */
    @PostMapping("/{id}/send")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_ORDER_SEND)")
    @Operation(
            summary = "Enviar orden de compra",
            description = "Permiso: purchasing:order:send. Transiciona DRAFT -> SENT y opcionalmente encola PDF en la outbox.")
    @ApiResponse(responseCode = "200", description = "Orden de compra enviada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION o CONCURRENT_MODIFICATION")
    public PurchaseOrderResponse send(@PathVariable Long id,
                                      @Valid @RequestBody SendPurchaseOrderRequest request) {
        return PurchaseOrderResponse.from(
                orderService.send(id, request.shouldEmailSupplier(), request.version()));
    }

    /** Cancela una orden de compra en estado DRAFT o SENT sin recepciones confirmadas. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_ORDER_CANCEL)")
    @Operation(
            summary = "Cancelar orden de compra",
            description = "Permiso: purchasing:order:cancel. Rechaza si tiene recepciones confirmadas.")
    @ApiResponse(responseCode = "200", description = "Orden de compra cancelada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    public PurchaseOrderResponse cancel(@PathVariable Long id,
                                        @Valid @RequestBody CancelPurchaseOrderRequest request) {
        return PurchaseOrderResponse.from(orderService.cancel(id, request.reason()));
    }

    /** Exporta la orden de compra en formato PDF. */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_ORDER_READ)")
    @Operation(
            summary = "Exportar orden de compra a PDF",
            description = "Permiso: purchasing:order:read. Oculta costos si no posee catalog:cost:read.")
    @ApiResponse(responseCode = "200", description = "Documento PDF generado")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Long storeId = StoreContext.require().storeId();
        PurchaseOrderDetail detail = queryService.getById(id, storeId);
        byte[] pdfBytes = queryService.renderPdf(id, storeId);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename("OC-" + detail.number() + ".pdf", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(pdfBytes);
    }

    private static List<String> resolveRawSort(List<String> sort, HttpServletRequest request) {
        String[] rawValues = request.getParameterValues("sort");
        if (rawValues != null && rawValues.length > 0) {
            return Arrays.asList(rawValues);
        }
        return sort == null ? List.of() : sort;
    }
}
