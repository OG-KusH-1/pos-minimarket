package cl.minimarket.pos.sales.application.sale;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repositorio JPA de líneas de venta ({@code sale_lines}).
 */
public interface SaleLineRepository extends JpaRepository<SaleLine, Long> {

    List<SaleLine> findBySaleIdOrderByLineNumberAsc(Long saleId);
}
