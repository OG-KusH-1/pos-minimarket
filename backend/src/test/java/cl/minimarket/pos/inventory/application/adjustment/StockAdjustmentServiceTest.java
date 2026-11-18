package cl.minimarket.pos.inventory.application.adjustment;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.inventory.application.adjustment.CreateAdjustmentCommand.LineCommand;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentJdbcRepository.AdjustmentLineForExecution;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentJdbcRepository.LockedAdjustment;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentJdbcRepository.ProductRow;
import cl.minimarket.pos.security.AuthorizationRef;
import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.RoleLimit;
import cl.minimarket.pos.security.RoleLimitApi;
import cl.minimarket.pos.security.RoleLimitCode;
import cl.minimarket.pos.security.SupervisorOverrideApi;
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
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de reglas de validación, cálculo de impacto, segregación de funciones, límites
 * de rol y override en {@link StockAdjustmentService} (T49).
 */
@ExtendWith(MockitoExtension.class)
class StockAdjustmentServiceTest {

    private static final Long STORE_ID = 1L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long APPROVER_ID = 20L;
    private static final Instant FIXED_NOW = Instant.parse("2026-12-01T15:00:00Z");

    @Mock
    StockAdjustmentJdbcRepository repository;

    @Mock
    StockApi stockApi;

    @Mock
    SequenceService sequenceService;

    @Mock
    RoleLimitApi roleLimitApi;

    @Mock
    SupervisorOverrideApi supervisorOverrideApi;

    @Mock
    AuditService auditService;

    private StockAdjustmentService service;

    @BeforeEach
    void setUp() {
        BusinessClock clock = BusinessClock.fixed(FIXED_NOW, ZoneId.of("America/Santiago"));
        service = new StockAdjustmentService(
                repository,
                stockApi,
                sequenceService,
                roleLimitApi,
                supervisorOverrideApi,
                auditService,
                clock);
    }

    @Test
    void computeApprovalAmount_sumsAbsoluteLineImpactsWithoutNettingOppositeSigns() {
        List<AdjustmentLineForExecution> lines = List.of(
                new AdjustmentLineForExecution(1L, 100L, null, null, null,
                        new BigDecimal("10.000"), new BigDecimal("6000.0000")),
                new AdjustmentLineForExecution(2L, 101L, null, null, null,
                        new BigDecimal("-10.000"), new BigDecimal("6000.0000"))
        );

        BigDecimal approvalAmount = StockAdjustmentService.computeApprovalAmount(lines);

        assertThat(approvalAmount).isEqualByComparingTo("120000.00");
    }

    @Test
    void create_rejectsPackProductDecimalUnitAndZeroDelta_withoutCallingStockApi() {
        ProductRow packProduct = new ProductRow(100L, "SKU-P", "Pack 6",
                SaleType.PACK, UnitOfMeasure.UNIT, new BigDecimal("5000.0000"), false, null);
        ProductRow unitProduct = new ProductRow(101L, "SKU-U", "Bebida",
                SaleType.UNIT, UnitOfMeasure.UNIT, new BigDecimal("1000.0000"), false, null);

        when(repository.findProductsByIds(Set.of(100L))).thenReturn(Map.of(100L, packProduct));
        when(repository.findStockProductIds(STORE_ID, Set.of(100L))).thenReturn(Set.of(100L));
        when(repository.findLotsByIds(STORE_ID, Set.of())).thenReturn(Map.of());

        CreateAdjustmentCommand packCmd = new CreateAdjustmentCommand(
                AdjustmentReason.COUNT_CORRECTION,
                "Prueba pack",
                List.of(new LineCommand(100L, BigDecimal.ONE, null, null, null)));

        assertThatThrownBy(() -> service.create(STORE_ID, REQUESTER_ID, packCmd, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        when(repository.findProductsByIds(Set.of(101L))).thenReturn(Map.of(101L, unitProduct));
        when(repository.findStockProductIds(STORE_ID, Set.of(101L))).thenReturn(Set.of(101L));

        CreateAdjustmentCommand decimalUnitCmd = new CreateAdjustmentCommand(
                AdjustmentReason.COUNT_CORRECTION,
                "Cantidad decimal",
                List.of(new LineCommand(101L, new BigDecimal("1.500"), null, null, null)));

        assertThatThrownBy(() -> service.create(STORE_ID, REQUESTER_ID, decimalUnitCmd, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        CreateAdjustmentCommand zeroCmd = new CreateAdjustmentCommand(
                AdjustmentReason.COUNT_CORRECTION,
                "Cantidad cero",
                List.of(new LineCommand(101L, new BigDecimal("0.0000"), null, null, null)));

        assertThatThrownBy(() -> service.create(STORE_ID, REQUESTER_ID, zeroCmd, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        verifyNoInteractions(stockApi);
    }

    @Test
    void create_enforcesLotRulesForLotControlledAndNonLotProducts() {
        ProductRow lotPerishable = new ProductRow(200L, "SKU-L", "Yogurt",
                SaleType.UNIT, UnitOfMeasure.UNIT, new BigDecimal("800.0000"), true, 15);
        ProductRow noLot = new ProductRow(201L, "SKU-N", "Arroz",
                SaleType.WEIGHT, UnitOfMeasure.KG, new BigDecimal("1200.0000"), false, null);

        when(repository.findProductsByIds(Set.of(200L))).thenReturn(Map.of(200L, lotPerishable));
        when(repository.findStockProductIds(STORE_ID, Set.of(200L))).thenReturn(Set.of(200L));
        when(repository.findLotsByIds(STORE_ID, Set.of())).thenReturn(Map.of());

        // Salida de producto con lotes sin lotId -> 422 LOT_REQUIRED
        CreateAdjustmentCommand missingLotOut = new CreateAdjustmentCommand(
                AdjustmentReason.LOT_CORRECTION,
                null,
                List.of(new LineCommand(200L, new BigDecimal("-2"), null, null, null)));

        assertThatThrownBy(() -> service.create(STORE_ID, REQUESTER_ID, missingLotOut, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOT_REQUIRED);

        // Entrada con shelfLifeDays sin expiryDate -> 422 LOT_REQUIRED
        CreateAdjustmentCommand missingExpiry = new CreateAdjustmentCommand(
                AdjustmentReason.FOUND_GOODS,
                null,
                List.of(new LineCommand(200L, new BigDecimal("3"), null, "LOT-1", null)));

        assertThatThrownBy(() -> service.create(STORE_ID, REQUESTER_ID, missingExpiry, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOT_REQUIRED);

        // Producto sin lotes con lotNumber -> 400 VALIDATION_ERROR
        when(repository.findProductsByIds(Set.of(201L))).thenReturn(Map.of(201L, noLot));
        when(repository.findStockProductIds(STORE_ID, Set.of(201L))).thenReturn(Set.of(201L));
        CreateAdjustmentCommand unexpectedLot = new CreateAdjustmentCommand(
                AdjustmentReason.FOUND_GOODS,
                null,
                List.of(new LineCommand(201L, new BigDecimal("1.250"), null, "LOT-X", null)));

        assertThatThrownBy(() -> service.create(STORE_ID, REQUESTER_ID, unexpectedLot, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void create_persistsPendingAdjustmentAndAuditsWithoutTouchingStock() {
        ProductRow prod = new ProductRow(300L, "SKU-300", "Leche",
                SaleType.UNIT, UnitOfMeasure.UNIT, new BigDecimal("1000.0000"), true, 30);

        when(repository.findProductsByIds(Set.of(300L))).thenReturn(Map.of(300L, prod));
        when(repository.findStockProductIds(STORE_ID, Set.of(300L))).thenReturn(Set.of(300L));
        when(repository.findLotsByIds(STORE_ID, Set.of())).thenReturn(Map.of());
        when(repository.findOrCreatePlaceholderLot(
                eq(STORE_ID), eq(300L), eq("L-2026"), eq(LocalDate.of(2026, 12, 31)),
                eq(new BigDecimal("1000.0000")), eq(REQUESTER_ID), eq(FIXED_NOW)))
                .thenReturn(99L);
        when(sequenceService.next(SequenceType.STOCK_ADJUSTMENT, STORE_ID)).thenReturn("L01-AJ-000001");
        when(repository.insertAdjustment(
                eq("L01-AJ-000001"), eq(STORE_ID), eq(AdjustmentReason.FOUND_GOODS),
                eq("Hallazgo en bodega"), eq(AdjustmentStatus.PENDING_APPROVAL),
                eq(new BigDecimal("4000.00")), eq(REQUESTER_ID), eq(FIXED_NOW)))
                .thenReturn(500L);

        StockAdjustmentView expectedView = new StockAdjustmentView(
                500L, "L01-AJ-000001", AdjustmentStatus.PENDING_APPROVAL, AdjustmentReason.FOUND_GOODS,
                "Hallazgo en bodega", new BigDecimal("4000.00"),
                new StockAdjustmentView.UserSummaryView(REQUESTER_ID, "bod", "Bodeguero"),
                null, null, null, OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC), List.of());
        when(repository.findById(STORE_ID, 500L, true)).thenReturn(Optional.of(expectedView));

        StockAdjustmentView created = service.create(
                STORE_ID,
                REQUESTER_ID,
                new CreateAdjustmentCommand(
                        AdjustmentReason.FOUND_GOODS,
                        "  Hallazgo en bodega  ",
                        List.of(new LineCommand(300L, new BigDecimal("4"), null, "L-2026",
                                LocalDate.of(2026, 12, 31)))),
                true);

        assertThat(created.status()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
        verify(repository).insertLine(500L, 300L, 99L, new BigDecimal("4.000"),
                new BigDecimal("1000.0000"), FIXED_NOW);
        verify(auditService).record(any(AuditEvent.class));
        verifyNoInteractions(stockApi);
    }

    @Test
    void approve_enforcesSod_roleLimitAndConsumesOverrideToken() {
        LockedAdjustment pending = new LockedAdjustment(
                700L, "L01-AJ-000007", STORE_ID, AdjustmentReason.SYSTEM_ERROR,
                "Corrección", AdjustmentStatus.PENDING_APPROVAL,
                new BigDecimal("80000.00"), REQUESTER_ID);
        when(repository.lockHeaderForUpdate(STORE_ID, 700L)).thenReturn(Optional.of(pending));

        // 1. Mismo usuario que solicitó -> 422 SEGREGATION_OF_DUTIES
        assertThatThrownBy(() -> service.approve(STORE_ID, 700L, REQUESTER_ID, null, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES);

        // 2. Supera límite sin token -> 422 OVERRIDE_REQUIRED con action y context
        when(repository.findLinesForExecution(700L)).thenReturn(List.of(
                new AdjustmentLineForExecution(1L, 10L, null, null, null,
                        new BigDecimal("8.000"), new BigDecimal("10000.0000"))));
        when(roleLimitApi.limitFor(APPROVER_ID, RoleLimitCode.ADJUSTMENT_APPROVE_MAX_AMOUNT))
                .thenReturn(new RoleLimit.Limited(new BigDecimal("50000")));

        assertThatThrownBy(() -> service.approve(STORE_ID, 700L, APPROVER_ID, null, true))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.OVERRIDE_REQUIRED);
                    assertThat(be.getExtensions()).containsEntry("action", "STOCK_ADJUSTMENT_APPROVAL");
                    assertThat(be.getExtensions()).containsKey("context");
                });
        verifyNoInteractions(stockApi);

        // 3. Con token válido -> consume override, llama a StockApi.apply y audita con authorizationId
        when(supervisorOverrideApi.canonicalContextHash(any())).thenReturn("hash-123");
        when(supervisorOverrideApi.consume(
                "tok-abc", OverrideAction.STOCK_ADJUSTMENT_APPROVAL, "hash-123",
                APPROVER_ID, "stock_adjustment", "700"))
                .thenReturn(new AuthorizationRef(888L, 30L, OverrideAction.STOCK_ADJUSTMENT_APPROVAL));
        when(stockApi.apply(any(StockCommand.class))).thenReturn(List.of(
                new StockMovementResult(9001L, STORE_ID, 10L, new BigDecimal("8.000"),
                        new BigDecimal("18.000"), new BigDecimal("10000.0000"), List.of())));
        when(repository.findById(STORE_ID, 700L, true)).thenReturn(Optional.of(new StockAdjustmentView(
                700L, "L01-AJ-000007", AdjustmentStatus.APPROVED, AdjustmentReason.SYSTEM_ERROR,
                "Corrección", new BigDecimal("80000.00"), null, null, null, null, null, List.of())));

        service.approve(STORE_ID, 700L, APPROVER_ID, "tok-abc", true);

        ArgumentCaptor<StockCommand> stockCmdCaptor = ArgumentCaptor.forClass(StockCommand.class);
        verify(stockApi).apply(stockCmdCaptor.capture());
        assertThat(stockCmdCaptor.getValue().movementType()).isEqualTo(StockMovementType.ADJUSTMENT_IN);
        assertThat(stockCmdCaptor.getValue().sourceType()).isEqualTo(StockSourceType.STOCK_ADJUSTMENT);
        assertThat(stockCmdCaptor.getValue().sourceId()).isEqualTo(700L);

        verify(repository).updateLineMovementId(1L, 9001L);
        verify(repository).markApproved(700L, APPROVER_ID, FIXED_NOW);

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("STOCK_ADJUSTMENT_APPROVED");
        assertThat(auditCaptor.getValue().authorizationId()).isEqualTo(888L);
    }

    @Test
    void list_rejectsInvalidDateRange_and_rejectValidatesReasonAndState() {
        StockAdjustmentFilter invalidRange = new StockAdjustmentFilter(
                null, LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 1));
        assertThatThrownBy(() -> service.list(STORE_ID, invalidRange, PageRequest.of(0, 20), true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> service.reject(STORE_ID, 1L, APPROVER_ID, "no", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        LockedAdjustment alreadyApproved = new LockedAdjustment(
                1L, "L01-AJ-000001", STORE_ID, AdjustmentReason.OTHER,
                null, AdjustmentStatus.APPROVED, BigDecimal.TEN, REQUESTER_ID);
        when(repository.lockHeaderForUpdate(STORE_ID, 1L)).thenReturn(Optional.of(alreadyApproved));

        assertThatThrownBy(() -> service.reject(STORE_ID, 1L, APPROVER_ID, "Motivo válido", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }
}
