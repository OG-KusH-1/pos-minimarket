package cl.minimarket.pos.shared.rut;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RutTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "12.345.678-5", "12345678-5", "123456785", " 12.345.678 - 5 ",
            "11.111.111-1", "76.086.428-5", "1-9", "14.000.000-0",
            "6.703.130-K", "6.703.130-k", "6703130K", "10.000.013-K"})
    void acceptsValidRuts(String raw) {
        assertThat(Rut.isValid(raw)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "12.345.678-4", "12345678-K", "6.703.130-0", "11.111.111-2",
            "abc", "12.345.67X-5", "123456789012-3", "0-0", "00000000-0", "-5", "5", "12 345 678-5x"})
    void rejectsInvalidRuts(String raw) {
        assertThat(Rut.isValid(raw)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsBlankOrNull(String raw) {
        assertThat(Rut.parse(raw)).isEmpty();
    }

    @Test
    void normalizesAndFormats() {
        Rut rut = Rut.of("12.345.678-5");

        assertThat(rut.normalized()).isEqualTo("12345678-5");
        assertThat(rut.formatted()).isEqualTo("12.345.678-5");
        assertThat(rut).hasToString("12345678-5");
        assertThat(Rut.of("6703130k").formatted()).isEqualTo("6.703.130-K");
        assertThat(Rut.of("1-9").formatted()).isEqualTo("1-9");
        assertThat(Rut.of("76086428-5")).isEqualTo(Rut.of("76.086.428-5"));
    }

    @Test
    void ofThrowsBusinessExceptionWithRutInvalid() {
        assertThatThrownBy(() -> Rut.of("12.345.678-0"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RUT_INVALID));
    }

    @Test
    void constructorRejectsWrongCheckDigit() {
        assertThatThrownBy(() -> new Rut(12345678, '0')).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void beanValidationAnnotation() {
        record Holder(@ValidRut String rut) { }
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new Holder("12.345.678-5"))).isEmpty();
            assertThat(validator.validate(new Holder(null))).isEmpty();
            assertThat(validator.validate(new Holder("12.345.678-0"))).hasSize(1);
            assertThat(validator.validate(new Holder(""))).hasSize(1);
        }
    }
}
