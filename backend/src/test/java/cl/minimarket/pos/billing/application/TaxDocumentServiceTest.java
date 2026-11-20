package cl.minimarket.pos.billing.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.billing.ElectronicInvoiceProvider;
import cl.minimarket.pos.billing.IssueResult;
import cl.minimarket.pos.billing.TaxDocumentRequest;
import cl.minimarket.pos.billing.TaxDocumentStatus;
import cl.minimarket.pos.billing.TaxDocumentType;
import cl.minimarket.pos.billing.domain.TaxDocument;
import cl.minimarket.pos.billing.domain.TaxDocumentRepository;
import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.sales.SaleCompletedEvent;
import cl.minimarket.pos.sales.SaleReturnedEvent;
import cl.minimarket.pos.shared.context.StoreContext;
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
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link TaxDocumentService}.
 */
@ExtendWith(MockitoExtension.class)
class TaxDocumentServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-18T15:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("America/Santiago");

    @Mock
    TaxDocumentRepository repository;
    @Mock
    ElectronicInvoiceProvider provider;
    @Mock
    SequenceService sequenceService;
    @Mock
    StoreApi storeApi;
    @Mock
    AuditService auditService;

    private TaxDocumentService service;
    private BusinessClock clock;

    @BeforeEach
    void setUp() {
        clock = BusinessClock.fixed(FIXED_NOW, ZONE);
        service = new TaxDocumentService(
                repository,
                provider,
                sequenceService,
                storeApi,
                auditService,
                clock,
                JsonMapper.builder().build()
        );
    }

    @Test
    void issueForSaleEmitsBoleta39WithCorrelativeFolio() {
        when(repository.findBySaleId(10L)).thenReturn(Optional.empty());
        when(sequenceService.next(SequenceType.FOLIO_BOLETA, null)).thenReturn("BOL-00000042");
        when(storeApi.find(1L)).thenReturn(Optional.of(new StoreInfo(
                1L, "L01", "Casa Matriz", "Av. Principal 100", "Santiago", "Santiago", null, "123", true)));
        when(provider.providerCode()).thenReturn("SIMULATED");
        when(provider.issue(any())).thenReturn(new IssueResult(
                IssueResult.Status.ISSUED, "SIM-39-42", "TIMBRE-39-42", null, "OK"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaleCompletedEvent event = new SaleCompletedEvent(
                10L, "L01-V-00000001", 1L, 2L, 3L, 4L, null,
                FIXED_NOW, LocalDate.of(2026, 11, 18),
                new BigDecimal("1190"), BigDecimal.ZERO, new BigDecimal("1190"),
                new BigDecimal("1000"), new BigDecimal("190"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("1190"),
                List.of(new SaleCompletedEvent.Line(
                        1, 100L, "SKU-1", "Café", BigDecimal.ONE,
                        new BigDecimal("1190"), new BigDecimal("1190"),
                        new BigDecimal("1000"), new BigDecimal("190"), BigDecimal.ZERO))
        );

        TaxDocument doc = service.issueForSale(event);

        assertThat(doc.getDocumentType()).isEqualTo(TaxDocumentType.BOLETA);
        assertThat(doc.getSiiTypeCode()).isEqualTo((short) 39);
        assertThat(doc.getFolio()).isEqualTo(42L);
        assertThat(doc.getStatus()).isEqualTo(TaxDocumentStatus.ISSUED);
        assertThat(doc.getProviderTrackId()).isEqualTo("SIM-39-42");
        assertThat(doc.stampText()).isEqualTo("TIMBRE-39-42");
        assertThat(doc.getAttempts()).isEqualTo(1);
        assertThat(doc.getIssuedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void issueForSaleEmitsBoletaExenta41WhenPurelyExempt() {
        when(repository.findBySaleId(11L)).thenReturn(Optional.empty());
        when(sequenceService.next(SequenceType.FOLIO_BOLETA, null)).thenReturn("BOL-00000043");
        when(storeApi.find(1L)).thenReturn(Optional.empty());
        when(provider.providerCode()).thenReturn("SIMULATED");
        when(provider.issue(any())).thenReturn(new IssueResult(
                IssueResult.Status.ISSUED, "SIM-41-43", "TIMBRE-41-43", null, "OK"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaleCompletedEvent event = new SaleCompletedEvent(
                11L, "L01-V-00000002", 1L, 2L, 3L, 4L, null,
                FIXED_NOW, LocalDate.of(2026, 11, 18),
                new BigDecimal("5000"), BigDecimal.ZERO, new BigDecimal("5000"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000"),
                BigDecimal.ZERO, new BigDecimal("5000"),
                List.of()
        );

        TaxDocument doc = service.issueForSale(event);

        assertThat(doc.getDocumentType()).isEqualTo(TaxDocumentType.BOLETA_EXENTA);
        assertThat(doc.getSiiTypeCode()).isEqualTo((short) 41);
        assertThat(doc.getFolio()).isEqualTo(43L);
        assertThat(doc.getExemptAmount()).isEqualByComparingTo("5000");
    }

    @Test
    void issueForSaleReturnEmitsCreditNote61ReferencingOriginalBoleta() {
        TaxDocument originalBoleta = new TaxDocument(
                1L, TaxDocumentType.BOLETA, 42L, 10L, null, null, "SIMULATED",
                new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("190"), new BigDecimal("1190"),
                null
        );
        ReflectionTestUtils.setField(originalBoleta, "id", 99L);
        originalBoleta.markIssued("SIM-39-42", "TIMBRE-39-42", FIXED_NOW);

        when(repository.findBySaleReturnId(50L)).thenReturn(Optional.empty());
        when(repository.findBySaleId(10L)).thenReturn(Optional.of(originalBoleta));
        when(sequenceService.next(SequenceType.FOLIO_CREDIT_NOTE, null)).thenReturn("NC-00000007");
        when(storeApi.find(1L)).thenReturn(Optional.empty());
        when(provider.providerCode()).thenReturn("SIMULATED");
        when(provider.issue(any())).thenReturn(new IssueResult(
                IssueResult.Status.ISSUED, "SIM-61-7", "TIMBRE-61-7", null, "OK"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaleReturnedEvent event = new SaleReturnedEvent(
                50L, "L01-D-00000001", 10L, "L01-V-00000001", "RETURN",
                1L, 2L, 3L, 4L, FIXED_NOW, LocalDate.of(2026, 11, 18),
                new BigDecimal("1190"),
                List.of(new SaleReturnedEvent.Line(1L, 100L, "SKU-1", "Café", BigDecimal.ONE, new BigDecimal("1190")))
        );

        TaxDocument creditNote = service.issueForSaleReturn(event);

        assertThat(creditNote.getDocumentType()).isEqualTo(TaxDocumentType.CREDIT_NOTE);
        assertThat(creditNote.getSiiTypeCode()).isEqualTo((short) 61);
        assertThat(creditNote.getFolio()).isEqualTo(7L);
        assertThat(creditNote.getReferencedDocumentId()).isEqualTo(99L);
        assertThat(creditNote.getNetAmount()).isEqualByComparingTo("1000");
        assertThat(creditNote.getTaxAmount()).isEqualByComparingTo("190");
        assertThat(creditNote.getTotalAmount()).isEqualByComparingTo("1190");
        assertThat(creditNote.getStatus()).isEqualTo(TaxDocumentStatus.ISSUED);
    }

    @Test
    void retryAfterProviderErrorReusesFolioAndRecordsAudit() {
        when(repository.findBySaleId(12L)).thenReturn(Optional.empty());
        when(sequenceService.next(SequenceType.FOLIO_BOLETA, null)).thenReturn("BOL-00000044");
        when(storeApi.find(1L)).thenReturn(Optional.empty());
        when(provider.providerCode()).thenReturn("SIMULATED");
        when(provider.issue(any()))
                .thenThrow(new IllegalStateException("SII no disponible"))
                .thenReturn(new IssueResult(
                        IssueResult.Status.ISSUED, "SIM-39-44", "TIMBRE-39-44", null, "OK"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaleCompletedEvent event = new SaleCompletedEvent(
                12L, "L01-V-00000003", 1L, 2L, 3L, 4L, null,
                FIXED_NOW, LocalDate.of(2026, 11, 18),
                new BigDecimal("1190"), BigDecimal.ZERO, new BigDecimal("1190"),
                new BigDecimal("1000"), new BigDecimal("190"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("1190"), List.of()
        );

        TaxDocument failedDoc = service.issueForSale(event);
        ReflectionTestUtils.setField(failedDoc, "id", 200L);
        assertThat(failedDoc.getStatus()).isEqualTo(TaxDocumentStatus.ERROR);
        assertThat(failedDoc.getAttempts()).isEqualTo(1);
        assertThat(failedDoc.getLastError()).contains("SII no disponible");

        when(repository.findByIdAndStoreId(200L, 1L)).thenReturn(Optional.of(failedDoc));

        try (var scope = StoreContext.bind(new StoreContext(1L, null))) {
            TaxDocument retried = service.retry(200L);

            assertThat(retried.getFolio()).isEqualTo(44L);
            assertThat(retried.getStatus()).isEqualTo(TaxDocumentStatus.ISSUED);
            assertThat(retried.getAttempts()).isEqualTo(2);
            assertThat(retried.getLastError()).isNull();
            assertThat(retried.getProviderTrackId()).isEqualTo("SIM-39-44");

            ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
            verify(auditService).record(auditCaptor.capture());
            assertThat(auditCaptor.getValue().action()).isEqualTo("TAX_DOCUMENT_RETRIED");
            assertThat(auditCaptor.getValue().entityId()).isEqualTo("200");

            assertThatThrownBy(() -> service.retry(200L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }
}
