package cl.minimarket.pos.inventory.application.transfer;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Entidad raíz de una transferencia de stock entre locales ({@code stock_transfers}, {@code
 * MODELO_DATOS.md} B.5).
 *
 * <p>Modela el ciclo {@code DRAFT -> SENT -> RECEIVED} o {@code DRAFT -> CANCELLED}. Al despacharse
 * ({@link TransferStatus#SENT}) descuenta inventario en origen mediante {@code TRANSFER_OUT} y la
 * mercadería permanece en tránsito hasta ser recepcionada en destino ({@code TRANSFER_IN}).
 */
@Entity
@Table(name = "stock_transfers")
public class StockTransfer extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "number", nullable = false, length = 24, unique = true)
    private String number;

    @Column(name = "origin_store_id", nullable = false)
    private Long originStoreId;

    @Column(name = "destination_store_id", nullable = false)
    private Long destinationStoreId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransferStatus status = TransferStatus.DRAFT;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "sent_by")
    private Long sentBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "received_by")
    private Long receivedBy;

    @Column(name = "notes")
    private String notes;

    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<StockTransferLine> lines = new ArrayList<>();

    protected StockTransfer() {}

    /**
     * Crea una transferencia nueva en estado {@link TransferStatus#DRAFT}.
     *
     * @param number folio asignado al documento.
     * @param originStoreId local de origen que despacha.
     * @param destinationStoreId local de destino que recepcionará.
     * @param notes notas opcionales de la transferencia.
     * @return instancia en estado borrador lista para agregar líneas.
     */
    public static StockTransfer draft(
            String number, Long originStoreId, Long destinationStoreId, String notes) {
        StockTransfer transfer = new StockTransfer();
        transfer.number = Objects.requireNonNull(number, "number");
        transfer.originStoreId = Objects.requireNonNull(originStoreId, "originStoreId");
        transfer.destinationStoreId =
                Objects.requireNonNull(destinationStoreId, "destinationStoreId");
        transfer.status = TransferStatus.DRAFT;
        transfer.notes = notes;
        return transfer;
    }

    /**
     * Agrega una línea a la transferencia estableciendo la referencia bidireccional.
     *
     * @param line línea a agregar.
     */
    public void addLine(StockTransferLine line) {
        line.attachTo(this);
        this.lines.add(line);
    }

    /**
     * Reemplaza los datos editables de un borrador (destino, notas y colección completa de líneas).
     *
     * @param destinationStoreId nuevo local de destino.
     * @param notes notas actualizadas.
     * @param newLines nuevas líneas del borrador.
     */
    public void replaceDraftContent(
            Long destinationStoreId, String notes, List<StockTransferLine> newLines) {
        this.destinationStoreId =
                Objects.requireNonNull(destinationStoreId, "destinationStoreId");
        this.notes = notes;
        this.lines.clear();
        for (StockTransferLine line : newLines) {
            addLine(line);
        }
    }

    /**
     * Marca la transferencia como despachada desde el local de origen.
     *
     * @param userId identificador del usuario que ejecuta el despacho.
     * @param sentAt instante del despacho.
     */
    public void markSent(Long userId, Instant sentAt) {
        this.status = TransferStatus.SENT;
        this.sentBy = Objects.requireNonNull(userId, "userId");
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
    }

    /**
     * Marca la transferencia como recepcionada en el local de destino.
     *
     * @param userId identificador del usuario que recepciona.
     * @param receivedAt instante de la recepción.
     */
    public void markReceived(Long userId, Instant receivedAt) {
        this.status = TransferStatus.RECEIVED;
        this.receivedBy = Objects.requireNonNull(userId, "userId");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }

    /**
     * Cancela una transferencia en estado borrador anexando el motivo indicado a las notas.
     *
     * @param reason motivo de cancelación informado por el operador.
     */
    public void cancel(String reason) {
        this.status = TransferStatus.CANCELLED;
        if (reason != null && !reason.isBlank()) {
            String trimmedReason = reason.trim();
            String cancelNote = "Motivo cancelación: " + trimmedReason;
            this.notes =
                    (this.notes == null || this.notes.isBlank())
                            ? cancelNote
                            : this.notes + "\n" + cancelNote;
        }
    }

    public Long getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public Long getOriginStoreId() {
        return originStoreId;
    }

    public Long getDestinationStoreId() {
        return destinationStoreId;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Long getSentBy() {
        return sentBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Long getReceivedBy() {
        return receivedBy;
    }

    public String getNotes() {
        return notes;
    }

    public List<StockTransferLine> getLines() {
        return lines;
    }
}
