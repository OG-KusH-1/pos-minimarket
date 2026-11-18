package cl.minimarket.pos.shared.security;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Valida cada setting sembrado en {@code V2026120100151__seed_base.sql} contra la tabla de claves
 * de MODELO_DATOS B.1 (tipo y Mín/Máx), codificada aquí de forma independiente del SQL.
 *
 * <p>Además verifica que estén todas las claves esperadas y ninguna de más, y que la semilla use
 * solo el scope GLOBAL (los overrides por local los crea el usuario).
 */
class SettingsSeedTest {

    /** Fila de la tabla B.1: tipo, rango numérico inclusivo (null si no aplica). */
    private record Spec(String type, BigDecimal min, BigDecimal max) {
        static Spec number(long min, long max) {
            return new Spec("NUMBER", BigDecimal.valueOf(min), BigDecimal.valueOf(max));
        }

        static Spec bool() {
            return new Spec("BOOLEAN", null, null);
        }

        static Spec stringArray() {
            return new Spec("JSON", null, null);
        }
    }

    private static final Map<String, Spec> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("pos.cash_rounding.enabled", Spec.bool());
        EXPECTED.put("pos.suspended.max_per_terminal", Spec.number(1, 20));
        EXPECTED.put("pos.suspended.ttl_hours", Spec.number(1, 72));
        EXPECTED.put("pos.return.max_days", Spec.number(1, 365));
        EXPECTED.put("ticket.header_lines", Spec.stringArray());
        EXPECTED.put("ticket.footer_lines", Spec.stringArray());
        EXPECTED.put("ticket.show_tax_breakdown", Spec.bool());
        EXPECTED.put("ticket.paper_width_mm", Spec.number(58, 80));
        EXPECTED.put("alerts.expiry.days_ahead", Spec.number(1, 90));
        EXPECTED.put("cash.difference_tolerance", Spec.number(0, 50_000));
        EXPECTED.put("cash.default_opening_amount", Spec.number(0, 1_000_000));
        EXPECTED.put("security.password.min_length", Spec.number(8, 64));
        EXPECTED.put("security.lockout.max_attempts", Spec.number(3, 20));
        EXPECTED.put("security.lockout.minutes", Spec.number(1, 1440));
        EXPECTED.put("security.pin.max_attempts", Spec.number(3, 10));
        EXPECTED.put("security.pin.lockout_minutes", Spec.number(1, 1440));
        EXPECTED.put("security.refresh.ttl_hours", Spec.number(1, 72));
        EXPECTED.put("security.refresh.absolute_days", Spec.number(1, 30));
        EXPECTED.put("email.ticket.enabled", Spec.bool());
    }

    /** Defaults documentados en B.1 (los que no son arreglos de texto). */
    private static final Map<String, String> EXPECTED_DEFAULTS = Map.ofEntries(
            Map.entry("pos.cash_rounding.enabled", "true"),
            Map.entry("pos.suspended.max_per_terminal", "5"),
            Map.entry("pos.suspended.ttl_hours", "24"),
            Map.entry("pos.return.max_days", "30"),
            Map.entry("ticket.header_lines", "[]"),
            Map.entry("ticket.footer_lines", "[]"),
            Map.entry("ticket.show_tax_breakdown", "true"),
            Map.entry("ticket.paper_width_mm", "80"),
            Map.entry("alerts.expiry.days_ahead", "15"),
            Map.entry("cash.difference_tolerance", "500"),
            Map.entry("cash.default_opening_amount", "50000"),
            Map.entry("security.password.min_length", "10"),
            Map.entry("security.lockout.max_attempts", "5"),
            Map.entry("security.lockout.minutes", "15"),
            Map.entry("security.pin.max_attempts", "5"),
            Map.entry("security.pin.lockout_minutes", "15"),
            Map.entry("security.refresh.ttl_hours", "12"),
            Map.entry("security.refresh.absolute_days", "7"),
            Map.entry("email.ticket.enabled", "true"));

    private static final Pattern SETTING_ROW = Pattern.compile(
            "^\\s*\\('([A-Z]+)', '([a-z._]+)', '(.*?)'::jsonb, '([A-Z]+)', '", Pattern.MULTILINE);
    private static final Pattern STRING_ARRAY = Pattern.compile("^\\[(\\s*\"[^\"]*\"\\s*(,\\s*\"[^\"]*\"\\s*)*)?]$");
    private static final Pattern ARRAY_ITEM = Pattern.compile("\"([^\"]*)\"");

    private record Row(String scope, String key, String json, String type) {
    }

    private static List<Row> seededSettings() {
        List<String> statements = SeedBaseSql.insertsInto("settings");
        assertThat(statements).hasSize(1);
        List<Row> rows = new ArrayList<>();
        Matcher m = SETTING_ROW.matcher(statements.get(0));
        while (m.find()) {
            rows.add(new Row(m.group(1), m.group(2), m.group(3), m.group(4)));
        }
        return rows;
    }

    @Test
    void seedHasExactlyTheDocumentedKeysOnce() {
        List<String> keys = seededSettings().stream().map(Row::key).toList();
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(keys).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
        assertThat(keys).hasSize(19);
    }

    @Test
    void everySettingIsGlobalAndMatchesItsDocumentedDefault() {
        for (Row row : seededSettings()) {
            assertThat(row.scope()).as("scope de %s", row.key()).isEqualTo("GLOBAL");
            assertThat(row.json()).as("default de %s", row.key()).isEqualTo(EXPECTED_DEFAULTS.get(row.key()));
        }
    }

    @Test
    void everySettingValueRespectsItsTypeAndRange() {
        for (Row row : seededSettings()) {
            Spec spec = EXPECTED.get(row.key());
            assertThat(spec).as("clave desconocida %s", row.key()).isNotNull();
            assertThat(row.type()).as("value_type de %s", row.key()).isEqualTo(spec.type());
            switch (spec.type()) {
                case "BOOLEAN" -> assertThat(row.json()).as(row.key()).isIn("true", "false");
                case "NUMBER" -> assertNumberInRange(row, spec);
                case "JSON" -> assertStringArrayWithinLimits(row);
                default -> throw new AssertionError("Tipo no esperado: " + spec.type());
            }
        }
    }

    private static void assertNumberInRange(Row row, Spec spec) {
        assertThat(row.json()).as("%s debe ser un número", row.key()).matches("-?\\d+(\\.\\d+)?");
        BigDecimal value = new BigDecimal(row.json());
        assertThat(value).as("%s dentro de [%s, %s]", row.key(), spec.min(), spec.max())
                .isBetween(spec.min(), spec.max());
    }

    /** B.1: arreglo de string con máximo 6 líneas de 48 caracteres. */
    private static void assertStringArrayWithinLimits(Row row) {
        assertThat(row.json()).as("%s debe ser un arreglo de string", row.key()).matches(STRING_ARRAY);
        List<String> lines = new ArrayList<>();
        Matcher item = ARRAY_ITEM.matcher(row.json());
        while (item.find()) {
            lines.add(item.group(1));
        }
        assertThat(lines).as("líneas de %s", row.key()).hasSizeLessThanOrEqualTo(6);
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(48));
    }
}
