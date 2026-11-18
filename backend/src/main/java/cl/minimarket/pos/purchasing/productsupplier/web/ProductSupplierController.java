package cl.minimarket.pos.purchasing.productsupplier.web;

import cl.minimarket.pos.purchasing.productsupplier.application.ProductSupplierQueryService;
import cl.minimarket.pos.purchasing.productsupplier.application.ProductSupplierService;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Endpoints globales de asociaciones producto-proveedor ({@code API.md} F.2, {@code HU-PRV-02}).
 */
@RestController
@RequestMapping("/api/v1")
@GlobalEndpoint
@Tag(name = "Purchasing")
public class ProductSupplierController {

    private static final SortParser SUPPLIER_PRODUCTS_SORT = SortParser.allowing(
                    "id", "productId", "productSku", "productName", "supplierSku",
                    "purchaseUnitName", "costPerPurchaseUnit", "minOrderQty",
                    "isPreferred", "active", "createdAt")
            .withDefault(Sort.by(Sort.Order.desc("isPreferred"), Sort.Order.asc("productName")));

    private final ProductSupplierQueryService queryService;
    private final ProductSupplierService productSupplierService;

    public ProductSupplierController(ProductSupplierQueryService queryService,
                                     ProductSupplierService productSupplierService) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.productSupplierService = Objects.requireNonNull(productSupplierService, "productSupplierService");
    }

    /** Lista paginada de productos asociados a un proveedor. */
    @GetMapping("/suppliers/{id}/products")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_SUPPLIER_READ)")
    @Operation(
            summary = "Listar productos por proveedor",
            description = "Permiso: purchasing:supplier:read. Costos visibles solo con catalog:cost:read.")
    @ApiResponse(responseCode = "200", description = "Página de productos asociados al proveedor")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por paginación u orden inválido")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el proveedor no existe")
    public PageResponse<ProductSupplierResponse> listBySupplier(
            @PathVariable Long id,
            @Parameter(description = "Filtro por SKU o nombre de producto, o SKU del proveedor.")
            @RequestParam(required = false) String q,
            @Parameter(description = "Filtrar por estado activo/inactivo.")
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest) {
        List<String> rawSort = resolveRawSort(sort, httpRequest);
        Pageable pageable = PageQuery.of(page, size).toPageable(SUPPLIER_PRODUCTS_SORT.parse(rawSort));
        Page<ProductSupplierQueryService.ProductSupplierView> result =
                queryService.listBySupplier(id, q, active, pageable);
        return PageResponse.from(result, ProductSupplierResponse::from);
    }

    /** Lista los proveedores asociados a un producto. */
    @GetMapping("/products/{id}/suppliers")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_SUPPLIER_READ)")
    @Operation(
            summary = "Listar proveedores por producto",
            description = "Permiso: purchasing:supplier:read. Costos visibles solo con catalog:cost:read.")
    @ApiResponse(responseCode = "200", description = "Lista de proveedores del producto")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el producto no existe")
    public List<ProductSupplierResponse> listByProduct(@PathVariable Long id) {
        return queryService.listByProduct(id).stream()
                .map(ProductSupplierResponse::from)
                .toList();
    }

    /** Asocia un producto (no PACK) a un proveedor. */
    @PostMapping("/product-suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_PRODUCT_SUPPLIER_MANAGE)")
    @Operation(
            summary = "Crear asociación producto-proveedor",
            description = "Permiso: purchasing:product-supplier:manage (sensible, auditado como PRODUCT_SUPPLIER_CHANGED).")
    @ApiResponse(responseCode = "201", description = "Asociación creada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (incluye producto PACK)")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si producto o proveedor no existen")
    @ApiResponse(responseCode = "409", description = "DUPLICATED_VALUE si ya existe el par producto-proveedor")
    public ProductSupplierResponse create(@Valid @RequestBody ProductSupplierRequest request) {
        return ProductSupplierResponse.from(productSupplierService.create(toCommand(request)));
    }

    /** Actualiza una asociación producto-proveedor existente. */
    @PutMapping("/product-suppliers/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_PRODUCT_SUPPLIER_MANAGE)")
    @Operation(
            summary = "Actualizar asociación producto-proveedor",
            description = "Permiso: purchasing:product-supplier:manage (sensible, auditado como PRODUCT_SUPPLIER_CHANGED).")
    @ApiResponse(responseCode = "200", description = "Asociación actualizada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (incluye producto PACK)")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el registro, producto o proveedor no existen")
    @ApiResponse(responseCode = "409", description = "DUPLICATED_VALUE, INVALID_STATE_TRANSITION o CONCURRENT_MODIFICATION")
    public ProductSupplierResponse update(@PathVariable Long id,
                                          @Valid @RequestBody ProductSupplierRequest request) {
        return ProductSupplierResponse.from(productSupplierService.update(id, toCommand(request)));
    }

    /** Desactiva una asociación producto-proveedor. */
    @PostMapping("/product-suppliers/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_PRODUCT_SUPPLIER_MANAGE)")
    @Operation(
            summary = "Desactivar asociación producto-proveedor",
            description = "Permiso: purchasing:product-supplier:manage (sensible, auditado como PRODUCT_SUPPLIER_CHANGED).")
    @ApiResponse(responseCode = "204", description = "Asociación desactivada")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la asociación no existe")
    public void deactivate(@PathVariable Long id) {
        productSupplierService.deactivate(id);
    }

    /** Reactiva una asociación producto-proveedor desactivada. */
    @PostMapping("/product-suppliers/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_PRODUCT_SUPPLIER_MANAGE)")
    @Operation(
            summary = "Activar asociación producto-proveedor",
            description = "Permiso: purchasing:product-supplier:manage (sensible, auditado como PRODUCT_SUPPLIER_CHANGED).")
    @ApiResponse(responseCode = "204", description = "Asociación activada")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la asociación no existe")
    public void activate(@PathVariable Long id) {
        productSupplierService.activate(id);
    }

    /** Marca la asociación como proveedor preferido único del producto. */
    @PutMapping("/product-suppliers/{id}/preferred")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_PRODUCT_SUPPLIER_MANAGE)")
    @Operation(
            summary = "Marcar proveedor preferido del producto",
            description = "Permiso: purchasing:product-supplier:manage (desmarca al preferido anterior y audita PRODUCT_SUPPLIER_CHANGED).")
    @ApiResponse(responseCode = "204", description = "Proveedor marcado como preferido")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si la asociación no existe")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION si la asociación está inactiva")
    public void markPreferred(@PathVariable Long id) {
        productSupplierService.markPreferred(id);
    }

    private static List<String> resolveRawSort(List<String> boundSort, HttpServletRequest httpRequest) {
        if (httpRequest != null) {
            String[] values = httpRequest.getParameterValues("sort");
            return values == null ? null : Arrays.asList(values);
        }
        return boundSort;
    }

    private static ProductSupplierService.SaveCommand toCommand(ProductSupplierRequest request) {
        return new ProductSupplierService.SaveCommand(
                request.productId(),
                request.supplierId(),
                request.supplierSku(),
                request.purchaseUnitName(),
                request.unitsPerPurchaseUnit(),
                request.costPerPurchaseUnit(),
                request.minOrderQty(),
                request.leadTimeDaysOverride(),
                request.isPreferred(),
                request.version()
        );
    }
}
