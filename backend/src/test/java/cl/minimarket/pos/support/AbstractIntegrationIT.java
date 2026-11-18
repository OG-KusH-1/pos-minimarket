package cl.minimarket.pos.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de todas las pruebas de integración ({@code *IT}) del backend: contexto completo de Spring
 * (MockMvc, sin servidor real) contra un PostgreSQL 17 real en Testcontainers con el esquema
 * completo migrado por Flyway.
 *
 * <h2>Decisiones de diseño</h2>
 * <ul>
 *   <li><b>Contenedor singleton por JVM:</b> un campo estático iniciado una sola vez; todos los
 *       IT lo comparten y el arranque se paga una vez. No se usa {@code @Testcontainers} porque
 *       reiniciaría el contenedor por clase. Como el contexto de Spring se cachea por
 *       configuración, las subclases que no cambien anotaciones comparten también el contexto.</li>
 *   <li><b>Reutilización ({@code withReuse(true)}):</b> solo tiene efecto si el desarrollador
 *       habilita {@code testcontainers.reuse.enable=true} en su {@code ~/.testcontainers.properties};
 *       en CI no está habilitada y el contenedor se crea y destruye en cada ejecución. Con
 *       reutilización la BD conserva datos entre ejecuciones (Flyway solo aplica lo pendiente y el
 *       script de roles no se repite), por eso los IT deben ser <b>robustos a datos previos</b>:
 *       usar claves únicas por prueba y afirmar de forma relativa, o limpiar lo que inserten.</li>
 *   <li><b>Dos roles, como en producción:</b> Flyway migra como {@code pos_owner} y la aplicación
 *       corre como {@code pos_app}, de modo que los IT ejercen los permisos reales (p. ej.
 *       {@code audit_log} sin UPDATE/DELETE). Los roles los crea {@code it-init-roles.sql}.</li>
 *   <li><b>Esquema completo:</b> sin {@code target}; se aplican todas las migraciones y la semilla
 *       base, cuyo placeholder {@code admin_password_hash} (BCrypt costo 12) sale de
 *       {@link #TEST_ADMIN_PASSWORD_HASH}, su única fuente de verdad.</li>
 *   <li><b>Aserciones relativas:</b> la BD es compartida por todos los IT y futuras tareas la
 *       mutarán (usuarios, stock, secuencias...). Los IT no deben depender del estado de la
 *       semilla ni de tablas vacías: crean sus propios datos con claves únicas (ver
 *       {@link TestData}) y afirman sobre ellos o con diferencias. Los conteos absolutos de la
 *       semilla se validan en una BD aislada ({@code FlywayMigrationIT}).</li>
 * </ul>
 *
 * <h2>Cómo extenderla</h2>
 * <pre>{@code
 * class MiFeatureIT extends AbstractIntegrationIT {
 *     @Autowired MockMvc mockMvc;
 *
 *     @Test
 *     void ejemplo() {
 *         ownerJdbc().sql("DELETE FROM mi_tabla WHERE ...").update(); // limpieza fuera de los permisos de pos_app
 *     }
 * }
 * }</pre>
 * Reglas: no agregar {@code @MockitoBean}/{@code @Import} a la base (fragmentan la caché de
 * contextos; hazlo en la subclase solo si es necesario); usar {@link #ownerJdbc()} para preparar o
 * limpiar datos que {@code pos_app} no puede tocar, y {@link #appJdbc()} para verificar lo que la
 * aplicación sí puede o no puede hacer. Para otro servicio contenedorizado, agregar su propio
 * singleton y su {@code @DynamicPropertySource} en la subclase.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationIT {

    /** Rol dueño del esquema: el que usa Flyway. */
    protected static final String OWNER_USER = "pos_owner";
    /** Contraseña de prueba de {@link #OWNER_USER} (definida en {@code it-init-roles.sql}). */
    protected static final String OWNER_PASSWORD = "owner-test";
    /** Rol de la aplicación: DML limitado, como en producción. */
    protected static final String APP_USER = "pos_app";
    /** Contraseña de prueba de {@link #APP_USER} (definida en {@code it-init-roles.sql}). */
    protected static final String APP_PASSWORD = "app-test";

    /**
     * Hash BCrypt (costo 12) de la contraseña de prueba del administrador ({@code DevAdmin#2026}).
     * Única fuente de verdad: {@code application-test.yml} no lo define; se inyecta como
     * placeholder de Flyway en {@link #databaseProperties} y lo reutiliza {@code FlywayMigrationIT}.
     */
    protected static final String TEST_ADMIN_PASSWORD_HASH =
            "$2a$12$Fj3xg5frDtXewHsfrPyoN.NjY1S4PsPRELMXPjHaoQcF95y0fq2D.";

    /** Base de datos del contenedor (la compartida por los IT). */
    protected static final String DATABASE = "pos";
    private static final String SUPERUSER = "postgres";
    private static final String SUPERUSER_PASSWORD = "postgres-test";

    // Sin reutilización, Ryuk detiene el contenedor al salir la JVM. Con reutilización habilitada
    // el contenedor persiste a propósito entre ejecuciones y Ryuk no lo elimina.
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.12")
            .withDatabaseName(DATABASE)
            .withUsername(SUPERUSER)
            .withPassword(SUPERUSER_PASSWORD)
            .withInitScript("it-init-roles.sql")
            .withReuse(true);

    static {
        POSTGRES.start();
    }

    /** Conecta el datasource (pos_app) y Flyway (pos_owner) al contenedor; sin {@code target}. */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = POSTGRES.getJdbcUrl();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> OWNER_USER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.flyway.placeholders.admin_password_hash", () -> TEST_ADMIN_PASSWORD_HASH);
    }

    /**
     * Crea una base vacía propiedad de {@code pos_owner} (con las extensiones del init) para pruebas
     * que necesitan un esquema virgen y aislado de la BD compartida, y devuelve su URL JDBC.
     * Se ejecuta como superusuario del contenedor.
     *
     * @param name nombre de la base; identificador simple en minúsculas (se valida)
     * @return URL JDBC de la nueva base
     * @throws IllegalArgumentException si el nombre no es un identificador simple
     */
    protected static String createIsolatedDatabase(String name) {
        if (!name.matches("[a-z][a-z0-9_]{0,40}")) {
            throw new IllegalArgumentException("Nombre de base inválido: " + name);
        }
        JdbcClient admin = JdbcClient.create(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), SUPERUSER, SUPERUSER_PASSWORD));
        admin.sql("DROP DATABASE IF EXISTS " + name).update();
        admin.sql("CREATE DATABASE " + name + " OWNER " + OWNER_USER).update();
        String url = POSTGRES.getJdbcUrl().replaceFirst("/" + DATABASE + "(\\?|$)", "/" + name + "$1");
        JdbcClient inDb = JdbcClient.create(new DriverManagerDataSource(url, SUPERUSER, SUPERUSER_PASSWORD));
        inDb.sql("ALTER SCHEMA public OWNER TO " + OWNER_USER).update();
        inDb.sql("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public").update();
        inDb.sql("CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public").update();
        inDb.sql("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER_USER
                + " IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + APP_USER).update();
        inDb.sql("GRANT USAGE ON SCHEMA public TO " + APP_USER).update();
        return url;
    }

    /**
     * Cliente JDBC con el rol {@code pos_owner}: para preparar o limpiar datos cuando
     * {@code pos_app} no tiene permiso. Abre una conexión por sentencia (autocommit).
     *
     * @return cliente nuevo y liviano; no requiere cierre
     */
    protected static JdbcClient ownerJdbc() {
        return jdbcAs(OWNER_USER, OWNER_PASSWORD);
    }

    /**
     * Cliente JDBC con el rol {@code pos_app}, independiente del datasource de Spring: sirve para
     * comprobar permisos sin tocar transacciones del contexto.
     *
     * @return cliente nuevo y liviano; no requiere cierre
     */
    protected static JdbcClient appJdbc() {
        return jdbcAs(APP_USER, APP_PASSWORD);
    }

    private static JdbcClient jdbcAs(String user, String password) {
        return JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password));
    }
}
