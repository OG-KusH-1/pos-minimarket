package cl.minimarket.pos.promotions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado de la evaluación de promociones para un carrito ({@code PLAN.md} A.4,
 * {@code MODELO_DATOS.md} B.10).
 *
 * @param linePromotions descuentos promocionales aplicados por línea de carrito
 */
public record PromotionResult(List<LinePromotion> linePromotions) {

    private static final PromotionResult EMPTY = new PromotionResult(List.of());

    /** Copia defensiva de la lista de promociones por línea. */
    public PromotionResult {
        linePromotions = linePromotions == null ? List.of() : List.copyOf(linePromotions);
    }

    /**
     * Devuelve un resultado vacío sin promociones aplicadas.
     *
     * @return instancia vacía compartida
     */
    public static PromotionResult empty() {
        return EMPTY;
    }

    /**
     * Busca la promoción aplicada a una línea específica por su número correlativo.
     *
     * @param lineNumber número correlativo de línea (1-based)
     * @return promoción aplicada a esa línea, o vacío si no tiene
     */
    public Optional<LinePromotion> forLine(int lineNumber) {
        return linePromotions.stream()
                .filter(p -> p.lineNumber() == lineNumber)
                .findFirst();
    }

    /**
     * Descuento promocional asignado a una línea específica del carrito.
     *
     * @param lineNumber     número correlativo de línea (1-based)
     * @param promotionId    identificador de la promoción aplicada
     * @param promotionName  nombre legible de la promoción
     * @param discountAmount monto del descuento en CLP (escala 0, {@code >= 0})
     */
    public record LinePromotion(
            int lineNumber,
            Long promotionId,
            String promotionName,
            BigDecimal discountAmount
    ) {
        /** Valida los campos obligatorios del resultado por línea. */
        public LinePromotion {
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber debe ser >= 1");
            }
            Objects.requireNonNull(promotionId, "promotionId");
            Objects.requireNonNull(promotionName, "promotionName");
            Objects.requireNonNull(discountAmount, "discountAmount");
            if (discountAmount.signum() < 0) {
                throw new IllegalArgumentException("discountAmount no puede ser negativo");
            }
        }
    }
}
