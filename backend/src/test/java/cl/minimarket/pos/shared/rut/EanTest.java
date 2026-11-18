package cl.minimarket.pos.shared.rut;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EanTest {

    @ParameterizedTest
    @ValueSource(strings = {"4006381333931", "7801234567894", "5901234123457", "96385074", "73513537", "00000000"})
    void acceptsValidCodes(String code) {
        assertThat(Ean.isValid(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4006381333932", "96385075", "123", "12345678901234", "40063813339A1", "4006381333 31",
            "400638133393", "9638507", "٣٤٥٦٧٨٩٠١٢٣٤٥"})
    void rejectsInvalidCodes(String code) {
        assertThat(Ean.isValid(code)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(Ean.isValid(null)).isFalse();
    }

    @Test
    void computesCheckDigit() {
        assertThat(Ean.checkDigit("400638133393")).isEqualTo(1);
        assertThat(Ean.checkDigit("9638507")).isEqualTo(4);
    }

    @Test
    void checkDigitRejectsWrongLength() {
        assertThatIllegalArgumentException().isThrownBy(() -> Ean.checkDigit("123"));
        assertThatIllegalArgumentException().isThrownBy(() -> Ean.checkDigit(null));
    }

    @Test
    void beanValidationAnnotation() {
        record Holder(@ValidEan String code) { }
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new Holder("4006381333931"))).isEmpty();
            assertThat(validator.validate(new Holder(null))).isEmpty();
            assertThat(validator.validate(new Holder("4006381333932"))).hasSize(1);
        }
    }
}
