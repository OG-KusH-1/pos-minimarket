package cl.minimarket.pos.shared.persistence;

import cl.minimarket.pos.shared.idempotency.IdempotencyService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Operaciones de purga de datos efímeros. Cada método recibe el instante de referencia ({@code now})
 * para que las pruebas no dependan del reloj; {@link DataPurgeJobs} los invoca periódicamente.
 *
 * <p>Se opera con SQL directo y no con las entidades de cada módulo: {@code login_attempts},
 * {@code refresh_tokens} y {@code stored_files} pertenecen a security y files, y shared no puede
 * depender de ellos (A.4). Las sentencias solo usan columnas definidas en MODELO_DATOS B.1/B.2 y
 * los permisos de {@code pos_app}: {@code DELETE} sobre las tablas de seguridad y, sobre
 * {@code stored_files}, únicamente {@code UPDATE (deleted_at)} (la tabla es append-only).
 *
 * <p>Cada purga es una única sentencia en su propia transacción. Con los volúmenes de un minimarket
 * no hace falta trocearla en lotes; si el volumen creciera, el punto de extensión es agregar
 * {@code LIMIT} con un bucle aquí, sin cambiar los llamadores.
 */
@Service
public class DataPurgeService {

    private final JdbcClient jdbc;
    private final IdempotencyService idempotency;
    private final PurgeProperties properties;

    /**
     * @param jdbc cliente JDBC
     * @param idempotency dueño de la tabla {@code idempotency_keys}
     * @param properties retenciones
     */
    public DataPurgeService(JdbcClient jdbc, IdempotencyService idempotency, PurgeProperties properties) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.properties = properties;
    }

    /**
     * Elimina los intentos de inicio de sesión más antiguos que {@code loginAttemptsRetention}
     * (90 días por defecto).
     *
     * @param now instante de referencia
     * @return filas eliminadas
     */
    @Transactional
    public int purgeLoginAttempts(Instant now) {
        return jdbc.sql("DELETE FROM login_attempts WHERE attempted_at < :cutoff")
                .param("cutoff", utc(now.minus(properties.loginAttemptsRetention())))
                .update();
    }

    /**
     * Elimina los refresh tokens inservibles hace más de {@code refreshTokensRetention}.
     *
     * <p><b>Criterio:</b> un token está <i>muerto</i> desde el primero de estos instantes:
     * {@code expires_at} (vencimiento deslizante), {@code absolute_expires_at} (tope de la sesión) o
     * {@code revoked_at} (rotado, logout, reuso detectado, etc.). Se elimina cuando ese instante es
     * anterior a {@code now - retención}. Un token activo (no vencido ni revocado) nunca se toca, y
     * uno muerto hace poco se conserva para que la detección de reuso (RN-32) pueda reconocerlo.
     * Las referencias {@code replaced_by_id} a filas eliminadas pasan a {@code NULL} (FK
     * {@code ON DELETE SET NULL}).
     *
     * @param now instante de referencia
     * @return filas eliminadas
     */
    @Transactional
    public int purgeRefreshTokens(Instant now) {
        return jdbc.sql("""
                DELETE FROM refresh_tokens
                WHERE LEAST(expires_at, absolute_expires_at, COALESCE(revoked_at, 'infinity'::timestamptz)) < :cutoff
                """)
                .param("cutoff", utc(now.minus(properties.refreshTokensRetention())))
                .update();
    }

    /**
     * Elimina las claves de idempotencia cuyo TTL venció ({@code expires_at <= now}).
     *
     * @param now instante de referencia
     * @return filas eliminadas
     */
    @Transactional
    public int purgeIdempotencyKeys(Instant now) {
        return idempotency.purgeExpired(now);
    }

    /**
     * Marca como eliminados ({@code deleted_at = now}) los archivos huérfanos: categorías
     * {@code IMPORT} y {@code EXPORT} (temporales por naturaleza) creados hace más de
     * {@code storedFilesOrphanRetention} y aún sin marcar. <b>No borra filas ni binarios</b>: el
     * borrado físico del archivo lo hace el módulo files a partir de {@code deleted_at} (T18).
     * El resto de categorías (imágenes de producto, logo, evidencias) nunca se marca aquí.
     *
     * @param now instante de referencia
     * @return filas marcadas
     */
    @Transactional
    public int markOrphanStoredFiles(Instant now) {
        return jdbc.sql("""
                UPDATE stored_files SET deleted_at = :now
                WHERE category IN ('IMPORT', 'EXPORT') AND deleted_at IS NULL AND created_at < :cutoff
                """)
                .param("now", utc(now))
                .param("cutoff", utc(now.minus(properties.storedFilesOrphanRetention())))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
