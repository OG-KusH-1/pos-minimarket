package cl.minimarket.pos.billing.application;

import cl.minimarket.pos.billing.domain.TaxDocument;
import cl.minimarket.pos.billing.domain.TaxDocumentRepository;
import cl.minimarket.pos.sales.SaleLookupApi;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Servicio de consultas de solo lectura sobre documentos tributarios electrónicos.
 */
@Service
@Transactional(readOnly = true)
public class TaxDocumentQueryService {

    private final TaxDocumentRepository repository;
    private final SaleLookupApi saleLookupApi;
    private final BusinessClock clock;

    public TaxDocumentQueryService(
            TaxDocumentRepository repository,
            SaleLookupApi saleLookupApi,
            BusinessClock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.saleLookupApi = Objects.requireNonNull(saleLookupApi, "saleLookupApi");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Lista paginada de documentos tributarios del local activo según los filtros indicados.
     */
    public Page<TaxDocument> list(TaxDocumentFilter filter, Pageable pageable) {
        Long storeId = StoreContext.require().storeId();
        Specification<TaxDocument> spec = buildSpec(storeId, filter);
        return repository.findAll(spec, pageable);
    }

    /**
     * Obtiene el detalle de un documento tributario del local activo.
     */
    public TaxDocument getById(Long id) {
        Long storeId = StoreContext.require().storeId();
        return repository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Documento tributario no encontrado."));
    }

    /**
     * Busca el documento tributario asociado a una venta respetando el alcance "P" (cajero).
     *
     * <p>Un usuario sin alcance global de lectura de ventas solo ve el documento de sus propias
     * ventas: {@code billing} valida la propiedad con {@link SaleLookupApi#cashierOf(Long)} y una
     * venta ajena responde 404 {@code NOT_FOUND} ({@code API.md} F.2, nota de alcance de Sales).
     *
     * @param saleId identificador de la venta
     * @return documento si ya fue emitido, o vacío si aún está pendiente de emisión asíncrona
     */
    public Optional<TaxDocument> findBySaleId(Long saleId) {
        Long storeId = StoreContext.require().storeId();
        Long originCashierId = saleLookupApi.cashierOf(saleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Venta no encontrada."));
        if (!hasFullSaleReadScope()) {
            assertCurrentUserIsCashier(originCashierId, "Venta no encontrada.");
        }
        return repository.findBySaleIdAndStoreId(saleId, storeId);
    }

    /**
     * Busca la nota de crédito asociada a una devolución o anulación en el local activo,
     * respetando el alcance "P" (cajero).
     *
     * <p>El alcance se determina por la venta origen: un cajero solo ve las notas de crédito de
     * las devoluciones de SUS ventas y una devolución ajena responde 404 {@code NOT_FOUND}
     * ({@code API.md} F.2: "El alcance P también aplica a {@code GET /sale-returns*}"). La venta
     * origen se resuelve dentro de {@code billing} con
     * {@link #resolveOriginSaleId(TaxDocument, Long)}. Mientras no exista el documento no hay
     * nada que proteger, por lo que la validación solo aplica cuando el documento existe.
     *
     * @param saleReturnId identificador de la devolución
     * @return documento si ya fue emitido, o vacío si aún no se emite
     */
    public Optional<TaxDocument> findBySaleReturnId(Long saleReturnId) {
        Long storeId = StoreContext.require().storeId();
        Optional<TaxDocument> document = repository.findBySaleReturnIdAndStoreId(saleReturnId, storeId);
        if (document.isPresent() && !hasFullSaleReadScope()) {
            Long originSaleId = resolveOriginSaleId(document.get(), storeId);
            Long originCashierId = saleLookupApi.cashierOf(originSaleId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Devolución no encontrada."));
            assertCurrentUserIsCashier(originCashierId, "Devolución no encontrada.");
        }
        return document;
    }

    /**
     * Valida, para usuarios sin alcance global de lectura de ventas, que el usuario autenticado
     * sea el cajero dueño de la venta origen (alcance "P").
     *
     * <p>La validación no filtra información: cualquier discrepancia (venta inexistente o
     * ajena) se reporta como 404 {@code NOT_FOUND}, sin revelar la existencia del recurso a
     * quien no es su dueño.
     *
     * @param originCashierId cajero que emitió la venta origen, ya resuelto vía
     *                        {@link SaleLookupApi#cashierOf(Long)}
     * @param notFoundMessage mensaje del error 404 mostrado al usuario
     */
    private static void assertCurrentUserIsCashier(Long originCashierId, String notFoundMessage) {
        Long currentUserId = CurrentUserId.find()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHENTICATED, "No hay usuario autenticado."));
        if (!currentUserId.equals(originCashierId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, notFoundMessage);
        }
    }

    /**
     * Resuelve la venta origen de un documento tributario sin depender de las tablas internas
     * del módulo {@code sales}.
     *
     * <p>En las boletas el origen es directo ({@code sale_id}). Las notas de crédito solo
     * guardan el id de la devolución, por lo que se sigue la referencia al documento original:
     * {@code referenced_document_id} → boleta → {@code sale_id}, y de ahí se consulta
     * {@link SaleLookupApi#cashierOf(Long)}.
     *
     * <p>Limitación conocida (revisar al consolidar): si la boleta original nunca llegó a
     * persistirse, la nota de crédito queda sin referencia y no existe vía dentro de
     * {@code billing} para resolver la venta origen. En ese caso se responde 404
     * {@code NOT_FOUND} (cierre seguro) para no exponer la nota a cajeros sin alcance global.
     * Extender {@code SaleLookupApi} con un {@code cashierOfReturn} sería un cambio de base
     * común que decide la orquestación.
     *
     * @param document documento tributario cuyo origen se quiere resolver
     * @param storeId  local activo, para restringir la búsqueda del documento referenciado
     * @return identificador de la venta origen del documento
     */
    private Long resolveOriginSaleId(TaxDocument document, Long storeId) {
        if (document.getSaleId() != null) {
            return document.getSaleId();
        }
        Long referencedId = document.getReferencedDocumentId();
        if (referencedId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Devolución no encontrada.");
        }
        return repository.findByIdAndStoreId(referencedId, storeId)
                .map(TaxDocument::getSaleId)
                .filter(Objects::nonNull)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Devolución no encontrada."));
    }

    private Specification<TaxDocument> buildSpec(Long storeId, TaxDocumentFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("storeId"), storeId));

            if (filter != null) {
                if (filter.status() != null) {
                    predicates.add(cb.equal(root.get("status"), filter.status()));
                }
                if (filter.documentType() != null) {
                    predicates.add(cb.equal(root.get("documentType"), filter.documentType()));
                }
                if (filter.folio() != null) {
                    predicates.add(cb.equal(root.get("folio"), filter.folio()));
                }
                if (filter.from() != null) {
                    Instant fromInstant = clock.startOfDay(filter.from());
                    predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInstant));
                }
                if (filter.to() != null) {
                    Instant toExclusive = clock.startOfDay(filter.to().plusDays(1));
                    predicates.add(cb.lessThan(root.get("createdAt"), toExclusive));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Indica si el usuario actual tiene alcance global de lectura de ventas.
     *
     * <p>Se alinea con {@code SaleSecuritySupport} (módulo {@code sales}) y con T71:
     * {@code sales:sale:read} NO está en la lista porque en el rol CAJ es alcance "P"
     * (solo lo propio, {@code ROLES_PERMISOS.md} D.1), de modo que un cajero real siempre pasa
     * por la validación de propiedad. Los permisos listados solo los tienen en D.1 roles con
     * lectura global (ADM/GER/SUP).
     */
    private static boolean hasFullSaleReadScope() {
        return hasAuthority(Permissions.SALES_TAX_DOCUMENT_READ)
                || hasAuthority(Permissions.SALES_SALE_VOID)
                || hasAuthority(Permissions.SALES_RETURN_CREATE)
                || hasAuthority(Permissions.SALES_DISCOUNT_OVERRIDE)
                || hasAuthority(Permissions.SALES_RECEIPT_REPRINT)
                || hasAuthority(Permissions.REPORT_SALES_READ);
    }

    private static boolean hasAuthority(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (code.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
