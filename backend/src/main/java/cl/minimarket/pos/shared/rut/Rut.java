package cl.minimarket.pos.shared.rut;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;

import java.util.Locale;
import java.util.Optional;

/**
 * RUT chileno: value object inmutable, normalizado y validado con módulo 11 (RN-30).
 *
 * <p>Se acepta cualquier formato de entrada habitual ({@code 12.345.678-5}, {@code 12345678-5},
 * {@code 123456785}, con espacios o con {@code k} minúscula) y se normaliza a:
 * <ul>
 *   <li>{@link #normalized()}: {@code 12345678-5} (sin puntos, con guion, DV en mayúscula). Es el
 *       formato que debe persistirse (cabe en {@code varchar(12)}) y comparar para unicidad.</li>
 *   <li>{@link #formatted()}: {@code 12.345.678-5}, para mostrar.</li>
 * </ul>
 *
 * <p>Algoritmo del dígito verificador: se recorren los dígitos del cuerpo de derecha a izquierda
 * multiplicándolos por la serie cíclica 2,3,4,5,6,7; {@code dv = 11 − (suma mod 11)}, donde 11
 * se escribe {@code 0} y 10 se escribe {@code K}.
 *
 * <p>El cuerpo tiene entre 1 y 8 dígitos y no puede ser cero.
 *
 * @param body cuerpo numérico (sin dígito verificador), mayor que cero
 * @param checkDigit dígito verificador: {@code '0'..'9'} o {@code 'K'}
 */
public record Rut(long body, char checkDigit) {

    private static final int MAX_BODY_DIGITS = 8;
    private static final int MODULUS = 11;

    /**
     * Valida el dígito verificador.
     *
     * @throws IllegalArgumentException si el cuerpo está fuera de rango o el DV no corresponde
     */
    public Rut {
        if (body <= 0 || String.valueOf(body).length() > MAX_BODY_DIGITS) {
            throw new IllegalArgumentException("El cuerpo del RUT debe tener entre 1 y 8 dígitos");
        }
        if (computeCheckDigit(body) != checkDigit) {
            throw new IllegalArgumentException("El dígito verificador del RUT no es válido");
        }
    }

    /**
     * Interpreta un RUT en cualquier formato, devolviendo vacío si es inválido.
     *
     * @param raw texto de entrada (puede ser nulo)
     * @return el RUT normalizado, o vacío si el texto es nulo, mal formado o con DV incorrecto
     */
    public static Optional<Rut> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String clean = raw.replaceAll("[.\\s-]", "").toUpperCase(Locale.ROOT);
        if (!clean.matches("\\d{1," + MAX_BODY_DIGITS + "}[0-9K]")) {
            return Optional.empty();
        }
        long body = Long.parseLong(clean.substring(0, clean.length() - 1));
        char dv = clean.charAt(clean.length() - 1);
        if (body <= 0 || computeCheckDigit(body) != dv) {
            return Optional.empty();
        }
        return Optional.of(new Rut(body, dv));
    }

    /**
     * Interpreta un RUT o falla con el error de negocio del catálogo.
     *
     * @param raw texto de entrada
     * @return el RUT normalizado
     * @throws BusinessException 422 {@code RUT_INVALID} si el RUT es inválido
     */
    public static Rut of(String raw) {
        return parse(raw).orElseThrow(() -> new BusinessException(ErrorCode.RUT_INVALID, "El RUT ingresado no es válido."));
    }

    /** Indica si el texto es un RUT válido. */
    public static boolean isValid(String raw) {
        return parse(raw).isPresent();
    }

    /** Formato de persistencia: {@code 12345678-5}. */
    public String normalized() {
        return body + "-" + checkDigit;
    }

    /** Formato de presentación con puntos y guion: {@code 12.345.678-5}. */
    public String formatted() {
        String digits = String.valueOf(body);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append('.');
            }
            grouped.append(digits.charAt(i));
        }
        return grouped + "-" + checkDigit;
    }

    /** Calcula el dígito verificador (módulo 11) de un cuerpo numérico. */
    static char computeCheckDigit(long body) {
        int sum = 0;
        int factor = 2;
        for (long rest = body; rest > 0; rest /= 10) {
            sum += (int) (rest % 10) * factor;
            factor = factor == 7 ? 2 : factor + 1;
        }
        int dv = MODULUS - (sum % MODULUS);
        if (dv == MODULUS) {
            return '0';
        }
        return dv == 10 ? 'K' : (char) ('0' + dv);
    }

    @Override
    public String toString() {
        return normalized();
    }
}
