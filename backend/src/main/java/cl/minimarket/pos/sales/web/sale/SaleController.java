package cl.minimarket.pos.sales.web.sale;

import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import cl.minimarket.pos.sales.application.pricing.PricingModels.DiscountSpec;
import cl.minimarket.pos.sales.application.pricing.PricingModels.LineInput;
import cl.minimarket.pos.sales.application.sale.SaleEmailService;
import cl.minimarket.pos.sales.application.sale.SaleFilter;
import cl.minimarket.pos.sales.application.sale.SaleQueryService;
import cl.minimarket.pos.sales.application.sale.SaleService;
import cl.minimarket.pos.sales.application.sale.SaleService.CreateSaleCommand;
import cl.minimarket.pos.sales.application.sale.SaleService.PaymentCommand;
import cl.minimarket.pos.sales.application.sale.SaleStatus;
import cl.minimarket.pos.sales.web.pos.DiscountInput;
import cl.minimarket.pos.shared.context.RequiresTerminal;
import cl.minimarket.pos.shared.idempotency.IdempotencyFilter;
import cl.minimarket.pos.shared.idempotency.Idempotent;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.List;
import java.util.UUID;

/**
 * Endpoints REST de creación, consulta, ticket y reimpresión de ventas ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/sales")
@Tag(name = "Sales")
public class SaleController {

    private static final SortParser SORT = SortParser.allowing(
            "id", "number", "soldAt", "businessDate", "totalPayable", "status"
    ).withDefault(Sort.by(Sort.Direction.DESC, "soldAt"));

    private final SaleService saleService;
    private final SaleQueryService queryService;
    private final SaleEmailService emailService;

    public SaleController(SaleService saleService, SaleQueryService queryService, SaleEmailService emailService) {
        this.saleService = saleService;
        this.queryService = queryService;
        this.emailService = emailService;
    }

    /**
     * Registra y cobra una venta en el POS ([I][T][O:DISCOUNT_ITEM_OVER_LIMIT,
     * DISCOUNT_TOTAL_OVER_LIMIT, CREDIT_OVER_LIMIT]).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @RequiresTerminal
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Crear venta en POS",
            description = "Permiso: sales:pos:operate (más customers:credit:sell si el pago es fiado). "
                    + "Idempotente ([I]) y ligado a terminal ([T]). "
                    + "Acepta múltiples tokens en X-Override-Token separados por coma. "
                    + "El fiado exige cliente con crédito habilitado; sobre el cupo requiere override CREDIT_OVER_LIMIT."
    )
    public SaleResponse create(
            @RequestHeader(IdempotencyFilter.KEY_HEADER) UUID idempotencyKey,
            @RequestHeader(value = "X-Override-Token", required = false) String overrideToken,
            @Valid @RequestBody CreateSaleRequest request
    ) {
        List<LineInput> lines = request.lines().stream()
                .map(l -> new LineInput(
                        l.productId(),
                        l.quantity(),
                        l.scannedCode(),
                        l.embeddedPrice(),
                        toDiscountSpec(l.manualDiscount())
                ))
                .toList();

        List<PaymentCommand> payments = request.payments().stream()
                .map(p -> new PaymentCommand(
                        p.method(),
                        p.amount(),
                        p.tenderedAmount(),
                        p.cardAuthorizationId(),
                        p.transferReference()
                ))
                .toList();

        CreateSaleCommand command = new CreateSaleCommand(
                lines,
                toDiscountSpec(request.totalDiscount()),
                request.customerId(),
                payments,
                request.ageConfirmed(),
                request.emailTo(),
                idempotencyKey,
                overrideToken
        );

        return SaleResponse.from(saleService.create(command));
    }

    /** Lista ventas del local activo con filtros y alcance P para cajeros. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_SALE_READ)")
    @Operation(
            summary = "Listar ventas",
            description = "Permiso: sales:sale:read. Alcance P: los cajeros ven solo sus propias ventas."
    )
    public PageResponse<SaleSummary> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long cashierId,
            @RequestParam(required = false) Long terminalId,
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(SORT.parse(sort));
        SaleFilter filter = new SaleFilter(from, to, cashierId, terminalId, status, paymentMethod, customerId, q);
        return PageResponse.from(queryService.list(filter, pageable), SaleSummary::from);
    }

    /** Obtiene el detalle de una venta por su identificador respetando alcance P. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_SALE_READ)")
    @Operation(
            summary = "Obtener venta por id",
            description = "Permiso: sales:sale:read. Alcance P: ventas ajenas responden 404 NOT_FOUND."
    )
    public SaleResponse getById(@PathVariable Long id) {
        return SaleResponse.from(queryService.getById(id));
    }

    /** Obtiene el detalle de una venta por su folio interno respetando alcance P. */
    @GetMapping("/by-number/{number}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_SALE_READ)")
    @Operation(
            summary = "Obtener venta por número",
            description = "Permiso: sales:sale:read. Alcance P: ventas ajenas responden 404 NOT_FOUND."
    )
    public SaleResponse getByNumber(@PathVariable String number) {
        return SaleResponse.from(queryService.getByNumber(number));
    }

    /** Obtiene los datos del ticket térmico (80 mm) de una venta para su impresión inicial. */
    @GetMapping("/{id}/ticket")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Obtener ticket térmico de venta",
            description = "Permiso: sales:pos:operate. La primera impresión es libre; aplica alcance P."
    )
    public TicketResponse ticket(@PathVariable Long id) {
        return TicketResponse.from(queryService.getTicket(id));
    }

    /** Reimprime el ticket térmico con etiqueta COPIA ([O:RECEIPT_REPRINT]; auditado). */
    @PostMapping("/{id}/reprint")
    @PreAuthorize("hasAnyAuthority("
            + "T(cl.minimarket.pos.shared.security.Permissions).SALES_RECEIPT_REPRINT,"
            + "T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Reimprimir ticket de venta",
            description = "Permiso: sales:receipt:reprint (o sales:pos:operate con override RECEIPT_REPRINT). "
                    + "Devuelve TicketResponse marcado como COPIA y audita RECEIPT_REPRINTED."
    )
    public TicketResponse reprint(
            @PathVariable Long id,
            @RequestHeader(value = "X-Override-Token", required = false) String overrideToken
    ) {
        return TicketResponse.from(queryService.reprintTicket(id, overrideToken));
    }

    /** Envía el comprobante de la venta por correo, encolándolo en la outbox (HU-POS-11). */
    @PostMapping("/{id}/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Enviar comprobante de venta por correo",
            description = "Permiso: sales:pos:operate. Encola el correo en la outbox de notificación y "
                    + "responde 202 Accepted; si el SMTP está caído la venta no falla y el job de la "
                    + "outbox reintenta el envío (HU-POS-11)."
    )
    public void emailTicket(
            @PathVariable Long id,
            @Valid @RequestBody EmailTicketRequest request
    ) {
        emailService.sendTicketByEmail(id, request.email());
    }

    private static DiscountSpec toDiscountSpec(DiscountInput input) {
        if (input == null) {
            return null;
        }
        return new DiscountSpec(input.kind(), input.value(), input.reason());
    }
}
