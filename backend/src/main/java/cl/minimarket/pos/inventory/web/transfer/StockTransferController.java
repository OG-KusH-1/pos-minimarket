package cl.minimarket.pos.inventory.web.transfer;

import cl.minimarket.pos.inventory.application.transfer.TransferDirection;
import cl.minimarket.pos.inventory.application.transfer.TransferQueryService;
import cl.minimarket.pos.inventory.application.transfer.TransferQueryService.TransferDetail;
import cl.minimarket.pos.inventory.application.transfer.TransferService;
import cl.minimarket.pos.inventory.application.transfer.TransferService.CreateTransferCommand;
import cl.minimarket.pos.inventory.application.transfer.TransferService.ReceiveLineInput;
import cl.minimarket.pos.inventory.application.transfer.TransferService.ReceiveTransferCommand;
import cl.minimarket.pos.inventory.application.transfer.TransferService.TransferLineInput;
import cl.minimarket.pos.inventory.application.transfer.TransferService.UpdateTransferCommand;
import cl.minimarket.pos.inventory.application.transfer.TransferStatus;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Endpoints REST de transferencias de stock entre locales ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/stock-transfers")
@Tag(name = "Inventory")
public class StockTransferController {

    private static final SortParser SORT =
            SortParser.allowing("id", "number", "status", "sentAt", "receivedAt", "createdAt")
                    .withDefault(Sort.by(Sort.Direction.DESC, "id"));

    private final TransferService transferService;
    private final TransferQueryService queryService;

    public StockTransferController(
            TransferService transferService, TransferQueryService queryService) {
        this.transferService = Objects.requireNonNull(transferService, "transferService");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
    }

    /** Lista transferencias del local activo (entrantes, salientes o ambas). */
    @GetMapping
    @PreAuthorize(
            "hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_TRANSFER_READ)")
    @Operation(
            summary = "Listar transferencias entre locales",
            description =
                    "Permiso: inventory:transfer:read. Filtra por dirección (in/out) respecto al local activo X-Store-Id, estado y rango de fechas.")
    @ApiResponse(responseCode = "200", description = "Página de transferencias visibles para el local activo")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por parámetros de filtro u orden inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public PageResponse<StockTransferResponse> list(
            @Parameter(description = "Dirección respecto al local activo (in o out).")
                    @RequestParam(required = false)
                    String direction,
            @Parameter(description = "Filtrar por estado de la transferencia.")
                    @RequestParam(required = false)
                    TransferStatus status,
            @Parameter(description = "Fecha inicial inclusive (YYYY-MM-DD, zona horaria de negocio).")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "Fecha final inclusive (YYYY-MM-DD, zona horaria de negocio).")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest) {
        TransferDirection parsedDirection = parseDirection(direction);
        List<String> rawSort = resolveRawSort(sort, httpRequest);
        Pageable pageable = PageQuery.of(page, size).toPageable(SORT.parse(rawSort));
        Page<TransferDetail> result =
                queryService.list(parsedDirection, status, from, to, pageable);
        return PageResponse.from(result, StockTransferResponse::from);
    }

    /** Obtiene el detalle de una transferencia visible para el local activo. */
    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_TRANSFER_READ)")
    @Operation(
            summary = "Obtener detalle de transferencia",
            description =
                    "Permiso: inventory:transfer:read. Solo accesible si X-Store-Id es el local de origen o destino.")
    @ApiResponse(responseCode = "200", description = "Detalle de la transferencia")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la transferencia no existe o no involucra al local activo")
    public StockTransferResponse getById(@PathVariable Long id) {
        return StockTransferResponse.from(queryService.getById(id));
    }

    /** Crea una nueva transferencia en estado DRAFT con el local activo como origen. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(
            "hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_TRANSFER_SEND)")
    @Operation(
            summary = "Crear borrador de transferencia",
            description =
                    "Permiso: inventory:transfer:send. Crea el documento en estado DRAFT desde X-Store-Id.")
    @ApiResponse(responseCode = "201", description = "Borrador de transferencia creado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por datos inválidos, producto PACK o cantidad fraccionaria")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el local de destino o algún producto no existe")
    @ApiResponse(responseCode = "422", description = "PRODUCT_INACTIVE si algún producto está inactivo")
    public StockTransferResponse create(@Valid @RequestBody StockTransferRequest request) {
        List<TransferLineInput> lines =
                request.lines().stream()
                        .map(l -> new TransferLineInput(l.productId(), l.qtySent(), l.originLotId()))
                        .toList();
        TransferDetail created =
                transferService.create(
                        new CreateTransferCommand(
                                request.destinationStoreId(), request.notes(), lines));
        return StockTransferResponse.from(created);
    }

    /** Actualiza un borrador de transferencia existente desde su local de origen. */
    @PutMapping("/{id}")
    @PreAuthorize(
            "hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_TRANSFER_SEND)")
    @Operation(
            summary = "Editar borrador de transferencia",
            description =
                    "Permiso: inventory:transfer:send. Solo editable en estado DRAFT y desde el local de origen.")
    @ApiResponse(responseCode = "200", description = "Borrador de transferencia actualizado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por datos inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la transferencia, destino o producto no existe")
    @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION o INVALID_STATE_TRANSITION")
    @ApiResponse(responseCode = "422", description = "PRODUCT_INACTIVE si algún producto está inactivo")
    public StockTransferResponse update(
            @PathVariable Long id, @Valid @RequestBody StockTransferRequest request) {
        List<TransferLineInput> lines =
                request.lines().stream()
                        .map(l -> new TransferLineInput(l.productId(), l.qtySent(), l.originLotId()))
                        .toList();
        TransferDetail updated =
                transferService.update(
                        id,
                        new UpdateTransferCommand(
                                request.destinationStoreId(),
                                request.notes(),
                                lines,
                                request.version()));
        return StockTransferResponse.from(updated);
    }

    /** Despacha la transferencia desde el local de origen descontando stock (TRANSFER_OUT). */
    @PostMapping("/{id}/send")
    @Idempotent
    @PreAuthorize(
            "hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_TRANSFER_SEND)")
    @Operation(
            summary = "Enviar transferencia entre locales",
            description =
                    "Permiso: inventory:transfer:send. Idempotente ([I]). Descuenta stock en origen (TRANSFER_OUT) y deja mercadería en tránsito.")
    @ApiResponse(responseCode = "200", description = "Transferencia despachada en estado SENT")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR si falta Idempotency-Key")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la transferencia no existe para el local activo")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION si no está en estado DRAFT")
    @ApiResponse(responseCode = "422", description = "STOCK_INSUFFICIENT o PRODUCT_INACTIVE")
    public StockTransferResponse send(@PathVariable Long id) {
        return StockTransferResponse.from(transferService.send(id));
    }

    /** Recepciona la transferencia en el local de destino abonando stock (TRANSFER_IN). */
    @PostMapping("/{id}/receive")
    @Idempotent
    @PreAuthorize(
            "hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_TRANSFER_RECEIVE)")
    @Operation(
            summary = "Recepcionar transferencia en destino",
            description =
                    "Permiso: inventory:transfer:receive. Idempotente ([I]). Solo desde local destino; abona TRANSFER_IN y reporta diferencias a TransferDifferenceHandler.")
    @ApiResponse(responseCode = "200", description = "Transferencia recepcionada en estado RECEIVED")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por líneas faltantes, cantidades inválidas, falta de motivo de diferencia o falta de Idempotency-Key")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED si no se ejecuta desde el local destino o falta permiso")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la transferencia no existe para el local activo")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION si no está en estado SENT")
    public StockTransferResponse receive(
            @PathVariable Long id, @Valid @RequestBody TransferReceiveRequest request) {
        List<ReceiveLineInput> lines =
                request.lines().stream()
                        .map(
                                l ->
                                        new ReceiveLineInput(
                                                l.lineId(), l.qtyReceived(), l.differenceReason()))
                        .toList();
        return StockTransferResponse.from(
                transferService.receive(id, new ReceiveTransferCommand(lines)));
    }

    /** Cancela una transferencia en estado DRAFT desde el local de origen. */
    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(
            "hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).INVENTORY_TRANSFER_CANCEL)")
    @Operation(
            summary = "Cancelar borrador de transferencia",
            description =
                    "Permiso: inventory:transfer:cancel. Solo cancelable en estado DRAFT desde el local de origen.")
    @ApiResponse(responseCode = "204", description = "Borrador de transferencia cancelado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR si el motivo de cancelación es inválido")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la transferencia no existe para el local activo")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION si no está en estado DRAFT")
    public void cancel(
            @PathVariable Long id, @Valid @RequestBody CancelTransferRequest request) {
        transferService.cancel(id, request.reason());
    }

    private static List<String> resolveRawSort(
            List<String> boundSort, HttpServletRequest httpRequest) {
        // Spring MVC divide ?sort=campo,dir por coma al enlazar List<String>; leer los valores
        // crudos de HttpServletRequest conserva cada criterio "campo,dir" completo.
        if (httpRequest != null) {
            String[] values = httpRequest.getParameterValues("sort");
            return values == null ? null : Arrays.asList(values);
        }
        return boundSort;
    }

    private TransferDirection parseDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return null;
        }
        String normalized = direction.trim().toUpperCase(Locale.ROOT);
        try {
            return TransferDirection.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Dirección de transferencia inválida (use 'in' o 'out')",
                    Map.of(),
                    List.of(
                            new FieldErrorDetail(
                                    "direction",
                                    "INVALID_DIRECTION",
                                    "Valores permitidos: in, out")));
        }
    }
}
