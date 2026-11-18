package cl.minimarket.pos.purchasing.productsupplier.domain;

import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.RestrictedType;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.purchasing.productsupplier.application.ProductSupplierService;
import cl.minimarket.pos.purchasing.supplier.application.SupplierService;
import cl.minimarket.pos.purchasing.supplier.domain.PaymentTermsType;
import cl.minimarket.pos.purchasing.supplier.domain.Supplier;
import cl.minimarket.pos.purchasing.supplier.domain.SupplierRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de cálculo de costo unitario neto y validaciones de dominio/servicio de
 * proveedores y producto-proveedor ({@code T57}).
 */
class PurchasingValidationTest {

    @Test
    void computeUnitCostNet_calculatesExactAndRoundedScale4() {
        // HU-PRV-02: caja de 12 a $6.000 -> unitCostNet = 500.0000
        assertThat(ProductSupplier.computeUnitCostNet(new BigDecimal("6000"), new BigDecimal("12")))
                .isEqualByComparingTo("500.0000");

        // División periódica con HALF_UP a 4 decimales
        assertThat(ProductSupplier.computeUnitCostNet(new BigDecimal("1000"), new BigDecimal("3")))
                .isEqualByComparingTo("333.3333");

        // Costo cero permitido
        assertThat(ProductSupplier.computeUnitCostNet(BigDecimal.ZERO, new BigDecimal("6")))
                .isEqualByComparingTo("0.0000");

        // Unidades decimales (pesables)
        assertThat(ProductSupplier.computeUnitCostNet(new BigDecimal("12500"), new BigDecimal("2.500")))
                .isEqualByComparingTo("5000.0000");

        // Entradas nulas o no positivas
        assertThat(ProductSupplier.computeUnitCostNet(null, BigDecimal.ONE)).isNull();
        assertThat(ProductSupplier.computeUnitCostNet(BigDecimal.TEN, null)).isNull();
        assertThat(ProductSupplier.computeUnitCostNet(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
    }

    @Test
    void supplierService_rejectsInvalidRutAndInvalidWeekdays() {
        SupplierRepository repo = mock(SupplierRepository.class);
        JdbcClient jdbc = mock(JdbcClient.class);
        SupplierService service = new SupplierService(repo, jdbc);

        // RUT inválido -> 422 RUT_INVALID (para 76123456 el DV válido es 0)
        SupplierService.SupplierCommand badRutCmd = new SupplierService.SupplierCommand(
                "76123456-1", "Proveedor X", null, null, null, null, null,
                null, null, null, null, null,
                PaymentTermsType.CASH_ON_DELIVERY, 0, 2, List.of(1, 4), null, null);
        assertThatThrownBy(() -> service.create(badRutCmd))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.RUT_INVALID));

        // Días de despacho repetidos -> 400 VALIDATION_ERROR
        SupplierService.SupplierCommand dupDaysCmd = new SupplierService.SupplierCommand(
                "76123456-0", "Proveedor X", null, null, null, null, null,
                null, null, null, null, null,
                PaymentTermsType.CASH_ON_DELIVERY, 0, 2, List.of(1, 1), null, null);
        assertThatThrownBy(() -> service.create(dupDaysCmd))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        // Día de despacho fuera de 1..7 -> 400 VALIDATION_ERROR
        SupplierService.SupplierCommand outOfRangeCmd = new SupplierService.SupplierCommand(
                "76123456-0", "Proveedor X", null, null, null, null, null,
                null, null, null, null, null,
                PaymentTermsType.CREDIT, 30, 2, List.of(8), null, null);
        assertThatThrownBy(() -> service.create(outOfRangeCmd))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void productSupplierService_rejectsPackAndInvalidQuantities() {
        ProductSupplierRepository psRepo = mock(ProductSupplierRepository.class);
        SupplierRepository supRepo = mock(SupplierRepository.class);
        ProductCatalogApi catalogApi = mock(ProductCatalogApi.class);
        AuditService auditService = mock(AuditService.class);
        ProductSupplierService service = new ProductSupplierService(psRepo, supRepo, catalogApi, auditService);

        ProductCatalogApi.ProductSnapshot pack = new ProductCatalogApi.ProductSnapshot(
                10L, "PACK-1", "Pack 6", 1L, null, SaleType.PACK, UnitOfMeasure.UNIT,
                BigDecimal.valueOf(5000), BigDecimal.ZERO, false, (RestrictedType) null,
                false, true, null, null, true, 6, null);
        when(catalogApi.getForSale(10L)).thenReturn(pack);

        assertThatThrownBy(() -> service.create(new ProductSupplierService.SaveCommand(
                10L, 1L, null, "Caja", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, null, false, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        ProductCatalogApi.ProductSnapshot unit = new ProductCatalogApi.ProductSnapshot(
                11L, "UNIT-1", "Bebida", 1L, null, SaleType.UNIT, UnitOfMeasure.UNIT,
                BigDecimal.valueOf(1000), BigDecimal.ZERO, false, (RestrictedType) null,
                false, true, null, null, true, null, null);
        when(catalogApi.getForSale(11L)).thenReturn(unit);
        when(supRepo.findById(1L)).thenReturn(Optional.of(new Supplier()));

        // unitsPerPurchaseUnit <= 0 tras redondeo a escala 3
        assertThatThrownBy(() -> service.create(new ProductSupplierService.SaveCommand(
                11L, 1L, null, "Caja", new BigDecimal("0.0001"), BigDecimal.TEN, BigDecimal.ONE, null, false, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        // minOrderQty < 1
        assertThatThrownBy(() -> service.create(new ProductSupplierService.SaveCommand(
                11L, 1L, null, "Caja", BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("0.5"), null, false, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
