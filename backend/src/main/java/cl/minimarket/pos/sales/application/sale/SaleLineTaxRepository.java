package cl.minimarket.pos.sales.application.sale;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Repositorio JPA del desglose de impuestos por línea ({@code sale_line_taxes}).
 */
public interface SaleLineTaxRepository extends JpaRepository<SaleLineTax, Long> {

    List<SaleLineTax> findBySaleLineIdInOrderByTaxIdAsc(Collection<Long> saleLineIds);
}
