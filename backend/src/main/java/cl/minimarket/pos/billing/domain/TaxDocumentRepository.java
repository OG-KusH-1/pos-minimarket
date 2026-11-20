package cl.minimarket.pos.billing.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * Repositorio JPA de documentos tributarios electrónicos ({@code tax_documents}).
 */
public interface TaxDocumentRepository
        extends JpaRepository<TaxDocument, Long>, JpaSpecificationExecutor<TaxDocument> {

    /** Busca un documento por id restringido al local indicado. */
    Optional<TaxDocument> findByIdAndStoreId(Long id, Long storeId);

    /** Busca el documento emitido para una venta. */
    Optional<TaxDocument> findBySaleId(Long saleId);

    /** Busca el documento emitido para una venta restringido al local indicado. */
    Optional<TaxDocument> findBySaleIdAndStoreId(Long saleId, Long storeId);

    /** Busca la nota de crédito emitida para una devolución o anulación. */
    Optional<TaxDocument> findBySaleReturnId(Long saleReturnId);

    /** Busca la nota de crédito emitida para una devolución restringida al local indicado. */
    Optional<TaxDocument> findBySaleReturnIdAndStoreId(Long saleReturnId, Long storeId);
}
