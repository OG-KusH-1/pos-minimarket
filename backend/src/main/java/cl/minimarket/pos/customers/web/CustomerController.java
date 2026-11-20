package cl.minimarket.pos.customers.web;

import cl.minimarket.pos.customers.application.CustomerService;
import cl.minimarket.pos.customers.application.CustomerService.CustomerCommand;
import cl.minimarket.pos.customers.domain.CustomerJdbcRepository.CustomerRow;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.idempotency.Idempotent;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Endpoints REST de clientes y cuenta corriente ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers")
public class CustomerController {

    private static final SortParser CUSTOMER_SORT = SortParser.allowing(
            "id", "name", "rut", "creditLimit", "creditBalance", "active", "createdAt"
    ).withDefault(Sort.by(Sort.Direction.ASC, "name"));

    private static final SortParser MOVEMENT_SORT = SortParser.allowing(
            "id", "occurredAt", "movementType", "amount", "balanceAfter"
    ).withDefault(Sort.by(Sort.Direction.DESC, "occurredAt", "id"));

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    /** Lista clientes de forma paginada con filtros opcionales ([G]). */
    @GetMapping
    @GlobalEndpoint
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CUSTOMERS_CUSTOMER_READ)")
    @Operation(summary = "Listar clientes", description = "Permiso: customers:customer:read")
    public PageResponse<CustomerResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean withDebt,
            @RequestParam(required = false) Boolean creditEnabled,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(
                CUSTOMER_SORT.parse(resolveRawSort(sort, httpRequest))
        );
        Page<CustomerRow> result = service.list(q, withDebt, creditEnabled, active, pageable);
        return PageResponse.from(result, CustomerResponse::from);
    }

    /** Obtiene un cliente por su identificador ([G]). */
    @GetMapping("/{id}")
    @GlobalEndpoint
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CUSTOMERS_CUSTOMER_READ)")
    @Operation(summary = "Obtener cliente", description = "Permiso: customers:customer:read")
    public CustomerResponse get(@PathVariable Long id) {
        return CustomerResponse.from(service.get(id));
    }

    /** Crea un cliente nuevo ([G]). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @GlobalEndpoint
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CUSTOMERS_CUSTOMER_MANAGE)")
    @Operation(summary = "Crear cliente", description = "Permiso: customers:customer:manage")
    public CustomerResponse create(@Valid @RequestBody CustomerRequest request) {
        return CustomerResponse.from(service.create(toCommand(request)));
    }

    /** Actualiza los datos de un cliente existente ([G]). */
    @PutMapping("/{id}")
    @GlobalEndpoint
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CUSTOMERS_CUSTOMER_MANAGE)")
    @Operation(summary = "Actualizar cliente", description = "Permiso: customers:customer:manage")
    public CustomerResponse update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {
        return CustomerResponse.from(service.update(id, toCommand(request)));
    }

    /** Desactiva un cliente sin deuda vigente ([G]). */
    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @GlobalEndpoint
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CUSTOMERS_CUSTOMER_MANAGE)")
    @Operation(summary = "Desactivar cliente", description = "Permiso: customers:customer:manage")
    public void deactivate(@PathVariable Long id) {
        service.deactivate(id);
    }

    /** Reactiva un cliente desactivado ([G]). */
    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @GlobalEndpoint
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CUSTOMERS_CUSTOMER_MANAGE)")
    @Operation(summary = "Activar cliente", description = "Permiso: customers:customer:manage")
    public void activate(@PathVariable Long id) {
        service.activate(id);
    }

    /** Habilita o edita el cupo de crédito de un cliente ([G]). */
    @PutMapping("/{id}/credit")
    @GlobalEndpoint
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CUSTOMERS_CREDIT_MANAGE_LIMIT)")
    @Operation(summary = "Configurar crédito del cliente", description = "Permiso: customers:credit:manage-limit")
    public CustomerResponse updateCredit(
            @PathVariable Long id,
            @Valid @RequestBody CreditSettingsRequest request
    ) {
        return CustomerResponse.from(service.updateCreditSettings(
                id,
                request.creditEnabled(),
                request.creditLimit(),
                request.reason()
        ));
    }

    /** Obtiene el estado de cuenta y cartola paginada de movimientos de un cliente. */
    @GetMapping("/{id}/account")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CUSTOMERS_CUSTOMER_READ)")
    @Operation(summary = "Obtener estado de cuenta del cliente", description = "Permiso: customers:customer:read")
    public AccountStatementResponse account(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            HttpServletRequest httpRequest
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(
                MOVEMENT_SORT.parse(resolveRawSort(sort, httpRequest))
        );
        return AccountStatementResponse.from(service.accountStatement(id, from, to, pageable));
    }

    /** Registra un abono a la cuenta corriente del cliente ([I], [T] si es en efectivo). */
    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CUSTOMERS_CREDIT_PAYMENT)")
    @Operation(summary = "Registrar abono de cliente", description = "Permiso: customers:credit:payment")
    public AccountMovementResponse registerPayment(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CustomerPaymentRequest request
    ) {
        return AccountMovementResponse.from(service.registerPayment(
                id,
                request.amount(),
                request.paymentMethod(),
                request.reference(),
                request.notes(),
                idempotencyKey
        ));
    }

    /** Registra un ajuste manual de cuenta corriente ([I]). */
    @PostMapping("/{id}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CUSTOMERS_CREDIT_MANAGE_LIMIT)")
    @Operation(summary = "Registrar ajuste de cuenta corriente", description = "Permiso: customers:credit:manage-limit")
    public AccountMovementResponse registerAdjustment(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CustomerAdjustmentRequest request
    ) {
        return AccountMovementResponse.from(service.registerAdjustment(
                id,
                request.amount(),
                request.reason(),
                idempotencyKey
        ));
    }

    private static List<String> resolveRawSort(List<String> boundSort, HttpServletRequest httpRequest) {
        if (httpRequest != null) {
            String[] values = httpRequest.getParameterValues("sort");
            return values == null ? null : Arrays.asList(values);
        }
        return boundSort;
    }

    private static CustomerCommand toCommand(CustomerRequest request) {
        return new CustomerCommand(
                request.rut(),
                request.name(),
                request.email(),
                request.phone(),
                request.address(),
                request.notes(),
                request.version()
        );
    }
}
