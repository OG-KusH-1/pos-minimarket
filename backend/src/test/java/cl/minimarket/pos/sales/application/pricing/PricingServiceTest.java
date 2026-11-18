package cl.minimarket.pos.sales.application.pricing;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.ProductCatalogApi.TaxRate;
import cl.minimarket.pos.catalog.RestrictedType;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.promotions.application.NoopPromotionEngine;
import cl.minimarket.pos.sales.application.pricing.PricingModels.DiscountSpec;
import cl.minimarket.pos.sales.application.pricing.PricingModels.LineInput;
import cl.minimarket.pos.sales.application.pricing.PricingModels.PaymentInput;
import cl.minimarket.pos.sales.application.pricing.PricingModels.QuoteCommand;
import cl.minimarket.pos.sales.application.pricing.PricingModels.QuoteResult;
import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.RoleLimit;
import cl.minimarket.pos.security.RoleLimitApi;
import cl.minimarket.pos.security.RoleLimitCode;
import cl.minimarket.pos.security.SupervisorOverrideApi;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.CashRounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link PricingService}: pesables (HU-POS-19), redondeo de efectivo
 * (HU-POS-08 / F.4), prorrateo, impuestos, exentos y {@code requiredOverrides} con límites
 * fail-closed (T32).
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    private static final Long STORE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final TaxRate IVA = new TaxRate(1L, "IVA", "VAT", new BigDecimal("19.0000"));

    @Mock
    ProductCatalogApi productCatalogApi;
    @Mock
    RoleLimitApi roleLimitApi;
    @Mock
    SupervisorOverrideApi supervisorOverrideApi;
    @Mock
    SettingsApi settingsApi;

    PricingService pricingService;

    @BeforeEach
    void setUp() {
        BusinessClock fixedClock = BusinessClock.fixed(
                Instant.parse("2026-11-13T15:00:00Z"),
                ZoneId.of("America/Santiago")
        );
        pricingService = new PricingService(
                productCatalogApi,
                new NoopPromotionEngine(),
                roleLimitApi,
                supervisorOverrideApi,
                settingsApi,
                fixedClock
        );

        lenient().when(settingsApi.get(CashRounding.SETTING_KEY, STORE_ID, Boolean.class))
                .thenReturn(Boolean.TRUE);
        lenient().when(supervisorOverrideApi.canonicalContextHash(any()))
                .thenAnswer(inv -> sha256(java.util.Objects.toString(inv.getArgument(0))));
        lenient().when(roleLimitApi.limitFor(any(), any())).thenReturn(RoleLimit.zero());
    }

    @Test
    void weighableProductRoundsHalfUpPerHuPos19() {
        // HU-POS-19: 0,750 kg a $8.990/kg -> round(6742.5) = $6.743
        ProductSnapshot queso = product(100L, "QUESO", "Queso Mantecoso", SaleType.WEIGHT, UnitOfMeasure.KG, 8990, false, null, true);
        when(productCatalogApi.findByIds(anyCollection())).thenReturn(List.of(queso));
        when(productCatalogApi.taxesFor(100L)).thenReturn(List.of(IVA));

        QuoteResult result = pricingService.quote(new QuoteCommand(
                STORE_ID,
                USER_ID,
                true,
                List.of(new LineInput(100L, new BigDecimal("0.750"), null, null, null)),
                null,
                null,
                List.of()
        ));

        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().getFirst().lineSubtotalGross()).isEqualByComparingTo("6743");
        assertThat(result.lines().getFirst().lineTotalGross()).isEqualByComparingTo("6743");
        assertThat(result.totalGross()).isEqualByComparingTo("6743");
        assertThat(result.netTotal().add(result.taxTotal()).add(result.exemptTotal()))
                .isEqualByComparingTo("6743");
    }

    @Test
    void fractionalQuantityOnUnitProductWithoutEmbeddedPriceThrowsValidationError() {
        ProductSnapshot bebida = product(101L, "BEB1", "Bebida 1L", SaleType.UNIT, UnitOfMeasure.UNIT, 1500, false, null, true);
        when(productCatalogApi.findByIds(anyCollection())).thenReturn(List.of(bebida));

        assertThatThrownBy(() -> pricingService.quote(new QuoteCommand(
                STORE_ID,
                USER_ID,
                true,
                List.of(new LineInput(101L, new BigDecimal("0.500"), null, null, null)),
                null,
                null,
                List.of()
        )))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void manualDiscountOnDisallowedProductThrowsValidationError() {
        ProductSnapshot cigarros = product(102L, "CIG1", "Cigarros", SaleType.UNIT, UnitOfMeasure.UNIT, 4500, false, RestrictedType.TOBACCO, false);
        when(productCatalogApi.findByIds(anyCollection())).thenReturn(List.of(cigarros));

        assertThatThrownBy(() -> pricingService.quote(new QuoteCommand(
                STORE_ID,
                USER_ID,
                true,
                List.of(new LineInput(102L, BigDecimal.ONE, null, null, new DiscountSpec(DiscountKind.PERCENT, new BigDecimal("5"), "promo"))),
                null,
                null,
                List.of()
        )))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void mixedPaymentAppliesCashRoundingOnlyToCashPortionAndComputesChange() {
        // API.md F.4 flujo 1: total 12.347, DEBIT 5.000 + CASH tendered 10.000 -> +3 -> 12.350, vuelto 2.650
        ProductSnapshot item = product(103L, "ITEM", "Producto", SaleType.UNIT, UnitOfMeasure.UNIT, 12347, false, RestrictedType.ALCOHOL, true);
        when(productCatalogApi.findByIds(anyCollection())).thenReturn(List.of(item));
        when(productCatalogApi.taxesFor(103L)).thenReturn(List.of(IVA));

        QuoteResult result = pricingService.quote(new QuoteCommand(
                STORE_ID,
                USER_ID,
                true,
                List.of(new LineInput(103L, BigDecimal.ONE, null, null, null)),
                null,
                null,
                List.of(
                        new PaymentInput(PaymentMethod.DEBIT, new BigDecimal("5000"), null),
                        new PaymentInput(PaymentMethod.CASH, null, new BigDecimal("10000"))
                )
        ));

        assertThat(result.totalGross()).isEqualByComparingTo("12347");
        assertThat(result.roundingAdjustment()).isEqualByComparingTo("3");
        assertThat(result.totalPayable()).isEqualByComparingTo("12350");
        assertThat(result.changeTotal()).isEqualByComparingTo("2650");
        assertThat(result.requiresAgeConfirmation()).isTrue();
    }

    @Test
    void prorationAndExemptTaxesSumExactlyToTotalGross() {
        ProductSnapshot p1 = product(104L, "P1", "Afecto", SaleType.UNIT, UnitOfMeasure.UNIT, 1000, false, null, true);
        ProductSnapshot p2 = product(105L, "P2", "Exento", SaleType.UNIT, UnitOfMeasure.UNIT, 2000, true, null, true);
        when(productCatalogApi.findByIds(anyCollection())).thenReturn(List.of(p1, p2));
        when(productCatalogApi.taxesFor(104L)).thenReturn(List.of(IVA));
        when(productCatalogApi.taxesFor(105L)).thenReturn(List.of());
        when(roleLimitApi.limitFor(USER_ID, RoleLimitCode.DISCOUNT_TOTAL_MAX_PCT))
                .thenReturn(RoleLimit.unlimited());
        when(roleLimitApi.limitFor(USER_ID, RoleLimitCode.DISCOUNT_TOTAL_MAX_AMOUNT))
                .thenReturn(RoleLimit.unlimited());

        QuoteResult result = pricingService.quote(new QuoteCommand(
                STORE_ID,
                USER_ID,
                true,
                List.of(
                        new LineInput(104L, BigDecimal.ONE, null, null, null),
                        new LineInput(105L, BigDecimal.ONE, null, null, null)
                ),
                new DiscountSpec(DiscountKind.AMOUNT, new BigDecimal("100"), "ajuste"),
                null,
                List.of()
        ));

        // 1000 y 2000 -> prorrateo de 100 = 33 y 67
        assertThat(result.lines().get(0).proratedDiscount()).isEqualByComparingTo("33");
        assertThat(result.lines().get(1).proratedDiscount()).isEqualByComparingTo("67");
        assertThat(result.subtotalGross()).isEqualByComparingTo("3000");
        assertThat(result.discountTotal()).isEqualByComparingTo("100");
        assertThat(result.totalGross()).isEqualByComparingTo("2900");
        assertThat(result.exemptTotal()).isEqualByComparingTo("1933");
        assertThat(result.netTotal().add(result.taxTotal()).add(result.exemptTotal()))
                .isEqualByComparingTo("2900");
    }

    @Test
    void requiredOverridesEvaluateLimitsFailClosedAndIncludeCartHash() {
        ProductSnapshot p1 = product(106L, "P6", "Producto", SaleType.UNIT, UnitOfMeasure.UNIT, 10000, false, null, true);
        when(productCatalogApi.findByIds(anyCollection())).thenReturn(List.of(p1));
        when(productCatalogApi.taxesFor(106L)).thenReturn(List.of(IVA));
        when(roleLimitApi.limitFor(eq(USER_ID), eq(RoleLimitCode.DISCOUNT_ITEM_MAX_PCT)))
                .thenReturn(RoleLimit.limited(new BigDecimal("5")));
        when(roleLimitApi.limitFor(eq(USER_ID), eq(RoleLimitCode.DISCOUNT_TOTAL_MAX_PCT)))
                .thenReturn(RoleLimit.limited(new BigDecimal("3")));
        when(roleLimitApi.limitFor(eq(USER_ID), eq(RoleLimitCode.DISCOUNT_TOTAL_MAX_AMOUNT)))
                .thenReturn(RoleLimit.limited(new BigDecimal("3000")));

        // 1. Dentro de límite (5%) -> sin overrides
        QuoteResult withinLimit = pricingService.quote(new QuoteCommand(
                STORE_ID,
                USER_ID,
                true,
                List.of(new LineInput(106L, BigDecimal.ONE, null, null, new DiscountSpec(DiscountKind.PERCENT, new BigDecimal("5"), null))),
                null,
                null,
                List.of()
        ));
        assertThat(withinLimit.requiredOverrides()).isEmpty();

        // 2. Sobre límite de ítem (12%) y total (10% = 880) -> requiere ambos overrides
        QuoteResult overLimit = pricingService.quote(new QuoteCommand(
                STORE_ID,
                USER_ID,
                true,
                List.of(new LineInput(106L, BigDecimal.ONE, null, null, new DiscountSpec(DiscountKind.PERCENT, new BigDecimal("12"), null))),
                new DiscountSpec(DiscountKind.PERCENT, new BigDecimal("10"), null),
                null,
                List.of()
        ));

        assertThat(overLimit.requiredOverrides()).hasSize(2);
        assertThat(overLimit.requiredOverrides().get(0).action()).isEqualTo(OverrideAction.DISCOUNT_ITEM_OVER_LIMIT);
        Map<String, Object> itemCtx = overLimit.requiredOverrides().get(0).context();
        assertThat(itemCtx).containsEntry("lineIndex", 0)
                .containsEntry("productId", 106L)
                .containsEntry("amount", 1200L)
                .containsKey("cartHash");

        assertThat(overLimit.requiredOverrides().get(1).action()).isEqualTo(OverrideAction.DISCOUNT_TOTAL_OVER_LIMIT);
        Map<String, Object> totalCtx = overLimit.requiredOverrides().get(1).context();
        assertThat(totalCtx).doesNotContainKey("lineIndex")
                .containsEntry("amount", 880L)
                .containsKey("cartHash");

        // 3. Fail-closed: usuario sin permiso sales:discount:apply aunque el rol tenga límite
        QuoteResult withoutPermission = pricingService.quote(new QuoteCommand(
                STORE_ID,
                USER_ID,
                false,
                List.of(new LineInput(106L, BigDecimal.ONE, null, null, new DiscountSpec(DiscountKind.PERCENT, new BigDecimal("1"), null))),
                null,
                null,
                List.of()
        ));
        assertThat(withoutPermission.requiredOverrides())
                .extracting(PricingModels.RequiredOverride::action)
                .containsExactly(OverrideAction.DISCOUNT_ITEM_OVER_LIMIT);
    }

    private static ProductSnapshot product(
            Long id,
            String sku,
            String name,
            SaleType saleType,
            UnitOfMeasure uom,
            long priceGross,
            boolean vatExempt,
            RestrictedType restrictedType,
            boolean allowManualDiscount
    ) {
        return new ProductSnapshot(
                id,
                sku,
                name,
                1L,
                null,
                saleType,
                uom,
                BigDecimal.valueOf(priceGross),
                BigDecimal.ZERO,
                vatExempt,
                restrictedType,
                false,
                true,
                null,
                null,
                allowManualDiscount,
                null
        );
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
