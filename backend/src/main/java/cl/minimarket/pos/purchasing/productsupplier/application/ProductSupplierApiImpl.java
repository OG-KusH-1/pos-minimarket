package cl.minimarket.pos.purchasing.productsupplier.application;

import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.purchasing.productsupplier.domain.ProductSupplierRepository;
import cl.minimarket.pos.purchasing.supplier.domain.Supplier;
import cl.minimarket.pos.purchasing.supplier.domain.SupplierRepository;
import cl.minimarket.pos.shared.rut.Rut;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Implementación de la API pública {@link ProductSupplierApi} del módulo {@code purchasing}.
 */
@Service
public class ProductSupplierApiImpl implements ProductSupplierApi {

    private final ProductSupplierService productSupplierService;
    private final ProductSupplierQueryService productSupplierQueryService;
    private final ProductSupplierRepository productSupplierRepository;
    private final SupplierRepository supplierRepository;

    public ProductSupplierApiImpl(ProductSupplierService productSupplierService,
                                  ProductSupplierQueryService productSupplierQueryService,
                                  ProductSupplierRepository productSupplierRepository,
                                  SupplierRepository supplierRepository) {
        this.productSupplierService = Objects.requireNonNull(productSupplierService, "productSupplierService");
        this.productSupplierQueryService = Objects.requireNonNull(productSupplierQueryService, "productSupplierQueryService");
        this.productSupplierRepository = Objects.requireNonNull(productSupplierRepository, "productSupplierRepository");
        this.supplierRepository = Objects.requireNonNull(supplierRepository, "supplierRepository");
    }

    @Override
    @Transactional
    public ProductSupplierInfo upsert(ProductSupplierCommand command) {
        return productSupplierService.upsert(command);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductSupplierInfo> find(Long productId, Long supplierId) {
        return productSupplierRepository.findByProductIdAndSupplierId(productId, supplierId)
                .map(ProductSupplierService::toApiInfo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductSupplierCandidate> candidates(Long productId) {
        return productSupplierQueryService.findCandidates(productId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SupplierRef> findSupplierById(Long supplierId) {
        return supplierRepository.findById(supplierId).map(ProductSupplierApiImpl::toSupplierRef);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SupplierRef> findSupplierByRut(String rut) {
        return Rut.parse(rut)
                .flatMap(parsed -> supplierRepository.findByRut(parsed.normalized()))
                .map(ProductSupplierApiImpl::toSupplierRef);
    }

    private static SupplierRef toSupplierRef(Supplier s) {
        return new SupplierRef(
                s.getId(),
                s.getRut(),
                s.getLegalName(),
                s.getTradeName(),
                s.getLeadTimeDays(),
                s.getDeliveryWeekdaysList(),
                s.isActive());
    }
}
