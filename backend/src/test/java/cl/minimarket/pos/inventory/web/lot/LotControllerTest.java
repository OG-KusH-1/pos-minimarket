package cl.minimarket.pos.inventory.web.lot;

import cl.minimarket.pos.inventory.application.lot.LotQueryService;
import cl.minimarket.pos.inventory.application.lot.LotQueryService.FefoSuggestion;
import cl.minimarket.pos.inventory.application.lot.LotQueryService.FefoSuggestionView;
import cl.minimarket.pos.inventory.application.lot.LotQueryService.LotExpiryAlertView;
import cl.minimarket.pos.inventory.application.lot.LotQueryService.LotView;
import cl.minimarket.pos.inventory.application.lot.LotUpdateService;
import cl.minimarket.pos.inventory.web.stock.ExpiryAlertResponse;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.web.PageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link LotController} y {@link ExpiryAlertController}.
 */
@ExtendWith(MockitoExtension.class)
class LotControllerTest {

    @Mock
    LotQueryService lotQueryService;

    @Mock
    LotUpdateService lotUpdateService;

    private LotController lotController;
    private ExpiryAlertController expiryAlertController;
    private StoreContext.Scope storeScope;

    @BeforeEach
    void setUp() {
        lotController = new LotController(lotQueryService, lotUpdateService);
        expiryAlertController = new ExpiryAlertController(lotQueryService);
        storeScope = StoreContext.bind(new StoreContext(10L, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (storeScope != null) {
            storeScope.close();
        }
    }

    @Test
    void list_mapsWithStockToActiveOrDepleted_andRejectsConflictingStatus() {
        when(lotQueryService.findLots(eq(10L), eq(5L), eq("ACTIVE"), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        PageResponse<LotResponse> response = lotController.list(
                5L, null, true, null, null, 0, 20, List.of("name,asc"), null);
        assertThat(response.content()).isEmpty();
        verify(lotQueryService).findLots(eq(10L), eq(5L), eq("ACTIVE"), isNull(), isNull(), any(Pageable.class));

        assertThatThrownBy(() -> lotController.list(5L, null, true, "DEPLETED", null, 0, 20, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void suggestFefo_delegatesDirectlyToLotQueryService_forFullAndPartialSuggestions() {
        LocalDate exp1 = LocalDate.of(2027, 2, 1);
        LocalDate exp2 = LocalDate.of(2027, 3, 1);
        when(lotQueryService.suggestFefo(10L, 99L, new BigDecimal("5")))
                .thenReturn(new FefoSuggestion(
                        99L,
                        new BigDecimal("5.000"),
                        List.of(new FefoSuggestionView(
                                1L, "L1", exp1, new BigDecimal("6.000"), new BigDecimal("5.000"))),
                        new BigDecimal("0.000")));

        FefoSuggestionResponse covered = lotController.suggestFefo(99L, new BigDecimal("5"));
        assertThat(covered.requestedQty()).isEqualByComparingTo("5.000");
        assertThat(covered.shortage()).isEqualByComparingTo("0.000");
        assertThat(covered.allocations()).hasSize(1);

        when(lotQueryService.suggestFefo(10L, 99L, new BigDecimal("10")))
                .thenReturn(new FefoSuggestion(
                        99L,
                        new BigDecimal("10.000"),
                        List.of(
                                new FefoSuggestionView(1L, "L1", exp1, new BigDecimal("4.000"), new BigDecimal("4.000")),
                                new FefoSuggestionView(2L, "L2", exp2, new BigDecimal("2.500"), new BigDecimal("2.500"))),
                        new BigDecimal("3.500")));

        FefoSuggestionResponse partial = lotController.suggestFefo(99L, new BigDecimal("10"));
        assertThat(partial.requestedQty()).isEqualByComparingTo("10.000");
        assertThat(partial.allocations()).hasSize(2);
        assertThat(partial.allocations().get(0).quantity()).isEqualByComparingTo("4.000");
        assertThat(partial.allocations().get(1).quantity()).isEqualByComparingTo("2.500");
        assertThat(partial.shortage()).isEqualByComparingTo("3.500");
        verifyNoMoreInteractions(lotQueryService);
    }

    @Test
    void update_delegatesStoreIdAndReasonToUpdateService() {
        LocalDate exp = LocalDate.of(2027, 4, 1);
        LotView updated = lotView(77L, 10L, "L-NEW", exp, new BigDecimal("3.000"));
        when(lotUpdateService.updateLot(10L, 77L, "L-NEW", exp, "Corrección de etiqueta", 0L))
                .thenReturn(updated);

        LotUpdateRequest req = new LotUpdateRequest("L-NEW", exp, "Corrección de etiqueta", 0L);
        LotResponse response = lotController.update(77L, req);

        assertThat(response.id()).isEqualTo(77L);
        assertThat(response.lotNumber()).isEqualTo("L-NEW");
        verify(lotUpdateService).updateLot(10L, 77L, "L-NEW", exp, "Corrección de etiqueta", 0L);
        verifyNoInteractions(lotQueryService);
    }

    @Test
    void expiryAlerts_delegatesPageableSortingAndCostPermission() {
        LocalDate exp = LocalDate.of(2026, 12, 1);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "1",
                        "N/A",
                        List.of(new SimpleGrantedAuthority(Permissions.INVENTORY_EXPIRY_READ))));
        when(lotQueryService.findExpiryAlerts(eq(10L), eq(15), eq(false), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(new LotExpiryAlertView(
                                3L, 10L, "L01", "Central", 103L, "SKU3", "Prod 3", "A3",
                                exp.plusDays(2), new BigDecimal("1.000"), 5, false, null)),
                        PageRequest.of(1, 2, Sort.by(Sort.Order.asc("expiryDate"), Sort.Order.asc("lotId"))),
                        3));

        PageResponse<ExpiryAlertResponse> page1 = expiryAlertController.list(15, 1, 2, null, null);
        assertThat(page1.totalElements()).isEqualTo(3);
        assertThat(page1.totalPages()).isEqualTo(2);
        assertThat(page1.content()).hasSize(1);
        assertThat(page1.content().getFirst().lotId()).isEqualTo(3L);
        assertThat(page1.content().getFirst().valuation()).isNull();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "1",
                        "N/A",
                        List.of(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_EXPIRY_READ),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))));
        when(lotQueryService.findExpiryAlerts(eq(10L), eq(15), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(new LotExpiryAlertView(
                                1L, 10L, "L01", "Central", 101L, "SKU1", "Prod 1", "A1",
                                exp, new BigDecimal("5.000"), 3, false, new BigDecimal("4000.00"))),
                        PageRequest.of(0, 20, Sort.by(Sort.Order.desc("expiryDate"))),
                        1));

        PageResponse<ExpiryAlertResponse> withCost =
                expiryAlertController.list(15, 0, 20, List.of("daysToExpiry,desc"), null);
        assertThat(withCost.content()).hasSize(1);
        assertThat(withCost.content().getFirst().valuation()).isEqualByComparingTo("4000.00");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(lotQueryService).findExpiryAlerts(eq(10L), eq(15), eq(true), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("expiryDate"))
                .isEqualTo(Sort.Order.desc("expiryDate"));
    }

    private static LotView lotView(Long id, Long storeId, String lotNumber, LocalDate expiry, BigDecimal qty) {
        return new LotView(
                id,
                storeId,
                "L01",
                "Local",
                99L,
                "SKU-99",
                "Producto",
                "KG",
                lotNumber,
                expiry,
                qty,
                new BigDecimal("1000.0000"),
                OffsetDateTime.now(),
                qty.signum() > 0 ? "ACTIVE" : "DEPLETED",
                expiry != null ? 10L : null,
                false,
                0L);
    }
}
