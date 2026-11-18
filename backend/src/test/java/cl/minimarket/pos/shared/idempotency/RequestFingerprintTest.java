package cl.minimarket.pos.shared.idempotency;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestFingerprintTest {

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String hash(String body) {
        return RequestFingerprint.of("POST", "/api/v1/sales", null, 1L, json(body));
    }

    @Test
    void isA64CharLowercaseHexSha256() {
        assertThat(hash("{\"a\":1}")).matches("[0-9a-f]{64}");
    }

    @Test
    void sameRequestSameHash() {
        assertThat(hash("{\"a\":1}")).isEqualTo(hash("{\"a\":1}"));
    }

    @Test
    void jsonFieldOrderAndWhitespaceDoNotMatterRecursively() {
        assertThat(hash("{\"b\":{\"y\":2,\"x\":1},\"a\":[1,2]}"))
                .isEqualTo(hash(" { \"a\" : [1, 2], \"b\" : { \"x\":1, \"y\":2 } } "));
    }

    @Test
    void arrayOrderIsSignificant() {
        assertThat(hash("{\"lines\":[1,2]}")).isNotEqualTo(hash("{\"lines\":[2,1]}"));
    }

    @Test
    void differentBodyMethodPathQueryOrUserChangeTheHash() {
        String base = hash("{\"a\":1}");
        assertThat(hash("{\"a\":2}")).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("PUT", "/api/v1/sales", null, 1L, json("{\"a\":1}"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("POST", "/api/v1/other", null, 1L, json("{\"a\":1}"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("POST", "/api/v1/sales", "x=1", 1L, json("{\"a\":1}"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("POST", "/api/v1/sales", null, 2L, json("{\"a\":1}"))).isNotEqualTo(base);
    }

    @Test
    void methodIsCaseInsensitiveAndNullEqualsEmpty() {
        assertThat(RequestFingerprint.of("post", "/p", null, 1L, null))
                .isEqualTo(RequestFingerprint.of("POST", "/p", "", 1L, new byte[0]));
    }

    @Test
    void nonJsonBodyIsHashedAsRawBytes() {
        assertThat(hash("no es json")).isEqualTo(hash("no es json")).isNotEqualTo(hash("no es  json"));
    }

    @Test
    void requiredArgumentsAreChecked() {
        assertThatThrownBy(() -> RequestFingerprint.of(null, "/p", null, 1L, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RequestFingerprint.of("POST", null, null, 1L, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RequestFingerprint.of("POST", "/p", null, null, null)).isInstanceOf(NullPointerException.class);
    }
}
