package cl.minimarket.pos.organization.application;

import cl.minimarket.pos.organization.domain.Company;
import cl.minimarket.pos.organization.domain.CompanyRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.rut.Rut;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Casos de uso de la empresa singleton ({@code company}).
 */
@Service
public class CompanyService {

    private final CompanyRepository repository;
    private final OrganizationAuditor auditor;

    public CompanyService(CompanyRepository repository, OrganizationAuditor auditor) {
        this.repository = repository;
        this.auditor = auditor;
    }

    /**
     * Comando de actualización de los datos de la empresa.
     */
    public record UpdateCompanyCommand(
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
            String timezone
    ) {
    }

    /** Obtiene la empresa única o lanza 404 {@code NOT_FOUND} si aún no existe. */
    @Transactional(readOnly = true)
    public Company get() {
        return repository.findTopByOrderByIdAsc()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Empresa no configurada."));
    }

    /**
     * Actualiza (o inicializa si no existe) los datos de la empresa validando RUT y zona horaria.
     */
    @Transactional
    public Company update(UpdateCompanyCommand cmd) {
        Rut validRut = Rut.of(cmd.rut());
        validateTimezone(cmd.timezone());

        Company company = repository.findTopByOrderByIdAsc().orElseGet(Company::new);
        Map<String, Object> oldSnapshot = company.getId() != null ? snapshot(company) : null;

        company.setRut(validRut.normalized());
        company.setLegalName(cmd.legalName().trim());
        company.setTradeName(cmd.tradeName().trim());
        company.setBusinessActivity(cmd.businessActivity().trim());
        company.setAddress(cmd.address().trim());
        company.setCommune(cmd.commune().trim());
        company.setCity(cmd.city().trim());
        company.setPhone(trimToNull(cmd.phone()));
        company.setEmail(trimToNull(cmd.email()));
        company.setLogoFileId(cmd.logoFileId());
        company.setTimezone(cmd.timezone().trim());

        Company saved = repository.save(company);
        auditor.companyUpdated(saved.getId(), oldSnapshot, snapshot(saved));
        return saved;
    }

    private static void validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw invalidTimezone("La zona horaria es obligatoria.");
        }
        try {
            ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            throw invalidTimezone("Zona horaria IANA inválida: " + timezone);
        }
    }

    private static BusinessException invalidTimezone(String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                message,
                Map.of(),
                List.of(new FieldErrorDetail("timezone", "INVALID_TIMEZONE", message)));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Object> snapshot(Company c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rut", c.getRut());
        map.put("legalName", c.getLegalName());
        map.put("tradeName", c.getTradeName());
        map.put("businessActivity", c.getBusinessActivity());
        map.put("address", c.getAddress());
        map.put("commune", c.getCommune());
        map.put("city", c.getCity());
        map.put("phone", c.getPhone());
        map.put("email", c.getEmail());
        map.put("logoFileId", c.getLogoFileId());
        map.put("timezone", c.getTimezone());
        return map;
    }
}
