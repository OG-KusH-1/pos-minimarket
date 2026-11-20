package cl.minimarket.pos.replenishment.application.run;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.purchasing.PurchaseOrderApi;
import cl.minimarket.pos.purchasing.PurchaseOrderApi.DraftLineCommand;
import cl.minimarket.pos.purchasing.PurchaseOrderApi.DraftOrderCommand;
import cl.minimarket.pos.purchasing.PurchaseOrderApi.OrderRef;
import cl.minimarket.pos.purchasing.PurchaseOrderOrigin;
import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.replenishment.domain.ReplenishmentStatus;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generación de borradores de órdenes de compra desde una corrida
 * ({@code POST /replenishment/runs/{id}/purchase-orders}, {@code API.md} F.2 y {@code PLAN.md} C.5).
 *
 * <p>Es la "acción del usuario tras revisar/editar {@code final_purchase_units}": agrupa las
 * líneas {@code SUGGESTED} o {@code ACCEPTED} con proveedor de la corrida (una cantidad
 * efectiva mayor que cero: la editada o, si no, la sugerida) por proveedor, y crea una OC
 * {@code DRAFT} por proveedor con {@code origin=REPLENISHMENT}, el {@code replenishmentRunId} de
 * trazabilidad y cada línea enlazada a su línea de sugerencia. Las líneas
 * {@code NO_SUPPLIER} quedan fuera de las órdenes por diseño (C.4).
 */
@Service
public class ReplenishmentDraftService {

    private static final String ACTION_DRAFTS_GENERATED = "REPLENISHMENT_DRAFTS_GENERATED";
    private static final String ENTITY_RUN = "replenishment_run";

    private final ReplenishmentRunRepository runRepository;
    private final ReplenishmentLineRepository lineRepository;
    private final PurchaseOrderApi purchaseOrderApi;
    private final ProductSupplierApi productSupplierApi;
    private final BusinessClock clock;
    private final AuditService auditService;

    public ReplenishmentDraftService(ReplenishmentRunRepository runRepository,
                                     ReplenishmentLineRepository lineRepository,
                                     PurchaseOrderApi purchaseOrderApi,
                                     ProductSupplierApi productSupplierApi,
                                     BusinessClock clock,
                                     AuditService auditService) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.lineRepository = Objects.requireNonNull(lineRepository, "lineRepository");
        this.purchaseOrderApi = Objects.requireNonNull(purchaseOrderApi, "purchaseOrderApi");
        this.productSupplierApi = Objects.requireNonNull(productSupplierApi, "productSupplierApi");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
    }

    private static final Set<ReplenishmentStatus> DRAFT_STATUSES =
            Set.of(ReplenishmentStatus.SUGGESTED, ReplenishmentStatus.ACCEPTED);

    /**
     * Genera los borradores de la corrida (uno por proveedor con líneas elegibles).
     *
     * <p>Sin {@code lineIds} toma primero las líneas {@code ACCEPTED}; si no hay ninguna, las
     * {@code SUGGESTED} (comportamiento por defecto de {@code API.md} F.3). Cuando se envían
     * {@code lineIds} valida que todos pertenezcan a la corrida, estén en {@code SUGGESTED} o
     * {@code ACCEPTED}, tengan proveedor y {@code effectivePurchaseUnits} mayor que cero.
     *
     * @param storeId local de la corrida
     * @param runId   corrida completada de la que se generan los borradores
     * @param lineIds filtro opcional de identificadores de línea; vacío o {@code null} = default
     * @return las órdenes creadas, en el mismo orden que los proveedores elegibles
     * @throws BusinessException 404 {@code NOT_FOUND} si la corrida no pertenece al local;
     *         409 {@code INVALID_STATE_TRANSITION} si la corrida no está COMPLETED;
     *         400 {@code VALIDATION_ERROR} si no hay líneas elegibles o los ids son inválidos
     */
    @Transactional
    public List<GeneratedOrder> generate(Long storeId, long runId, List<Long> lineIds) {
        Objects.requireNonNull(storeId, "storeId");
        ReplenishmentRun run = runRepository.findByIdAndStoreId(runId, storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Corrida de reabastecimiento no encontrada."));
        if (run.getStatus() != ReplenishmentRunStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden generar borradores desde una corrida COMPLETED.");
        }

        List<ReplenishmentLine> eligible = selectEligibleLines(runId, lineIds);
        if (eligible.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "La corrida no tiene líneas sugeridas o aceptadas con proveedor para generar borradores.");
        }

        Map<Long, List<ReplenishmentLine>> bySupplier = new LinkedHashMap<>();
        for (ReplenishmentLine line : eligible) {
            bySupplier.computeIfAbsent(line.getSupplierId(), id -> new ArrayList<>()).add(line);
        }

        LocalDate today = clock.businessDate();
        List<DraftOrderCommand> commands = new ArrayList<>(bySupplier.size());
        List<List<ReplenishmentLine>> linesByCommand = new ArrayList<>(bySupplier.size());
        for (Map.Entry<Long, List<ReplenishmentLine>> entry : bySupplier.entrySet()) {
            List<ReplenishmentLine> lines = entry.getValue();
            commands.add(new DraftOrderCommand(
                    storeId,
                    entry.getKey(),
                    PurchaseOrderOrigin.REPLENISHMENT,
                    runId,
                    expectedDate(run, lines, today),
                    "Borrador desde corrida de reabastecimiento #" + runId,
                    lines.stream()
                            .map(line -> new DraftLineCommand(
                                    line.getProductId(),
                                    line.effectivePurchaseUnits().setScale(3, RoundingMode.HALF_UP),
                                    line.getUnitCostNet(),
                                    line.getId()))
                            .toList()));
            linesByCommand.add(lines);
        }

        List<Long> orderIds = purchaseOrderApi.createDrafts(commands);

        List<GeneratedOrder> orders = new ArrayList<>(orderIds.size());
        List<Map<String, Object>> auditOrders = new ArrayList<>(orderIds.size());
        for (int i = 0; i < orderIds.size(); i++) {
            OrderRef ref = purchaseOrderApi.findRef(orderIds.get(i)).orElse(null);
            Long supplierId = commands.get(i).supplierId();
            String supplierName = productSupplierApi.findSupplierById(supplierId)
                    .map(ref2 -> ref2.tradeName() != null && !ref2.tradeName().isBlank()
                            ? ref2.tradeName()
                            : ref2.legalName())
                    .orElse("Proveedor #" + supplierId);
            orders.add(new GeneratedOrder(
                    orderIds.get(i),
                    ref == null ? null : ref.number(),
                    supplierId,
                    supplierName,
                    linesByCommand.get(i).size(),
                    ref == null ? null : ref.netTotal()));
            auditOrders.add(Map.of(
                    "orderId", orderIds.get(i),
                    "supplierId", supplierId,
                    "lines", linesByCommand.get(i).size()));
        }

        auditService.record(new AuditEvent(
                ACTION_DRAFTS_GENERATED,
                ENTITY_RUN,
                String.valueOf(runId),
                null,
                Map.of("purchaseOrders", auditOrders),
                null,
                null));
        return orders;
    }

    /**
     * Selecciona las líneas que participarán en los borradores de OC.
     *
     * <p>Sin {@code lineIds} aplica el comportamiento por defecto de {@code API.md} F.3: primero
     * las líneas {@code ACCEPTED} con proveedor; si no hay ninguna, las {@code SUGGESTED} con
     * proveedor. Con {@code lineIds} carga esas líneas de la corrida y las valida.
     *
     * @param runId   corrida dueña de las líneas
     * @param lineIds filtro opcional de ids de línea
     * @return líneas elegibles, ordenadas por id
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si algún id no pertenece a la corrida,
     *         no está en {@code SUGGESTED}/{@code ACCEPTED}, no tiene proveedor o su cantidad
     *         efectiva es cero
     */
    private List<ReplenishmentLine> selectEligibleLines(long runId, List<Long> lineIds) {
        if (lineIds == null || lineIds.isEmpty()) {
            List<ReplenishmentLine> accepted = lineRepository
                    .findByRunIdAndStatusAndSupplierIdIsNotNullOrderByIdAsc(runId, ReplenishmentStatus.ACCEPTED);
            if (!accepted.isEmpty()) {
                return accepted.stream()
                        .filter(line -> line.effectivePurchaseUnits().signum() > 0)
                        .toList();
            }
            return lineRepository
                    .findByRunIdAndStatusAndSupplierIdIsNotNullOrderByIdAsc(runId, ReplenishmentStatus.SUGGESTED)
                    .stream()
                    .filter(line -> line.effectivePurchaseUnits().signum() > 0)
                    .toList();
        }

        List<ReplenishmentLine> selected = lineRepository.findByRunIdAndIdInOrderByIdAsc(runId, lineIds);
        Set<Long> foundIds = selected.stream()
                .map(ReplenishmentLine::getId)
                .collect(Collectors.toSet());
        if (foundIds.size() != lineIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Algunos identificadores de línea no pertenecen a la corrida.");
        }
        for (ReplenishmentLine line : selected) {
            if (!DRAFT_STATUSES.contains(line.getStatus())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "La línea " + line.getId() + " no está en estado SUGGESTED o ACCEPTED.");
            }
            if (line.getSupplierId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "La línea " + line.getId() + " no tiene proveedor asignado.");
            }
            if (line.effectivePurchaseUnits().signum() <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "La línea " + line.getId() + " no tiene unidades efectivas mayor que cero.");
            }
        }
        return selected.stream()
                .sorted(java.util.Comparator.comparing(ReplenishmentLine::getId))
                .toList();
    }

    /**
     * Fecha esperada de entrega del borrador: la fecha de corte más el lead time efectivo de la
     * línea (snapshot del cálculo); nunca anterior a hoy, requisito del módulo de compras.
     */
    private static LocalDate expectedDate(ReplenishmentRun run, List<ReplenishmentLine> lines, LocalDate today) {
        int leadTime = lines.stream().mapToInt(ReplenishmentLine::getLeadTimeDays).max().orElse(0);
        LocalDate expected = run.getAsOfDate().plusDays(leadTime);
        return expected.isBefore(today) ? today : expected;
    }

    /**
     * Resumen de una orden creada a partir de la corrida ({@code API.md} F.3
     * {@code GeneratedOrdersResponse.purchaseOrders[]}).
     *
     * @param id           identificador de la orden
     * @param number       folio de la orden (leído por {@code PurchaseOrderApi.findRef})
     * @param supplierId   proveedor de la orden
     * @param supplierName nombre del proveedor
     * @param linesCount   líneas de sugerencia que entraron en la orden
     * @param netTotal     total neto CLP calculado por el módulo de compras
     */
    public record GeneratedOrder(
            Long id,
            String number,
            Long supplierId,
            String supplierName,
            int linesCount,
            BigDecimal netTotal
    ) {
    }
}
