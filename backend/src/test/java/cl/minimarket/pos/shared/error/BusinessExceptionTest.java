package cl.minimarket.pos.shared.error;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class BusinessExceptionTest {

    @Test
    void keepsCodeDetailAndExtensions() {
        BusinessException ex = new BusinessException(ErrorCode.OVERRIDE_REQUIRED, "Falta autorización",
                Map.of("action", "VOID_SALE", "context", "x"));

        assertThat(ex.getCode()).isEqualTo(ErrorCode.OVERRIDE_REQUIRED);
        assertThat(ex).hasMessage("Falta autorización");
        assertThat(ex.getExtensions()).containsEntry("action", "VOID_SALE");
        assertThat(ex.getErrors()).isEmpty();
    }

    @Test
    void nullDetailFallsBackToTheCodeTitle() {
        assertThat(new BusinessException(ErrorCode.NOT_FOUND, null)).hasMessage(ErrorCode.NOT_FOUND.title());
    }

    @Test
    void keepsFieldErrors() {
        FieldErrorDetail detail = new FieldErrorDetail("sort", "X", "y");

        BusinessException ex = new BusinessException(ErrorCode.VALIDATION_ERROR, "d", Map.of(), List.of(detail));

        assertThat(ex.getErrors()).containsExactly(detail);
    }

    @Test
    void rejectsReservedExtensionNames() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "d", Map.of("code", "otro")));
    }

    @Test
    void rejectsNullCode() {
        assertThatNullPointerException().isThrownBy(() -> new BusinessException(null, "d"));
    }
}
