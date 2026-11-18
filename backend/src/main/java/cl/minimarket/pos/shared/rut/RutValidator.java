package cl.minimarket.pos.shared.rut;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Implementación de {@link ValidRut}: delega en {@link Rut#isValid(String)}; nulo es válido. */
public class RutValidator implements ConstraintValidator<ValidRut, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || Rut.isValid(value.toString());
    }
}
