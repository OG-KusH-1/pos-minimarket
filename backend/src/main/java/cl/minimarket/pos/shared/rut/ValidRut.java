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
 * Bean Validation: el texto anotado debe ser un RUT chileno válido (módulo 11, {@link Rut}).
 *
 * <p>Un valor nulo se considera válido: combinar con {@code @NotNull} / {@code @NotBlank} cuando
 * el campo sea obligatorio. Un texto vacío sí es inválido.
 */
@Documented
@Constraint(validatedBy = RutValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface ValidRut {

    /** Mensaje de error. */
    String message() default "El RUT no es válido";

    /** Grupos de validación. */
    Class<?>[] groups() default {};

    /** Carga útil. */
    Class<? extends Payload>[] payload() default {};
}
