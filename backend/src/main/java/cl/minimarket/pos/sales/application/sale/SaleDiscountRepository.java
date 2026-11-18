package cl.minimarket.pos.sales.application.sale;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repositorio JPA de descuentos aplicados a una venta ({@code sale_discounts}).
 */
public interface SaleDiscountRepository extends JpaRepository<SaleDiscount, Long> {

    List<SaleDiscount> findBySaleIdOrderByIdAsc(Long saleId);
}
