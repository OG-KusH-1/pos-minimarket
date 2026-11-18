package cl.minimarket.pos.catalog.tax.web;

import cl.minimarket.pos.catalog.tax.domain.Tax;
import cl.minimarket.pos.catalog.tax.domain.TaxType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Respuesta pública de impuesto ({@code API.md} F.3).
 */
public record TaxResponse(
        Long id,
        String code,
        String name,
        TaxType taxType,
        BigDecimal rate,
        String siiCode,
        LocalDate validFrom,
        LocalDate validTo,
        boolean active
) {

    /** Convierte una entidad {@link Tax} a su DTO de respuesta. */
    public static TaxResponse from(Tax tax) {
        return new TaxResponse(
                tax.getId(),
                tax.getCode(),
                tax.getName(),
                tax.getTaxType(),
                tax.getRate(),
                tax.getSiiCode(),
                tax.getValidFrom(),
                tax.getValidTo(),
                tax.isActive()
        );
    }
}
