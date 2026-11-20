package cl.minimarket.pos.purchasing.order.application;

import cl.minimarket.pos.purchasing.PurchaseOrderApi;
import cl.minimarket.pos.purchasing.PurchaseOrderApi.DraftOrderCommand;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderService.LineCommand;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderService.SaveCommand;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrder;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Implementación de la API pública {@link PurchaseOrderApi} del módulo {@code purchasing}.
 *
 * <p>Traduce los comandos públicos de borrador a los comandos internos del servicio de
 * órdenes ({@link SaveCommand}/{@link LineCommand}) sin duplicar lógica de dominio: la
 * validación de proveedor, productos, cantidades, costos y totales, y la numeración por
 * local son siempre las del servicio interno, idénticas a las de una orden manual.
 */
@Service
public class PurchaseOrderApiImpl implements PurchaseOrderApi {

    private final PurchaseOrderService orderService;
    private final PurchaseOrderRepository orderRepository;

    public PurchaseOrderApiImpl(PurchaseOrderService orderService,
                                PurchaseOrderRepository orderRepository) {
        this.orderService = Objects.requireNonNull(orderService, "orderService");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cada comando produce una orden {@code DRAFT} distinta (un comando = un
     * proveedor) y toda la tanda corre en una única transacción: si algún comando falla,
     * no se persiste ninguna. Las validaciones de contenido (proveedor activo, productos
     * activos y no {@code PACK}, cantidades y costos, fecha esperada) las hace el servicio
     * interno con los mismos códigos de error que la creación manual; aquí solo se
     * validan la lista y los campos que el servicio no conoce ({@code storeId} y
     * {@code origin}, que en el camino web provienen del request y aquí deben venir
     * explícitos en el comando).
     */
    @Override
    @Transactional
    public List<Long> createDrafts(List<DraftOrderCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw validationError(
                    "Debe indicar al menos un borrador de orden de compra.",
                    "commands", "INVALID_SIZE", "La lista de comandos no puede estar vacía.");
        }

        List<Long> ids = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            DraftOrderCommand command = commands.get(i);
            String field = "commands[" + i + "]";
            if (command == null) {
                throw validationError("El comando de borrador no puede ser nulo.", field, "NOT_NULL", "Comando nulo.");
            }
            if (command.storeId() == null) {
                throw validationError(
                        "Cada borrador debe indicar el local para el que se crea la orden.",
                        field + ".storeId", "NOT_NULL", "El local es obligatorio.");
            }
            if (command.origin() == null) {
                throw validationError(
                        "Cada borrador debe indicar el origen de la orden.",
                        field + ".origin", "NOT_NULL", "El origen es obligatorio.");
            }
            ids.add(orderService.createDraft(
                    command.storeId(),
                    command.origin(),
                    command.replenishmentRunId(),
                    toSaveCommand(command)));
        }
        return ids;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lectura directa de la orden por id: el llamador acaba de crearla (borradores de
     * reabastecimiento) o conoce su id, y el resumen no expone nada sensible.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<OrderRef> findRef(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return orderRepository.findById(orderId)
                .map(order -> new OrderRef(order.getId(), order.getNumber(), order.getSupplierId(),
                        order.getNetTotal()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Consulta de la consulta JPQL {@code findUsedReplenishmentLineIds}: ids de líneas de
     * reabastecimiento presentes en cualquier línea de orden (el filtro {@code IN} descarta nulos).
     */
    @Override
    @Transactional(readOnly = true)
    public Set<Long> replenishmentLineIdsInOrders(Collection<Long> replenishmentLineIds) {
        if (replenishmentLineIds == null || replenishmentLineIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(orderRepository.findUsedReplenishmentLineIds(List.copyOf(replenishmentLineIds)));
    }

    /**
     * Mapea un comando público de borrador al comando interno de alta de órdenes. Las
     * líneas conservan el enlace opcional a la línea de sugerencia
     * ({@code replenishmentLineId}); la versión queda en {@code null} porque es alta, no
     * edición.
     */
    private static SaveCommand toSaveCommand(DraftOrderCommand command) {
        List<LineCommand> lines = command.lines().stream()
                .map(line -> new LineCommand(
                        line.productId(),
                        line.qtyPurchaseUnits(),
                        line.unitCostNet(),
                        line.replenishmentLineId()))
                .toList();
        return new SaveCommand(command.supplierId(), command.expectedDate(), command.notes(), lines, null);
    }

    private static BusinessException validationError(String detail,
                                                      String field,
                                                      String fieldCode,
                                                      String fieldMessage) {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                detail,
                Map.of(),
                List.of(new FieldErrorDetail(field, fieldCode, fieldMessage)));
    }
}
