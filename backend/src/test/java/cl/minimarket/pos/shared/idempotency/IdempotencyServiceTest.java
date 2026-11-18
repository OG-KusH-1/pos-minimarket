package cl.minimarket.pos.shared.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceTest {

    @Test
    void endpointIsMethodAndPath() {
        assertThat(IdempotencyService.endpointOf("post", "/api/v1/sales")).isEqualTo("POST /api/v1/sales");
    }

    @Test
    void longEndpointIsTruncatedWithHashSuffixToFitTheColumn() {
        String a = IdempotencyService.endpointOf("POST", "/api/v1/" + "a".repeat(150));
        String b = IdempotencyService.endpointOf("POST", "/api/v1/" + "a".repeat(149) + "b");

        assertThat(a).hasSize(IdempotencyService.ENDPOINT_MAX_LENGTH);
        assertThat(b).hasSize(IdempotencyService.ENDPOINT_MAX_LENGTH).isNotEqualTo(a);
    }

    @Test
    void propertiesRejectNonPositiveValues() {
        assertThatThrownBy(() -> new IdempotencyProperties(Duration.ZERO, Duration.ofMinutes(5), DataSize.ofBytes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyProperties(Duration.ofHours(1), Duration.ofSeconds(-1), DataSize.ofBytes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyProperties(Duration.ofHours(1), Duration.ofMinutes(5), DataSize.ofBytes(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inProgressTimeoutMustExceedTheTransactionTimeout() {
        IdempotencyService.requireInProgressTimeoutAbove(Duration.ofMinutes(5), Duration.ofSeconds(60));
        assertThatThrownBy(() -> IdempotencyService.requireInProgressTimeoutAbove(
                Duration.ofSeconds(60), Duration.ofSeconds(60))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IdempotencyService.requireInProgressTimeoutAbove(
                Duration.ofSeconds(30), Duration.ofSeconds(60))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void envelopeRoundTripsBodyAndHeaders() {
        String stored = ResponseEnvelope.wrap("{\"a\": 1}", java.util.Map.of("Location", "/x/1"));
        StoredResponse response = ResponseEnvelope.unwrap(201, stored);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("{\"a\":1}");
        assertThat(response.headers()).containsEntry("Location", "/x/1");
    }

    @Test
    void envelopeWithoutBodyAndLegacyRawBodyAreHandled() {
        StoredResponse empty = ResponseEnvelope.unwrap(204, ResponseEnvelope.wrap(null, java.util.Map.of()));
        assertThat(empty.body()).isNull();
        assertThat(empty.headers()).isEmpty();

        StoredResponse legacy = ResponseEnvelope.unwrap(200, "{\"x\":1}");
        assertThat(legacy.body()).isEqualTo("{\"x\":1}");
        assertThat(ResponseEnvelope.unwrap(204, null).body()).isNull();
    }
}
