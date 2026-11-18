package cl.minimarket.pos.inventory.application.transfer;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.LotAllocation;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.inventory.TransferDifferenceHandler;
import cl.minimarket.pos.inventory.TransferDifferenceHandler.TransferDifference;
import cl.minimarket.pos.inventory.application.transfer.TransferLookupRepository.LotLookupInfo;
import cl.minimarket.pos.inventory.application.transfer.TransferQueryService.StoreSummary;
import cl.minimarket.pos.inventory.application.transfer.TransferQueryService.TransferDetail;
import cl.minimarket.pos.inventory.application.transfer.TransferService.CreateTransferCommand;
import cl.minimarket.pos.inventory.application.transfer.TransferService.ReceiveLineInput;
import cl.minimarket.pos.inventory.application.transfer.TransferService.ReceiveTransferCommand;
import cl.minimarket.pos.inventory.application.transfer.TransferService.TransferLineInput;
import cl.minimarket.pos.inventory.application.transfer.TransferService.UpdateTransferCommand;
import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pruebas unitarias de reglas de negocio de {@link TransferService}. */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final long ORIGIN_STORE_ID = 1L;
    private static final long DEST_STORE_ID = 2L;
    private static final long OTHER_STORE_ID = 3L;
    private static final long USER_ID = 77L;
    private static final Instant NOW = Instant.parse("2026-12-01T15:30:00Z");

    @Mock StockTransferRepository repository;
    @Mock TransferLookupRepository lookup;
    @Mock TransferQueryService queryService;
    @Mock StockApi stockApi;
    @Mock ProductCatalogApi catalogApi;
    @Mock StoreApi storeApi;
    @Mock SequenceService sequenceService;
    @Mock TransferDifferenceHandler differenceHandler;
    @Mock AuditService auditService;
    @Mock BusinessClock clock;

    private TransferService service;

    @BeforeEach
    void setUp() {
        service =
                new TransferService(
                        repository,
                        lookup,
                        queryService,
                        stockApi,
                        catalogApi,
                        storeApi,
                        sequenceService,
                        differenceHandler,
                        auditService,
                        clock);
        TestingAuthenticationToken auth =
                new TestingAuthenticationToken(String.valueOf(USER_ID), null, List.of());
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
        lenient().when(clock.now()).thenReturn(NOW);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_rejectsSameOriginAndDestination() {
        try (var ignored = StoreContext.bind(new StoreContext(ORIGIN_STORE_ID, null))) {
            assertThatThrownBy(
                            () ->
                                    service.create(
                                            new CreateTransferCommand(
                                                    ORIGIN_STORE_ID,
                                                    null,
                                                    List.of(
                                                            new TransferLineInput(
                                                                    10L,
                                                                    new BigDecimal("2"),
                                                                    null)))))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.VALIDATION_ERROR);
        }
    }

    @Test
    void create_rejectsPackProductAndFractionalUnitQuantity() {
        when(storeApi.find(DEST_STORE_ID)).thenReturn(Optional.of(storeInfo(DEST_STORE_ID, true)));
        when(catalogApi.findByIds(any()))
                .thenReturn(
                        List.of(
                                productSnapshot(
                                        10L,
                                        "PACK-1",
                                        SaleType.PACK,
                                        UnitOfMeasure.UNIT,
                                        true,
                                        new BigDecimal("1000.0000")),
                                productSnapshot(
                                        11L,
                                        "UNIT-1",
                                        SaleType.UNIT,
                                        UnitOfMeasure.UNIT,
                                        true,
                                        new BigDecimal("500.0000"))));

        try (var ignored = StoreContext.bind(new StoreContext(ORIGIN_STORE_ID, null))) {
            assertThatThrownBy(
                            () ->
                                    service.create(
                                            new CreateTransferCommand(
                                                    DEST_STORE_ID,
                                                    null,
                                                    List.of(
                                                            new TransferLineInput(
                                                                    10L,
                                                                    new BigDecimal("1"),
                                                                    null),
                                                            new TransferLineInput(
                                                                    11L,
                                                                    new BigDecimal("1.5"),
                                                                    null)))))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.VALIDATION_ERROR);
        }
    }

    @Test
    void update_checksOptimisticVersion() {
        StockTransfer transfer = buildDraftTransfer(50L, 100L, 10L, new BigDecimal("5.000"));
        ReflectionTestUtils.setField(transfer, "version", 3L);
        when(repository.findWithLinesById(50L)).thenReturn(Optional.of(transfer));

        try (var ignored = StoreContext.bind(new StoreContext(ORIGIN_STORE_ID, null))) {
            assertThatThrownBy(
                            () ->
                                    service.update(
                                            50L,
                                            new UpdateTransferCommand(
                                                    DEST_STORE_ID,
                                                    "nota",
                                                    List.of(
                                                            new TransferLineInput(
                                                                    10L,
                                                                    new BigDecimal("4"),
                                                                    null)),
                                                    2L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    @Test
    void send_appliesTransferOut_updatesStatusAndAudits() {
        StockTransfer transfer = buildDraftTransfer(50L, 100L, 10L, new BigDecimal("10.000"));
        when(repository.findWithLinesById(50L)).thenReturn(Optional.of(transfer));
        when(storeApi.find(DEST_STORE_ID)).thenReturn(Optional.of(storeInfo(DEST_STORE_ID, true)));
        when(catalogApi.findByIds(any()))
                .thenReturn(
                        List.of(
                                productSnapshot(
                                        10L,
                                        "SKU-10",
                                        SaleType.UNIT,
                                        UnitOfMeasure.UNIT,
                                        true,
                                        new BigDecimal("450.0000"))));
        when(stockApi.apply(any()))
                .thenReturn(
                        List.of(
                                new StockMovementResult(
                                        901L,
                                        ORIGIN_STORE_ID,
                                        10L,
                                        new BigDecimal("-10.000"),
                                        new BigDecimal("5.000"),
                                        new BigDecimal("450.0000"),
                                        List.<LotAllocation>of())));
        when(queryService.loadDetailInternal(50L)).thenReturn(dummyDetail(50L, TransferStatus.SENT));

        try (var ignored = StoreContext.bind(new StoreContext(ORIGIN_STORE_ID, null))) {
            TransferDetail result = service.send(50L);
            assertThat(result.status()).isEqualTo(TransferStatus.SENT);
        }

        ArgumentCaptor<StockCommand> cmdCaptor = ArgumentCaptor.forClass(StockCommand.class);
        verify(stockApi).apply(cmdCaptor.capture());
        StockCommand sentCmd = cmdCaptor.getValue();
        assertThat(sentCmd.storeId()).isEqualTo(ORIGIN_STORE_ID);
        assertThat(sentCmd.movementType()).isEqualTo(StockMovementType.TRANSFER_OUT);
        assertThat(sentCmd.sourceType()).isEqualTo(StockSourceType.STOCK_TRANSFER);
        assertThat(sentCmd.sourceId()).isEqualTo(50L);
        assertThat(sentCmd.lines()).hasSize(1);
        assertThat(sentCmd.lines().getFirst().quantity()).isEqualByComparingTo("10.000");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SENT);
        assertThat(transfer.getSentAt()).isEqualTo(NOW);

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TRANSFER_SENT");
        assertThat(auditCaptor.getValue().entityType()).isEqualTo("stock_transfer");
    }

    @Test
    void send_withFefoMultiLot_splitsTransferLinePerAllocation() {
        StockTransfer transfer = buildDraftTransfer(50L, 100L, 10L, new BigDecimal("10.000"));
        when(repository.findWithLinesById(50L)).thenReturn(Optional.of(transfer));
        when(storeApi.find(DEST_STORE_ID)).thenReturn(Optional.of(storeInfo(DEST_STORE_ID, true)));
        when(catalogApi.findByIds(any()))
                .thenReturn(
                        List.of(
                                productSnapshot(
                                        10L,
                                        "SKU-10",
                                        SaleType.UNIT,
                                        UnitOfMeasure.UNIT,
                                        true,
                                        new BigDecimal("450.0000"))));
        when(stockApi.apply(any()))
                .thenReturn(
                        List.of(
                                new StockMovementResult(
                                        901L,
                                        ORIGIN_STORE_ID,
                                        10L,
                                        new BigDecimal("-10.000"),
                                        new BigDecimal("5.000"),
                                        new BigDecimal("450.0000"),
                                        List.of(
                                                new LotAllocation(101L, new BigDecimal("4.000")),
                                                new LotAllocation(102L, new BigDecimal("6.000"))))));
        LocalDate exp1 = LocalDate.of(2026, 12, 10);
        LocalDate exp2 = LocalDate.of(2026, 12, 20);
        when(lookup.findLotsInStore(ORIGIN_STORE_ID, Set.of(101L, 102L)))
                .thenReturn(
                        Map.of(
                                101L,
                                new LotLookupInfo(
                                        101L,
                                        ORIGIN_STORE_ID,
                                        10L,
                                        "L-001",
                                        exp1,
                                        BigDecimal.ZERO,
                                        "DEPLETED"),
                                102L,
                                new LotLookupInfo(
                                        102L,
                                        ORIGIN_STORE_ID,
                                        10L,
                                        "L-002",
                                        exp2,
                                        new BigDecimal("5.000"),
                                        "ACTIVE")));
        when(queryService.loadDetailInternal(50L)).thenReturn(dummyDetail(50L, TransferStatus.SENT));

        try (var ignored = StoreContext.bind(new StoreContext(ORIGIN_STORE_ID, null))) {
            TransferDetail result = service.send(50L);
            assertThat(result.status()).isEqualTo(TransferStatus.SENT);
        }

        assertThat(transfer.getLines()).hasSize(2);
        StockTransferLine firstLine = transfer.getLines().get(0);
        assertThat(firstLine.getId()).isEqualTo(100L);
        assertThat(firstLine.getOriginLotId()).isEqualTo(101L);
        assertThat(firstLine.getLotNumber()).isEqualTo("L-001");
        assertThat(firstLine.getExpiryDate()).isEqualTo(exp1);
        assertThat(firstLine.getQtySent()).isEqualByComparingTo("4.000");

        StockTransferLine secondLine = transfer.getLines().get(1);
        assertThat(secondLine.getOriginLotId()).isEqualTo(102L);
        assertThat(secondLine.getLotNumber()).isEqualTo("L-002");
        assertThat(secondLine.getExpiryDate()).isEqualTo(exp2);
        assertThat(secondLine.getQtySent()).isEqualByComparingTo("6.000");
        assertThat(secondLine.getUnitCost()).isEqualByComparingTo("450.0000");
    }

    @Test
    void send_withMixedExplicitAndFefoLinesForSameProduct_deductsExplicitBeforeSplittingFefo() {
        StockTransfer transfer = buildDraftTransfer(50L, 100L, 10L, new BigDecimal("5.000"));
        LocalDate exp1 = LocalDate.of(2026, 12, 10);
        LocalDate exp2 = LocalDate.of(2026, 12, 20);
        StockTransferLine explicitLine =
                StockTransferLine.create(
                        10L, 101L, "L-001", exp1, new BigDecimal("3.000"), new BigDecimal("450.0000"));
        ReflectionTestUtils.setField(explicitLine, "id", 101L);
        transfer.addLine(explicitLine);

        when(repository.findWithLinesById(50L)).thenReturn(Optional.of(transfer));
        when(storeApi.find(DEST_STORE_ID)).thenReturn(Optional.of(storeInfo(DEST_STORE_ID, true)));
        when(catalogApi.findByIds(any()))
                .thenReturn(
                        List.of(
                                productSnapshot(
                                        10L,
                                        "SKU-10",
                                        SaleType.UNIT,
                                        UnitOfMeasure.UNIT,
                                        true,
                                        new BigDecimal("450.0000"))));
        when(stockApi.apply(any()))
                .thenReturn(
                        List.of(
                                new StockMovementResult(
                                        901L,
                                        ORIGIN_STORE_ID,
                                        10L,
                                        new BigDecimal("-8.000"),
                                        new BigDecimal("2.000"),
                                        new BigDecimal("450.0000"),
                                        List.of(
                                                new LotAllocation(101L, new BigDecimal("5.000")),
                                                new LotAllocation(102L, new BigDecimal("3.000"))))));
        when(lookup.findLotsInStore(ORIGIN_STORE_ID, Set.of(101L, 102L)))
                .thenReturn(
                        Map.of(
                                101L,
                                new LotLookupInfo(
                                        101L,
                                        ORIGIN_STORE_ID,
                                        10L,
                                        "L-001",
                                        exp1,
                                        BigDecimal.ZERO,
                                        "DEPLETED"),
                                102L,
                                new LotLookupInfo(
                                        102L,
                                        ORIGIN_STORE_ID,
                                        10L,
                                        "L-002",
                                        exp2,
                                        new BigDecimal("2.000"),
                                        "ACTIVE")));
        when(queryService.loadDetailInternal(50L)).thenReturn(dummyDetail(50L, TransferStatus.SENT));

        try (var ignored = StoreContext.bind(new StoreContext(ORIGIN_STORE_ID, null))) {
            service.send(50L);
        }

        assertThat(transfer.getLines()).hasSize(3);
        // Línea FEFO original (id 100): toma los 2 restantes del lote 101
        assertThat(transfer.getLines().get(0).getId()).isEqualTo(100L);
        assertThat(transfer.getLines().get(0).getOriginLotId()).isEqualTo(101L);
        assertThat(transfer.getLines().get(0).getQtySent()).isEqualByComparingTo("2.000");
        // Línea explícita (id 101): conserva sus 3 del lote 101
        assertThat(transfer.getLines().get(1).getId()).isEqualTo(101L);
        assertThat(transfer.getLines().get(1).getOriginLotId()).isEqualTo(101L);
        assertThat(transfer.getLines().get(1).getQtySent()).isEqualByComparingTo("3.000");
        // Línea desdoblada para el lote 102: 3 unidades
        assertThat(transfer.getLines().get(2).getOriginLotId()).isEqualTo(102L);
        assertThat(transfer.getLines().get(2).getLotNumber()).isEqualTo("L-002");
        assertThat(transfer.getLines().get(2).getQtySent()).isEqualByComparingTo("3.000");
    }

    @Test
    void receive_enforcesDestinationStoreAccess() {
        StockTransfer sent = buildDraftTransfer(50L, 100L, 10L, new BigDecimal("10.000"));
        sent.markSent(USER_ID, NOW);
        when(repository.findWithLinesById(50L)).thenReturn(Optional.of(sent));

        ReceiveTransferCommand cmd =
                new ReceiveTransferCommand(
                        List.of(new ReceiveLineInput(100L, new BigDecimal("10"), null)));

        // Desde local de origen -> 403 ACCESS_DENIED
        try (var ignored = StoreContext.bind(new StoreContext(ORIGIN_STORE_ID, null))) {
            assertThatThrownBy(() -> service.receive(50L, cmd))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.ACCESS_DENIED);
        }

        // Desde un tercer local -> 404 NOT_FOUND
        try (var ignored = StoreContext.bind(new StoreContext(OTHER_STORE_ID, null))) {
            assertThatThrownBy(() -> service.receive(50L, cmd))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Test
    void receive_withPartialQuantity_appliesTransferInAndInvokesDifferenceHandler() {
        StockTransfer sent = buildDraftTransfer(50L, 100L, 10L, new BigDecimal("10.000"));
        sent.markSent(USER_ID, NOW.minusSeconds(60));
        when(repository.findWithLinesById(50L)).thenReturn(Optional.of(sent));
        when(catalogApi.findByIds(any()))
                .thenReturn(
                        List.of(
                                productSnapshot(
                                        10L,
                                        "SKU-10",
                                        SaleType.UNIT,
                                        UnitOfMeasure.UNIT,
                                        true,
                                        new BigDecimal("450.0000"))));
        when(queryService.loadDetailInternal(50L))
                .thenReturn(dummyDetail(50L, TransferStatus.RECEIVED));

        try (var ignored = StoreContext.bind(new StoreContext(DEST_STORE_ID, null))) {
            service.receive(
                    50L,
                    new ReceiveTransferCommand(
                            List.of(
                                    new ReceiveLineInput(
                                            100L, new BigDecimal("9"), "Falta 1 caja"))));
        }

        ArgumentCaptor<StockCommand> cmdCaptor = ArgumentCaptor.forClass(StockCommand.class);
        verify(stockApi).apply(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().storeId()).isEqualTo(DEST_STORE_ID);
        assertThat(cmdCaptor.getValue().movementType()).isEqualTo(StockMovementType.TRANSFER_IN);
        assertThat(cmdCaptor.getValue().lines().getFirst().quantity())
                .isEqualByComparingTo("9.000");

        ArgumentCaptor<TransferDifference> diffCaptor =
                ArgumentCaptor.forClass(TransferDifference.class);
        verify(differenceHandler).handle(diffCaptor.capture());
        TransferDifference diff = diffCaptor.getValue();
        assertThat(diff.originStoreId()).isEqualTo(ORIGIN_STORE_ID);
        assertThat(diff.destinationStoreId()).isEqualTo(DEST_STORE_ID);
        assertThat(diff.differenceQuantity()).isEqualByComparingTo("1.000");
        assertThat(diff.reason()).isEqualTo("Falta 1 caja");

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TRANSFER_RECEIVED");
    }

    @Test
    void receive_withZeroQuantity_skipsStockApplyAndNotifiesFullDifference() {
        StockTransfer sent = buildDraftTransfer(50L, 100L, 10L, new BigDecimal("4.000"));
        sent.markSent(USER_ID, NOW.minusSeconds(60));
        when(repository.findWithLinesById(50L)).thenReturn(Optional.of(sent));
        when(catalogApi.findByIds(any()))
                .thenReturn(
                        List.of(
                                productSnapshot(
                                        10L,
                                        "SKU-10",
                                        SaleType.UNIT,
                                        UnitOfMeasure.UNIT,
                                        true,
                                        new BigDecimal("450.0000"))));
        when(queryService.loadDetailInternal(50L))
                .thenReturn(dummyDetail(50L, TransferStatus.RECEIVED));

        try (var ignored = StoreContext.bind(new StoreContext(DEST_STORE_ID, null))) {
            service.receive(
                    50L,
                    new ReceiveTransferCommand(
                            List.of(
                                    new ReceiveLineInput(
                                            100L, BigDecimal.ZERO, "Mercadería extraviada"))));
        }

        verify(stockApi, never()).apply(any());
        ArgumentCaptor<TransferDifference> diffCaptor =
                ArgumentCaptor.forClass(TransferDifference.class);
        verify(differenceHandler).handle(diffCaptor.capture());
        assertThat(diffCaptor.getValue().differenceQuantity()).isEqualByComparingTo("4.000");
    }

    @Test
    void cancel_transitionsDraftToCancelled_andRejectsSent() {
        StockTransfer draft = buildDraftTransfer(50L, 100L, 10L, new BigDecimal("5.000"));
        when(repository.findWithLinesById(50L)).thenReturn(Optional.of(draft));

        try (var ignored = StoreContext.bind(new StoreContext(ORIGIN_STORE_ID, null))) {
            service.cancel(50L, "Error de digitación");
        }
        assertThat(draft.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(draft.getNotes()).contains("Error de digitación");
        verify(auditService).record(any());

        try (var ignored = StoreContext.bind(new StoreContext(ORIGIN_STORE_ID, null))) {
            assertThatThrownBy(() -> service.cancel(50L, "Otro motivo"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private StockTransfer buildDraftTransfer(
            Long transferId, Long lineId, Long productId, BigDecimal qtySent) {
        StockTransfer transfer =
                StockTransfer.draft("L01-TR-00000001", ORIGIN_STORE_ID, DEST_STORE_ID, "Nota");
        ReflectionTestUtils.setField(transfer, "id", transferId);
        ReflectionTestUtils.setField(transfer, "version", 0L);
        StockTransferLine line =
                StockTransferLine.create(
                        productId,
                        null,
                        null,
                        null,
                        qtySent,
                        new BigDecimal("450.0000"));
        ReflectionTestUtils.setField(line, "id", lineId);
        transfer.addLine(line);
        return transfer;
    }

    private StoreInfo storeInfo(long id, boolean active) {
        return new StoreInfo(
                id, "L0" + id, "Local " + id, "Calle " + id, "Santiago", "Santiago", null, null, active);
    }

    private ProductSnapshot productSnapshot(
            long id,
            String sku,
            SaleType saleType,
            UnitOfMeasure uom,
            boolean active,
            BigDecimal avgCost) {
        return new ProductSnapshot(
                id,
                sku,
                "Producto " + sku,
                1L,
                null,
                saleType,
                uom,
                new BigDecimal("1000"),
                avgCost,
                false,
                null,
                false,
                active,
                null,
                null,
                true,
                null);
    }

    private TransferDetail dummyDetail(long id, TransferStatus status) {
        return new TransferDetail(
                id,
                "L01-TR-00000001",
                new StoreSummary(ORIGIN_STORE_ID, "L01", "Local 1"),
                new StoreSummary(DEST_STORE_ID, "L02", "Local 2"),
                status,
                NOW,
                null,
                null,
                null,
                null,
                1L,
                List.of());
    }
}
