package cl.minimarket.pos.sales.payment.web;

import cl.minimarket.pos.sales.payment.application.CardPaymentService;
import cl.minimarket.pos.sales.payment.domain.CardAuthorization;
import cl.minimarket.pos.shared.context.RequiresTerminal;
import cl.minimarket.pos.shared.idempotency.IdempotencyFilter;
import cl.minimarket.pos.shared.idempotency.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Endpoints POS de autorización y anulación de pagos con tarjeta ({@code API.md} F.2, {@code PLAN.md} H.3 T33).
 */
@RestController
@RequestMapping("/api/v1/pos/card-payments")
@RequiresTerminal
@Tag(name = "Sales")
public class CardPaymentController {

    private final CardPaymentService service;

    public CardPaymentController(CardPaymentService service) {
        this.service = service;
    }

    /**
     * Solicita una autorización de tarjeta contra la pasarela configurada ([I][T]).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Autorizar pago con tarjeta en POS",
            description = "Permiso: sales:pos:operate. Errores: VALIDATION_ERROR, CASH_SESSION_NOT_OPEN, "
                    + "PAYMENT_DECLINED, IDEMPOTENCY_CONFLICT, IDEMPOTENCY_IN_PROGRESS."
    )
    public CardAuthorizationResponse authorize(
            @RequestHeader(IdempotencyFilter.KEY_HEADER) UUID idempotencyKey,
            @Valid @RequestBody CardAuthorizationRequest request
    ) {
        CardAuthorization authorization = service.authorize(
                request.method(),
                request.amount(),
                idempotencyKey
        );
        return CardAuthorizationResponse.from(authorization);
    }

    /**
     * Anula una autorización aprobada que aún no ha sido asociada a ninguna venta ([T]).
     */
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SALES_POS_OPERATE)")
    @Operation(
            summary = "Anular autorización de tarjeta no usada",
            description = "Permiso: sales:pos:operate. Errores: VALIDATION_ERROR, NOT_FOUND, INVALID_STATE_TRANSITION."
    )
    public CardAuthorizationResponse voidAuthorization(@PathVariable Long id) {
        return CardAuthorizationResponse.from(service.voidAuthorization(id));
    }
}
