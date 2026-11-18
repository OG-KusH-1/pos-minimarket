package cl.minimarket.pos.sales.application.pos;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.PackInfo;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.ProductCatalogApi.ScanResult;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Servicio POS de búsqueda y resolución de productos ({@code PLAN.md} H.3 T32, {@code API.md} F.2).
 *
 * <p>Compone las proyecciones de {@link ProductCatalogApi} con el saldo disponible en el local
 * activo obtenido desde {@link StockApi#onHand(Long, Long)}.
 */
@Service
public class PosProductService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 20;

    private final ProductCatalogApi productCatalogApi;
    private final StockApi stockApi;

    public PosProductService(
            ProductCatalogApi productCatalogApi,
            StockApi stockApi
    ) {
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.stockApi = Objects.requireNonNull(stockApi, "stockApi");
    }

    /**
     * Busca productos activos por nombre (trigram sin tildes), SKU, atajo o prefijo de código,
     * enriqueciendo cada resultado con el stock disponible en {@code storeId}.
     *
     * @param storeId identificador del local activo
     * @param q       texto de búsqueda
     * @param limit   máximo de resultados (se acota a [1, 20], por defecto 20)
     * @return lista de productos activos con su existencia en el local
     */
    @Transactional(readOnly = true)
    public List<PosProductResult> search(Long storeId, String q, Integer limit) {
        Objects.requireNonNull(storeId, "storeId");
        if (q == null || q.isBlank()) {
            return List.of();
        }
        int effectiveLimit = limit == null
                ? DEFAULT_LIMIT
                : Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<ProductSnapshot> snapshots = productCatalogApi.search(q, effectiveLimit);
        return snapshots.stream()
                .map(snapshot -> toPosProductResult(storeId, snapshot))
                .toList();
    }

    /**
     * Resuelve un código escaneado o ingresado en POS (código de barras, balanza, atajo o SKU)
     * y lo enriquece con el stock disponible en {@code storeId}.
     *
     * @param storeId identificador del local activo
     * @param code    código a resolver
     * @return resultado con producto, cantidad/precio embebido y origen
     * @throws BusinessException {@code VALIDATION_ERROR} si el código viene vacío,
     *                           {@code PRODUCT_INACTIVE} si corresponde a un producto inactivo,
     *                           {@code CODE_NOT_FOUND} si no existe ningún producto asociado
     */
    @Transactional(readOnly = true)
    public ScanResultResponse resolve(Long storeId, String code) {
        Objects.requireNonNull(storeId, "storeId");
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El código es obligatorio.");
        }
        String trimmed = code.trim();

        Optional<ScanResult> resolved = productCatalogApi.resolveCode(trimmed);
        if (resolved.isPresent()) {
            ScanResult scan = resolved.get();
            if (!scan.product().active()) {
                throw new BusinessException(ErrorCode.PRODUCT_INACTIVE, "El producto está inactivo.");
            }
            return new ScanResultResponse(
                    toPosProductResult(storeId, scan.product()),
                    scan.quantity(),
                    scan.embeddedPrice(),
                    scan.source()
            );
        }

        if (productCatalogApi.isInactiveCodeMatch(trimmed)) {
            throw new BusinessException(ErrorCode.PRODUCT_INACTIVE, "El producto está inactivo.");
        }
        throw new BusinessException(ErrorCode.CODE_NOT_FOUND, "Código no encontrado.");
    }

    private PosProductResult toPosProductResult(Long storeId, ProductSnapshot snapshot) {
        BigDecimal stockOnHand = resolveStockOnHand(storeId, snapshot);
        return new PosProductResult(
                snapshot.id(),
                snapshot.sku(),
                snapshot.name(),
                snapshot.saleType(),
                snapshot.unitOfMeasure(),
                snapshot.priceGross(),
                snapshot.primaryBarcode(),
                snapshot.shortcutCode(),
                snapshot.restrictedType() != null,
                stockOnHand,
                snapshot.packFactor()
        );
    }

    private BigDecimal resolveStockOnHand(Long storeId, ProductSnapshot snapshot) {
        if (snapshot.saleType() == SaleType.PACK) {
            Optional<PackInfo> packInfo = productCatalogApi.packOf(snapshot.id());
            if (packInfo.isPresent() && packInfo.get().factor() > 0) {
                BigDecimal baseStock = stockApi.onHand(storeId, packInfo.get().baseProductId());
                return Rounding.quantity(
                        baseStock.divide(BigDecimal.valueOf(packInfo.get().factor()), 0, RoundingMode.DOWN)
                );
            }
        }
        return stockApi.onHand(storeId, snapshot.id());
    }
}
