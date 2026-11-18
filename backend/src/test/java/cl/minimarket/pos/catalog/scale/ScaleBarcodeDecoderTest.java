package cl.minimarket.pos.catalog.scale.domain;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.rut.Ean;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de decodificación de códigos de balanza EAN-13 ({@code HU-CAT-05}).
 */
class ScaleBarcodeDecoderTest {

    @Test
    void decodesWeightBarcodeAndCalculatesQuantityInKg() {
        ScaleBarcodeConfig weightConfig = config("20", EmbeddedType.WEIGHT, (short) 2, (short) 5, (short) 7, (short) 5, (short) 3);
        // 20 + PLU 00123 + 01500 (1.500 kg) + dígito verificador
        String body = "200012301500";
        String barcode = body + Ean.checkDigit(body);

        Optional<ScaleBarcodeDecoder.DecodedScaleBarcode> decoded =
                ScaleBarcodeDecoder.decode(barcode, List.of(weightConfig));

        assertThat(decoded).isPresent();
        assertThat(decoded.get().plu()).isEqualTo("00123");
        assertThat(ScaleBarcodeDecoder.resolveQuantity(decoded.get(), new BigDecimal("2500")))
                .isEqualByComparingTo("1.500");
        assertThat(ScaleBarcodeDecoder.resolveEmbeddedPrice(decoded.get())).isNull();
    }

    @Test
    void decodesPriceBarcodeAndCalculatesQuantityToThreeDecimals() {
        ScaleBarcodeConfig priceConfig = config("21", EmbeddedType.PRICE, (short) 2, (short) 5, (short) 7, (short) 5, (short) 0);
        // 21 + PLU 00456 + 03500 ($3.500 CLP) + dígito verificador
        String body = "210045603500";
        String barcode = body + Ean.checkDigit(body);

        Optional<ScaleBarcodeDecoder.DecodedScaleBarcode> decoded =
                ScaleBarcodeDecoder.decode(barcode, List.of(priceConfig));

        assertThat(decoded).isPresent();
        assertThat(decoded.get().plu()).isEqualTo("00456");
        assertThat(ScaleBarcodeDecoder.resolveEmbeddedPrice(decoded.get()))
                .isEqualByComparingTo("3500");
        // 3500 / 2490 = 1.405622... -> 1.406 kg
        assertThat(ScaleBarcodeDecoder.resolveQuantity(decoded.get(), new BigDecimal("2490")))
                .isEqualByComparingTo("1.406");
    }

    @Test
    void invalidCheckDigitReturnsEmptySoCallerResolvesCodeNotFound() {
        ScaleBarcodeConfig weightConfig = config("20", EmbeddedType.WEIGHT, (short) 2, (short) 5, (short) 7, (short) 5, (short) 3);
        String body = "200012301500";
        int validDigit = Ean.checkDigit(body);
        int invalidDigit = (validDigit + 1) % 10;
        String invalidBarcode = body + invalidDigit;

        Optional<ScaleBarcodeDecoder.DecodedScaleBarcode> decoded =
                ScaleBarcodeDecoder.decode(invalidBarcode, List.of(weightConfig));

        assertThat(decoded).isEmpty();
    }

    @Test
    void inactiveConfigOrNonMatchingPrefixReturnsEmpty() {
        ScaleBarcodeConfig inactive = config("20", EmbeddedType.WEIGHT, (short) 2, (short) 5, (short) 7, (short) 5, (short) 3);
        inactive.setActive(false);

        String body = "200012301500";
        String barcode = body + Ean.checkDigit(body);

        assertThat(ScaleBarcodeDecoder.decode(barcode, List.of(inactive))).isEmpty();
        assertThat(ScaleBarcodeDecoder.decode("7801234567890", List.of(config("20", EmbeddedType.WEIGHT, (short) 2, (short) 5, (short) 7, (short) 5, (short) 3)))).isEmpty();
    }

    @Test
    void priceBarcodeWithZeroProductPriceThrowsPriceNotSet() {
        ScaleBarcodeConfig priceConfig = config("21", EmbeddedType.PRICE, (short) 2, (short) 5, (short) 7, (short) 5, (short) 0);
        String body = "210045603500";
        String barcode = body + Ean.checkDigit(body);

        ScaleBarcodeDecoder.DecodedScaleBarcode decoded =
                ScaleBarcodeDecoder.decode(barcode, List.of(priceConfig)).orElseThrow();

        assertThatThrownBy(() -> ScaleBarcodeDecoder.resolveQuantity(decoded, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PRICE_NOT_SET);
    }

    private static ScaleBarcodeConfig config(String prefix,
                                             EmbeddedType embeddedType,
                                             short pluStart,
                                             short pluLength,
                                             short valueStart,
                                             short valueLength,
                                             short valueDecimals) {
        ScaleBarcodeConfig cfg = new ScaleBarcodeConfig();
        cfg.setName("Balanza " + prefix);
        cfg.setPrefix(prefix);
        cfg.setEmbeddedType(embeddedType);
        cfg.setPluStart(pluStart);
        cfg.setPluLength(pluLength);
        cfg.setValueStart(valueStart);
        cfg.setValueLength(valueLength);
        cfg.setValueDecimals(valueDecimals);
        cfg.setActive(true);
        return cfg;
    }
}
