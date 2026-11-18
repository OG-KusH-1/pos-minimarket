package cl.minimarket.pos.catalog.product.web;

import cl.minimarket.pos.catalog.RestrictedType;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.catalog.product.application.ProductService;
import cl.minimarket.pos.catalog.product.domain.Product;
import cl.minimarket.pos.catalog.tax.web.TaxResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ficha detallada de producto ({@code API.md} F.3).
 *
 * <p>Los campos {@code avgCost}, {@code lastCost} y {@code marginPct} se devuelven únicamente
 * cuando el usuario posee {@code catalog:cost:read}; en caso contrario son {@code null}.
 */
public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        BrandRef brand,
        CategoryRef category,
        SaleType saleType,
        UnitOfMeasure unitOfMeasure,
        BigDecimal priceGross,
        BigDecimal netPrice,
        BigDecimal avgCost,
        BigDecimal lastCost,
        BigDecimal marginPct,
        boolean vatExempt,
        RestrictedType restrictedType,
        boolean lotControlled,
        Integer shelfLifeDays,
        String imageUrl,
        String shortcutCode,
        boolean allowManualDiscount,
        boolean active,
        List<BarcodeResponse> barcodes,
        List<TaxResponse> taxes,
        PackResponse pack,
        Long version
) {

    /** Referencia compacta a marca. */
    public record BrandRef(Long id, String name) {
    }

    /** Referencia compacta a categoría (con su padre si es subcategoría). */
    public record CategoryRef(Long id, String name, Long parentId, String parentName) {
    }

    /** Proyecta desde la vista de detalle del servicio. */
    public static ProductResponse from(ProductService.ProductDetailView view) {
        Product p = view.product();
        BrandRef brandRef = view.brand() != null
                ? new BrandRef(view.brand().getId(), view.brand().getName())
                : null;
        CategoryRef categoryRef = view.category() != null
                ? new CategoryRef(
                        view.category().getId(),
                        view.category().getName(),
                        view.category().getParentId(),
                        view.parentCategory() != null ? view.parentCategory().getName() : null)
                : null;

        String imageUrl = p.getImageFileId() != null
                ? "/api/v1/files/" + p.getImageFileId() + "/content"
                : null;

        return new ProductResponse(
                p.getId(),
                p.getSku(),
                p.getName(),
                p.getDescription(),
                brandRef,
                categoryRef,
                p.getSaleType(),
                p.getUnitOfMeasure(),
                p.getPriceGross(),
                view.netPrice(),
                view.includeCosts() ? p.getAvgCost() : null,
                view.includeCosts() ? p.getLastCost() : null,
                view.includeCosts() ? view.marginPct() : null,
                p.isVatExempt(),
                p.getRestrictedType(),
                p.isLotControlled(),
                p.getShelfLifeDays(),
                imageUrl,
                p.getShortcutCode(),
                p.isAllowManualDiscount(),
                p.isActive(),
                view.barcodes().stream().map(BarcodeResponse::from).toList(),
                view.taxes().stream().map(TaxResponse::from).toList(),
                PackResponse.from(view.pack()),
                p.getVersion()
        );
    }
}
