package cl.minimarket.pos.shrinkage.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileMetadata;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.TransferDifferenceHandler.TransferDifference;
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
import cl.minimarket.pos.shrinkage.ShrinkageApi.SaleReturnShrinkageCommand;
import cl.minimarket.pos.shrinkage.ShrinkageStatus;
import cl.minimarket.pos.shrinkage.ShrinkageType;
import cl.minimarket.pos.shrinkage.application.ShrinkageJdbcRepository.LockedShrinkage;
import cl.minimarket.pos.shrinkage.application.ShrinkageView.ProductSummaryView;
import cl.minimarket.pos.shrinkage.application.ShrinkageView.UserSummaryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de reglas de negocio en {@link ShrinkageService} (RN-19, RN-21, SOD,
 * validación de tipos, mermas sistémicas y reloj de negocio).
 */
@ExtendWith(MockitoExtension.class)
class ShrinkageServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-18T15:00:00Z");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Santiago");

    @Mock
    ShrinkageJdbcRepository repository;
    @Mock
    StockApi stockApi;
    @Mock
    ProductCatalogApi productCatalogApi;
    @Mock
    FileApi fileApi;
    @Mock
    SequenceService sequenceService;
    @Mock
    RoleLimitApi roleLimitApi;
    @Mock
    SupervisorOverrideApi supervisorOverrideApi;
    @Mock
    AuditService auditService;

    private ShrinkageService service;

    @BeforeEach
    void setUp() {
        BusinessClock clock = BusinessClock.fixed(FIXED_NOW, BUSINESS_ZONE);
        service = new ShrinkageService(
                repository,
                stockApi,
                productCatalogApi,
                fileApi,
                sequenceService,
                roleLimitApi,
                supervisorOverrideApi,
                auditService,
                clock);

        lenient().when(sequenceService.next(eq(SequenceType.SHRINKAGE), any()))
                .thenReturn("L01-MR-000001");
    }

    @Test
    void create_zeroCostDoesNotAutoApprove_evenWithUnlimitedLimit() {
        ProductSnapshot zeroCostProduct = product(10L, "SKU-0", UnitOfMeasure.UNIT, new BigDecimal("0.0000"), false);
        when(repository.findProductById(10L)).thenReturn(Optional.of(zeroCostProduct));
        when(repository.hasStockRow(1L, 10L)).thenReturn(true);
        when(repository.userExists(2L)).thenReturn(true);
        when(roleLimitApi.limitFor(2L, RoleLimitCode.SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT))
                .thenReturn(RoleLimit.unlimited());
        when(repository.insertShrinkage(
                any(), eq(1L), eq(10L), isNull(), eq(ShrinkageType.DAMAGED),
                eq(new BigDecimal("2.000")), eq(new BigDecimal("0.0000")), eq(new BigDecimal("0.00")),
                any(), isNull(), eq(2L), eq(2L), eq(ShrinkageStatus.PENDING_APPROVAL),
                isNull(), isNull(), isNull(), isNull(), eq(false), eq(true), isNull(), any(), any()))
                .thenReturn(100L);
        when(repository.findById(1L, 100L, true))
                .thenReturn(Optional.of(dummyView(100L, ShrinkageStatus.PENDING_APPROVAL, false, true)));

        ShrinkageView view = service.create(
                1L,
                2L,
                new CreateShrinkageCommand(10L, null, ShrinkageType.DAMAGED, new BigDecimal("2"), "Dañado", null, 2L, FIXED_NOW),
                true);

        assertThat(view.status()).isEqualTo(ShrinkageStatus.PENDING_APPROVAL);
        assertThat(view.autoApproved()).isFalse();
        verify(stockApi, never()).apply(any());
    }

    @Test
    void create_positiveCostWithinLimit_autoApprovesWithNullApproverAndAppliesStock() {
        ProductSnapshot prod = product(10L, "SKU-1", UnitOfMeasure.UNIT, new BigDecimal("2500.0000"), false);
        when(repository.findProductById(10L)).thenReturn(Optional.of(prod));
        when(repository.hasStockRow(1L, 10L)).thenReturn(true);
        when(repository.userExists(2L)).thenReturn(true);
        when(roleLimitApi.limitFor(2L, RoleLimitCode.SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT))
                .thenReturn(RoleLimit.limited(new BigDecimal("10000")));
        when(repository.insertShrinkage(
                any(), eq(1L), eq(10L), isNull(), eq(ShrinkageType.BROKEN),
                eq(new BigDecimal("3.000")), eq(new BigDecimal("2500.0000")), eq(new BigDecimal("7500.00")),
                any(), isNull(), eq(2L), eq(2L), eq(ShrinkageStatus.APPROVED),
                isNull(), eq(FIXED_NOW), isNull(), isNull(), eq(true), eq(true), isNull(), any(), any()))
                .thenReturn(101L);
        when(stockApi.apply(any())).thenReturn(List.of(new StockMovementResult(
                55L, 1L, 10L, new BigDecimal("-3.000"), new BigDecimal("7.000"), new BigDecimal("2500.0000"), List.of())));
        when(repository.findById(1L, 101L, true))
                .thenReturn(Optional.of(dummyView(101L, ShrinkageStatus.APPROVED, true, true)));

        ShrinkageView view = service.create(
                1L,
                2L,
                new CreateShrinkageCommand(10L, null, ShrinkageType.BROKEN, new BigDecimal("3"), "Roto", null, 2L, FIXED_NOW),
                true);

        assertThat(view.status()).isEqualTo(ShrinkageStatus.APPROVED);
        assertThat(view.autoApproved()).isTrue();
        ArgumentCaptor<StockCommand> cmdCaptor = ArgumentCaptor.forClass(StockCommand.class);
        verify(stockApi).apply(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().movementType()).isEqualTo(StockMovementType.SHRINKAGE);
        verify(repository).updateStockMovement(101L, 55L, 2L, FIXED_NOW);
    }

    @Test
    void create_rejectsTransferDifferenceFutureDateAndMissingReasonForTheft() {
        assertThatThrownBy(() -> service.create(
                1L,
                2L,
                new CreateShrinkageCommand(10L, null, ShrinkageType.TRANSFER_DIFFERENCE, BigDecimal.ONE, "x", null, 2L, FIXED_NOW),
                true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> service.create(
                1L,
                2L,
                new CreateShrinkageCommand(10L, null, ShrinkageType.THEFT_LOSS, BigDecimal.ONE, "   ", null, 2L, FIXED_NOW),
                true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> service.create(
                1L,
                2L,
                new CreateShrinkageCommand(10L, null, ShrinkageType.DAMAGED, BigDecimal.ONE, "x", null, 2L, FIXED_NOW.plusSeconds(60)),
                true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void create_lotControlledWithoutLot_throwsLotRequired_andInvalidEvidenceCategoryThrowsValidationError() {
        ProductSnapshot lotProd = product(10L, "SKU-L", UnitOfMeasure.UNIT, new BigDecimal("1000.0000"), true);
        when(repository.findProductById(10L)).thenReturn(Optional.of(lotProd));
        when(repository.hasStockRow(1L, 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                1L,
                2L,
                new CreateShrinkageCommand(10L, null, ShrinkageType.EXPIRED, BigDecimal.ONE, "Vencido", null, 2L, FIXED_NOW),
                true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOT_REQUIRED);

        ProductSnapshot nonLotProd = product(11L, "SKU-NL", UnitOfMeasure.UNIT, new BigDecimal("1000.0000"), false);
        when(repository.findProductById(11L)).thenReturn(Optional.of(nonLotProd));
        when(repository.hasStockRow(1L, 11L)).thenReturn(true);
        when(fileApi.get(99L)).thenReturn(new FileMetadata(
                99L, "k", "img.png", "image/png", 123L, "abc", FileCategory.PRODUCT_IMAGE, FIXED_NOW));

        assertThatThrownBy(() -> service.create(
                1L,
                2L,
                new CreateShrinkageCommand(11L, null, ShrinkageType.DAMAGED, BigDecimal.ONE, "Dañado", 99L, 2L, FIXED_NOW),
                true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void approve_sodAndSystemicNoStockAndOverrideRequired() {
        LockedShrinkage systemic = new LockedShrinkage(
                200L, "L01-MR-000002", 1L, 10L, null, ShrinkageType.TRANSFER_DIFFERENCE,
                new BigDecimal("2.000"), new BigDecimal("1500.0000"), new BigDecimal("3000.00"),
                "Faltante", 2L, ShrinkageStatus.PENDING_APPROVAL, false);
        when(repository.lockForUpdate(1L, 200L)).thenReturn(Optional.of(systemic));

        // SOD
        assertThatThrownBy(() -> service.approve(1L, 200L, 2L, null, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES);

        // Supera límite sin token -> OVERRIDE_REQUIRED
        when(roleLimitApi.limitFor(3L, RoleLimitCode.SHRINKAGE_APPROVE_MAX_AMOUNT))
                .thenReturn(RoleLimit.limited(new BigDecimal("1000")));
        assertThatThrownBy(() -> service.approve(1L, 200L, 3L, null, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.OVERRIDE_REQUIRED);

        // Con token válido y affectsStock=false -> aprueba sin llamar a StockApi
        when(supervisorOverrideApi.canonicalContextHash(any())).thenReturn("hash123");
        when(supervisorOverrideApi.consume("tok", OverrideAction.SHRINKAGE_APPROVAL, "hash123", 3L, "shrinkage", "200"))
                .thenReturn(new AuthorizationRef(77L, 9L, OverrideAction.SHRINKAGE_APPROVAL));
        when(repository.findById(1L, 200L, true))
                .thenReturn(Optional.of(dummyView(200L, ShrinkageStatus.APPROVED, false, false)));

        ShrinkageView approved = service.approve(1L, 200L, 3L, "tok", true);
        assertThat(approved.status()).isEqualTo(ShrinkageStatus.APPROVED);
        assertThat(approved.affectsStock()).isFalse();
        verify(stockApi, never()).apply(any());
        verify(repository).markApproved(200L, 3L, FIXED_NOW, null);
        verify(auditService).record(any(AuditEvent.class));
    }

    @Test
    void handleTransferDifference_andCreateFromSaleReturn_createSystemicPendingOnTargetStore() {
        when(repository.insertShrinkage(
                any(), eq(5L), eq(10L), eq(88L), eq(ShrinkageType.TRANSFER_DIFFERENCE),
                eq(new BigDecimal("2.000")), eq(new BigDecimal("1200.0000")), eq(new BigDecimal("2400.00")),
                eq("Caja rota"), isNull(), eq(4L), eq(4L), eq(ShrinkageStatus.PENDING_APPROVAL),
                isNull(), isNull(), eq(33L), isNull(), eq(false), eq(false), isNull(), eq(FIXED_NOW), eq(FIXED_NOW)))
                .thenReturn(300L);

        service.handle(new TransferDifference(
                15L, "L05-TR-000001", 33L, 5L, 6L, 10L, 88L,
                new BigDecimal("2"), new BigDecimal("1200"), "Caja rota", 4L, FIXED_NOW));

        verify(stockApi, never()).apply(any());

        when(repository.insertShrinkage(
                any(), eq(7L), eq(10L), eq(99L), eq(ShrinkageType.DAMAGED),
                eq(new BigDecimal("1.000")), eq(new BigDecimal("1000.0000")), eq(new BigDecimal("1000.00")),
                eq("Envase abollado"), isNull(), eq(8L), eq(8L), eq(ShrinkageStatus.PENDING_APPROVAL),
                isNull(), isNull(), isNull(), eq(44L), eq(false), eq(false), isNull(), eq(FIXED_NOW), eq(FIXED_NOW)))
                .thenReturn(301L);

        Long createdId = service.createFromSaleReturn(new SaleReturnShrinkageCommand(
                7L, 10L, 99L, 44L, BigDecimal.ONE, new BigDecimal("1000"), "Envase abollado", 8L, FIXED_NOW));

        assertThat(createdId).isEqualTo(301L);
        verify(stockApi, never()).apply(any());
    }

    private static ProductSnapshot product(
            Long id, String sku, UnitOfMeasure uom, BigDecimal avgCost, boolean lotControlled) {
        return new ProductSnapshot(
                id, sku, "Producto " + sku, 1L, null, SaleType.UNIT, uom,
                new BigDecimal("3000"), avgCost, false, null, lotControlled, true,
                null, null, true, null, null);
    }

    private static ShrinkageView dummyView(
            Long id, ShrinkageStatus status, boolean autoApproved, boolean affectsStock) {
        OffsetDateTime odt = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        return new ShrinkageView(
                id,
                "L01-MR-000001",
                new ProductSummaryView(10L, "SKU-1", "Prod"),
                null,
                ShrinkageType.DAMAGED,
                new BigDecimal("1.000"),
                new BigDecimal("1000.0000"),
                new BigDecimal("1000.00"),
                "Motivo",
                null,
                new UserSummaryView(2L, "u2", "User 2"),
                new UserSummaryView(2L, "u2", "User 2"),
                status,
                autoApproved,
                affectsStock,
                null,
                autoApproved ? odt : null,
                null,
                odt,
                null,
                null);
    }
}
