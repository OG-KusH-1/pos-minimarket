package cl.minimarket.pos.inventory.application.stock;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.LotAllocation;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.application.transfer.InTransitStockQuery;
import cl.minimarket.pos.inventory.domain.ConsolidatedLotLine;
import cl.minimarket.pos.inventory.domain.ConsolidatedStockLine;
import cl.minimarket.pos.inventory.domain.FefoAllocator;
import cl.minimarket.pos.inventory.domain.StockConsolidator;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Quantity;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implementación de {@link StockApi}: única vía de modificación de {@code stock}, saldos por lote
 * ({@code lots}) y escritura en el kardex ({@code stock_movements}).
 *
 * <p><b>Estrategia de concurrencia (ADR-13 / RN-01..RN-04):</b>
 * <ul>
 *   <li>Se ejecuta obligatoriamente dentro de la transacción del llamador
 *       ({@link Propagation#MANDATORY}), bajo aislamiento {@code READ COMMITTED}.</li>
 *   <li>Las líneas se consolidan por {@code product_id} y se procesan en orden ascendente de
 *       {@code product_id} para prevenir deadlocks ante pedidos cruzados.</li>
 *   <li>El bloqueo de {@code stock} precede siempre a cualquier consulta {@code SELECT ... FOR UPDATE}
 *       sobre {@code lots} para el par {@code (store_id, product_id)}.</li>
 *   <li>Las salidas usan {@code UPDATE stock SET quantity = quantity - :qty ... WHERE quantity >= :qty
 *       RETURNING quantity}: es atómico, no requiere {@code @Version} ni reintentos y está
 *       respaldado por el {@code CHECK (quantity >= 0)} en PostgreSQL.</li>
 *   <li>En productos con {@code lot_controlled = true}, las salidas sin lote explícito consumen
 *       lotes en orden FEFO ({@code expiry_date ASC NULLS LAST, id ASC FOR UPDATE}) preservando el
 *       invariante {@code SUM(lots.quantity) = stock.quantity} por local.</li>
 *   <li>Las filas de {@code stock} deben existir previamente (las crean {@code ProductCreatedListener}
 *       y {@code StoreCreatedListener} en T24/T25); si falta la fila, se responde {@code NOT_FOUND}.</li>
 * </ul>
 */
@Service
public class StockService implements StockApi {

    private static final String LOT_STATUS_ACTIVE = "ACTIVE";
    private static final String LOT_STATUS_DEPLETED = "DEPLETED";

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final ProductCatalogApi productCatalogApi;
    private final InTransitStockQuery inTransitStockQuery;

    public StockService(
            JdbcClient jdbc,
            BusinessClock clock,
            ProductCatalogApi productCatalogApi,
            InTransitStockQuery inTransitStockQuery) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.inTransitStockQuery =
                Objects.requireNonNull(inTransitStockQuery, "inTransitStockQuery");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockMovementResult> apply(StockCommand cmd) {
        List<ConsolidatedStockLine> consolidated = StockConsolidator.consolidate(cmd);
        List<Long> productIds = consolidated.stream()
                .map(ConsolidatedStockLine::productId)
                .toList();

        Map<Long, ProductStockMetadata> products = loadProducts(productIds);
        validateProductsAndQuantities(cmd, consolidated, products);

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        boolean inbound = cmd.movementType().isInbound();
        List<StockMovementResult> results = new ArrayList<>(consolidated.size());

        for (ConsolidatedStockLine line : consolidated) {
            ProductStockMetadata product = products.get(line.productId());
            BigDecimal qty = line.quantity();
            BigDecimal balanceAfter = inbound
                    ? applyInboundUpdate(cmd.storeId(), line.productId(), qty, now)
                    : applyOutboundUpdate(cmd.storeId(), line.productId(), qty, now);

            BigDecimal delta = inbound ? qty : qty.negate();
            // ADR-15: las salidas siempre valorizan al avg_cost vigente, ignorando unitCost recibido.
            BigDecimal unitCost = inbound && line.unitCost() != null
                    ? line.unitCost()
                    : Rounding.unitCost(product.avgCost());

            List<LotAllocation> allocations = product.lotControlled()
                    ? applyLotMovements(cmd, line, unitCost, inbound, now)
                    : List.of();

            Long movementLotId = allocations.size() == 1 ? allocations.getFirst().lotId() : null;
            Long movementId = insertMovement(cmd, line, movementLotId, delta, balanceAfter, unitCost, now);
            results.add(new StockMovementResult(
                    movementId,
                    cmd.storeId(),
                    line.productId(),
                    delta,
                    balanceAfter,
                    unitCost,
                    allocations));
        }
        return List.copyOf(results);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal onHand(Long storeId, Long productId) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(productId, "productId");
        return jdbc.sql("""
                SELECT quantity
                FROM stock
                WHERE store_id = :storeId AND product_id = :productId
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .query(BigDecimal.class)
                .optional()
                .map(Rounding::quantity)
                .orElse(Quantity.ZERO.value());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal companyQuantity(Long productId) {
        Objects.requireNonNull(productId, "productId");
        BigDecimal onHandTotal = jdbc.sql("""
                SELECT COALESCE(SUM(quantity), 0)
                FROM stock
                WHERE product_id = :productId
                """)
                .param("productId", productId)
                .query(BigDecimal.class)
                .single();
        BigDecimal inTransit = inTransitStockQuery.inTransitCompanyQuantity(productId);
        return Rounding.quantity(onHandTotal.add(inTransit));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> inTransitInbound(Long storeId) {
        Objects.requireNonNull(storeId, "storeId");
        return inTransitStockQuery.inTransitInbound(storeId);
    }

    private Map<Long, ProductStockMetadata> loadProducts(List<Long> productIds) {
        List<ProductCatalogApi.ProductSnapshot> snapshots = productCatalogApi.findByIds(productIds);

        Map<Long, ProductStockMetadata> map = new HashMap<>(snapshots.size());
        for (ProductCatalogApi.ProductSnapshot snapshot : snapshots) {
            map.put(snapshot.id(), new ProductStockMetadata(
                    snapshot.id(),
                    snapshot.saleType(),
                    snapshot.unitOfMeasure(),
                    snapshot.avgCost(),
                    snapshot.lotControlled()));
        }
        return map;
    }

    private void validateProductsAndQuantities(
            StockCommand cmd,
            List<ConsolidatedStockLine> consolidated,
            Map<Long, ProductStockMetadata> products) {
        for (ConsolidatedStockLine line : consolidated) {
            ProductStockMetadata product = products.get(line.productId());
            if (product == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                        "Producto no encontrado: " + line.productId());
            }
            if (product.saleType() == SaleType.PACK) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Un producto PACK no maneja stock directo; debe resolverse al producto base: "
                                + line.productId());
            }
            if (product.unitOfMeasure() == UnitOfMeasure.UNIT) {
                if (!Quantity.of(line.quantity()).isWholeNumber()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "La cantidad debe ser entera para el producto por unidad: " + line.productId());
                }
                for (ConsolidatedLotLine lotLine : line.lotLines()) {
                    if (!Quantity.of(lotLine.quantity()).isWholeNumber()) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                "La cantidad por lote debe ser entera para el producto por unidad: "
                                        + line.productId());
                    }
                }
            }
            if (!product.lotControlled() && line.hasLotData()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "El producto " + line.productId() + " no tiene control de lotes habilitado");
            }
            if (product.lotControlled() && cmd.movementType().isInbound()) {
                for (ConsolidatedLotLine lotLine : line.lotLines()) {
                    if (!lotLine.hasExplicitLotTarget()) {
                        throw new BusinessException(ErrorCode.LOT_REQUIRED,
                                "El producto con control de lotes " + line.productId()
                                        + " requiere identificar el lote en movimientos de entrada");
                    }
                }
            }
        }
    }

    private BigDecimal applyInboundUpdate(
            Long storeId, Long productId, BigDecimal qty, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE stock
                SET quantity = quantity + :qty,
                    last_movement_at = :now,
                    updated_at = :now
                WHERE store_id = :storeId AND product_id = :productId
                RETURNING quantity
                """)
                .param("qty", qty)
                .param("now", now)
                .param("storeId", storeId)
                .param("productId", productId)
                .query(BigDecimal.class)
                .optional()
                .map(Rounding::quantity)
                .orElseThrow(() -> missingStockRowException(storeId, productId));
    }

    private BigDecimal applyOutboundUpdate(
            Long storeId, Long productId, BigDecimal qty, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE stock
                SET quantity = quantity - :qty,
                    last_movement_at = :now,
                    updated_at = :now
                WHERE store_id = :storeId
                  AND product_id = :productId
                  AND quantity >= :qty
                RETURNING quantity
                """)
                .param("qty", qty)
                .param("now", now)
                .param("storeId", storeId)
                .param("productId", productId)
                .query(BigDecimal.class)
                .optional()
                .map(Rounding::quantity)
                .orElseThrow(() -> diagnoseOutboundFailure(storeId, productId));
    }

    private BusinessException diagnoseOutboundFailure(Long storeId, Long productId) {
        boolean exists = jdbc.sql("""
                SELECT 1
                FROM stock
                WHERE store_id = :storeId AND product_id = :productId
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .query(Integer.class)
                .optional()
                .isPresent();

        if (!exists) {
            return missingStockRowException(storeId, productId);
        }
        return new BusinessException(ErrorCode.STOCK_INSUFFICIENT,
                "Stock insuficiente para el producto " + productId + " en el local " + storeId);
    }

    private BusinessException missingStockRowException(Long storeId, Long productId) {
        return new BusinessException(ErrorCode.NOT_FOUND,
                "No existe fila de stock para el producto " + productId + " en el local " + storeId);
    }

    private List<LotAllocation> applyLotMovements(
            StockCommand cmd,
            ConsolidatedStockLine line,
            BigDecimal defaultUnitCost,
            boolean inbound,
            OffsetDateTime now) {
        Map<Long, BigDecimal> mergedAllocations = new LinkedHashMap<>();

        for (ConsolidatedLotLine lotLine : line.lotLines()) {
            if (inbound) {
                BigDecimal lotCost = lotLine.unitCost() != null ? lotLine.unitCost() : defaultUnitCost;
                LotAllocation allocation = applyInboundLot(
                        cmd.storeId(), line.productId(), lotLine, lotCost, cmd.userId(), now);
                mergedAllocations.merge(allocation.lotId(), allocation.quantity(),
                        (a, b) -> Rounding.quantity(a.add(b)));
            } else {
                List<LotAllocation> deductions = applyOutboundLot(
                        cmd.storeId(), line.productId(), lotLine, cmd.userId(), now);
                for (LotAllocation deduction : deductions) {
                    mergedAllocations.merge(deduction.lotId(), deduction.quantity(),
                            (a, b) -> Rounding.quantity(a.add(b)));
                }
            }
        }

        return mergedAllocations.entrySet().stream()
                .map(entry -> new LotAllocation(entry.getKey(), Rounding.quantity(entry.getValue())))
                .toList();
    }

    private LotAllocation applyInboundLot(
            Long storeId,
            Long productId,
            ConsolidatedLotLine lotLine,
            BigDecimal lotCost,
            Long userId,
            OffsetDateTime now) {
        if (lotLine.lotId() != null) {
            LockedLotRow locked = lockLotById(lotLine.lotId(), storeId, productId, lotLine);
            jdbc.sql("""
                    UPDATE lots
                    SET quantity = quantity + :qty,
                        unit_cost = COALESCE(unit_cost, :unitCost),
                        status = :status,
                        updated_by = :userId,
                        updated_at = :now,
                        version = version + 1
                    WHERE id = :lotId
                    """)
                    .param("qty", lotLine.quantity())
                    .param("unitCost", lotCost, Types.NUMERIC)
                    .param("status", LOT_STATUS_ACTIVE)
                    .param("userId", userId, Types.BIGINT)
                    .param("now", now)
                    .param("lotId", locked.id())
                    .update();
            return new LotAllocation(locked.id(), lotLine.quantity());
        }

        Long upsertedId = jdbc.sql("""
                INSERT INTO lots (
                    store_id, product_id, lot_number, expiry_date,
                    quantity, unit_cost, received_at, status,
                    created_by, updated_by, created_at, updated_at
                ) VALUES (
                    :storeId, :productId, :lotNumber, :expiryDate,
                    :qty, :unitCost, :now, :status,
                    :userId, :userId, :now, :now
                )
                ON CONFLICT (store_id, product_id, lot_number, COALESCE(expiry_date, 'infinity'::date))
                DO UPDATE SET
                    quantity = lots.quantity + EXCLUDED.quantity,
                    unit_cost = COALESCE(lots.unit_cost, EXCLUDED.unit_cost),
                    status = :status,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at,
                    version = lots.version + 1
                RETURNING id
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .param("lotNumber", lotLine.lotNumber())
                .param("expiryDate", lotLine.expiryDate(), Types.DATE)
                .param("qty", lotLine.quantity())
                .param("unitCost", lotCost, Types.NUMERIC)
                .param("now", now)
                .param("status", LOT_STATUS_ACTIVE)
                .param("userId", userId, Types.BIGINT)
                .query(Long.class)
                .single();

        return new LotAllocation(upsertedId, lotLine.quantity());
    }

    private List<LotAllocation> applyOutboundLot(
            Long storeId,
            Long productId,
            ConsolidatedLotLine lotLine,
            Long userId,
            OffsetDateTime now) {
        if (lotLine.lotId() != null) {
            LockedLotRow locked = lockLotById(lotLine.lotId(), storeId, productId, lotLine);
            BigDecimal available = Rounding.quantity(locked.quantity());
            if (available.compareTo(lotLine.quantity()) < 0) {
                throw new BusinessException(ErrorCode.STOCK_INSUFFICIENT,
                        "Saldo insuficiente en el lote " + locked.lotNumber()
                                + " (id=" + locked.id() + "); disponible=" + available
                                + ", requerido=" + lotLine.quantity());
            }
            BigDecimal balanceAfter = Rounding.quantity(available.subtract(lotLine.quantity()));
            updateLotBalance(locked.id(), balanceAfter, userId, now);
            return List.of(new LotAllocation(locked.id(), lotLine.quantity()));
        }

        List<FefoAllocator.AvailableLot> candidates;
        if (lotLine.lotNumber() != null) {
            candidates = lockLotsByNumber(storeId, productId, lotLine.lotNumber(), lotLine.expiryDate());
            if (candidates.isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                        "Lote no encontrado: " + lotLine.lotNumber()
                                + " para producto " + productId + " en local " + storeId);
            }
        } else {
            candidates = lockFefoLots(storeId, productId);
        }

        List<FefoAllocator.LotDeduction> deductions = FefoAllocator.allocate(
                productId, lotLine.quantity(), candidates);
        for (FefoAllocator.LotDeduction deduction : deductions) {
            updateLotBalance(deduction.lotId(), deduction.balanceAfter(), userId, now);
        }
        return deductions.stream()
                .map(FefoAllocator.LotDeduction::toLotAllocation)
                .toList();
    }

    private LockedLotRow lockLotById(
            Long lotId,
            Long expectedStoreId,
            Long expectedProductId,
            ConsolidatedLotLine lotLine) {
        LockedLotRow row = jdbc.sql("""
                SELECT id, store_id, product_id, lot_number, expiry_date, quantity
                FROM lots
                WHERE id = :lotId
                FOR UPDATE
                """)
                .param("lotId", lotId)
                .query((rs, rowNum) -> {
                    Date sqlExpiry = rs.getDate("expiry_date");
                    return new LockedLotRow(
                            rs.getLong("id"),
                            rs.getLong("store_id"),
                            rs.getLong("product_id"),
                            rs.getString("lot_number"),
                            sqlExpiry != null ? sqlExpiry.toLocalDate() : null,
                            rs.getBigDecimal("quantity"));
                })
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Lote no encontrado: " + lotId));

        if (!row.storeId().equals(expectedStoreId) || !row.productId().equals(expectedProductId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El lote " + lotId + " no pertenece al producto " + expectedProductId
                            + " ni al local " + expectedStoreId);
        }
        if (lotLine.lotNumber() != null && !lotLine.lotNumber().equals(row.lotNumber())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El número de lote recibido no coincide con el lote " + lotId);
        }
        if (lotLine.expiryDate() != null && !Objects.equals(lotLine.expiryDate(), row.expiryDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "La fecha de vencimiento recibida no coincide con el lote " + lotId);
        }
        return row;
    }

    private List<FefoAllocator.AvailableLot> lockLotsByNumber(
            Long storeId,
            Long productId,
            String lotNumber,
            LocalDate expiryDate) {
        if (expiryDate != null) {
            return jdbc.sql("""
                    SELECT id, lot_number, expiry_date, quantity
                    FROM lots
                    WHERE store_id = :storeId
                      AND product_id = :productId
                      AND lot_number = :lotNumber
                      AND expiry_date = :expiryDate
                    ORDER BY id ASC
                    FOR UPDATE
                    """)
                    .param("storeId", storeId)
                    .param("productId", productId)
                    .param("lotNumber", lotNumber)
                    .param("expiryDate", expiryDate)
                    .query((rs, rowNum) -> {
                        Date sqlExpiry = rs.getDate("expiry_date");
                        return new FefoAllocator.AvailableLot(
                                rs.getLong("id"),
                                rs.getString("lot_number"),
                                sqlExpiry != null ? sqlExpiry.toLocalDate() : null,
                                rs.getBigDecimal("quantity"));
                    })
                    .list();
        }
        return jdbc.sql("""
                SELECT id, lot_number, expiry_date, quantity
                FROM lots
                WHERE store_id = :storeId
                  AND product_id = :productId
                  AND lot_number = :lotNumber
                ORDER BY expiry_date ASC NULLS LAST, id ASC
                FOR UPDATE
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .param("lotNumber", lotNumber)
                .query((rs, rowNum) -> {
                    Date sqlExpiry = rs.getDate("expiry_date");
                    return new FefoAllocator.AvailableLot(
                            rs.getLong("id"),
                            rs.getString("lot_number"),
                            sqlExpiry != null ? sqlExpiry.toLocalDate() : null,
                            rs.getBigDecimal("quantity"));
                })
                .list();
    }

    private List<FefoAllocator.AvailableLot> lockFefoLots(Long storeId, Long productId) {
        return jdbc.sql("""
                SELECT id, lot_number, expiry_date, quantity
                FROM lots
                WHERE store_id = :storeId
                  AND product_id = :productId
                  AND quantity > 0
                ORDER BY expiry_date ASC NULLS LAST, id ASC
                FOR UPDATE
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .query((rs, rowNum) -> {
                    Date sqlExpiry = rs.getDate("expiry_date");
                    return new FefoAllocator.AvailableLot(
                            rs.getLong("id"),
                            rs.getString("lot_number"),
                            sqlExpiry != null ? sqlExpiry.toLocalDate() : null,
                            rs.getBigDecimal("quantity"));
                })
                .list();
    }

    private void updateLotBalance(
            Long lotId, BigDecimal balanceAfter, Long userId, OffsetDateTime now) {
        String status = balanceAfter.signum() == 0 ? LOT_STATUS_DEPLETED : LOT_STATUS_ACTIVE;
        jdbc.sql("""
                UPDATE lots
                SET quantity = :balanceAfter,
                    status = :status,
                    updated_by = :userId,
                    updated_at = :now,
                    version = version + 1
                WHERE id = :lotId
                """)
                .param("balanceAfter", balanceAfter)
                .param("status", status)
                .param("userId", userId, Types.BIGINT)
                .param("now", now)
                .param("lotId", lotId)
                .update();
    }

    private Long insertMovement(
            StockCommand cmd,
            ConsolidatedStockLine line,
            Long lotId,
            BigDecimal delta,
            BigDecimal balanceAfter,
            BigDecimal unitCost,
            OffsetDateTime occurredAt) {
        return jdbc.sql("""
                INSERT INTO stock_movements (
                    store_id, product_id, lot_id, movement_type,
                    quantity_delta, balance_after, unit_cost,
                    source_type, source_id, source_line_id,
                    reason, user_id, occurred_at
                ) VALUES (
                    :storeId, :productId, :lotId, :movementType,
                    :quantityDelta, :balanceAfter, :unitCost,
                    :sourceType, :sourceId, :sourceLineId,
                    :reason, :userId, :occurredAt
                )
                RETURNING id
                """)
                .param("storeId", cmd.storeId())
                .param("productId", line.productId())
                .param("lotId", lotId, Types.BIGINT)
                .param("movementType", cmd.movementType().name())
                .param("quantityDelta", delta)
                .param("balanceAfter", balanceAfter)
                .param("unitCost", unitCost)
                .param("sourceType", cmd.sourceType().name())
                .param("sourceId", cmd.sourceId())
                .param("sourceLineId", line.sourceLineId(), Types.BIGINT)
                .param("reason", cmd.reason(), Types.VARCHAR)
                .param("userId", cmd.userId())
                .param("occurredAt", occurredAt)
                .query(Long.class)
                .single();
    }

    private record ProductStockMetadata(
            Long id,
            SaleType saleType,
            UnitOfMeasure unitOfMeasure,
            BigDecimal avgCost,
            boolean lotControlled) {
    }

    private record LockedLotRow(
            Long id,
            Long storeId,
            Long productId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal quantity) {
    }
}
