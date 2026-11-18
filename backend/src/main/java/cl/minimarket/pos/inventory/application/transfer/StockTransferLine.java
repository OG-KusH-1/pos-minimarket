package cl.minimarket.pos.inventory.application.transfer;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import cl.minimarket.pos.shared.money.Rounding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Línea de detalle de una transferencia entre locales ({@code stock_transfer_lines}, {@code
 * MODELO_DATOS.md} B.5).
 */
@Entity
@Table(name = "stock_transfer_lines")
public class StockTransferLine extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false)
    private StockTransfer transfer;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "origin_lot_id")
    private Long originLotId;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "qty_sent", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtySent;

    @Column(name = "qty_received", precision = 14, scale = 3)
    private BigDecimal qtyReceived;

    @Column(name = "difference_reason", length = 250)
    private String differenceReason;

    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;

    protected StockTransferLine() {}

    /**
     * Construye una línea nueva de transferencia con los datos iniciales del borrador.
     *
     * @param productId identificador del producto.
     * @param originLotId identificador del lote en origen ({@code null} si no especificado).
     * @param lotNumber número de lote denormalizado ({@code null} si no aplica).
     * @param expiryDate fecha de vencimiento del lote ({@code null} si no aplica).
     * @param qtySent cantidad planificada/enviada.
     * @param unitCost costo unitario snapshot.
     * @return nueva instancia lista para asociar a {@link StockTransfer}.
     */
    public static StockTransferLine create(
            Long productId,
            Long originLotId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal qtySent,
            BigDecimal unitCost) {
        StockTransferLine line = new StockTransferLine();
        line.productId = Objects.requireNonNull(productId, "productId");
        line.originLotId = originLotId;
        line.lotNumber = lotNumber;
        line.expiryDate = expiryDate;
        line.qtySent = Rounding.quantity(Objects.requireNonNull(qtySent, "qtySent"));
        line.unitCost = Rounding.unitCost(Objects.requireNonNull(unitCost, "unitCost"));
        return line;
    }

    void attachTo(StockTransfer transfer) {
        this.transfer = transfer;
    }

    /**
     * Actualiza el costo unitario snapshot y, cuando el despacho asignó lote por FEFO, completa la
     * referencia al lote de origen consumido (ajustando {@code qtySent} si la línea se desdobló).
     *
     * @param unitCost costo unitario aplicado por el movimiento {@code TRANSFER_OUT}.
     * @param originLotId lote de origen asignado, o {@code null} para mantener el existente.
     * @param lotNumber número de lote asignado, o {@code null} para mantener el existente.
     * @param expiryDate fecha de vencimiento del lote, o {@code null} para mantener la existente.
     * @param qtySent cantidad asignada al lote tras desdoble FEFO, o {@code null} para mantenerla.
     */
    public void applyDispatchSnapshot(
            BigDecimal unitCost,
            Long originLotId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal qtySent) {
        if (unitCost != null) {
            this.unitCost = Rounding.unitCost(unitCost);
        }
        if (originLotId != null) {
            this.originLotId = originLotId;
        }
        if (lotNumber != null) {
            this.lotNumber = lotNumber;
        }
        if (expiryDate != null) {
            this.expiryDate = expiryDate;
        }
        if (qtySent != null) {
            this.qtySent = Rounding.quantity(qtySent);
        }
    }

    /**
     * Registra la cantidad efectivamente recibida en destino y el motivo de diferencia si aplica.
     *
     * @param qtyReceived cantidad recepcionada ({@code 0 <= qtyReceived <= qtySent}).
     * @param differenceReason motivo de diferencia (requerido cuando {@code qtyReceived < qtySent}).
     */
    public void recordReceipt(BigDecimal qtyReceived, String differenceReason) {
        this.qtyReceived = Rounding.quantity(Objects.requireNonNull(qtyReceived, "qtyReceived"));
        this.differenceReason =
                (differenceReason != null && !differenceReason.isBlank())
                        ? differenceReason.trim()
                        : null;
    }

    /**
     * Calcula la diferencia positiva faltante entre lo enviado y lo recibido.
     *
     * @return {@code qtySent - qtyReceived}, o {@link BigDecimal#ZERO} si no hay faltante.
     */
    public BigDecimal missingQuantity() {
        if (qtyReceived == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = qtySent.subtract(qtyReceived);
        return diff.signum() > 0 ? Rounding.quantity(diff) : BigDecimal.ZERO;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getOriginLotId() {
        return originLotId;
    }

    public String getLotNumber() {
        return lotNumber;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public BigDecimal getQtySent() {
        return qtySent;
    }

    public BigDecimal getQtyReceived() {
        return qtyReceived;
    }

    public String getDifferenceReason() {
        return differenceReason;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }
}
