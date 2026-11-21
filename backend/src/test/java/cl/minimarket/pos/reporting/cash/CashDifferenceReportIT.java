package cl.minimarket.pos.reporting.cash;

import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del reporte de diferencias de caja ({@code T85}, {@code HU-REP-09}).
 */
class CashDifferenceReportIT extends AbstractIntegrationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate FROM = LocalDate.of(2031, 6, 1);
    private static final LocalDate TO = LocalDate.of(2031, 6, 10);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private String sfx;
    private long storeA;
    private long storeB;
    private long cashier1;
    private long cashier2;
    private long supervisorId;
    private long restrictedUserId;
    private long sessDiffApproved;
    private long sessExact;
    private long sessDiffUnapproved;
    private long sessOpen;
    private int seq;

    @BeforeEach
    void setUp() {
        sfx = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeA = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        storeB = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        cashier1 = insertNamedUser("caj1_" + sfx, "Cajero Uno " + sfx);
        cashier2 = insertNamedUser("caj2_" + sfx, "Cajero Dos " + sfx);
        supervisorId = insertNamedUser("sup_" + sfx, "Supervisor Caja " + sfx);
        restrictedUserId = TestData.insertUser(jdbc);
        jdbc.sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:u, :s, true)")
                .param("u", restrictedUserId)
                .param("s", storeA)
                .update();

        long term1 = jdbc.sql("""
                        INSERT INTO terminals (store_id, code, name, active)
                        VALUES (:s, :c, 'Caja Uno', true) RETURNING id
                        """)
                .param("s", storeA).param("c", "C1-" + sfx).query(Long.class).single();
        long term2 = jdbc.sql("""
                        INSERT INTO terminals (store_id, code, name, active)
                        VALUES (:s, :c, 'Caja Dos', true) RETURNING id
                        """)
                .param("s", storeA).param("c", "C2-" + sfx).query(Long.class).single();

        sessDiffApproved = insertClosedSession(storeA, term1, cashier1, LocalDate.of(2031, 6, 1),
                100000, 98000, -2000, supervisorId);
        sessExact = insertClosedSession(storeA, term1, cashier1, LocalDate.of(2031, 6, 2),
                50000, 50000, 0, null);
        sessDiffUnapproved = insertClosedSession(storeA, term1, cashier2, LocalDate.of(2031, 6, 3),
                80000, 81500, 1500, null);
        sessOpen = insertOpenSession(storeA, term2, cashier2, LocalDate.of(2031, 6, 4));
    }

    @Test
    void filtersClosedSessionsByWithDifferenceAndCashier() throws Exception {
        // 1. Todos los turnos cerrados del rango (el OPEN no aparece)
        MvcResult allRes = mvc.perform(get("/api/v1/reports/cash-differences")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_CASH_READ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode allRoot = JSON.readTree(allRes.getResponse().getContentAsString());
        Map<Long, JsonNode> bySession = rowsBySessionId(allRoot.get("rows"));
        assertThat(bySession).containsOnlyKeys(sessDiffApproved, sessExact, sessDiffUnapproved);
        assertThat(bySession).doesNotContainKey(sessOpen);

        assertThat(bySession.get(sessDiffApproved).get("difference").decimalValue()).isEqualByComparingTo("-2000");
        assertThat(bySession.get(sessDiffApproved).get("approvedBy").asString())
                .isEqualTo("Supervisor Caja " + sfx);
        assertThat(bySession.get(sessDiffUnapproved).get("difference").decimalValue()).isEqualByComparingTo("1500");
        assertThat(bySession.get(sessDiffUnapproved).get("approvedBy").isNull()).isTrue();

        JsonNode allTotals = allRoot.get("totals");
        assertThat(allTotals.get("expected").decimalValue()).isEqualByComparingTo("230000");
        assertThat(allTotals.get("counted").decimalValue()).isEqualByComparingTo("229500");
        assertThat(allTotals.get("difference").decimalValue()).isEqualByComparingTo("-500");

        // 2. withDifference=true -> excluye sessExact (diferencia 0)
        MvcResult diffOnlyRes = mvc.perform(get("/api/v1/reports/cash-differences")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("withDifference", "true")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_CASH_READ))))
                .andExpect(status().isOk())
                .andReturn();
        Map<Long, JsonNode> diffOnly = rowsBySessionId(
                JSON.readTree(diffOnlyRes.getResponse().getContentAsString()).get("rows"));
        assertThat(diffOnly).containsOnlyKeys(sessDiffApproved, sessDiffUnapproved);

        // 3. withDifference=false -> solo sessExact
        MvcResult exactOnlyRes = mvc.perform(get("/api/v1/reports/cash-differences")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("withDifference", "false")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_CASH_READ))))
                .andExpect(status().isOk())
                .andReturn();
        Map<Long, JsonNode> exactOnly = rowsBySessionId(
                JSON.readTree(exactOnlyRes.getResponse().getContentAsString()).get("rows"));
        assertThat(exactOnly).containsOnlyKeys(sessExact);

        // 4. Filtro cashierId + withDifference=true -> solo sessDiffApproved
        MvcResult cashierRes = mvc.perform(get("/api/v1/reports/cash-differences")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("cashierId", String.valueOf(cashier1))
                        .param("withDifference", "true")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_CASH_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].sessionId").value(sessDiffApproved))
                .andReturn();
        assertThat(JSON.readTree(cashierRes.getResponse().getContentAsString())
                .get("totals").get("difference").decimalValue()).isEqualByComparingTo("-2000");
    }

    @Test
    void rejectsUnauthorizedInvalidCashierIdAndUnassignedStore() throws Exception {
        mvc.perform(get("/api/v1/reports/cash-differences")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SALES_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(get("/api/v1/reports/cash-differences")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("cashierId", "0")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_CASH_READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/reports/cash-differences")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("storeIds", storeA + "," + storeB)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(restrictedUserId))
                                .authorities(new SimpleGrantedAuthority(Permissions.REPORT_CASH_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));
    }

    private static Map<Long, JsonNode> rowsBySessionId(JsonNode rows) {
        Map<Long, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            map.put(row.get("sessionId").asLong(), row);
        }
        return map;
    }

    private long insertNamedUser(String username, String fullName) {
        return jdbc.sql("""
                        INSERT INTO users (username, full_name, password_hash)
                        VALUES (:u, :fn, 'x') RETURNING id
                        """)
                .param("u", username).param("fn", fullName)
                .query(Long.class).single();
    }

    private long insertClosedSession(long storeId, long terminalId, long cashierId, LocalDate date,
                                     long expected, long counted, long diff, Long approvedBy) {
        OffsetDateTime closedAt = OffsetDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                21, 0, 0, 0, ZoneOffset.UTC);
        return jdbc.sql("""
                        INSERT INTO cash_sessions (
                            number, store_id, terminal_id, cashier_user_id, status, business_date,
                            opened_at, opening_amount, closed_at, closed_by,
                            expected_total, counted_total, difference_total,
                            difference_approved_by, difference_approved_at
                        )
                        VALUES (
                            :num, :s, :t, :c, 'CLOSED', :d,
                            :opened, 50000, :closed, :c,
                            :exp, :cnt, :diff,
                            :appBy, :appAt
                        )
                        RETURNING id
                        """)
                .param("num", "C85-" + sfx + "-" + (++seq))
                .param("s", storeId).param("t", terminalId).param("c", cashierId).param("d", date)
                .param("opened", closedAt.minusHours(8)).param("closed", closedAt)
                .param("exp", expected).param("cnt", counted).param("diff", diff)
                .param("appBy", approvedBy, Types.BIGINT).param("appAt", approvedBy != null ? closedAt : null, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class).single();
    }

    private long insertOpenSession(long storeId, long terminalId, long cashierId, LocalDate date) {
        return jdbc.sql("""
                        INSERT INTO cash_sessions (
                            number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount
                        )
                        VALUES (:num, :s, :t, :c, 'OPEN', :d, 50000)
                        RETURNING id
                        """)
                .param("num", "C85O-" + sfx + "-" + (++seq))
                .param("s", storeId).param("t", terminalId).param("c", cashierId).param("d", date)
                .query(Long.class).single();
    }
}
