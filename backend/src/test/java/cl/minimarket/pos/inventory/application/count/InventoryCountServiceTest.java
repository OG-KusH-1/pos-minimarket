package cl.minimarket.pos.inventory.application.count;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CategoryLookupView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountDetailView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountLineInput;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountLineView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.UserRefView;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link InventoryCountService}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryCountServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Mock
    InventoryCountRepository countRepo;
    @Mock
    InventoryCountLineRepository lineRepo;
    @Mock
    InventoryCountLookupRepository lookup;
    @Mock
    StockApi stockApi;
    @Mock
    ProductCatalogApi productCatalogApi;
    @Mock
    SequenceService sequenceService;
    @Mock
    AuditService auditService;
    @Mock
    BusinessClock clock;

    InventoryCountService service;

    @BeforeEach
    void setUp() {
        service = new InventoryCountService(
                countRepo, lineRepo, lookup, stockApi, productCatalogApi,
                sequenceService, auditService, clock);
        lenient().when(clock.now()).thenReturn(NOW);
    }

    @Test
    void create_validatesTypeAndCategoryRules() {
        assertThatThrownBy(() -> service.create(1L, CountType.CYCLIC, null, true, null, 10L, false, false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> service.create(1L, CountType.FULL, 5L, true, null, 10L, false, false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        when(lookup.findCategory(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(1L, CountType.CYCLIC, 99L, true, null, 10L, false, false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void create_mapsUniqueConstraintViolationToDuplicatedValue() {
        when(lookup.findPositiveStockProductIds(1L)).thenReturn(List.of(100L));
        when(productCatalogApi.findByIds(List.of(100L))).thenReturn(List.of(snapshot(100L, UnitOfMeasure.UNIT)));
        when(lookup.existsOpenScope(1L, null)).thenReturn(false);
        when(sequenceService.next(SequenceType.INVENTORY_COUNT, 1L)).thenReturn("TI-001-000001");

        SQLException sqlEx = new SQLException("duplicate key violates uq_inventory_counts_open_scope", "23505");
        when(countRepo.saveAndFlush(any(InventoryCount.class)))
                .thenThrow(new DataIntegrityViolationException("conflict", sqlEx));

        assertThatThrownBy(() -> service.create(1L, CountType.FULL, null, true, null, 10L, false, false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.DUPLICATED_VALUE);
    }

    @Test
    void create_rejectsWhenFullHasNoPositiveStock() {
        when(lookup.findPositiveStockProductIds(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(1L, CountType.FULL, null, true, null, 10L, false, false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void create_rejectsInactiveCyclicCategory() {
        when(lookup.findCategory(5L)).thenReturn(Optional.of(new CategoryLookupView(5L, "Inactiva", false)));

        assertThatThrownBy(() -> service.create(1L, CountType.CYCLIC, 5L, true, null, 10L, false, false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void recordLines_validatesDuplicateLineIdsAndWholeUnits_andHidesBlindFields() {
        InventoryCount count = InventoryCount.open("TI-001-000002", 1L, CountType.FULL, null, true, 10L, null);
        setId(count, 20L);
        when(countRepo.findByIdAndStoreId(20L, 1L)).thenReturn(Optional.of(count));

        // Líneas duplicadas en la misma solicitud
        assertThatThrownBy(() -> service.recordLines(
                1L,
                20L,
                List.of(
                        new CountLineInput(1L, new BigDecimal("5")),
                        new CountLineInput(1L, new BigDecimal("6"))),
                11L,
                false,
                false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        InventoryCountLine unitLine = InventoryCountLine.createPending(20L, 100L, null);
        setId(unitLine, 1L);
        InventoryCountLine kgLine = InventoryCountLine.createPending(20L, 101L, null);
        setId(kgLine, 2L);

        when(lineRepo.findByCountIdAndIdIn(eq(20L), anyCollection())).thenReturn(List.of(unitLine));
        when(productCatalogApi.findByIds(anyCollection()))
                .thenReturn(List.of(snapshot(100L, UnitOfMeasure.UNIT)));

        // Producto UNIT con cantidad decimal → VALIDATION_ERROR
        assertThatThrownBy(() -> service.recordLines(
                1L,
                20L,
                List.of(new CountLineInput(1L, new BigDecimal("2.500"))),
                11L,
                false,
                false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        // Conteo válido sobre línea KG en toma ciega sin permiso approve
        when(lineRepo.findByCountIdAndIdIn(eq(20L), anyCollection())).thenReturn(List.of(kgLine));
        when(productCatalogApi.findByIds(anyCollection()))
                .thenReturn(List.of(snapshot(101L, UnitOfMeasure.KG)));
        when(stockApi.onHand(1L, 101L)).thenReturn(new BigDecimal("10.000"));
        when(lookup.findLotQuantities(eq(1L), anyCollection())).thenReturn(Map.of());
        when(lookup.findLotNumbers(eq(1L), anyCollection())).thenReturn(Map.of());

        List<CountLineView> result = service.recordLines(
                1L,
                20L,
                List.of(new CountLineInput(2L, new BigDecimal("8.750"))),
                11L,
                false,
                false);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().countedQty()).isEqualByComparingTo("8.750");
        assertThat(result.getFirst().theoreticalQty()).isNull();
        assertThat(result.getFirst().difference()).isNull();
        assertThat(result.getFirst().unitCost()).isNull();
        assertThat(kgLine.getTheoreticalQty()).isEqualByComparingTo("10.000");
        assertThat(kgLine.getCountedBy()).isEqualTo(11L);
    }

    @Test
    void submit_rejectsWhenUncountedLinesRemain() {
        InventoryCount count = InventoryCount.open("TI-001-000003", 1L, CountType.FULL, null, false, 10L, null);
        setId(count, 30L);
        when(countRepo.findByIdAndStoreId(30L, 1L)).thenReturn(Optional.of(count));
        when(lineRepo.countByCountIdAndCountedAtIsNull(30L)).thenReturn(2L);

        assertThatThrownBy(() -> service.submit(1L, 30L, 10L, true, false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void approve_enforcesSodForStarterAndCounter_andAppliesInboundOutboundDeltas() {
        InventoryCount count = InventoryCount.open("TI-001-000004", 1L, CountType.FULL, null, false, 10L, null);
        setId(count, 40L);
        count.submit(10L, NOW);

        when(countRepo.findByIdAndStoreId(40L, 1L)).thenReturn(Optional.of(count));

        // 1) Aprobador == startedBy → 422 SEGREGATION_OF_DUTIES
        assertThatThrownBy(() -> service.approve(1L, 40L, 10L, true, true))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES);

        // 2) Aprobador == countedBy → 422 SEGREGATION_OF_DUTIES
        when(lineRepo.existsByCountIdAndCountedBy(40L, 11L)).thenReturn(true);
        assertThatThrownBy(() -> service.approve(1L, 40L, 11L, true, true))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES);

        // 3) Aprobador distinto (12L): aplica +3 y -2, omite 0, audita
        InventoryCountLine inLine = InventoryCountLine.createPending(40L, 100L, null);
        setId(inLine, 1001L);
        inLine.recordCount(new BigDecimal("10.000"), new BigDecimal("13.000"), new BigDecimal("500.0000"), 11L, NOW);

        InventoryCountLine outLine = InventoryCountLine.createPending(40L, 101L, null);
        setId(outLine, 1002L);
        outLine.recordCount(new BigDecimal("20.000"), new BigDecimal("18.000"), new BigDecimal("800.0000"), 11L, NOW);

        InventoryCountLine zeroLine = InventoryCountLine.createPending(40L, 102L, null);
        setId(zeroLine, 1003L);
        zeroLine.recordCount(new BigDecimal("5.000"), new BigDecimal("5.000"), new BigDecimal("300.0000"), 11L, NOW);

        when(lineRepo.existsByCountIdAndCountedBy(40L, 12L)).thenReturn(false);
        when(lineRepo.findByCountIdOrderByIdAsc(40L)).thenReturn(List.of(inLine, outLine, zeroLine));

        when(stockApi.apply(any(StockCommand.class))).thenAnswer(inv -> {
            StockCommand cmd = inv.getArgument(0);
            if (cmd.movementType() == StockMovementType.ADJUSTMENT_IN) {
                return List.of(new StockMovementResult(
                        9001L, 1L, 100L, new BigDecimal("3.000"), new BigDecimal("13.000"),
                        new BigDecimal("500.0000"), List.of()));
            }
            return List.of(new StockMovementResult(
                    9002L, 1L, 101L, new BigDecimal("-2.000"), new BigDecimal("18.000"),
                    new BigDecimal("800.0000"), List.of()));
        });
        when(lookup.findUserRefs(anyCollection())).thenReturn(Map.of(
                10L, new UserRefView(10L, "Iniciador"),
                12L, new UserRefView(12L, "Aprobador")));
        when(lookup.findProgressByCountIds(List.of(40L)))
                .thenReturn(Map.of(40L, new InventoryCountViews.CountProgressView(3L, 3L)));
        when(lookup.findDifferenceSummariesByCountIds(List.of(40L))).thenReturn(Map.of());

        CountDetailView approved = service.approve(1L, 40L, 12L, true, true);

        assertThat(approved.status()).isEqualTo(CountStatus.APPROVED);
        assertThat(inLine.getStockMovementId()).isEqualTo(9001L);
        assertThat(outLine.getStockMovementId()).isEqualTo(9002L);
        assertThat(zeroLine.getStockMovementId()).isNull();

        ArgumentCaptor<StockCommand> cmdCaptor = ArgumentCaptor.forClass(StockCommand.class);
        verify(stockApi, times(2)).apply(cmdCaptor.capture());
        List<StockCommand> commands = cmdCaptor.getAllValues();
        assertThat(commands.get(0).sourceType()).isEqualTo(StockSourceType.INVENTORY_COUNT);
        assertThat(commands.get(0).sourceId()).isEqualTo(40L);
        assertThat(commands.get(0).movementType()).isEqualTo(StockMovementType.ADJUSTMENT_IN);
        assertThat(commands.get(0).lines()).singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("3.000"));
        assertThat(commands.get(1).movementType()).isEqualTo(StockMovementType.ADJUSTMENT_OUT);
        assertThat(commands.get(1).lines()).singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("2.000"));

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("INVENTORY_COUNT_APPROVED");
        assertThat(auditCaptor.getValue().entityType()).isEqualTo("inventory_count");
        assertThat(auditCaptor.getValue().entityId()).isEqualTo("40");
    }

    @Test
    void rejectAndCancel_enforceTransitionsAndAudit() {
        InventoryCount count = InventoryCount.open("TI-001-000005", 1L, CountType.FULL, null, false, 10L, null);
        setId(count, 50L);
        count.submit(10L, NOW);

        when(countRepo.findByIdAndStoreId(50L, 1L)).thenReturn(Optional.of(count));
        when(lookup.findUserRefs(anyCollection())).thenReturn(Map.of(10L, new UserRefView(10L, "Iniciador")));
        when(lookup.findProgressByCountIds(List.of(50L)))
                .thenReturn(Map.of(50L, new InventoryCountViews.CountProgressView(1L, 1L)));

        CountDetailView reopened = service.reject(1L, 50L, "Falta revisar estante B", false, true);
        assertThat(reopened.status()).isEqualTo(CountStatus.OPEN);
        assertThat(reopened.rejectionReason()).isEqualTo("Falta revisar estante B");
        verify(auditService).record(any(AuditEvent.class));

        service.cancel(1L, 50L, "Se pospone inventario");
        assertThat(count.getStatus()).isEqualTo(CountStatus.CANCELLED);

        assertThatThrownBy(() -> service.cancel(1L, 50L, "Otro motivo"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verify(stockApi, never()).apply(any());
    }

    private static ProductSnapshot snapshot(Long id, UnitOfMeasure uom) {
        return new ProductSnapshot(
                id,
                "SKU-" + id,
                "Producto " + id,
                1L,
                null,
                SaleType.UNIT,
                uom,
                new BigDecimal("1500"),
                new BigDecimal("500.0000"),
                false,
                null,
                false,
                true,
                null,
                null,
                true,
                null);
    }

    private static void setId(Object target, Long id) {
        try {
            Field f = target.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
