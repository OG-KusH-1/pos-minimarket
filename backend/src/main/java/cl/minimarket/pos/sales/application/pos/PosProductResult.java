package cl.minimarket.pos.sales.application.pos;

import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;

import java.math.BigDecimal;

/**
 * Proyección de producto para el POS que compone la ficha de catálogo con la existencia disponible
 * en el local activo ({@code API.md} F.3 {@code PosProductResult}).
 *
 * @param productId      identificador del producto
 * @param sku            código SKU
 * @param name           nombre del producto
 * @param saleType       tipo de venta ({@code UNIT}, {@code WEIGHT}, {@code PACK})
 * @param unitOfMeasure  unidad de medida ({@code UNIT}, {@code KG})
 * @param priceGross     precio bruto vigente en CLP (escala 0)
 * @param primaryBarcode código de barras principal (puede ser {@code null})
 * @param shortcutCode   código de atajo rápido en POS (puede ser {@code null})
 * @param restricted     {@code true} si requiere confirmación de edad
 * @param stockOnHand    existencia disponible en el local activo (escala 3; para packs, en unidades de pack)
 * @param packFactor     factor de conversión si es {@code PACK} (puede ser {@code null})
 */
public record PosProductResult(
        Long productId,
        String sku,
        String name,
        SaleType saleType,
        UnitOfMeasure unitOfMeasure,
        BigDecimal priceGross,
        String primaryBarcode,
        String shortcutCode,
        boolean restricted,
        BigDecimal stockOnHand,
        Integer packFactor
) {
}
