package cl.minimarket.pos.catalog.product.web;

import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.product.application.ProductService;
import cl.minimarket.pos.catalog.product.domain.PriceHistory;
import cl.minimarket.pos.catalog.tax.web.TaxResponse;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints globales de productos, códigos de barras, packs, impuestos por producto, precio,
 * costo, historial e imagen ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/products")
@GlobalEndpoint
@Tag(name = "Catalog")
public class ProductController {

    private static final SortParser PRODUCT_SORT = SortParser.allowing(
                    "id", "sku", "name", "priceGross", "active", "createdAt")
            .withDefault(Sort.by("name"));

    private static final SortParser HISTORY_SORT = SortParser.allowing("id", "changedAt")
            .withDefault(Sort.by(Sort.Direction.DESC, "changedAt"));

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** Lista productos paginados con filtros de catálogo. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_READ)")
    @Operation(summary = "Listar productos", description = "Permiso: catalog:product:read")
    public PageResponse<ProductSummary> list(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) Long categoryId,
                                             @RequestParam(required = false) Long brandId,
                                             @RequestParam(required = false) SaleType saleType,
                                             @RequestParam(required = false) Boolean active,
                                             @RequestParam(required = false) Boolean restricted,
                                             @RequestParam(required = false) Boolean lotControlled,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) List<String> sort) {
        Pageable pageable = PageQuery.of(page, size).toPageable(PRODUCT_SORT.parse(sort));
        ProductService.ProductFilter filter = new ProductService.ProductFilter(
                q, categoryId, brandId, saleType, active, restricted, lotControlled);
        Page<ProductService.ProductSummaryView> result = productService.list(filter, pageable);
        return PageResponse.from(result, ProductSummary::from);
    }

    /** Obtiene la ficha completa de un producto. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_READ)")
    @Operation(summary = "Obtener producto", description = "Permiso: catalog:product:read")
    public ProductResponse get(@PathVariable Long id) {
        return ProductResponse.from(productService.getDetail(id));
    }

    /** Crea un nuevo producto en el catálogo. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_CREATE)")
    @Operation(summary = "Crear producto", description = "Permiso: catalog:product:create")
    public ProductResponse create(@Valid @RequestBody ProductRequest request) {
        return ProductResponse.from(productService.create(toCommand(request)));
    }

    /** Actualiza los datos no económicos de un producto. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_UPDATE)")
    @Operation(summary = "Actualizar producto", description = "Permiso: catalog:product:update")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return ProductResponse.from(productService.update(id, toCommand(request)));
    }

    /** Desactiva un producto y audita {@code PRODUCT_DEACTIVATED}. */
    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_DEACTIVATE)")
    @Operation(summary = "Desactivar producto", description = "Permiso: catalog:product:deactivate")
    public void deactivate(@PathVariable Long id) {
        productService.deactivate(id);
    }

    /** Reactiva un producto desactivado. */
    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_DEACTIVATE)")
    @Operation(summary = "Activar producto", description = "Permiso: catalog:product:deactivate")
    public void activate(@PathVariable Long id) {
        productService.activate(id);
    }

    /** Cambia el precio de venta bruto (CLP) con registro en historial y auditoría. */
    @PutMapping("/{id}/price")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_UPDATE_PRICE)")
    @Operation(summary = "Cambiar precio de venta", description = "Permiso: catalog:product:update-price")
    public ProductResponse changePrice(@PathVariable Long id, @Valid @RequestBody PriceChangeRequest request) {
        return ProductResponse.from(productService.changePrice(
                id, request.newPriceGross(), request.reason(), request.version()));
    }

    /** Ajusta manualmente el costo promedio del producto ({@code COST_MANUAL}). */
    @PutMapping("/{id}/cost")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_COST_UPDATE)")
    @Operation(summary = "Cambiar costo manual", description = "Permiso: catalog:cost:update")
    public ProductResponse changeCost(@PathVariable Long id, @Valid @RequestBody CostChangeRequest request) {
        return ProductResponse.from(productService.changeCost(
                id, request.newAvgCost(), request.reason(), request.version()));
    }

    /** Lista el historial de precios y costos del producto. */
    @GetMapping("/{id}/price-history")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_READ)")
    @Operation(summary = "Historial de precios y costos", description = "Permiso: catalog:product:read")
    public PageResponse<PriceHistoryResponse> priceHistory(@PathVariable Long id,
                                                           @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size,
                                                           @RequestParam(required = false) List<String> sort) {
        Pageable pageable = PageQuery.of(page, size).toPageable(HISTORY_SORT.parse(sort));
        boolean includeCosts = productService.canReadCosts();
        Page<PriceHistory> result = productService.listPriceHistory(id, pageable);
        return PageResponse.from(result, entry -> PriceHistoryResponse.from(entry, includeCosts));
    }

    /** Agrega un código de barras a un producto. */
    @PostMapping("/{id}/barcodes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_UPDATE)")
    @Operation(summary = "Agregar código de barras", description = "Permiso: catalog:product:update")
    public BarcodeResponse addBarcode(@PathVariable Long id, @Valid @RequestBody BarcodeRequest request) {
        return BarcodeResponse.from(productService.addBarcode(id,
                new ProductService.BarcodeCommand(request.code(), request.barcodeType(), request.isPrimary())));
    }

    /** Elimina un código de barras del producto. */
    @DeleteMapping("/{id}/barcodes/{bid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_UPDATE)")
    @Operation(summary = "Eliminar código de barras", description = "Permiso: catalog:product:update")
    public void deleteBarcode(@PathVariable Long id, @PathVariable Long bid) {
        productService.deleteBarcode(id, bid);
    }

    /** Marca un código de barras como principal del producto. */
    @PutMapping("/{id}/barcodes/{bid}/primary")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_UPDATE)")
    @Operation(summary = "Marcar código principal", description = "Permiso: catalog:product:update")
    public void setPrimaryBarcode(@PathVariable Long id, @PathVariable Long bid) {
        productService.setPrimaryBarcode(id, bid);
    }

    /** Define o actualiza el producto base y factor de un producto {@code PACK}. */
    @PutMapping("/{id}/pack")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_UPDATE)")
    @Operation(summary = "Configurar pack", description = "Permiso: catalog:product:update")
    public PackResponse setPack(@PathVariable Long id, @Valid @RequestBody PackRequest request) {
        return PackResponse.from(productService.setPack(
                id, new ProductService.PackCommand(request.baseProductId(), request.factor())));
    }

    /** Reemplaza los impuestos del producto y su estado {@code vatExempt}. */
    @PutMapping("/{id}/taxes")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_TAX_MANAGE)")
    @Operation(summary = "Actualizar impuestos del producto", description = "Permiso: config:tax:manage")
    public List<TaxResponse> updateTaxes(@PathVariable Long id, @Valid @RequestBody ProductTaxesRequest request) {
        return productService.updateTaxes(id, request.vatExempt(), request.taxIds()).stream()
                .map(TaxResponse::from)
                .toList();
    }

    /** Asocia una imagen al producto. */
    @PutMapping("/{id}/image")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_UPDATE)")
    @Operation(summary = "Asociar imagen al producto", description = "Permiso: catalog:product:update")
    public ProductResponse updateImage(@PathVariable Long id, @Valid @RequestBody ProductImageRequest request) {
        return ProductResponse.from(productService.updateImage(id, request.fileId()));
    }

    /** Desvincula la imagen del producto. */
    @DeleteMapping("/{id}/image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CATALOG_PRODUCT_UPDATE)")
    @Operation(summary = "Quitar imagen del producto", description = "Permiso: catalog:product:update")
    public void deleteImage(@PathVariable Long id) {
        productService.deleteImage(id);
    }

    private static ProductService.ProductCommand toCommand(ProductRequest request) {
        List<ProductService.BarcodeCommand> barcodes = request.barcodes() == null
                ? null
                : request.barcodes().stream()
                        .map(b -> new ProductService.BarcodeCommand(b.code(), b.barcodeType(), b.isPrimary()))
                        .toList();
        ProductService.PackCommand pack = request.pack() == null
                ? null
                : new ProductService.PackCommand(request.pack().baseProductId(), request.pack().factor());
        return new ProductService.ProductCommand(
                request.sku(),
                request.name(),
                request.description(),
                request.brandId(),
                request.categoryId(),
                request.saleType(),
                request.unitOfMeasure(),
                request.vatExempt(),
                request.restrictedType(),
                request.lotControlled(),
                request.shelfLifeDays(),
                request.shortcutCode(),
                request.allowManualDiscount(),
                request.initialPriceGross(),
                request.taxIds(),
                barcodes,
                pack,
                request.version()
        );
    }
}
