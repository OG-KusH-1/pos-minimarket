package cl.minimarket.pos.replenishment.application.run;

import cl.minimarket.pos.purchasing.PurchaseOrderApi;
import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.replenishment.domain.ReplenishmentStatus;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Edición de una línea de sugerencia de reabastecimiento
 * ({@code PATCH /replenishment/lines/{id}}, {@code API.md} F.2/F.3).
 *
 * <p>Permite fijar las {@code final_purchase_units} (las unidades finales que irán a la orden),
 * cambiar el estado ({@code ACCEPTED} o {@code DISCARDED}) y reelegir el proveedor entre los
 * candidatos activos del producto. Reglas:
 * <ul>
 *   <li>una línea que ya quedó en una orden de compra ya no se edita (409
 *       {@code INVALID_STATE_TRANSITION});</li>
 *   <li>solo las líneas evaluadas ({@code SUGGESTED}/{@code ACCEPTED}/{@code DISCARDED}) se
 *       editan: una línea {@code NO_SUPPLIER}/{@code NO_ACTION}/{@code EXCLUDED} no tiene
 *       orden que originar;</li>
 *   <li>las unidades finales mayores que cero deben respetar el MOQ del proveedor
 *       ({@code min_order_qty} de la asociación elegida);</li>
 *   <li>el cambio de proveedor actualiza el snapshot de costo y mantiene la trazabilidad de la
 *       asociación elegida.</li>
 * </ul>
 */
@Service
public class ReplenishmentLineService {

    private final ReplenishmentRunRepository runRepository;
    private final ReplenishmentLineRepository lineRepository;
    private final ProductSupplierApi productSupplierApi;
    private final PurchaseOrderApi purchaseOrderApi;

    public ReplenishmentLineService(ReplenishmentRunRepository runRepository,
                                    ReplenishmentLineRepository lineRepository,
                                    ProductSupplierApi productSupplierApi,
                                    PurchaseOrderApi purchaseOrderApi) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.lineRepository = Objects.requireNonNull(lineRepository, "lineRepository");
        this.productSupplierApi = Objects.requireNonNull(productSupplierApi, "productSupplierApi");
        this.purchaseOrderApi = Objects.requireNonNull(purchaseOrderApi, "purchaseOrderApi");
    }

    /**
     * Aplica la edición parcial de una línea.
     *
     * @param storeId local activo (debe ser el de la corrida dueña de la línea)
     * @param lineId  identificador de la línea
     * @param command cambios pedidos (al menos uno)
     * @return la línea actualizada
     * @throws BusinessException 404 {@code NOT_FOUND} si la línea o su corrida no pertenecen al
     *         local; 409 {@code INVALID_STATE_TRANSITION} si ya está en una OC, la corrida no
     *         está COMPLETED o el estado actual no admite edición; 400 {@code VALIDATION_ERROR}
     *         si el comando está vacío, las unidades no respetan el MOQ o el proveedor no es
     *         candidato activo del producto
     */
    @Transactional
    public ReplenishmentLine update(Long storeId, long lineId, LineUpdateCommand command) {
        Objects.requireNonNull(command, "command");
        ReplenishmentLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Línea de reabastecimiento no encontrada."));
        ReplenishmentRun run = runRepository.findById(line.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Corrida de reabastecimiento no encontrada."));
        if (!run.getStoreId().equals(storeId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "Línea de reabastecimiento no encontrada.");
        }
        if (run.getStatus() != ReplenishmentRunStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden editar líneas de una corrida COMPLETED.");
        }
        if (purchaseOrderApi.replenishmentLineIdsInOrders(List.of(lineId)).contains(lineId)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "La línea ya está en una orden de compra y no se puede editar.");
        }
        if (line.getStatus() != ReplenishmentStatus.SUGGESTED
                && line.getStatus() != ReplenishmentStatus.ACCEPTED
                && line.getStatus() != ReplenishmentStatus.DISCARDED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden editar líneas sugeridas, aceptadas o descartadas.");
        }
        if (command.finalPurchaseUnits() == null && command.status() == null
                && command.productSupplierId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Debe indicar al menos un cambio (unidades finales, estado o proveedor).");
        }

        if (command.productSupplierId() != null) {
            switchSupplier(line, command.productSupplierId());
        }
        if (command.finalPurchaseUnits() != null) {
            applyFinalUnits(line, command.finalPurchaseUnits());
        }
        if (command.status() != null) {
            if (command.status() != ReplenishmentStatus.ACCEPTED
                    && command.status() != ReplenishmentStatus.DISCARDED) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "El estado de la línea solo puede cambiar a ACCEPTED o DISCARDED.");
            }
            line.setStatus(command.status());
        }
        return lineRepository.save(line);
    }

    /** Reelige el proveedor de la línea entre los candidatos activos del producto. */
    private void switchSupplier(ReplenishmentLine line, Long productSupplierId) {
        Optional<ProductSupplierApi.ProductSupplierCandidate> candidate =
                productSupplierApi.candidates(line.getProductId()).stream()
                        .filter(c -> c.id().equals(productSupplierId))
                        .findFirst();
        if (candidate.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "La asociación indicada no es un proveedor activo del producto.");
        }
        line.setSupplier(candidate.get().supplierId(), candidate.get().id(), candidate.get().unitCostNet());
    }

    /** Valida y fija las unidades finales (escala 3; mayor que cero debe respetar el MOQ). */
    private void applyFinalUnits(ReplenishmentLine line, BigDecimal finalPurchaseUnits) {
        BigDecimal units = Rounding.quantity(finalPurchaseUnits);
        if (units.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Las unidades finales no pueden ser negativas.");
        }
        if (units.signum() > 0 && line.getProductSupplierId() != null) {
            productSupplierApi.candidates(line.getProductId()).stream()
                    .filter(c -> c.id().equals(line.getProductSupplierId()))
                    .findFirst()
                    .ifPresent(candidate -> {
                        BigDecimal moq = candidate.minOrderQty();
                        if (units.compareTo(moq) < 0) {
                            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                    "Las unidades finales deben ser al menos el mínimo de pedido ("
                                            + moq.toPlainString() + ").");
                        }
                    });
        }
        line.setFinalPurchaseUnits(units);
    }

    /**
     * Comando de edición parcial de una línea ({@code API.md} F.3
     * {@code ReplenishmentLineUpdateRequest}); {@code null} = sin cambio.
     *
     * @param finalPurchaseUnits unidades finales de compra (≥ 0; si &gt; 0, ≥ MOQ)
     * @param status             nuevo estado: {@code ACCEPTED} o {@code DISCARDED}
     * @param productSupplierId  asociación producto-proveedor elegida (candidato activo)
     */
    public record LineUpdateCommand(
            BigDecimal finalPurchaseUnits,
            ReplenishmentStatus status,
            Long productSupplierId
    ) {
    }
}
