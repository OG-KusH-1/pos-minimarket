package cl.minimarket.pos.catalog.product.domain;

import cl.minimarket.pos.catalog.BarcodeType;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.rut.Ean;

import java.util.regex.Pattern;

/**
 * Validación de formato y dígito verificador de códigos de barras (MODELO_DATOS B.3, RN-30).
 */
public final class BarcodeValidator {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[0-9A-Za-z]{3,20}$");
    private static final Pattern DIGITS_12 = Pattern.compile("^\\d{12}$");

    private BarcodeValidator() {
    }

    /**
     * Valida el código según su {@link BarcodeType}.
     *
     * @param code        código normalizado (sin espacios extremos)
     * @param barcodeType tipo de código de barras
     * @throws BusinessException con {@link ErrorCode#BARCODE_INVALID} si falla formato o dígito
     *                           verificador
     */
    public static void validate(String code, BarcodeType barcodeType) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new BusinessException(ErrorCode.BARCODE_INVALID,
                    "El código de barras debe tener entre 3 y 20 caracteres alfanuméricos.");
        }
        switch (barcodeType) {
            case EAN13 -> {
                if (code.length() != 13 || !Ean.isValid(code)) {
                    throw new BusinessException(ErrorCode.BARCODE_INVALID,
                            "Código EAN-13 inválido: longitud o dígito verificador incorrecto.");
                }
            }
            case EAN8 -> {
                if (code.length() != 8 || !Ean.isValid(code)) {
                    throw new BusinessException(ErrorCode.BARCODE_INVALID,
                            "Código EAN-8 inválido: longitud o dígito verificador incorrecto.");
                }
            }
            case UPC_A -> {
                // UPC-A de 12 dígitos equivale a EAN-13 anteponiendo '0'.
                if (!DIGITS_12.matcher(code).matches() || !Ean.isValid("0" + code)) {
                    throw new BusinessException(ErrorCode.BARCODE_INVALID,
                            "Código UPC-A inválido: longitud o dígito verificador incorrecto.");
                }
            }
            case INTERNAL, SCALE_PLU -> {
                // Ya validado con CODE_PATTERN (3..20 alfanumérico).
            }
        }
    }
}
