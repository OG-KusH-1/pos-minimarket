package cl.minimarket.pos.shared.persistence;

import cl.minimarket.pos.shared.idempotency.IdempotencyKey;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import cl.minimarket.testsupport.t11.AuditProbe;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Auditoría JPA ({@link AuditableEntity} + {@link CurrentUserAuditor} + reloj de negocio) con una
 * entidad de prueba mapeada a la tabla AUD real {@code roles}.
 */
@Import(AuditableEntityIT.ProbeEntities.class)
class AuditableEntityIT extends AbstractIntegrationIT {

    /** Registra la entidad de prueba además de las reales (que viven en {@code cl.minimarket.pos}). */
    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackages = {"cl.minimarket.pos", "cl.minimarket.testsupport.t11"})
    static class ProbeEntities {
    }

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    @PersistenceContext
    EntityManager entityManager;

    private TransactionTemplate tx;
    private long userId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        userId = TestData.insertUser(jdbc);
        otherUserId = TestData.insertUser(jdbc);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(long id) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(String.valueOf(id), "n/a", "ROLE_USER"));
    }

    private AuditProbe create(String name) {
        return tx.execute(status -> {
            AuditProbe probe = new AuditProbe("T11-" + UUID.randomUUID().toString().substring(0, 20), name);
            entityManager.persist(probe);
            return probe;
        });
    }

    @Test
    void fillsCreationFieldsWithTheAuthenticatedUser() {
        authenticateAs(userId);

        AuditProbe created = create("Rol A");

        assertThat(created.getCreatedBy()).isEqualTo(userId);
        assertThat(created.getUpdatedBy()).isEqualTo(userId);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isEqualTo(created.getCreatedAt());
        assertThat(created.getVersion()).isZero();
        assertThat(jdbc.sql("SELECT created_by FROM roles WHERE id = :id").param("id", created.getId())
                .query(Long.class).single()).isEqualTo(userId);
    }

    @Test
    void leavesUserNullWhenThereIsNoAuthenticatedUser() {
        AuditProbe created = create("Rol sistema");

        assertThat(created.getCreatedBy()).isNull();
        assertThat(created.getUpdatedBy()).isNull();
        assertThat(created.getCreatedAt()).isNotNull();
    }

    @Test
    void updateChangesUpdatedFieldsAndVersionButNotCreationFields() {
        authenticateAs(userId);
        AuditProbe created = create("Rol B");
        authenticateAs(otherUserId);

        AuditProbe updated = tx.execute(status -> {
            AuditProbe managed = entityManager.find(AuditProbe.class, created.getId());
            managed.setName("Rol B2");
            entityManager.flush();
            return managed;
        });

        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getCreatedBy()).isEqualTo(userId);
        assertThat(updated.getUpdatedBy()).isEqualTo(otherUserId);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(created.getCreatedAt().minus(1, ChronoUnit.MICROS));
        // PostgreSQL redondea a microsegundos; la copia en memoria tiene más precisión.
        assertThat(updated.getCreatedAt()).isCloseTo(created.getCreatedAt(), within(1, ChronoUnit.MICROS));
    }

    @Test
    void staleVersionFailsWithOptimisticLocking() {
        AuditProbe created = create("Rol C");
        tx.executeWithoutResult(status -> entityManager.find(AuditProbe.class, created.getId()).setName("primero"));

        // Una copia desactualizada (versión 0) no puede guardarse.
        created.setName("segundo");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> entityManager.merge(created)))
                .isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void idempotencyEntityMapsAgainstTheRealTable() {
        // El contexto arrancó con ddl-auto=validate: si el mapeo no coincidiera con la tabla, habría fallado.
        assertThat(entityManager.getMetamodel().entity(IdempotencyKey.class)).isNotNull();
    }
}
