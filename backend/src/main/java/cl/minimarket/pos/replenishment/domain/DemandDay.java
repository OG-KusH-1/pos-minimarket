package cl.minimarket.pos.replenishment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Observación diaria de demanda utilizada por el algoritmo de reabastecimiento.
 *
 * <p>La cantidad está expresada en la unidad base del producto: unidades para los productos
 * enteros y kilogramos para los pesables. Los packs deben llegar ya consolidados en su producto
 * base, tal como lo entrega el agregado {@code daily_product_sales}.</p>
 *
 * @param saleDate fecha de negocio de la observación
 * @param quantity venta neta del día en unidad base
 * @param hadStockout si el día tuvo quiebre de stock y, por tanto, debe censurarse
 */
public record DemandDay(LocalDate saleDate, BigDecimal quantity, boolean hadStockout) {

    public DemandDay {
        Objects.requireNonNull(saleDate, "saleDate");
        Objects.requireNonNull(quantity, "quantity");
    }
}
