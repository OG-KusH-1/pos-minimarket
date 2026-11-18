package cl.minimarket.pos.support;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el resultado de migrar el esquema completo (6 migraciones versionadas).
 *
 * <p>Sobre la BD compartida solo hay aserciones estructurales (versiones, tablas, FK e índices),
 * que ninguna prueba de datos puede alterar. Los conteos de la semilla y el estado del admin se
 * validan en una BD aislada ({@code pos_seed_check}) migrada con Flyway programático, sin contexto
 * Spring, porque la compartida será mutada por otros IT. Si una migración futura agrega tablas o
 * semilla, los conteos de aquí deben actualizarse a propósito.
 */
class FlywayMigrationIT extends AbstractIntegrationIT {

    private static JdbcClient seedJdbc;

    /** Crea y migra la BD aislada de la semilla (Flyway como pos_owner, sin Spring). */
    @BeforeAll
    static void migrateIsolatedSeedDatabase() {
        String url = createIsolatedDatabase("pos_seed_check");
        Flyway.configure()
                .dataSource(url, OWNER_USER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .placeholders(Map.of("admin_password_hash", TEST_ADMIN_PASSWORD_HASH))
                .load()
                .migrate();
        seedJdbc = JdbcClient.create(new DriverManagerDataSource(url, OWNER_USER, OWNER_PASSWORD));
    }

    private static long seedCount(String sql) {
        return seedJdbc.sql(sql).query(Long.class).single();
    }

    private long count(String sql, Object... params) {
        Long n = ownerJdbc().sql(sql).params(params).query(Long.class).single();
        return n;
    }

    @Test
    void sixMigrationsAppliedSuccessfully() {
        long ok = count("SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success");
        long failed = count("SELECT count(*) FROM flyway_schema_history WHERE NOT success");
        assertThat(ok).isEqualTo(6);
        assertThat(failed).isZero();
    }

    @Test
    void domainHasSeventyTables() {
        long tables = count("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """);
        assertThat(tables).isEqualTo(70);
    }

    @Test
    void lateForeignKeysExist() {
        List<String> fks = ownerJdbc().sql("""
                SELECT conname FROM pg_constraint WHERE contype = 'f' AND conname IN (
                  'fk_purchase_orders_replenishment_run',
                  'fk_purchase_order_lines_replenishment_line',
                  'fk_shrinkages_sale_return_line',
                  'fk_goods_receipt_lines_lot')
                """).query(String.class).list();
        assertThat(fks).containsExactlyInAnyOrder(
                "fk_purchase_orders_replenishment_run",
                "fk_purchase_order_lines_replenishment_line",
                "fk_shrinkages_sale_return_line",
                "fk_goods_receipt_lines_lot");
    }

    @Test
    void indexesOfMigration143Exist() {
        List<String> expected = List.of(
                "ix_sale_payments_sale", "ix_sale_discounts_sale", "ix_sale_lot_allocations_sale_line",
                "ix_sale_returns_cash_session", "ix_sale_return_lines_return",
                "ix_sale_return_lines_sale_line", "ix_sale_return_refunds_return",
                "ix_cash_movements_cash_session", "ix_card_authorizations_cash_session",
                "ix_customer_account_movements_sale", "ix_tax_documents_sale_return",
                "ix_goods_receipt_lines_receipt", "ix_stock_transfer_lines_transfer",
                "ix_stock_adjustment_lines_adjustment", "ix_promotion_items_promotion",
                "ix_user_stores_store", "ix_replenishment_runs_store_asof");
        List<String> found = ownerJdbc()
                .sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname = ANY (?)")
                .param(expected.toArray(String[]::new))
                .query(String.class).list();
        assertThat(expected).hasSize(17);
        assertThat(found).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void seedCounts() {
        assertThat(seedCount("SELECT count(*) FROM permissions")).isEqualTo(95);
        assertThat(seedCount("SELECT count(*) FROM role_permissions")).isEqualTo(276);
        assertThat(seedCount("SELECT count(*) FROM role_limits")).isEqualTo(50);
        assertThat(seedCount("SELECT count(*) FROM settings")).isEqualTo(19);
        assertThat(seedCount("SELECT count(*) FROM taxes")).isEqualTo(5);
    }

    @Test
    void permissionsPerRole() {
        Map<String, Long> byRole = seedJdbc.sql("""
                        SELECT r.code AS code, count(*) AS n FROM role_permissions rp
                        JOIN roles r ON r.id = rp.role_id GROUP BY r.code
                        """)
                .query((rs, i) -> Map.entry(rs.getString("code"), rs.getLong("n")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(byRole).containsEntry("ADMIN", 95L).containsEntry("MANAGER", 87L)
                .containsEntry("SUPERVISOR", 48L).containsEntry("CASHIER", 17L)
                .containsEntry("WAREHOUSE", 29L);
    }

    @Test
    void adminMustChangePasswordAndUsesTestHash() {
        Map<String, Object> admin = seedJdbc
                .sql("SELECT must_change_password, password_hash FROM users WHERE lower(username) = 'admin'")
                .query().singleRow();
        assertThat(admin.get("must_change_password")).isEqualTo(true);
        assertThat(admin.get("password_hash")).isEqualTo(TEST_ADMIN_PASSWORD_HASH);
    }
}
