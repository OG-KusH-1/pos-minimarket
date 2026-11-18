package cl.minimarket.pos.sales;

import java.util.Optional;

/**
 * Puerto público de consulta sobre ventas para otros módulos del sistema ({@code PLAN.md} A.4,
 * {@code API.md} F.2).
 *
 * <p>Permite que módulos como {@code billing} validen pertenencia de alcance "P" (cajero) sin
 * acceder a las tablas ni entidades internas de {@code sales}.
 */
public interface SaleLookupApi {

    /**
     * Obtiene el identificador del cajero que emitió una venta.
     *
     * @param saleId identificador de la venta
     * @return identificador del usuario cajero, o vacío si la venta no existe
     */
    Optional<Long> cashierOf(Long saleId);
}
