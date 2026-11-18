package cl.minimarket.pos.shared.error;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que cada {@link ErrorCode} tenga el estado HTTP de la tabla "Estado HTTP por código"
 * de F.1 (docs/API.md). La tabla se copia aquí a propósito: si alguien cambia el enum o la
 * tabla sin actualizar ambos, la prueba falla.
 */
class ErrorCodeHttpStatusTest {

    /** Copia literal de la tabla de F.1: estado HTTP a códigos. */
    private static final Map<Integer, List<String>> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put(400, List.of("VALIDATION_ERROR"));
        EXPECTED.put(401, List.of("UNAUTHENTICATED", "INVALID_CREDENTIALS", "TOKEN_INVALID"));
        EXPECTED.put(403, List.of("ACCESS_DENIED", "STORE_ACCESS_DENIED"));
        EXPECTED.put(404, List.of("NOT_FOUND", "CODE_NOT_FOUND"));
        EXPECTED.put(409, List.of("INVALID_STATE_TRANSITION", "IDEMPOTENCY_CONFLICT", "IDEMPOTENCY_IN_PROGRESS",
                "DUPLICATED_VALUE", "ENTITY_IN_USE", "CONCURRENT_MODIFICATION"));
        EXPECTED.put(422, List.of("STOCK_INSUFFICIENT", "CASH_SESSION_NOT_OPEN", "CASH_SESSION_ALREADY_OPEN",
                "CASH_OUT_EXCEEDS_AVAILABLE", "DISCOUNT_LIMIT_EXCEEDED", "OVERRIDE_REQUIRED", "OVERRIDE_INVALID",
                "OVERRIDE_LIMIT_EXCEEDED", "SEGREGATION_OF_DUTIES", "CREDIT_LIMIT_EXCEEDED", "CREDIT_NOT_ENABLED",
                "PAYMENT_EXCEEDS_DEBT", "PAYMENT_DECLINED", "PAYMENT_MISMATCH", "ACCOUNT_LOCKED", "PIN_LOCKED",
                "PASSWORD_POLICY", "TERMINAL_INVALID", "RUT_INVALID", "BARCODE_INVALID", "PRODUCT_INACTIVE",
                "PRICE_NOT_SET", "AGE_CONFIRMATION_REQUIRED", "LOT_REQUIRED", "RECEIPT_EXCEEDS_ORDER",
                "VOID_NOT_ALLOWED", "RETURN_WINDOW_EXPIRED", "RETURN_QUANTITY_EXCEEDED", "SUSPENDED_LIMIT_REACHED",
                "FILE_INVALID", "IMPORT_INVALID"));
        EXPECTED.put(405, List.of("METHOD_NOT_ALLOWED"));
        EXPECTED.put(406, List.of("NOT_ACCEPTABLE"));
        EXPECTED.put(415, List.of("UNSUPPORTED_MEDIA_TYPE"));
        EXPECTED.put(429, List.of("RATE_LIMITED"));
        EXPECTED.put(500, List.of("INTERNAL_ERROR"));
    }

    @Test
    void everyErrorCodeHasTheStatusOfTheF1Table() {
        Map<String, Integer> expectedByCode = new LinkedHashMap<>();
        EXPECTED.forEach((status, codes) -> codes.forEach(code -> expectedByCode.put(code, status)));

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(expectedByCode).as("El código %s no está en la tabla F.1", code).containsKey(code.name());
            assertThat(code.status().value()).as("Estado HTTP de %s", code).isEqualTo(expectedByCode.get(code.name()));
        }
    }

    @Test
    void enumAndTableHaveExactlyTheSameCodes() {
        List<String> tableCodes = new ArrayList<>();
        EXPECTED.values().forEach(tableCodes::addAll);
        Set<String> enumCodes = new HashSet<>();
        Arrays.stream(ErrorCode.values()).forEach(c -> enumCodes.add(c.name()));

        assertThat(tableCodes).doesNotHaveDuplicates();
        assertThat(enumCodes).containsExactlyInAnyOrderElementsOf(tableCodes);
        assertThat(ErrorCode.values()).hasSize(50);
    }

    @Test
    void typeUriIsStableKebabCase() {
        assertThat(ErrorCode.CASH_SESSION_NOT_OPEN.type())
                .hasToString("https://pos-minimarket/errors/cash-session-not-open");
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.type().toString()).startsWith("https://pos-minimarket/errors/")
                    .doesNotContain("_").isEqualTo(code.type().toString().toLowerCase());
            assertThat(code.title()).isNotBlank();
        }
    }
}
