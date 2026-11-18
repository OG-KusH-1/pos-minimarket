package cl.minimarket.pos.purchasing.receipt.web;

import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService.DraftTemplate;
import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptService.SaveCommand;
import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptService.SaveLineCommand;
import cl.minimarket.pos.purchasing.receipt.domain.SupplierDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Solicitud HTTP para crear o actualizar una recepción de mercadería
 * ({@code API.md} F.3 {@code GoodsReceiptRequest}).
 */
@Schema(name = "GoodsReceiptRequest", description = "Datos para crear o actualizar una recepción de mercadería.")
public record GoodsReceiptRequest(
        @Schema(description = "Identificador de la orden de compra asociada (null si es sin OC).")
        Long purchaseOrderId,

        @Schema(description = "Identificador del proveedor (obligatorio si no hay orden de compra).")
        Long supplierId,

        @Schema(description = "Tipo de documento tributario del proveedor.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        SupplierDocumentType supplierDocumentType,

        @Schema(description = "Número del documento del proveedor (obligatorio si supplierDocumentType != NONE).")
        @Size(max = 30)
        String supplierDocumentNumber,

        @Schema(description = "Observaciones generales de la recepción.")
        @Size(max = 2000)
        String notes,

        @Schema(description = "Líneas de productos recepcionados (mínimo 1).", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty
        List<@Valid LineRequest> lines,

        @Schema(description = "Versión de concurrencia optimista (requerida al actualizar).")
        Long version
) {

    /** Línea individual de una solicitud de recepción. */
    @Schema(name = "GoodsReceiptLineRequest", description = "Línea de producto en una recepción de mercadería.")
    public record LineRequest(
            @Schema(description = "Identificador de la línea de orden de compra asociada (si aplica).")
            Long purchaseOrderLineId,

            @Schema(description = "Identificador del producto.", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull
            Long productId,

            @Schema(description = "Cantidad recibida bruta (>= 0).", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull
            @DecimalMin("0")
            BigDecimal qtyReceived,

            @Schema(description = "Cantidad rechazada (>= 0 y <= qtyReceived). No ingresa a stock ni costo.")
            @DecimalMin("0")
            BigDecimal qtyRejected,

            @Schema(description = "Motivo de diferencia (obligatorio si qtyReceived != pendiente).")
            @Size(max = 250)
            String differenceReason,

            @Schema(description = "Costo unitario neto por unidad base.", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull
            @DecimalMin("0")
            BigDecimal unitCostNet,

            @Schema(description = "Número de lote (obligatorio al confirmar si el producto es lotControlled).")
            @Size(max = 40)
            String lotNumber,

            @Schema(description = "Fecha de vencimiento del lote (obligatoria si lotControlled y shelfLifeDays).")
            LocalDate expiryDate
    ) {
        SaveLineCommand toCommand() {
            return new SaveLineCommand(
                    purchaseOrderLineId,
                    productId,
                    qtyReceived,
                    qtyRejected != null ? qtyRejected : BigDecimal.ZERO,
                    differenceReason,
                    unitCostNet,
                    lotNumber,
                    expiryDate);
        }
    }

    /** Convierte la solicitud HTTP en comando de aplicación. */
    public SaveCommand toCommand() {
        return new SaveCommand(
                purchaseOrderId,
                supplierId,
                supplierDocumentType,
                supplierDocumentNumber,
                notes,
                lines.stream().map(LineRequest::toCommand).toList(),
                version);
    }

    /** Construye un {@link GoodsReceiptRequest} prellenado desde una plantilla de orden de compra. */
    public static GoodsReceiptRequest fromDraft(DraftTemplate draft) {
        List<LineRequest> draftLines = draft.lines().stream()
                .map(line -> new LineRequest(
                        line.purchaseOrderLineId(),
                        line.productId(),
                        line.qtyReceived(),
                        line.qtyRejected(),
                        null,
                        line.unitCostNet(),
                        null,
                        null))
                .toList();
        return new GoodsReceiptRequest(
                draft.purchaseOrderId(),
                draft.supplierId(),
                draft.supplierDocumentType(),
                null,
                null,
                draftLines,
                null);
    }
}
