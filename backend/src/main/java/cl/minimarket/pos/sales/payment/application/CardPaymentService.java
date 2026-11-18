package cl.minimarket.pos.sales.payment.application;

import cl.minimarket.pos.cash.CashSessionApi;
import cl.minimarket.pos.cash.CashSessionRef;
import cl.minimarket.pos.sales.payment.CardAuthorizationStatus;
import cl.minimarket.pos.sales.payment.CardPaymentMethod;
import cl.minimarket.pos.sales.payment.PaymentGateway;
import cl.minimarket.pos.sales.payment.PaymentRequest;
import cl.minimarket.pos.sales.payment.PaymentResult;
import cl.minimarket.pos.sales.payment.PaymentResultStatus;
import cl.minimarket.pos.sales.payment.RefundRequest;
import cl.minimarket.pos.sales.payment.domain.CardAuthorization;
import cl.minimarket.pos.sales.payment.domain.CardAuthorizationRepository;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Servicio de aplicación para autorizaciones y anulaciones de pagos con tarjeta en POS
 * ({@code PLAN.md} H.3 T33, {@code API.md} F.2 / F.4).
 */
@Service
public class CardPaymentService {

    private final CardAuthorizationRepository repository;
    private final PaymentGateway paymentGateway;
    private final CashSessionApi cashSessionApi;
    private final TransactionTemplate requiresNewTx;

    public CardPaymentService(
            CardAuthorizationRepository repository,
            PaymentGateway paymentGateway,
            CashSessionApi cashSessionApi,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.paymentGateway = paymentGateway;
        this.cashSessionApi = cashSessionApi;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Solicita una autorización de tarjeta contra {@link PaymentGateway} para la terminal y turno
     * activos.
     *
     * <p>Si la pasarela aprueba, persiste la autorización bajo la {@code idempotencyKey} recibida
     * (L-04: si ya existía con los mismos parámetros, devuelve la existente). Si la pasarela rechaza
     * ({@code DECLINED}) o falla ({@code ERROR}), registra el intento en {@code card_authorizations}
     * en una transacción independiente y lanza {@link BusinessException} ({@code PAYMENT_DECLINED} o
     * {@code INTERNAL_ERROR}) sin ocupar la {@code idempotencyKey} del cliente, permitiendo que
     * corrija el monto y reintente.
     *
     * @param method medio de pago ({@code DEBIT} o {@code CREDIT})
     * @param amount monto en CLP (debe ser {@code >= 1})
     * @param idempotencyKey clave de idempotencia del request
     * @return autorización aprobada y persistida
     */
    public CardAuthorization authorize(CardPaymentMethod method, BigDecimal amount, UUID idempotencyKey) {
        StoreContext ctx = StoreContext.require();
        Long terminalId = ctx.terminalId();
        if (terminalId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El header X-Terminal-Id es obligatorio.");
        }
        BigDecimal normalizedAmount = Rounding.clp(amount);
        if (normalizedAmount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El monto debe ser mayor o igual a 1.");
        }

        // Exige turno de caja abierto en la terminal activa (422 CASH_SESSION_NOT_OPEN)
        CashSessionRef session = cashSessionApi.requireOpen(terminalId);

        Optional<CardAuthorization> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return verifyMatchingRequest(existing.get(), ctx.storeId(), terminalId, method, normalizedAmount);
        }

        PaymentResult result = paymentGateway.authorize(new PaymentRequest(
                terminalId,
                method,
                normalizedAmount,
                idempotencyKey,
                "CS-" + session.id()
        ));

        if (result.status() == PaymentResultStatus.APPROVED) {
            return persistApproved(ctx.storeId(), terminalId, session.id(), method, normalizedAmount, idempotencyKey, result);
        }

        CardAuthorizationStatus failedStatus = result.status() == PaymentResultStatus.DECLINED
                ? CardAuthorizationStatus.DECLINED
                : CardAuthorizationStatus.ERROR;
        persistFailedAttempt(ctx.storeId(), terminalId, session.id(), method, normalizedAmount, failedStatus, result);

        if (result.status() == PaymentResultStatus.DECLINED) {
            throw new BusinessException(ErrorCode.PAYMENT_DECLINED, result.message());
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, result.message());
    }

    /**
     * Anula una autorización aprobada que aún no ha sido asociada a ninguna venta
     * ({@code API.md} F.2 / F.4).
     *
     * @param authorizationId identificador de la autorización en {@code card_authorizations}
     * @return autorización actualizada en estado {@link CardAuthorizationStatus#VOIDED}
     */
    @Transactional
    public CardAuthorization voidAuthorization(Long authorizationId) {
        StoreContext ctx = StoreContext.require();
        if (ctx.terminalId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El header X-Terminal-Id es obligatorio.");
        }
        CardAuthorization auth = repository.findByIdAndStoreId(authorizationId, ctx.storeId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "Autorización de tarjeta no encontrada."
                ));

        if (auth.getSaleId() != null || auth.getStatus() != CardAuthorizationStatus.APPROVED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden anular autorizaciones aprobadas que no estén asociadas a una venta."
            );
        }

        if (auth.getGatewayReference() != null) {
            paymentGateway.refund(new RefundRequest(
                    auth.getGatewayReference(),
                    auth.getAmount(),
                    UUID.randomUUID()
            ));
        }

        auth.markVoided("Autorización anulada");
        return repository.save(auth);
    }

    private CardAuthorization persistApproved(
            Long storeId,
            Long terminalId,
            Long cashSessionId,
            CardPaymentMethod method,
            BigDecimal amount,
            UUID idempotencyKey,
            PaymentResult result
    ) {
        CardAuthorization entity = new CardAuthorization(
                storeId,
                terminalId,
                cashSessionId,
                method,
                amount,
                idempotencyKey,
                CardAuthorizationStatus.APPROVED,
                result.gatewayReference(),
                result.authorizationCode(),
                result.cardBrand(),
                result.last4(),
                result.message()
        );
        try {
            return requiresNewTx.execute(status -> repository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            // L-04: si la UQ de idempotency_key saltó por concurrencia, devolver el recurso existente
            return repository.findByIdempotencyKey(idempotencyKey)
                    .map(found -> verifyMatchingRequest(found, storeId, terminalId, method, amount))
                    .orElseThrow(() -> ex);
        }
    }

    private void persistFailedAttempt(
            Long storeId,
            Long terminalId,
            Long cashSessionId,
            CardPaymentMethod method,
            BigDecimal amount,
            CardAuthorizationStatus status,
            PaymentResult result
    ) {
        // Se guarda con UUID propio para no bloquear la Idempotency-Key del cliente ante 422/500
        CardAuthorization entity = new CardAuthorization(
                storeId,
                terminalId,
                cashSessionId,
                method,
                amount,
                UUID.randomUUID(),
                status,
                result.gatewayReference(),
                result.authorizationCode(),
                result.cardBrand(),
                result.last4(),
                result.message()
        );
        requiresNewTx.executeWithoutResult(tx -> repository.saveAndFlush(entity));
    }

    private static CardAuthorization verifyMatchingRequest(
            CardAuthorization existing,
            Long storeId,
            Long terminalId,
            CardPaymentMethod method,
            BigDecimal amount
    ) {
        if (!existing.getStoreId().equals(storeId)
                || !existing.getTerminalId().equals(terminalId)
                || existing.getMethod() != method
                || existing.getAmount().compareTo(amount) != 0) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "La clave de idempotencia ya fue utilizada con otros parámetros."
            );
        }
        return existing;
    }
}
