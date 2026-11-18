package cl.minimarket.pos.inventory.web.stock;

import cl.minimarket.pos.inventory.application.query.StockQueryService.StockByStoreView;
import cl.minimarket.pos.inventory.application.query.StockQueryService.StoreStockView;

import java.math.BigDecimal;
import java.util.List;

/**
 * Existencia de un producto desglosada por los locales asignados al usuario, más tránsito entrante
 * y total de empresa ({@code API.md} F.3).
 */
public record StockByStoreResponse(
        Long productId,
        List<StoreStockResponse> stores,
        BigDecimal inTransit,
        BigDecimal companyQuantity
) {

    /** Convierte una vista de consulta en DTO de respuesta. */
    public static StockByStoreResponse from(StockByStoreView view) {
        List<StoreStockResponse> stores = view.stores().stream()
                .map(StoreStockResponse::from)
                .toList();
        return new StockByStoreResponse(
                view.productId(),
                stores,
                view.inTransit(),
                view.companyQuantity());
    }

    /** Existencia de un producto en un local específico. */
    public record StoreStockResponse(
            Long storeId,
            String storeName,
            BigDecimal quantity,
            BigDecimal minQty,
            BigDecimal maxQty
    ) {

        /** Convierte una vista de local en DTO. */
        public static StoreStockResponse from(StoreStockView view) {
            return new StoreStockResponse(
                    view.storeId(),
                    view.storeName(),
                    view.quantity(),
                    view.minQty(),
                    view.maxQty());
        }
    }
}
