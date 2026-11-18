package cl.minimarket.pos.organization.web;

import cl.minimarket.pos.organization.application.CompanyService;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints globales de la empresa ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/company")
@GlobalEndpoint
@Tag(name = "Organization")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    /** Obtiene los datos de la empresa. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Obtener datos de la empresa")
    public CompanyResponse get() {
        return CompanyResponse.from(companyService.get());
    }

    /** Actualiza los datos de la empresa (valida RUT y audita {@code COMPANY_UPDATED}). */
    @PutMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_COMPANY_UPDATE)")
    @Operation(summary = "Actualizar datos de la empresa")
    public CompanyResponse update(@Valid @RequestBody CompanyRequest request) {
        var cmd = new CompanyService.UpdateCompanyCommand(
                request.rut(),
                request.legalName(),
                request.tradeName(),
                request.businessActivity(),
                request.address(),
                request.commune(),
                request.city(),
                request.phone(),
                request.email(),
                request.logoFileId(),
                request.timezone()
        );
        return CompanyResponse.from(companyService.update(cmd));
    }
}
