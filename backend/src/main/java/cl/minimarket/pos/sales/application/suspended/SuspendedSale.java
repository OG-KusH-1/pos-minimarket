package cl.minimarket.pos.sales.application.suspended;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Entidad JPA para carritos pausados temporalmente en una terminal POS ({@code suspended_sales}).
 *
 * <p>Al retomarse o eliminarse la fila se borra físicamente de la tabla; las filas vencidas se
 * depuran mediante {@link SuspendedSaleExpirationJob}.
 */
@Entity
@Table(name = "suspended_sales")
public class SuspendedSale extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "terminal_id", nullable = false)
    private Long terminalId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "label", length = 60)
    private String label;

    @Column(name = "customer_id")
    private Long customerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cart", nullable = false)
    private String cart;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Constructor requerido por JPA. */
    protected SuspendedSale() {
    }

    /**
     * Crea una nueva instancia de venta suspendida lista para persistir.
     *
     * @param storeId identificador del local
     * @param terminalId identificador de la terminal donde se suspende
     * @param userId identificador del cajero que suspende
     * @param label etiqueta descriptiva opcional (máximo 60 caracteres)
     * @param customerId cliente asociado opcional
     * @param cart JSONB serializado del carrito
     * @param expiresAt instante UTC de expiración según TTL configurado
     * @return nueva entidad {@link SuspendedSale}
     */
    public static SuspendedSale create(
            Long storeId,
            Long terminalId,
            Long userId,
            String label,
            Long customerId,
            String cart,
            Instant expiresAt
    ) {
        SuspendedSale sale = new SuspendedSale();
        sale.storeId = storeId;
        sale.terminalId = terminalId;
        sale.userId = userId;
        sale.label = label;
        sale.customerId = customerId;
        sale.cart = cart;
        sale.expiresAt = expiresAt;
        return sale;
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public Long getTerminalId() {
        return terminalId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getLabel() {
        return label;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getCart() {
        return cart;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
