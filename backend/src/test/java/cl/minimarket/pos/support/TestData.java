package cl.minimarket.pos.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/**
 * Helpers para insertar filas mínimas en las pruebas de integración de T11 (la migración 71 no trae
 * semillas). Cada llamada genera valores únicos, así las pruebas no chocan aunque compartan la BD.
 */
public final class TestData {

    private TestData() {
    }

    /** Inserta un usuario y devuelve su id. */
    public static long insertUser(JdbcClient jdbc) {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return jdbc.sql("INSERT INTO users (username, full_name, password_hash) VALUES (:u, 'Usuario de prueba', 'x') RETURNING id")
                .param("u", username)
                .query(Long.class).single();
    }

    /** Inserta una empresa (si no hay) y un local con el código dado; devuelve el id del local. */
    public static long insertStore(JdbcClient jdbc, String code) {
        Long companyId = jdbc.sql("SELECT id FROM company LIMIT 1").query(Long.class).optional().orElse(null);
        if (companyId == null) {
            companyId = jdbc.sql("""
                    INSERT INTO company (rut, legal_name, trade_name, business_activity, address, commune, city)
                    VALUES ('76123456-7', 'Empresa SpA', 'Minimarket', 'Venta', 'Calle 1', 'Santiago', 'Santiago')
                    ON CONFLICT DO NOTHING
                    RETURNING id
                    """).query(Long.class).optional()
                    .orElseGet(() -> jdbc.sql("SELECT id FROM company LIMIT 1").query(Long.class).single());
        }
        return jdbc.sql("""
                INSERT INTO stores (company_id, code, name, address, commune, city)
                VALUES (:company, :code, 'Local de prueba', 'Calle 1', 'Santiago', 'Santiago') RETURNING id
                """)
                .param("company", companyId).param("code", code)
                .query(Long.class).single();
    }

    /** Código de local único y corto (máx. 10 caracteres, columna {@code stores.code}). */
    public static String uniqueStoreCode() {
        return "L" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
    }
}
