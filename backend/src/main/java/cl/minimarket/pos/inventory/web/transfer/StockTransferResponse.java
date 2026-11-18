package cl.minimarket.pos.inventory.web.transfer;

import cl.minimarket.pos.inventory.application.transfer.TransferQueryService.StoreSummary;
import cl.minimarket.pos.inventory.application.transfer.TransferQueryService.TransferDetail;
import cl.minimarket.pos.inventory.application.transfer.TransferQueryService.TransferLineDetail;
import cl.minimarket.pos.inventory.application.transfer.TransferQueryService.UserSummary;
import cl.minimarket.pos.inventory.application.transfer.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Representación pública de una transferencia de stock entre locales ({@code API.md} F.3).
 *
 * @param id identificador único de la transferencia.
 * @param number folio correlativo asignado desde el local de origen.
 * @param origin local que despacha la mercadería.
 * @param destination local que recepciona la mercadería.
 * @param status estado actual ({@code DRAFT}, {@code SENT}, {@code RECEIVED}, {@code CANCELLED}).
 * @param sentAt instante de despacho ({@code null} si aún no se envía).
 * @param sentBy usuario que despachó ({@code null} si aún no se envía).
 * @param receivedAt instante de recepción ({@code null} si aún no se recibe).
 * @param receivedBy usuario que recepcionó ({@code null} si aún no se recibe).
 * @param notes notas u observaciones.
 * @param version versión optimista vigente de la entidad.
 * @param lines líneas de productos de la transferencia.
 */
@Schema(
        name = "StockTransferResponse",
        description = "Detalle completo de una transferencia de stock entre locales.")
public record StockTransferResponse(
        @Schema(description = "Identificador único de la transferencia.") Long id,
        @Schema(description = "Folio correlativo asignado desde el local de origen.") String number,
        @Schema(description = "Local de origen que despacha la mercadería.") StoreRef origin,
        @Schema(description = "Local de destino que recepciona la mercadería.") StoreRef destination,
        @Schema(description = "Estado actual de la transferencia.") TransferStatus status,
        @Schema(description = "Instante UTC de despacho (null si aún está en borrador o cancelada).")
                Instant sentAt,
        @Schema(description = "Usuario que realizó el despacho.") UserRef sentBy,
        @Schema(description = "Instante UTC de recepción en destino.") Instant receivedAt,
        @Schema(description = "Usuario que realizó la recepción en destino.") UserRef receivedBy,
        @Schema(description = "Observaciones de la transferencia.") String notes,
        @Schema(description = "Versión optimista vigente del documento.") Long version,
        @Schema(description = "Líneas de detalle de productos y lotes transferidos.")
                List<Line> lines) {

    /** Referencia compacta de local. */
    @Schema(
            name = "StockTransferStoreRef",
            description = "Referencia compacta a un local participante en la transferencia.")
    public record StoreRef(
            @Schema(description = "Identificador del local.") Long id,
            @Schema(description = "Código del local.") String code,
            @Schema(description = "Nombre del local.") String name) {
        static StoreRef from(StoreSummary summary) {
            return summary == null ? null : new StoreRef(summary.id(), summary.code(), summary.name());
        }
    }

    /** Referencia compacta de usuario. */
    @Schema(
            name = "StockTransferUserRef",
            description = "Referencia compacta a un usuario que despachó o recepcionó.")
    public record UserRef(
            @Schema(description = "Identificador del usuario.") Long id,
            @Schema(description = "Nombre de usuario.") String username,
            @Schema(description = "Nombre completo del usuario.") String fullName) {
        static UserRef from(UserSummary summary) {
            return summary == null
                    ? null
                    : new UserRef(summary.id(), summary.username(), summary.fullName());
        }
    }

    /** Línea de detalle de una transferencia. */
    @Schema(
            name = "StockTransferLineResponse",
            description = "Línea de detalle de una transferencia entre locales.")
    public record Line(
            @Schema(description = "Identificador único de la línea.") Long id,
            @Schema(description = "Identificador del producto.") Long productId,
            @Schema(description = "SKU del producto.") String sku,
            @Schema(description = "Nombre del producto.") String name,
            @Schema(description = "Número de lote asociado (si el producto maneja lotes).")
                    String lotNumber,
            @Schema(description = "Fecha de vencimiento del lote (si aplica).") LocalDate expiryDate,
            @Schema(description = "Cantidad enviada desde origen.") BigDecimal qtySent,
            @Schema(description = "Cantidad recibida en destino (null hasta recepcionar).")
                    BigDecimal qtyReceived,
            @Schema(description = "Motivo de diferencia cuando qtyReceived < qtySent.")
                    String differenceReason) {

        static Line from(TransferLineDetail detail) {
            return new Line(
                    detail.id(),
                    detail.productId(),
                    detail.sku(),
                    detail.name(),
                    detail.lotNumber(),
                    detail.expiryDate(),
                    detail.qtySent(),
                    detail.qtyReceived(),
                    detail.differenceReason());
        }
    }

    /**
     * Convierte una vista de aplicación {@link TransferDetail} en su DTO REST.
     *
     * @param detail detalle leído por el servicio de consulta.
     * @return respuesta REST serializable.
     */
    public static StockTransferResponse from(TransferDetail detail) {
        return new StockTransferResponse(
                detail.id(),
                detail.number(),
                StoreRef.from(detail.origin()),
                StoreRef.from(detail.destination()),
                detail.status(),
                detail.sentAt(),
                UserRef.from(detail.sentBy()),
                detail.receivedAt(),
                UserRef.from(detail.receivedBy()),
                detail.notes(),
                detail.version(),
                detail.lines().stream().map(Line::from).toList());
    }
}
