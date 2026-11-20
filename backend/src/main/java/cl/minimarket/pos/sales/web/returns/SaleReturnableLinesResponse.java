package cl.minimarket.pos.sales.web.returns;

import cl.minimarket.pos.sales.application.returns.SaleReturnModels.ReturnableLineDetail;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.ReturnableLinesDetail;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta de consulta de líneas devolvibles y anulabilidad de una venta ({@code GET /sales/{id}/returnable}).
 */
@Schema(name = "SaleReturnableLinesResponse")
public record SaleReturnableLinesResponse(
        Long saleId,
        boolean voidAllowed,
        LocalDate returnDeadline,
        List<LineResponse> lines
) {

    /** Línea consultable para devolución. */
    @Schema(name = "SaleReturnableLineResponse")
    public record LineResponse(
            Long saleLineId,
            String name,
            BigDecimal quantity,
            BigDecimal returnedQuantity,
            BigDecimal returnableQuantity,
            BigDecimal unitRefundAmount
    ) {
        static LineResponse from(ReturnableLineDetail d) {
            return new LineResponse(
                    d.saleLineId(),
                    d.name(),
                    d.quantity(),
                    d.returnedQuantity(),
                    d.returnableQuantity(),
                    d.unitRefundAmount()
            );
        }
    }

    /** Convierte desde el modelo de aplicación {@link ReturnableLinesDetail}. */
    public static SaleReturnableLinesResponse from(ReturnableLinesDetail d) {
        return new SaleReturnableLinesResponse(
                d.saleId(),
                d.voidAllowed(),
                d.returnDeadline(),
                d.lines().stream().map(LineResponse::from).toList()
        );
    }
}
