package cl.minimarket.pos.sales.application.pricing;

import cl.minimarket.pos.security.OverrideAction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Comando y resultado de dominio para el servicio de cálculo de precios, impuestos, prorrateo,
 * redondeo y evaluación de overrides ({@code PricingService}).
 */
public final class PricingModels {

    private PricingModels() {
    }

    /**
     * Descuento manual solicitado sobre una línea o sobre el total del carrito.
     *
     * @param kind   tipo de descuento ({@link DiscountKind#PERCENT} o {@link DiscountKind#AMOUNT})
     * @param value  porcentaje (0..100] o monto CLP (> 0)
     * @param reason motivo opcional del descuento
     */
    public record DiscountSpec(
            DiscountKind kind,
            BigDecimal value,
            String reason
    ) {
        public DiscountSpec {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Línea de entrada para cotizar o crear una venta.
     *
     * @param productId      identificador del producto
     * @param quantity       cantidad solicitada (escala 3)
     * @param scannedCode    código escaneado si proviene de lectura (opcional)
     * @param embeddedPrice  precio bruto embebido en etiqueta de balanza (opcional)
     * @param manualDiscount descuento manual de línea (opcional)
     */
    public record LineInput(
            Long productId,
            BigDecimal quantity,
            String scannedCode,
            BigDecimal embeddedPrice,
            DiscountSpec manualDiscount
    ) {
        public LineInput {
            Objects.requireNonNull(productId, "productId");
            Objects.requireNonNull(quantity, "quantity");
        }
    }

    /**
     * Medio de pago propuesto en el carrito para calcular redondeo de efectivo y vuelto.
     *
     * @param method         medio de pago
     * @param amount         monto aplicado (obligatorio salvo en {@link PaymentMethod#CASH})
     * @param tenderedAmount monto entregado en efectivo (solo {@link PaymentMethod#CASH})
     */
    public record PaymentInput(
            PaymentMethod method,
            BigDecimal amount,
            BigDecimal tenderedAmount
    ) {
        public PaymentInput {
            Objects.requireNonNull(method, "method");
        }
    }

    /**
     * Entrada completa para {@link PricingService#quote(QuoteCommand)}.
     *
     * @param storeId           identificador del local activo
     * @param userId            identificador del usuario solicitante (para evaluar límites)
     * @param canApplyDiscount  si el usuario posee el permiso {@code sales:discount:apply}
     * @param lines             líneas del carrito (1..300)
     * @param totalDiscount     descuento manual al total (opcional)
     * @param customerId        identificador del cliente (opcional)
     * @param payments          pagos propuestos (opcional)
     */
    public record QuoteCommand(
            Long storeId,
            Long userId,
            boolean canApplyDiscount,
            List<LineInput> lines,
            DiscountSpec totalDiscount,
            Long customerId,
            List<PaymentInput> payments
    ) {
        public QuoteCommand {
            Objects.requireNonNull(storeId, "storeId");
            lines = lines == null ? List.of() : List.copyOf(lines);
            payments = payments == null ? List.of() : List.copyOf(payments);
        }
    }

    /**
     * Promoción aplicada a una línea.
     *
     * @param id   identificador de la promoción
     * @param name nombre de la promoción
     */
    public record AppliedPromotion(Long id, String name) {
    }

    /**
     * Línea cotizada con todos los importes desglosados en CLP.
     */
    public record QuotedLine(
            int lineNumber,
            Long productId,
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal unitPriceGross,
            BigDecimal lineSubtotalGross,
            BigDecimal promotionDiscount,
            BigDecimal manualDiscount,
            BigDecimal proratedDiscount,
            BigDecimal lineTotalGross,
            BigDecimal lineNet,
            BigDecimal lineTax,
            BigDecimal lineExempt,
            boolean restricted,
            AppliedPromotion appliedPromotion
    ) {
    }

    /**
     * Desglose agregado por impuesto en el documento.
     */
    public record TaxBreakdownItem(
            Long taxId,
            String taxCode,
            BigDecimal rate,
            BigDecimal baseAmount,
            BigDecimal taxAmount
    ) {
    }

    /**
     * Autorización de supervisor requerida para confirmar la venta.
     *
     * @param action  acción de override requerida
     * @param context contexto canónico que debe firmarse en {@code POST /auth/overrides}
     */
    public record RequiredOverride(
            OverrideAction action,
            Map<String, Object> context
    ) {
        public RequiredOverride {
            Objects.requireNonNull(action, "action");
            context = context == null ? Map.of() : Map.copyOf(context);
        }
    }

    /**
     * Resultado completo de la cotización del carrito.
     */
    public record QuoteResult(
            List<QuotedLine> lines,
            BigDecimal subtotalGross,
            BigDecimal discountTotal,
            BigDecimal totalGross,
            BigDecimal netTotal,
            BigDecimal taxTotal,
            BigDecimal exemptTotal,
            List<TaxBreakdownItem> taxBreakdown,
            BigDecimal roundingAdjustment,
            BigDecimal totalPayable,
            BigDecimal changeTotal,
            boolean requiresAgeConfirmation,
            List<RequiredOverride> requiredOverrides,
            String cartHash,
            BigDecimal totalDiscountAmount,
            BigDecimal totalDiscountPercent
    ) {
    }
}
