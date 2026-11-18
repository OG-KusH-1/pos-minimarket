package cl.minimarket.pos.inventory.application.count;

import cl.minimarket.pos.catalog.UnitOfMeasure;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Vistas inmutables internas de la capa de aplicación para tomas de inventario ({@code API.md} F.3).
 */
public final class InventoryCountViews {

    private InventoryCountViews() {
    }

    /** Referencia resumida a una categoría. */
    public record CategoryRefView(Long id, String name) {
    }

    /** Estado e identidad mínima de una categoría consultada en BD. */
    public record CategoryLookupView(Long id, String name, boolean active) {
    }

    /** Referencia resumida a un usuario. */
    public record UserRefView(Long id, String fullName) {
    }

    /** Progreso de líneas contadas en una toma. */
    public record CountProgressView(long totalLines, long countedLines) {
    }

    /** Resumen valorizado de diferencias detectadas en una toma. */
    public record DifferenceSummaryView(long lines, BigDecimal valueIn, BigDecimal valueOut) {
    }

    /** Datos mínimos de lote activo con existencia positiva al abrir una toma. */
    public record LotStockInfo(
            Long lotId,
            Long productId,
            String lotNumber,
            BigDecimal quantity,
            BigDecimal unitCost) {
    }

    /** Vista completa de cabecera de una toma de inventario. */
    public record CountDetailView(
            Long id,
            String number,
            Long storeId,
            CountType countType,
            CategoryRefView category,
            CountStatus status,
            boolean blind,
            UserRefView startedBy,
            Instant startedAt,
            Instant submittedAt,
            UserRefView approvedBy,
            Instant approvedAt,
            String rejectionReason,
            String notes,
            CountProgressView progress,
            DifferenceSummaryView differenceSummary,
            Long version) {
    }

    /** Vista enriquecida de una línea de toma de inventario (respetando modo ciego y costos). */
    public record CountLineView(
            Long id,
            Long productId,
            String sku,
            String name,
            UnitOfMeasure unitOfMeasure,
            Long lotId,
            String lotNumber,
            BigDecimal theoreticalQty,
            BigDecimal countedQty,
            BigDecimal difference,
            BigDecimal unitCost,
            Instant countedAt,
            Long stockMovementId) {
    }

    /** Filtros de listado paginado de tomas. */
    public record CountFilter(CountStatus status, CountType countType) {
    }

    /** Filtros de listado paginado de líneas de una toma. */
    public record CountLineFilter(Boolean counted, String q) {
    }

    /** Entrada individual para registrar o actualizar el conteo de una línea. */
    public record CountLineInput(Long lineId, BigDecimal countedQty) {
    }
}
