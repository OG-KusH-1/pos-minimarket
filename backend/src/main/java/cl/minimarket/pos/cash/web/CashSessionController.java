package cl.minimarket.pos.cash.web;

import cl.minimarket.pos.cash.CashSessionStatus;
import cl.minimarket.pos.cash.application.CashSessionFilter;
import cl.minimarket.pos.cash.application.CashSessionService;
import cl.minimarket.pos.cash.application.CashSessionService.CloseCountCommand;
import cl.minimarket.pos.cash.application.CashSessionService.SessionDetail;
import cl.minimarket.pos.shared.context.RequiresTerminal;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.idempotency.Idempotent;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.Map;
import java.util.UUID;

/**
 * Endpoints REST de caja y turnos ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/cash-sessions")
@Tag(name = "Cash")
public class CashSessionController {

    private static final SortParser SORT = SortParser.allowing(
            "id", "number", "businessDate", "openedAt", "closedAt", "status", "differenceTotal"
    ).withDefault(Sort.by(Sort.Direction.DESC, "openedAt"));

    private final CashSessionService service;

    public CashSessionController(CashSessionService service) {
        this.service = service;
    }

    /** Abre un turno de caja en la terminal activa ([I][T]). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @RequiresTerminal
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CASH_SESSION_OPEN)")
    @Operation(summary = "Abrir turno de caja")
    public CashSessionResponse open(@Valid @RequestBody OpenCashSessionRequest request) {
        return CashSessionResponse.from(service.open(request.openingAmount()));
    }

    /** Obtiene el turno abierto actual de la terminal o 204 si no hay ninguno ([T]). */
    @GetMapping("/current")
    @RequiresTerminal
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CASH_SESSION_OPEN)")
    @Operation(summary = "Obtener turno abierto actual de la terminal")
    public ResponseEntity<CashSessionResponse> current() {
        return service.findCurrent()
                .map(detail -> ResponseEntity.ok(CashSessionResponse.from(detail)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Lista el historial de turnos del local activo (cajero solo ve los propios). */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CASH_SESSION_READ)")
    @Operation(summary = "Listar historial de turnos")
    public PageResponse<CashSessionResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long cashierId,
            @RequestParam(required = false) Long terminalId,
            @RequestParam(required = false) CashSessionStatus status,
            @RequestParam(required = false) Boolean withDifference,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(SORT.parse(sort));
        CashSessionFilter filter = new CashSessionFilter(from, to, cashierId, terminalId, status, withDifference);
        Page<SessionDetail> result = service.list(filter, pageable);
        return PageResponse.from(result, CashSessionResponse::from);
    }

    /** Obtiene el detalle de un turno por su id. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CASH_SESSION_READ)")
    @Operation(summary = "Obtener turno de caja")
    public CashSessionResponse get(@PathVariable Long id) {
        return CashSessionResponse.from(service.get(id));
    }

    /** Calcula el resumen financiero esperado del turno según la fórmula B.8. */
    @GetMapping("/{id}/summary")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CASH_SESSION_READ)")
    @Operation(summary = "Obtener resumen financiero del turno")
    public CashSessionSummaryResponse summary(@PathVariable Long id) {
        return CashSessionSummaryResponse.from(service.summary(id));
    }

    /** Registra un ingreso o retiro de efectivo en el turno ([I][O:CASH_OUT_OVER_LIMIT]). */
    @PostMapping("/{id}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasAnyAuthority("
            + "T(cl.minimarket.pos.shared.security.Permissions).CASH_MOVEMENT_CASH_IN,"
            + "T(cl.minimarket.pos.shared.security.Permissions).CASH_MOVEMENT_CASH_OUT)")
    @Operation(summary = "Registrar ingreso o retiro de efectivo")
    public CashMovementResponse registerMovement(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(value = "X-Override-Token", required = false) String overrideToken,
            @Valid @RequestBody CashMovementRequest request
    ) {
        return CashMovementResponse.from(service.registerMovement(
                id,
                request.movementType(),
                request.amount(),
                request.reason(),
                idempotencyKey,
                overrideToken
        ));
    }

    /** Lista los movimientos manuales de efectivo de un turno. */
    @GetMapping("/{id}/movements")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CASH_SESSION_READ)")
    @Operation(summary = "Listar movimientos de efectivo del turno")
    public List<CashMovementResponse> listMovements(@PathVariable Long id) {
        return service.listMovements(id).stream()
                .map(CashMovementResponse::from)
                .toList();
    }

    /** Cierra el turno registrando el arqueo por medio de pago ([I][O:CASH_DIFFERENCE_APPROVAL]). */
    @PostMapping("/{id}/close")
    @Idempotent
    @PreAuthorize("hasAnyAuthority("
            + "T(cl.minimarket.pos.shared.security.Permissions).CASH_SESSION_CLOSE,"
            + "T(cl.minimarket.pos.shared.security.Permissions).CASH_SESSION_CLOSE_OTHERS)")
    @Operation(summary = "Cerrar turno de caja con arqueo")
    public CashSessionResponse close(
            @PathVariable Long id,
            @RequestHeader(value = "X-Override-Token", required = false) String overrideToken,
            @Valid @RequestBody CloseCashSessionRequest request
    ) {
        List<CloseCountCommand> counts = request.counts().stream()
                .map(c -> new CloseCountCommand(
                        c.paymentMethod(),
                        c.countedAmount(),
                        c.denominationDetail(),
                        c.notes()
                ))
                .toList();
        return CashSessionResponse.from(service.close(id, counts, request.notes(), overrideToken));
    }

    /** Aprueba una diferencia de cierre pendiente (SOD RN-23). */
    @PostMapping("/{id}/approve-difference")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CASH_DIFFERENCE_APPROVE)")
    @Operation(summary = "Aprobar diferencia de cierre")
    public CashSessionResponse approveDifference(@PathVariable Long id) {
        return CashSessionResponse.from(service.approveDifference(id));
    }

    /** Emite el comprobante de cierre del turno en PDF o JSON. */
    @GetMapping("/{id}/closing-receipt")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CASH_SESSION_READ)")
    @Operation(
            summary = "Obtener comprobante de cierre de caja (PDF o JSON)",
            responses = @ApiResponse(
                    responseCode = "200",
                    description = "Comprobante de cierre en JSON o PDF",
                    content = {
                            @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = CashClosingReceiptResponse.class)
                            ),
                            @Content(
                                    mediaType = MediaType.APPLICATION_PDF_VALUE,
                                    schema = @Schema(type = "string", format = "binary")
                            )
                    }
            )
    )
    public ResponseEntity<?> closingReceipt(
            @PathVariable Long id,
            @RequestParam(defaultValue = "pdf") String format
    ) {
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(CashClosingReceiptResponse.from(service.closingReceipt(id)));
        }
        if ("pdf".equalsIgnoreCase(format)) {
            byte[] pdf = service.renderClosingReceiptPdf(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"cierre-caja-" + id + ".pdf\"")
                    .body(pdf);
        }
        throw new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "Formato no soportado: " + format + ". Use 'pdf' o 'json'.",
                Map.of(),
                List.of(new FieldErrorDetail("format", "VALIDATION_ERROR", "Valores permitidos: pdf, json."))
        );
    }
}
