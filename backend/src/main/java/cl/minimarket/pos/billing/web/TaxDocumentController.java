package cl.minimarket.pos.billing.web;

import cl.minimarket.pos.billing.TaxDocumentStatus;
import cl.minimarket.pos.billing.TaxDocumentType;
import cl.minimarket.pos.billing.application.TaxDocumentFilter;
import cl.minimarket.pos.billing.application.TaxDocumentQueryService;
import cl.minimarket.pos.billing.application.TaxDocumentService;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Endpoints REST del módulo {@code billing} ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Billing")
public class TaxDocumentController {

    private static final SortParser SORT = SortParser.allowing(
            "id", "folio", "documentType", "siiTypeCode", "status", "totalAmount", "issuedAt", "createdAt"
    ).withDefault(Sort.by(Sort.Direction.DESC, "id"));

    private final TaxDocumentQueryService queryService;
    private final TaxDocumentService taxDocumentService;

    public TaxDocumentController(
            TaxDocumentQueryService queryService,
            TaxDocumentService taxDocumentService
    ) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.taxDocumentService = Objects.requireNonNull(taxDocumentService, "taxDocumentService");
    }

    /** Lista paginada de documentos tributarios del local activo con filtros opcionales. */
    @GetMapping("/tax-documents")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_TAX_DOCUMENT_READ)")
    @Operation(
            summary = "Listar documentos tributarios",
            description = "Permiso: sales:tax-document:read. Filtra por estado, tipo, rango de fechas y folio."
    )
    @ApiResponse(responseCode = "200", description = "Página de documentos tributarios")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public PageResponse<TaxDocumentResponse> list(
            @RequestParam(required = false) TaxDocumentStatus status,
            @RequestParam(required = false) TaxDocumentType documentType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long folio,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest
    ) {
        List<String> rawSort = resolveRawSort(sort, httpRequest);
        Pageable pageable = PageQuery.of(page, size).toPageable(SORT.parse(rawSort));
        TaxDocumentFilter filter = new TaxDocumentFilter(status, documentType, from, to, folio);
        return PageResponse.from(queryService.list(filter, pageable), TaxDocumentResponse::from);
    }

    /** Obtiene el detalle de un documento tributario por su identificador. */
    @GetMapping("/tax-documents/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_TAX_DOCUMENT_READ)")
    @Operation(
            summary = "Obtener documento tributario por id",
            description = "Permiso: sales:tax-document:read."
    )
    @ApiResponse(responseCode = "200", description = "Documento tributario")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    public TaxDocumentResponse getById(@PathVariable Long id) {
        return TaxDocumentResponse.from(queryService.getById(id));
    }

    /** Reintenta la emisión de un documento en estado ERROR o REJECTED. */
    @PostMapping("/tax-documents/{id}/retry")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_TAX_DOCUMENT_RETRY)")
    @Operation(
            summary = "Reintentar emisión de documento tributario",
            description = "Permiso: sales:tax-document:retry. Solo permitido en estado ERROR o REJECTED; "
                    + "conserva el folio original y audita TAX_DOCUMENT_RETRIED."
    )
    @ApiResponse(responseCode = "200", description = "Documento actualizado tras el reintento")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    public TaxDocumentResponse retry(@PathVariable Long id) {
        return TaxDocumentResponse.from(taxDocumentService.retry(id));
    }

    /**
     * Obtiene el documento tributario asociado a una venta (204 No Content mientras no se haya emitido).
     */
    @GetMapping("/sales/{id}/tax-document")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Obtener documento tributario de una venta",
            description = "Permiso: sales:pos:operate. Alcance P: los cajeros solo consultan sus propias ventas. "
                    + "Responde 204 No Content si aún no se emite."
    )
    @ApiResponse(responseCode = "200", description = "Documento tributario emitido")
    @ApiResponse(responseCode = "204", description = "Documento aún pendiente de emisión asíncrona")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND")
    public ResponseEntity<TaxDocumentResponse> getBySaleId(@PathVariable Long id) {
        return queryService.findBySaleId(id)
                .map(TaxDocumentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Obtiene la nota de crédito asociada a una devolución/anulación (204 No Content mientras no se haya emitido).
     */
    @GetMapping("/sale-returns/{id}/tax-document")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_SALE_READ)")
    @Operation(
            summary = "Obtener nota de crédito de una devolución",
            description = "Permiso: sales:sale:read. Responde 204 No Content si aún no se emite."
    )
    @ApiResponse(responseCode = "200", description = "Nota de crédito emitida")
    @ApiResponse(responseCode = "204", description = "Nota de crédito aún pendiente de emisión asíncrona")
    public ResponseEntity<TaxDocumentResponse> getBySaleReturnId(@PathVariable Long id) {
        return queryService.findBySaleReturnId(id)
                .map(TaxDocumentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static List<String> resolveRawSort(List<String> boundSort, HttpServletRequest httpRequest) {
        if (httpRequest != null) {
            String[] values = httpRequest.getParameterValues("sort");
            return values == null ? null : Arrays.asList(values);
        }
        return boundSort;
    }
}
