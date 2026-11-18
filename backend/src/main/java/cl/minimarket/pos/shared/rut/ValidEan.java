package cl.minimarket.pos.shared.rut;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Bean Validation: el texto debe ser un EAN-13 o EAN-8 con dígito verificador válido ({@link Ean}).
 *
 * <p>Úsese solo donde el código <b>debe</b> ser EAN. Los códigos internos o de balanza no
 * siempre lo son: ese criterio lo decide el servicio de catalog. Nulo es válido: combinar con
 * {@code @NotNull} si es obligatorio.
 */
@Documented
@Constraint(validatedBy = EanValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface ValidEan {

    /** Mensaje de error. */
    String message() default "El código EAN no es válido";

    /** Grupos de validación. */
    Class<?>[] groups() default {};

    /** Carga útil. */
    Class<? extends Payload>[] payload() default {};
}
