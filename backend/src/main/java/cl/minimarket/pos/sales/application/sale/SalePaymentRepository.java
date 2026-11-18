package cl.minimarket.pos.sales.application.sale;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repositorio JPA de pagos registrados en una venta ({@code sale_payments}).
 */
public interface SalePaymentRepository extends JpaRepository<SalePayment, Long> {

    List<SalePayment> findBySaleIdOrderByIdAsc(Long saleId);
}
