package cl.minimarket.pos.organization.web;

import cl.minimarket.pos.organization.domain.Company;

/**
 * DTO de respuesta de la empresa ({@code API.md} F.3 {@code CompanyResponse}).
 */
public record CompanyResponse(
        Long id,
        String rut,
        String legalName,
        String tradeName,
        String businessActivity,
        String address,
        String commune,
        String city,
        String phone,
        String email,
        Long logoFileId,
        String timezone,
        Long version
) {

    /** Construye el DTO desde la entidad persistida. */
    public static CompanyResponse from(Company company) {
        return new CompanyResponse(
                company.getId(),
                company.getRut(),
                company.getLegalName(),
                company.getTradeName(),
                company.getBusinessActivity(),
                company.getAddress(),
                company.getCommune(),
                company.getCity(),
                company.getPhone(),
                company.getEmail(),
                company.getLogoFileId(),
                company.getTimezone(),
                company.getVersion()
        );
    }
}
