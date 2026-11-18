package cl.minimarket.pos.purchasing.supplier.web;

import cl.minimarket.pos.purchasing.supplier.application.SupplierService;
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
 * Endpoints globales de proveedores ({@code API.md} F.2, {@code HU-PRV-01}).
 */
@RestController
@RequestMapping("/api/v1/suppliers")
@GlobalEndpoint
@Tag(name = "Purchasing")
public class SupplierController {

    private static final SortParser SUPPLIER_SORT = SortParser.allowing(
                    "id", "rut", "legalName", "tradeName", "leadTimeDays", "active", "createdAt")
            .withDefault(Sort.by("legalName"));

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = Objects.requireNonNull(supplierService, "supplierService");
    }

    /** Lista proveedores paginados filtrando opcionalmente por texto (RUT o nombre) y estado. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_SUPPLIER_READ)")
    @Operation(
            summary = "Listar proveedores",
            description = "Permiso: purchasing:supplier:read. Filtra por texto libre (RUT o nombre) y estado.")
    @ApiResponse(responseCode = "200", description = "Página de proveedores")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por paginación u orden inválido")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    public PageResponse<SupplierSummary> list(
            @Parameter(description = "Búsqueda por RUT, razón social o nombre de fantasía.")
            @RequestParam(required = false) String q,
            @Parameter(description = "Filtrar por estado activo/inactivo.")
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest) {
        List<String> rawSort = resolveRawSort(sort, httpRequest);
        Pageable pageable = PageQuery.of(page, size).toPageable(SUPPLIER_SORT.parse(rawSort));
        Page<SupplierService.SupplierSummaryView> result = supplierService.list(q, active, pageable);
        return PageResponse.from(result, SupplierSummary::from);
    }

    /** Obtiene la ficha completa de un proveedor. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_SUPPLIER_READ)")
    @Operation(summary = "Obtener proveedor", description = "Permiso: purchasing:supplier:read")
    @ApiResponse(responseCode = "200", description = "Ficha del proveedor")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el proveedor no existe")
    public SupplierResponse get(@PathVariable Long id) {
        return SupplierResponse.from(supplierService.get(id));
    }

    /** Crea un nuevo proveedor validando RUT chileno y unicidad. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_SUPPLIER_MANAGE)")
    @Operation(summary = "Crear proveedor", description = "Permiso: purchasing:supplier:manage")
    @ApiResponse(responseCode = "201", description = "Proveedor creado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por campos inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "409", description = "DUPLICATED_VALUE si el RUT ya existe")
    @ApiResponse(responseCode = "422", description = "RUT_INVALID si el RUT no cumple módulo 11")
    public SupplierResponse create(@Valid @RequestBody SupplierRequest request) {
        return SupplierResponse.from(supplierService.create(toCommand(request)));
    }

    /** Actualiza los datos de un proveedor existente. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_SUPPLIER_MANAGE)")
    @Operation(summary = "Actualizar proveedor", description = "Permiso: purchasing:supplier:manage")
    @ApiResponse(responseCode = "200", description = "Proveedor actualizado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR por campos inválidos")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el proveedor no existe")
    @ApiResponse(responseCode = "409", description = "DUPLICATED_VALUE o CONCURRENT_MODIFICATION")
    @ApiResponse(responseCode = "422", description = "RUT_INVALID si el RUT no cumple módulo 11")
    public SupplierResponse update(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return SupplierResponse.from(supplierService.update(id, toCommand(request)));
    }

    /** Desactiva un proveedor. */
    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_SUPPLIER_MANAGE)")
    @Operation(summary = "Desactivar proveedor", description = "Permiso: purchasing:supplier:manage")
    @ApiResponse(responseCode = "204", description = "Proveedor desactivado")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el proveedor no existe")
    public void deactivate(@PathVariable Long id) {
        supplierService.deactivate(id);
    }

    /** Reactiva un proveedor desactivado. */
    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).PURCHASING_SUPPLIER_MANAGE)")
    @Operation(summary = "Activar proveedor", description = "Permiso: purchasing:supplier:manage")
    @ApiResponse(responseCode = "204", description = "Proveedor activado")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND si el proveedor no existe")
    public void activate(@PathVariable Long id) {
        supplierService.activate(id);
    }

    private static List<String> resolveRawSort(List<String> boundSort, HttpServletRequest httpRequest) {
        if (httpRequest != null) {
            String[] values = httpRequest.getParameterValues("sort");
            return values == null ? null : Arrays.asList(values);
        }
        return boundSort;
    }

    private static SupplierService.SupplierCommand toCommand(SupplierRequest request) {
        return new SupplierService.SupplierCommand(
                request.rut(),
                request.legalName(),
                request.tradeName(),
                request.businessActivity(),
                request.address(),
                request.commune(),
                request.city(),
                request.phone(),
                request.email(),
                request.contactName(),
                request.contactPhone(),
                request.contactEmail(),
                request.paymentTermsType(),
                request.paymentTermsDays(),
                request.leadTimeDays(),
                request.deliveryWeekdays(),
                request.notes(),
                request.version()
        );
    }
}
