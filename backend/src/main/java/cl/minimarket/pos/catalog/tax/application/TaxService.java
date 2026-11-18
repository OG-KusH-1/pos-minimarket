package cl.minimarket.pos.catalog.tax.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.tax.domain.Tax;
import cl.minimarket.pos.catalog.tax.domain.TaxRepository;
import cl.minimarket.pos.catalog.tax.domain.TaxType;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Casos de uso de impuestos configurables ({@code taxes}).
 *
 * <p>Toda creación o modificación se registra en la bitácora inmutable con la acción
 * {@code TAX_CHANGED} (ROLES_PERMISOS D.5).
 */
@Service
public class TaxService {

    private static final String ACTION_TAX_CHANGED = "TAX_CHANGED";
    private static final String ENTITY_TYPE_TAX = "tax";

    private final TaxRepository taxRepository;
    private final AuditService auditService;

    public TaxService(TaxRepository taxRepository, AuditService auditService) {
        this.taxRepository = taxRepository;
        this.auditService = auditService;
    }

    /** Comando de creación o actualización de un impuesto. */
    public record TaxCommand(
            String code,
            String name,
            TaxType taxType,
            BigDecimal rate,
            String siiCode,
            LocalDate validFrom,
            LocalDate validTo
    ) {
    }

    /** Lista todos los impuestos configurados. */
    @Transactional(readOnly = true)
    public List<Tax> list() {
        return taxRepository.findAllByOrderByIdAsc();
    }

    /** Crea un nuevo impuesto y registra {@code TAX_CHANGED} en auditoría. */
    @Transactional
    public Tax create(TaxCommand cmd) {
        String code = cmd.code().trim();
        validateDateRange(cmd.validFrom(), cmd.validTo());
        if (taxRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un impuesto con el código '" + code + "'.");
        }

        Tax tax = new Tax();
        applyFields(tax, cmd, code);
        tax.setActive(true);
        Tax saved = taxRepository.save(tax);

        auditService.record(new AuditEvent(
                ACTION_TAX_CHANGED,
                ENTITY_TYPE_TAX,
                saved.getId().toString(),
                null,
                snapshot(saved),
                null,
                null
        ));
        return saved;
    }

    /** Actualiza un impuesto existente y registra {@code TAX_CHANGED} con el antes y después. */
    @Transactional
    public Tax update(Long id, TaxCommand cmd) {
        Tax tax = taxRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Impuesto no encontrado."));
        String code = cmd.code().trim();
        validateDateRange(cmd.validFrom(), cmd.validTo());
        if (taxRepository.existsByCodeAndIdNot(code, id)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un impuesto con el código '" + code + "'.");
        }

        Map<String, Object> oldSnapshot = snapshot(tax);
        applyFields(tax, cmd, code);
        Tax saved = taxRepository.save(tax);

        auditService.record(new AuditEvent(
                ACTION_TAX_CHANGED,
                ENTITY_TYPE_TAX,
                saved.getId().toString(),
                oldSnapshot,
                snapshot(saved),
                null,
                null
        ));
        return saved;
    }

    private static void validateDateRange(LocalDate validFrom, LocalDate validTo) {
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "La fecha de término de vigencia debe ser posterior a la fecha de inicio.",
                    Map.of(),
                    List.of(new FieldErrorDetail("validTo", "INVALID_RANGE",
                            "validTo debe ser posterior a validFrom."))
            );
        }
    }

    private static void applyFields(Tax tax, TaxCommand cmd, String normalizedCode) {
        tax.setCode(normalizedCode);
        tax.setName(cmd.name().trim());
        tax.setTaxType(cmd.taxType());
        tax.setRate(Rounding.percentage(cmd.rate()));
        tax.setSiiCode(trimToNull(cmd.siiCode()));
        tax.setValidFrom(cmd.validFrom());
        tax.setValidTo(cmd.validTo());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Object> snapshot(Tax tax) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", tax.getCode());
        map.put("name", tax.getName());
        map.put("taxType", tax.getTaxType().name());
        map.put("rate", tax.getRate());
        map.put("siiCode", tax.getSiiCode());
        map.put("validFrom", tax.getValidFrom().toString());
        map.put("validTo", tax.getValidTo() != null ? tax.getValidTo().toString() : null);
        map.put("active", tax.isActive());
        return map;
    }
}
