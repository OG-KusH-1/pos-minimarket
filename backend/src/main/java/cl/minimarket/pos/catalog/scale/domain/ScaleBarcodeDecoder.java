package cl.minimarket.pos.catalog.scale.domain;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.rut.Ean;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Decodificador puro de códigos EAN-13 emitidos por balanza ({@code MODELO_DATOS} B.3,
 * {@code HU-CAT-05}).
 *
 * <p>Valida el dígito verificador EAN-13 antes de extraer el PLU y el valor embebido. Si el código
 * no tiene 13 dígitos, no coincide con ningún prefijo activo o falla el dígito verificador,
 * devuelve vacío para que el flujo de POS resuelva {@code CODE_NOT_FOUND}.
 */
public final class ScaleBarcodeDecoder {

    private static final int EAN_13_LENGTH = 13;

    private ScaleBarcodeDecoder() {
    }

    /**
     * Resultado de decodificar un código de balanza sin consultar todavía el catálogo de productos.
     *
     * @param config        configuración que coincidió por prefijo
     * @param plu           subcadena PLU extraída según {@code pluStart} y {@code pluLength}
     * @param embeddedValue valor numérico escalado por {@code 10^-valueDecimals}
     */
    public record DecodedScaleBarcode(
            ScaleBarcodeConfig config,
            String plu,
            BigDecimal embeddedValue
    ) {
    }

    /**
     * Intenta decodificar {@code code} contra la lista de configuraciones activas.
     *
     * <p>Si varios prefijos activos coincidieran (p. ej. {@code "20"} y {@code "201"}), se prioriza
     * el prefijo más largo para mayor especificidad.
     *
     * @param code          código escaneado (sin espacios extremos)
     * @param activeConfigs configuraciones activas de balanza
     * @return resultado decodificado, o vacío si no es un EAN-13 válido o no coincide ningún prefijo
     */
    public static Optional<DecodedScaleBarcode> decode(String code, List<ScaleBarcodeConfig> activeConfigs) {
        if (code == null || code.length() != EAN_13_LENGTH || activeConfigs == null || activeConfigs.isEmpty()) {
            return Optional.empty();
        }
        // Exige 13 dígitos ASCII y dígito verificador EAN-13 válido (HU-CAT-05).
        if (!Ean.isValid(code)) {
            return Optional.empty();
        }

        Optional<ScaleBarcodeConfig> matched = activeConfigs.stream()
                .filter(ScaleBarcodeConfig::isActive)
                .filter(c -> code.startsWith(c.getPrefix()))
                .max(Comparator.comparingInt(c -> c.getPrefix().length()));

        if (matched.isEmpty()) {
            return Optional.empty();
        }

        ScaleBarcodeConfig config = matched.get();
        int pluEnd = config.getPluStart() + config.getPluLength();
        int valueEnd = config.getValueStart() + config.getValueLength();
        if (pluEnd > EAN_13_LENGTH || valueEnd > EAN_13_LENGTH) {
            return Optional.empty();
        }

        String plu = code.substring(config.getPluStart(), pluEnd);
        String rawValueStr = code.substring(config.getValueStart(), valueEnd);
        BigDecimal embeddedValue = new BigDecimal(rawValueStr).movePointLeft(config.getValueDecimals());

        return Optional.of(new DecodedScaleBarcode(config, plu, embeddedValue));
    }

    /**
     * Calcula la cantidad vendible (en kg o unidades según corresponda) a partir del valor embebido
     * y del precio bruto por kg/unidad del producto.
     *
     * <ul>
     *   <li>Si {@code embeddedType == WEIGHT}: devuelve {@code embeddedValue} redondeado a escala 3.</li>
     *   <li>Si {@code embeddedType == PRICE}: calcula {@code embeddedPrice / priceGross} redondeado
     *       a 3 decimales ({@code HALF_UP}); si {@code priceGross <= 0}, lanza
     *       {@link ErrorCode#PRICE_NOT_SET}.</li>
     * </ul>
     *
     * @param decoded    resultado decodificado del código de balanza
     * @param priceGross precio bruto vigente del producto
     * @return cantidad calculada con escala 3
     */
    public static BigDecimal resolveQuantity(DecodedScaleBarcode decoded, BigDecimal priceGross) {
        if (decoded.config().getEmbeddedType() == EmbeddedType.WEIGHT) {
            return Rounding.quantity(decoded.embeddedValue());
        }
        if (priceGross == null || priceGross.signum() <= 0) {
            throw new BusinessException(ErrorCode.PRICE_NOT_SET,
                    "El producto no tiene un precio de venta válido para calcular cantidad desde balanza.");
        }
        BigDecimal embeddedPrice = Rounding.clp(decoded.embeddedValue());
        return embeddedPrice.divide(priceGross, Rounding.QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Devuelve el precio embebido redondeado a CLP (escala 0) cuando {@code embeddedType == PRICE},
     * o {@code null} cuando {@code embeddedType == WEIGHT}.
     *
     * @param decoded resultado decodificado del código de balanza
     * @return monto CLP embebido o {@code null}
     */
    public static BigDecimal resolveEmbeddedPrice(DecodedScaleBarcode decoded) {
        if (decoded.config().getEmbeddedType() == EmbeddedType.PRICE) {
            return Rounding.clp(decoded.embeddedValue());
        }
        return null;
    }
}
