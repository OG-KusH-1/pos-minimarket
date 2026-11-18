package cl.minimarket.pos.shared.persistence;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurgePropertiesTest {

    @Test
    void acceptsNonNegativeRetentions() {
        PurgeProperties properties = new PurgeProperties(Duration.ofDays(90), Duration.ZERO, Duration.ofDays(30));
        assertThat(properties.loginAttemptsRetention()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void rejectsNegativeRetention() {
        assertThatThrownBy(() -> new PurgeProperties(Duration.ofDays(-1), Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
