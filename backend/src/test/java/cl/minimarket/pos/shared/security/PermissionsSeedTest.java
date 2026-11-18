package cl.minimarket.pos.shared.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica la coherencia entre {@link Permissions} y la semilla {@code V2026120100151__seed_base.sql}
 * (T15): catálogo de permisos, matriz por rol (D.2) y límites por rol (D.3).
 *
 * <p>Las tablas esperadas (conteos por rol y límites) están codificadas aquí a propósito, de forma
 * independiente de quien generó el SQL: si la semilla o el documento D cambian, esta prueba obliga
 * a revisar ambos. La aplicación real del script sobre PostgreSQL la cubre T16 (Testcontainers).
 */
class PermissionsSeedTest {

    private static final Pattern PERMISSION_ROW = Pattern.compile("^\\s*\\('([a-z:\\-]+)', '([a-z]+)', '", Pattern.MULTILINE);
    private static final Pattern PERMISSION_CODE = Pattern.compile("'([a-z]+:[a-z\\-]+:[a-z\\-]+)'");
    private static final Pattern LIMIT_ROW = Pattern.compile(
            "^\\s*\\('([A-Z]+)', '([A-Z_]+)', (NULL|\\d+)\\)", Pattern.MULTILINE);
    private static final Pattern ROLE_FILTER = Pattern.compile("WHERE r\\.code = '([A-Z]+)'");

    /** Roles base y orden de columnas de D.3. */
    private static final List<String> ROLES = List.of("ADMIN", "MANAGER", "SUPERVISOR", "CASHIER", "WAREHOUSE");

    /** Permisos por rol según D.2 (✔, ≤ y P cuentan como "tiene el permiso"). */
    private static final Map<String, Integer> EXPECTED_PERMISSIONS_PER_ROLE = Map.of(
            "ADMIN", 95, "MANAGER", 87, "SUPERVISOR", 48, "CASHIER", 17, "WAREHOUSE", 29);

    /** D.3: códigos y valores por rol en el orden ADMIN, MANAGER, SUPERVISOR, CASHIER, WAREHOUSE; null = ∞. */
    private static final Map<String, Long[]> EXPECTED_LIMITS = new LinkedHashMap<>();

    static {
        EXPECTED_LIMITS.put("DISCOUNT_ITEM_MAX_PCT", new Long[] {null, 30L, 15L, 5L, 0L});
        EXPECTED_LIMITS.put("DISCOUNT_TOTAL_MAX_PCT", new Long[] {null, 20L, 10L, 3L, 0L});
        EXPECTED_LIMITS.put("DISCOUNT_TOTAL_MAX_AMOUNT", new Long[] {null, 50_000L, 20_000L, 3_000L, 0L});
        EXPECTED_LIMITS.put("SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT", new Long[] {null, 100_000L, 20_000L, 0L, 10_000L});
        EXPECTED_LIMITS.put("SHRINKAGE_APPROVE_MAX_AMOUNT", new Long[] {null, null, 100_000L, 0L, 0L});
        EXPECTED_LIMITS.put("ADJUSTMENT_APPROVE_MAX_AMOUNT", new Long[] {null, null, 50_000L, 0L, 0L});
        EXPECTED_LIMITS.put("CASH_OUT_MAX_AMOUNT", new Long[] {null, 1_000_000L, 300_000L, 100_000L, 0L});
        EXPECTED_LIMITS.put("RETURN_MAX_AMOUNT", new Long[] {null, null, 50_000L, 0L, 0L});
        EXPECTED_LIMITS.put("CREDIT_LIMIT_MAX_ASSIGN", new Long[] {null, 300_000L, 50_000L, 0L, 0L});
        EXPECTED_LIMITS.put("CREDIT_ADJUSTMENT_MAX_AMOUNT", new Long[] {null, 50_000L, 0L, 0L, 0L});
    }

    @Test
    void permissionsAllHasNinetyFiveDistinctCodesAndIsImmutable() {
        assertThat(Permissions.ALL).hasSize(95);
        assertThat(Permissions.ALL).allSatisfy(code -> assertThat(code).matches("[a-z]+:[a-z\\-]+:[a-z\\-]+"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> Permissions.ALL.add("x:y:z"));
    }

    @Test
    void seededPermissionCodesAreExactlyPermissionsAll() {
        List<String> statements = SeedBaseSql.insertsInto("permissions");
        assertThat(statements).hasSize(1);

        List<String> seeded = new ArrayList<>();
        Matcher m = PERMISSION_ROW.matcher(statements.get(0));
        while (m.find()) {
            String code = m.group(1);
            assertThat(code).startsWith(m.group(2) + ":");   // module = primer segmento
            seeded.add(code);
        }

        assertThat(seeded).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seeded)).isEqualTo(Permissions.ALL);
    }

    @Test
    void rolePermissionsMatchTheMatrixCounts() {
        Map<String, Set<String>> byRole = new HashMap<>();
        for (String statement : SeedBaseSql.insertsInto("role_permissions")) {
            Matcher role = ROLE_FILTER.matcher(statement);
            assertThat(role.find()).as("filtro de rol en %s", statement).isTrue();
            Matcher code = PERMISSION_CODE.matcher(statement);
            Set<String> codes = new HashSet<>();
            while (code.find()) {
                codes.add(code.group(1));
            }
            assertThat(byRole.put(role.group(1), codes)).as("rol repetido %s", role.group(1)).isNull();
        }

        assertThat(byRole.keySet()).containsExactlyInAnyOrderElementsOf(ROLES);
        EXPECTED_PERMISSIONS_PER_ROLE.forEach((role, expected) ->
                assertThat(byRole.get(role)).as("permisos de %s", role).hasSize(expected));
        assertThat(byRole.get("ADMIN")).isEqualTo(Permissions.ALL);
        byRole.values().forEach(codes -> assertThat(Permissions.ALL).containsAll(codes));
    }

    @Test
    void roleLimitsHaveOneRowPerRoleAndCodeWithExpectedValues() {
        List<String> statements = SeedBaseSql.insertsInto("role_limits");
        assertThat(statements).hasSize(1);

        Map<String, Long> seeded = new HashMap<>();   // "ROL|CODIGO" -> valor (null = ∞)
        int rows = 0;
        Matcher m = LIMIT_ROW.matcher(statements.get(0));
        while (m.find()) {
            rows++;
            Long value = "NULL".equals(m.group(3)) ? null : Long.valueOf(m.group(3));
            assertThat(seeded.containsKey(m.group(1) + "|" + m.group(2)))
                    .as("fila duplicada %s/%s", m.group(1), m.group(2)).isFalse();
            seeded.put(m.group(1) + "|" + m.group(2), value);
        }

        int codes = EXPECTED_LIMITS.size();
        assertThat(codes).isEqualTo(10);
        assertThat(rows).isEqualTo(ROLES.size() * codes);   // 5 x 10: sin filas ausentes (fail-closed)
        for (int i = 0; i < ROLES.size(); i++) {
            for (Map.Entry<String, Long[]> limit : EXPECTED_LIMITS.entrySet()) {
                String key = ROLES.get(i) + "|" + limit.getKey();
                assertThat(seeded).as("fila %s", key).containsKey(key);
                assertThat(seeded.get(key)).as("valor %s", key).isEqualTo(limit.getValue()[i]);
            }
        }
    }
}
