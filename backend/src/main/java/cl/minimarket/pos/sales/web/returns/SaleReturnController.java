package cl.minimarket.pos.sales.web.returns;

import cl.minimarket.pos.sales.application.returns.SaleReturnFilter;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.RefundCommand;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.ReturnLineCommand;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.ReturnSaleCommand;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.VoidSaleCommand;
import cl.minimarket.pos.sales.application.returns.SaleReturnService;
import cl.minimarket.pos.sales.application.returns.SaleReturnType;
import cl.minimarket.pos.shared.context.RequiresTerminal;
import cl.minimarket.pos.shared.idempotency.IdempotencyFilter;
import cl.minimarket.pos.shared.idempotency.Idempotent;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.UUID;

/**
 * Endpoints REST de anulación y devoluciones de ventas ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Sale Returns")
public class SaleReturnController {

    private static final SortParser SORT = SortParser.allowing(
            "id", "number", "createdAt", "returnType", "refundTotal"
    ).withDefault(Sort.by(Sort.Direction.DESC, "createdAt"));

    private final SaleReturnService returnService;

    public SaleReturnController(SaleReturnService returnService) {
        this.returnService = returnService;
    }

    /** Consulta las líneas devolvibles y si la venta aún admite anulación (alcance P). */
    @GetMapping("/sales/{id}/returnable")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Consultar líneas devolvibles de una venta",
            description = "Permiso: sales:pos:operate. Alcance P: ventas ajenas responden 404 NOT_FOUND."
    )
    public SaleReturnableLinesResponse returnable(@PathVariable Long id) {
        return SaleReturnableLinesResponse.from(returnService.getReturnable(id));
    }

    /** Anula una venta completa en el mismo turno abierto ([I][T][O:SALE_VOID]). */
    @PostMapping("/sales/{id}/void")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @RequiresTerminal
    @PreAuthorize("hasAnyAuthority("
            + "T(cl.minimarket.pos.shared.security.Permissions).SALES_SALE_VOID,"
            + "T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Anular venta completa",
            description = "Permiso: sales:sale:void (o sales:pos:operate con override SALE_VOID). "
                    + "Idempotente ([I]) y ligado a terminal ([T]). Valida RN-12 (turno original abierto)."
    )
    public SaleReturnResponse voidSale(
            @PathVariable Long id,
            @RequestHeader(IdempotencyFilter.KEY_HEADER) UUID idempotencyKey,
            @RequestHeader(value = "X-Override-Token", required = false) String overrideToken,
            @Valid @RequestBody SaleVoidRequest request
    ) {
        VoidSaleCommand command = new VoidSaleCommand(
                id,
                request.reasonCode(),
                request.reasonText(),
                idempotencyKey,
                overrideToken
        );
        return SaleReturnResponse.from(returnService.voidSale(command));
    }

    /** Registra una devolución parcial o total de una venta ([I][T][O:SALE_RETURN]). */
    @PostMapping("/sales/{id}/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @RequiresTerminal
    @PreAuthorize("hasAnyAuthority("
            + "T(cl.minimarket.pos.shared.security.Permissions).SALES_RETURN_CREATE,"
            + "T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Registrar devolución de venta",
            description = "Permiso: sales:return:create (o sales:pos:operate con override SALE_RETURN, "
                    + "sujeto a límite RETURN_MAX_AMOUNT). Idempotente ([I]) y ligado a terminal ([T])."
    )
    public SaleReturnResponse createReturn(
            @PathVariable Long id,
            @RequestHeader(IdempotencyFilter.KEY_HEADER) UUID idempotencyKey,
            @RequestHeader(value = "X-Override-Token", required = false) String overrideToken,
            @Valid @RequestBody SaleReturnRequest request
    ) {
        List<ReturnLineCommand> lines = request.lines().stream()
                .map(l -> new ReturnLineCommand(l.saleLineId(), l.quantity(), l.effectiveRestock()))
                .toList();

        List<RefundCommand> refunds = request.refunds() == null
                ? List.of()
                : request.refunds().stream()
                        .map(r -> new RefundCommand(r.method(), r.amount()))
                        .toList();

        ReturnSaleCommand command = new ReturnSaleCommand(
                id,
                request.reasonCode(),
                request.reasonText(),
                lines,
                refunds,
                idempotencyKey,
                overrideToken
        );
        return SaleReturnResponse.from(returnService.returnSale(command));
    }

    /** Lista anulaciones y devoluciones del local activo con filtros y alcance P para cajeros. */
    @GetMapping("/sale-returns")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_SALE_READ)")
    @Operation(
            summary = "Listar anulaciones y devoluciones",
            description = "Permiso: sales:sale:read. Alcance P: los cajeros ven solo devoluciones de sus ventas."
    )
    public PageResponse<SaleReturnResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) SaleReturnType returnType,
            @RequestParam(required = false) Long saleId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request
    ) {
        String[] rawSort = request.getParameterValues("sort");
        Sort sort = SORT.parse(rawSort == null ? List.of() : Arrays.asList(rawSort));
        Pageable pageable = PageQuery.of(page, size).toPageable(sort);
        SaleReturnFilter filter = new SaleReturnFilter(from, to, returnType, saleId);
        return PageResponse.from(returnService.listReturns(filter, pageable), SaleReturnResponse::from);
    }

    /** Obtiene el detalle de una anulación o devolución por su id respetando alcance P. */
    @GetMapping("/sale-returns/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_SALE_READ)")
    @Operation(
            summary = "Obtener anulación o devolución por id",
            description = "Permiso: sales:sale:read. Alcance P: devoluciones de ventas ajenas responden 404."
    )
    public SaleReturnResponse getById(@PathVariable Long id) {
        return SaleReturnResponse.from(returnService.getReturnById(id));
    }
}
