package cl.minimarket.pos.shared.rut;

/**
 * Validación del dígito verificador de códigos EAN-13 y EAN-8 (RN-30, estándar GS1).
 *
 * <p>Algoritmo: a los dígitos del cuerpo (todos menos el último), contados desde la
 * <b>derecha</b> empezando en 1, se les asigna peso 3 si la posición es impar y 1 si es par;
 * {@code check = (10 − (Σ dígito×peso mod 10)) mod 10}. Esto es válido tanto para 13 como para
 * 8 dígitos, porque el peso solo depende de la distancia al dígito verificador.
 *
 * <p>Ejemplos válidos: {@code 4006381333931} (EAN-13) y {@code 96385074} (EAN-8).
 *
 * <p>Esta clase solo valida estructura: no sabe si el código pertenece a un producto ni decodifica
 * códigos de balanza (eso es de catalog).
 */
public final class Ean {

    private static final int EAN_8 = 8;
    private static final int EAN_13 = 13;

    private Ean() {
    }

    /**
     * Indica si el texto es un EAN-13 o EAN-8 con dígito verificador correcto.
     *
     * @param code texto a validar (puede ser nulo)
     * @return {@code true} si son exactamente 8 o 13 dígitos ASCII y el dígito verificador coincide
     */
    public static boolean isValid(String code) {
        if (code == null || (code.length() != EAN_8 && code.length() != EAN_13)) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        String body = code.substring(0, code.length() - 1);
        return checkDigit(body) == code.charAt(code.length() - 1) - '0';
    }

    /**
     * Calcula el dígito verificador de un cuerpo EAN (sin el dígito final).
     *
     * @param body 7 o 12 dígitos
     * @return dígito verificador de 0 a 9
     * @throws IllegalArgumentException si el cuerpo no tiene 7 o 12 dígitos
     */
    public static int checkDigit(String body) {
        if (body == null || (body.length() != EAN_8 - 1 && body.length() != EAN_13 - 1)
                || !body.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("El cuerpo de un EAN debe tener 7 o 12 dígitos");
        }
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int positionFromRight = body.length() - i;
            sum += (body.charAt(i) - '0') * (positionFromRight % 2 == 1 ? 3 : 1);
        }
        return (10 - sum % 10) % 10;
    }
}
