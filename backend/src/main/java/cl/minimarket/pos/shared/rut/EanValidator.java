package cl.minimarket.pos.shared.rut;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Implementación de {@link ValidEan}: delega en {@link Ean#isValid(String)}; nulo es válido. */
public class EanValidator implements ConstraintValidator<ValidEan, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || Ean.isValid(value.toString());
    }
}
