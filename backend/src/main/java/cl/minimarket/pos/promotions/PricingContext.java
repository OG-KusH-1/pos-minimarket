package cl.minimarket.pos.promotions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Contexto de entrada para la evaluación de promociones sobre un carrito de venta
 * ({@code PLAN.md} A.4, {@code MODELO_DATOS.md} B.10).
 *
 * @param storeId     identificador del local donde se evalúa la venta
 * @param evaluatedAt instante de evaluación (hora del negocio vía {@code BusinessClock})
 * @param customerId  identificador del cliente si se asoció uno (puede ser {@code null})
 * @param lines       líneas del carrito en orden, con cantidades y subtotales brutos ya calculados
 */
public record PricingContext(
        Long storeId,
        Instant evaluatedAt,
        Long customerId,
        List<Line> lines
) {

    /**
     * Valida la integridad básica del contexto y realiza copia defensiva de las líneas.
     */
    public PricingContext {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * Línea del carrito presentada al motor de promociones.
     *
     * @param lineNumber        número correlativo de línea (1-based)
     * @param productId         identificador del producto
     * @param categoryId        identificador de la categoría del producto
     * @param brandId           identificador de la marca (puede ser {@code null})
     * @param quantity          cantidad de la línea (escala 3)
     * @param unitPriceGross    precio unitario bruto CLP (escala 0)
     * @param lineSubtotalGross subtotal bruto de la línea CLP antes de descuentos (escala 0)
     */
    public record Line(
            int lineNumber,
            Long productId,
            Long categoryId,
            Long brandId,
            BigDecimal quantity,
            BigDecimal unitPriceGross,
            BigDecimal lineSubtotalGross
    ) {
        /** Valida los campos obligatorios de la línea de evaluación. */
        public Line {
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber debe ser >= 1");
            }
            Objects.requireNonNull(productId, "productId");
            Objects.requireNonNull(categoryId, "categoryId");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(unitPriceGross, "unitPriceGross");
            Objects.requireNonNull(lineSubtotalGross, "lineSubtotalGross");
        }
    }
}
