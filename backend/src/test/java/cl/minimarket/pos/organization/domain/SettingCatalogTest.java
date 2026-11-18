package cl.minimarket.pos.organization.domain;

import cl.minimarket.pos.organization.SettingScope;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de {@link SettingCatalog}.
 */
class SettingCatalogTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void rejectsOutOfRangeNumberWithValidationError() {
        var def = SettingCatalog.require("pos.return.max_days");
        assertThatThrownBy(() -> def.validateValue(mapper.readTree("400")))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsStoreScopeForGlobalOnlyKey() {
        var def = SettingCatalog.require("security.password.min_length");
        assertThatThrownBy(() -> def.validateScope(SettingScope.STORE))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void validatesTicketLinesMaxCountAndLength() {
        var def = SettingCatalog.require("ticket.footer_lines");
        assertThatCode(() -> def.validateValue(mapper.readTree("[\"Gracias por su compra\"]")))
                .doesNotThrowAnyException();

        String tooLong = "\"" + "a".repeat(49) + "\"";
        assertThatThrownBy(() -> def.validateValue(mapper.readTree("[" + tooLong + "]")))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
