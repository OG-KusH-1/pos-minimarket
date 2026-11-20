package cl.minimarket.pos.purchasing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * API pública del módulo {@code purchasing} para crear borradores de órdenes de compra
 * ({@code PLAN.md} A.4 y C.5).
 *
 * <p>La usa {@code replenishment} al generar los borradores a partir de las líneas
 * aceptadas de una corrida ({@code generarBorradores}): agrupa por proveedor y envía un
 * comando por cada orden a crear. Queda fuera del alcance de esta API todo lo demás del
 * ciclo de vida de la orden (edición, envío, cancelación), que sigue siendo HTTP
 * ({@code /api/v1/purchase-orders}) y del servicio interno del módulo.
 */
public interface PurchaseOrderApi {

    /**
     * Crea una orden de compra en estado {@code DRAFT} por cada comando recibido y
     * devuelve sus identificadores, en el mismo orden de la lista de entrada.
     *
     * <p><b>Transaccionalidad:</b> todos los borradores se crean en una única
     * transacción: si un comando falla, no queda creado ninguno (todo o nada), de modo
     * que el llamador no maneja órdenes parcialmente generadas.
     *
     * <p><b>Validaciones delegadas al servicio interno de órdenes</b>
     * ({@code PurchaseOrderService}), idénticas a las de una orden manual:
     * proveedor existente y activo, productos existentes, activos y no {@code PACK},
     * sin repetirse dentro de la misma orden, 1 a 500 líneas, cantidad en unidades de
     * compra &gt; 0, {@code unitCostNet} obligatorio cuando el producto no tiene
     * asociación producto-proveedor con ese proveedor (si la hay, se toma el costo de
     * la asociación y {@code unitCostNet} es opcional), y {@code expectedDate} no
     * anterior a hoy. Los totales los calcula el servicio con los redondeos de
     * {@code shared.money.Rounding}.
     *
     * <p>El local ({@code storeId}) y el origen ({@code origin}) vienen explícitos en
     * el comando: esta API no usa {@code StoreContext}, porque la llama otro módulo que
     * ya validó el local en su propio endpoint (p. ej. la corrida de reabastecimiento
     * pertenece a un local ya verificado por el filtro HTTP).
     *
     * @param commands borradores a crear (no vacío; un comando = una orden por proveedor)
     * @return identificadores de las órdenes creadas, en el mismo orden de los comandos
     * @throws cl.minimarket.pos.shared.error.BusinessException {@code VALIDATION_ERROR}
     *         si la lista está vacía o un comando no indica {@code storeId} u
     *         {@code origin}; el resto de los errores de validación los produce el
     *         servicio interno con los mismos códigos que la creación manual
     * @since preparación de T80 (reabastecimiento)
     */
    List<Long> createDrafts(List<DraftOrderCommand> commands);

    /**
     * Devuelve el resumen de referencia de una orden de compra ({@code id}, {@code number},
     * {@code supplierId} y {@code netTotal}).
     *
     * <p>La usa {@code replenishment} para armar la respuesta de generación de borradores
     * ({@code API.md} F.3 {@code GeneratedOrdersResponse}), sin que el módulo llamador conozca
     * las tablas internas de órdenes.
     *
     * @param orderId identificador de la orden
     * @return resumen de la orden, o vacío si no existe
     * @since T80 (reabastecimiento)
     */
    Optional<OrderRef> findRef(Long orderId);

    /**
     * Devuelve el subconjunto de identificadores de línea de reabastecimiento que ya fueron
     * usados por alguna línea de orden de compra ({@code purchase_order_lines.replenishment_line_id}).
     *
     * <p>Lo usa {@code replenishment} para bloquear la edición de una sugerencia que ya está
     * en una orden ({@code API.md} F.2 {@code PATCH /replenishment/lines/{id}} → 409
     * {@code INVALID_STATE_TRANSITION}).
     *
     * @param replenishmentLineIds identificadores de línea de {@code replenishment_lines} a consultar
     * @return conjunto con los ids de entrada que ya pertenecen a alguna orden de compra
     * @since T80 (reabastecimiento)
     */
    Set<Long> replenishmentLineIdsInOrders(Collection<Long> replenishmentLineIds);

    /**
     * Resumen de referencia de una orden de compra para consumidores de la API pública.
     *
     * @param id         identificador de la orden
     * @param number     folio de la orden ({@code document_sequences}, nunca nulo)
     * @param supplierId proveedor de la orden
     * @param netTotal   total neto (CLP, escala 0), como lo calculó el módulo de compras
     */
    record OrderRef(
            Long id,
            String number,
            Long supplierId,
            BigDecimal netTotal
    ) {
    }


    /**
     * Borrador de orden de compra para un proveedor ({@code PLAN.md} C.5: agrupación de
     * líneas aceptadas por proveedor).
     *
     * @param storeId           identificador del local para el que se crea la orden (obligatorio)
     * @param supplierId        identificador del proveedor (obligatorio; debe estar activo)
     * @param origin            origen de la orden (obligatorio; en reabastecimiento es
     *                          {@link PurchaseOrderOrigin#REPLENISHMENT})
     * @param replenishmentRunId identificador de la corrida que originó el borrador
     *                          (opcional; trazabilidad en {@code purchase_orders.replenishment_run_id})
     * @param expectedDate      fecha esperada de entrega (opcional; no anterior a hoy)
     * @param notes             observaciones de la orden (opcional)
     * @param lines             líneas a comprar (1 a 500; ver {@link DraftLineCommand})
     */
    record DraftOrderCommand(
            Long storeId,
            Long supplierId,
            PurchaseOrderOrigin origin,
            Long replenishmentRunId,
            LocalDate expectedDate,
            String notes,
            List<DraftLineCommand> lines
    ) {
        public DraftOrderCommand {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * Línea de un borrador de orden de compra. Mantiene los nombres del comando interno
     * de líneas del módulo ({@code PurchaseOrderService.LineCommand}).
     *
     * @param productId           identificador del producto (obligatorio; activo y no {@code PACK})
     * @param qtyPurchaseUnits    cantidad en unidades de compra (&gt; 0)
     * @param unitCostNet         costo unitario neto por unidad base (&ge; 0; opcional si
     *                            el producto tiene asociación con el proveedor del comando)
     * @param replenishmentLineId identificador de la línea de sugerencia que originó esta
     *                            línea (opcional; trazabilidad en
     *                            {@code purchase_order_lines.replenishment_line_id})
     */
    record DraftLineCommand(
            Long productId,
            BigDecimal qtyPurchaseUnits,
            BigDecimal unitCostNet,
            Long replenishmentLineId
    ) {
    }
}
