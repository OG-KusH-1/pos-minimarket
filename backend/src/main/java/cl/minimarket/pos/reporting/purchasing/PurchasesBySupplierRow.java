package cl.minimarket.pos.reporting.purchasing;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Fila del reporte de compras por proveedor ({@code API.md} F.3, {@code HU-REP-08}).
 *
 * @param supplierId      identificador del proveedor ({@code null} en la fila de totales)
 * @param name            razón social del proveedor
 * @param orders          cantidad de órdenes de compra enviadas o recibidas en el período
 * @param receipts        cantidad de recepciones confirmadas en el período
 * @param netTotal        monto neto total recibido en recepciones confirmadas (CLP, escala 0)
 * @param avgLeadTimeDays promedio real de días entre envío de OC y recepción confirmada (escala 2;
 *                        {@code null} si no hay recepciones asociadas a OC enviada)
 * @param fillRatePct     tasa de cumplimiento {@code recibido / pedido × 100} (escala 2;
 *                        {@code null} si no hubo cantidades pedidas en OC del período)
 */
@Schema(name = "PurchasesBySupplierRow", description = "Fila del reporte de compras por proveedor.")
public record PurchasesBySupplierRow(
        @Schema(description = "Identificador del proveedor (null en la fila de totales).")
        Long supplierId,

        @Schema(description = "Nombre o razón social del proveedor.")
        String name,

        @Schema(description = "Cantidad de órdenes de compra emitidas en el período.")
        long orders,

        @Schema(description = "Cantidad de recepciones confirmadas en el período.")
        long receipts,

        @Schema(description = "Monto neto total de recepciones confirmadas (CLP, escala 0).")
        BigDecimal netTotal,

        @Schema(description = "Promedio real de días desde envío de OC hasta recepción confirmada (escala 2).")
        BigDecimal avgLeadTimeDays,

        @Schema(description = "Tasa de cumplimiento recibido / pedido × 100 (escala 2).")
        BigDecimal fillRatePct
) {
}
