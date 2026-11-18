package cl.minimarket.pos.shared.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a {@code idempotency_keys}. Todas las operaciones de escritura son sentencias nativas
 * atómicas; la orquestación y las transacciones (REQUIRES_NEW) están en {@link IdempotencyService}.
 *
 * <p>La unicidad {@code (user_id, endpoint, idem_key)} de la tabla es la que arbitra las requests
 * simultáneas: solo un {@code INSERT} gana.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    /**
     * Intenta registrar la clave como {@code IN_PROGRESS}.
     *
     * @return 1 si se insertó (esta request es la dueña de la clave), 0 si ya existía
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO idempotency_keys (idem_key, user_id, endpoint, request_hash, status, created_at, expires_at)
            VALUES (:key, :userId, :endpoint, :requestHash, 'IN_PROGRESS', :now, :expiresAt)
            ON CONFLICT (user_id, endpoint, idem_key) DO NOTHING
            """)
    int insertInProgress(@Param("key") UUID key, @Param("userId") Long userId, @Param("endpoint") String endpoint,
                         @Param("requestHash") String requestHash, @Param("now") Instant now,
                         @Param("expiresAt") Instant expiresAt);

    /**
     * Elimina el registro de esa clave si ya no sirve: vencido por TTL, o {@code IN_PROGRESS}
     * abandonado desde antes de {@code staleBefore}. Permite reutilizar la clave.
     *
     * @return filas eliminadas (0 o 1)
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM idempotency_keys
            WHERE user_id = :userId AND endpoint = :endpoint AND idem_key = :key
              AND (expires_at <= :now OR (status = 'IN_PROGRESS' AND created_at < :staleBefore))
            """)
    int deleteUnusable(@Param("key") UUID key, @Param("userId") Long userId, @Param("endpoint") String endpoint,
                       @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    /** Busca la clave de un usuario sobre un endpoint. */
    @Query("SELECT k FROM IdempotencyKey k WHERE k.userId = :userId AND k.endpoint = :endpoint AND k.key = :key")
    Optional<IdempotencyKey> find(@Param("key") UUID key, @Param("userId") Long userId,
                                  @Param("endpoint") String endpoint);

    /**
     * Pasa la clave a {@code COMPLETED} y guarda la respuesta.
     *
     * @param responseBody JSON como texto, o {@code null} si la respuesta no tuvo cuerpo
     * @return 1 si se actualizó; 0 si la clave ya no estaba {@code IN_PROGRESS} (p. ej. purgada)
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE idempotency_keys
            SET status = 'COMPLETED', response_status = :responseStatus,
                response_body = CAST(:responseBody AS jsonb)
            WHERE user_id = :userId AND endpoint = :endpoint AND idem_key = :key AND status = 'IN_PROGRESS'
            """)
    int markCompleted(@Param("key") UUID key, @Param("userId") Long userId, @Param("endpoint") String endpoint,
                      @Param("responseStatus") int responseStatus, @Param("responseBody") String responseBody);

    /**
     * Elimina la clave solo si sigue {@code IN_PROGRESS} (nunca una completada).
     *
     * @return 1 si se liberó; 0 si no existía o ya estaba completada
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM idempotency_keys
            WHERE user_id = :userId AND endpoint = :endpoint AND idem_key = :key AND status = 'IN_PROGRESS'
            """)
    int deleteInProgress(@Param("key") UUID key, @Param("userId") Long userId, @Param("endpoint") String endpoint);

    /**
     * Purga: elimina todas las claves cuyo TTL venció.
     *
     * @return filas eliminadas
     */
    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM idempotency_keys WHERE expires_at <= :now")
    int deleteExpired(@Param("now") Instant now);
}
