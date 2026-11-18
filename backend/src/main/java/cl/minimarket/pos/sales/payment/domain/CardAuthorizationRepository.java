package cl.minimarket.pos.sales.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio JPA de autorizaciones de tarjeta ({@code card_authorizations}).
 */
public interface CardAuthorizationRepository extends JpaRepository<CardAuthorization, Long> {

    /**
     * Busca una autorización por su clave de idempotencia única.
     *
     * @param idempotencyKey UUID de idempotencia
     * @return autorización encontrada, si existe
     */
    Optional<CardAuthorization> findByIdempotencyKey(UUID idempotencyKey);

    /**
     * Busca una autorización por id restringida al local activo.
     *
     * @param id identificador de la autorización
     * @param storeId identificador del local
     * @return autorización encontrada, si pertenece al local
     */
    Optional<CardAuthorization> findByIdAndStoreId(Long id, Long storeId);
}
