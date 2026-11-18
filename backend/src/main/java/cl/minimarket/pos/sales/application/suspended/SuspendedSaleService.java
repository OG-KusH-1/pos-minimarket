package cl.minimarket.pos.sales.application.suspended;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.sales.application.pricing.PricingModels;
import cl.minimarket.pos.sales.application.pricing.PricingService;
import cl.minimarket.pos.sales.application.suspended.SuspendedCartPayload.DiscountPayload;
import cl.minimarket.pos.sales.application.suspended.SuspendedCartPayload.LinePayload;
import cl.minimarket.pos.sales.application.suspended.SuspendedSaleModels.CartData;
import cl.minimarket.pos.sales.application.suspended.SuspendedSaleModels.CartLineData;
import cl.minimarket.pos.sales.application.suspended.SuspendedSaleModels.DiscountData;
import cl.minimarket.pos.sales.application.suspended.SuspendedSaleModels.SuspendCommand;
import cl.minimarket.pos.sales.application.suspended.SuspendedSaleModels.SuspendedSaleDetail;
import cl.minimarket.pos.security.AuthorizationRef;
import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.SupervisorOverrideApi;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Servicio de aplicación para suspender, listar, retomar, eliminar y expirar carritos en el POS
 * ({@code HU-POS-04}, {@code API.md} F.2/F.3 y {@code MODELO_DATOS.md} B.9).
 */
@Service
public class SuspendedSaleService {

    static final String SETTING_MAX_PER_TERMINAL = "pos.suspended.max_per_terminal";
    static final String SETTING_TTL_HOURS = "pos.suspended.ttl_hours";
    private static final int MAX_LABEL_LENGTH = 60;
    private static final int MAX_CART_LINES = 300;

    private final SuspendedSaleRepository repository;
    private final PricingService pricingService;
    private final SettingsApi settingsApi;
    private final SupervisorOverrideApi supervisorOverrideApi;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper;

    public SuspendedSaleService(
            SuspendedSaleRepository repository,
            PricingService pricingService,
            SettingsApi settingsApi,
            SupervisorOverrideApi supervisorOverrideApi,
            AuditService auditService,
            BusinessClock businessClock,
            JdbcClient jdbcClient,
            JsonMapper jsonMapper
    ) {
        this.repository = repository;
        this.pricingService = pricingService;
        this.settingsApi = settingsApi;
        this.supervisorOverrideApi = supervisorOverrideApi;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.jdbcClient = jdbcClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Lista las ventas suspendidas vigentes de la terminal activa en orden cronológico ascendente.
     *
     * @return ventas suspendidas activas con su total estimado actualizado
     */
    @Transactional(readOnly = true)
    public List<SuspendedSaleDetail> listForCurrentTerminal() {
        StoreContext ctx = StoreContext.require();
        Long currentUserId = requireCurrentUserId();
        Instant now = businessClock.now();

        List<SuspendedSale> sales = repository
                .findByStoreIdAndTerminalIdAndExpiresAtAfterOrderByCreatedAtAsc(
                        ctx.storeId(), ctx.terminalId(), now);
        if (sales.isEmpty()) {
            return List.of();
        }

        Map<Long, String> userNames = resolveUserNames(
                sales.stream().map(SuspendedSale::getUserId).collect(Collectors.toSet()));

        return sales.stream()
                .map(sale -> toDetail(sale, userNames.getOrDefault(sale.getUserId(), String.valueOf(sale.getUserId())), currentUserId))
                .toList();
    }

    /**
     * Suspende el carrito actual de la terminal activa validando límite de suspendidas por terminal,
     * cliente opcional, existencia/vigencia de productos vía {@link PricingService} y calculando
     * {@code expires_at} según {@code pos.suspended.ttl_hours}.
     *
     * @param command datos del carrito a suspender
     * @return detalle de la venta suspendida recién creada
     */
    @Transactional
    public SuspendedSaleDetail suspend(SuspendCommand command) {
        StoreContext ctx = StoreContext.require();
        Long userId = requireCurrentUserId();
        Instant now = businessClock.now();

        validateCartBasics(command.cart());
        String normalizedLabel = normalizeLabel(command.label());
        validateCustomerIfPresent(command.customerId());

        // Cotizar antes de tomar el bloqueo de terminal para validar productos/precios y estimar total.
        BigDecimal estimatedTotal = quoteTotalPayable(ctx.storeId(), userId, command.cart(), command.customerId());

        // Serializar concurrencia por terminal al verificar el tope de ventas suspendidas.
        lockTerminalInStore(ctx.storeId(), ctx.terminalId());

        int maxPerTerminal = resolvePositiveSetting(SETTING_MAX_PER_TERMINAL, ctx.storeId(), 5);
        long activeCount = repository.countByStoreIdAndTerminalIdAndExpiresAtAfter(
                ctx.storeId(), ctx.terminalId(), now);
        if (activeCount >= maxPerTerminal) {
            throw new BusinessException(
                    ErrorCode.SUSPENDED_LIMIT_REACHED,
                    "La terminal alcanzó el límite de " + maxPerTerminal + " ventas suspendidas activas");
        }

        int ttlHours = resolvePositiveSetting(SETTING_TTL_HOURS, ctx.storeId(), 24);
        Instant expiresAt = now.plus(Duration.ofHours(ttlHours));

        SuspendedCartPayload payload = toPayload(command.cart(), estimatedTotal);
        String serializedCart = writePayload(payload);

        SuspendedSale saved = repository.saveAndFlush(SuspendedSale.create(
                ctx.storeId(),
                ctx.terminalId(),
                userId,
                normalizedLabel,
                command.customerId(),
                serializedCart,
                expiresAt
        ));

        String userName = resolveUserNames(Set.of(userId)).getOrDefault(userId, String.valueOf(userId));
        return toDetail(saved, payload, userName, estimatedTotal);
    }

    /**
     * Retoma una venta suspendida vigente de la terminal activa, devolviendo su carrito y
     * eliminándola físicamente de forma atómica bajo bloqueo pesimista.
     *
     * @param id identificador de la venta suspendida
     * @return detalle con el carrito recuperado
     */
    @Transactional
    public SuspendedSaleDetail resume(Long id) {
        StoreContext ctx = StoreContext.require();
        Long userId = requireCurrentUserId();
        Instant now = businessClock.now();

        SuspendedSale entity = repository
                .findByIdAndStoreIdAndTerminalIdForUpdate(id, ctx.storeId(), ctx.terminalId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Venta suspendida no encontrada"));

        if (!entity.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "La venta suspendida expiró");
        }

        String userName = resolveUserNames(Set.of(entity.getUserId()))
                .getOrDefault(entity.getUserId(), String.valueOf(entity.getUserId()));
        SuspendedSaleDetail detail = toDetail(entity, userName, userId);

        repository.delete(entity);
        return detail;
    }

    /**
     * Elimina físicamente una venta suspendida vigente de la terminal activa.
     *
     * <p>Si pertenece a otro usuario y quien ejecuta no posee el permiso
     * {@code sales:suspended:delete-others}, exige autorización de supervisor con acción
     * {@link OverrideAction#SUSPENDED_SALE_DELETE} y contexto {@code {suspendedSaleId}}. Registra el
     * evento de auditoría {@code SUSPENDED_SALE_DELETED}.
     *
     * @param id identificador de la venta suspendida
     * @param overrideToken token efímero opcional en encabezado {@code X-Override-Token}
     * @param canDeleteOthers si el usuario autenticado posee {@code sales:suspended:delete-others}
     */
    @Transactional
    public void delete(Long id, String overrideToken, boolean canDeleteOthers) {
        StoreContext ctx = StoreContext.require();
        Long currentUserId = requireCurrentUserId();
        Instant now = businessClock.now();

        SuspendedSale entity = repository
                .findByIdAndStoreIdAndTerminalIdForUpdate(id, ctx.storeId(), ctx.terminalId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Venta suspendida no encontrada"));

        if (!entity.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "La venta suspendida expiró");
        }

        AuthorizationRef authorizationRef = null;
        boolean isOwner = entity.getUserId().equals(currentUserId);
        if (!isOwner && !canDeleteOthers) {
            Map<String, Object> context = Map.of("suspendedSaleId", entity.getId());
            if (overrideToken == null || overrideToken.isBlank()) {
                throw new BusinessException(
                        ErrorCode.OVERRIDE_REQUIRED,
                        "Se requiere autorización de supervisor para eliminar la venta suspendida de otro usuario",
                        Map.of(
                                "action", OverrideAction.SUSPENDED_SALE_DELETE.name(),
                                "context", context
                        ));
            }
            String contextHash = supervisorOverrideApi.canonicalContextHash(context);
            authorizationRef = supervisorOverrideApi.consume(
                    overrideToken.trim(),
                    OverrideAction.SUSPENDED_SALE_DELETE,
                    contextHash,
                    currentUserId,
                    "suspended_sale",
                    String.valueOf(entity.getId()));
        }

        Map<String, Object> auditOldValue = buildAuditSnapshot(entity);
        repository.delete(entity);

        auditService.record(new AuditEvent(
                "SUSPENDED_SALE_DELETED",
                "suspended_sale",
                String.valueOf(entity.getId()),
                auditOldValue,
                null,
                null,
                authorizationRef == null ? null : authorizationRef.authorizationId()
        ));
    }

    /**
     * Purga ventas suspendidas vencidas en todos los locales sin requerir {@link StoreContext}.
     *
     * <p>Usa {@code FOR UPDATE SKIP LOCKED} para evitar contención con retomas o anulaciones
     * concurrentes en curso sobre una fila específica.
     *
     * @param asOf instante de corte; se eliminan filas con {@code expires_at <= asOf}
     * @return cantidad de filas eliminadas
     */
    @Transactional
    public int purgeExpired(Instant asOf) {
        return jdbcClient.sql("""
                        DELETE FROM suspended_sales
                        WHERE id IN (
                            SELECT id
                            FROM suspended_sales
                            WHERE expires_at <= :asOf
                            FOR UPDATE SKIP LOCKED
                        )
                        """)
                .param("asOf", Timestamp.from(asOf))
                .update();
    }

    private Long requireCurrentUserId() {
        return CurrentUserId.find().orElseThrow(() ->
                new BusinessException(ErrorCode.UNAUTHENTICATED, "Usuario autenticado requerido"));
    }

    private void validateCartBasics(CartData cart) {
        if (cart == null || cart.lines() == null || cart.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El carrito suspendido debe incluir al menos una línea");
        }
        if (cart.lines().size() > MAX_CART_LINES) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El carrito supera el máximo de " + MAX_CART_LINES + " líneas");
        }
    }

    private String normalizeLabel(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LABEL_LENGTH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "La etiqueta no puede superar los " + MAX_LABEL_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private void validateCustomerIfPresent(Long customerId) {
        if (customerId == null) {
            return;
        }
        Boolean active = jdbcClient.sql("SELECT active FROM customers WHERE id = :id")
                .param("id", customerId)
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Cliente no encontrado"));
        if (!Boolean.TRUE.equals(active)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El cliente está inactivo");
        }
    }

    private void lockTerminalInStore(Long storeId, Long terminalId) {
        jdbcClient.sql("SELECT id FROM terminals WHERE id = :terminalId AND store_id = :storeId FOR UPDATE")
                .param("terminalId", terminalId)
                .param("storeId", storeId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Terminal no encontrada en el local"));
    }

    private int resolvePositiveSetting(String key, Long storeId, int fallback) {
        Integer value = settingsApi.get(key, storeId, Integer.class);
        return (value != null && value > 0) ? value : fallback;
    }

    private BigDecimal quoteTotalPayable(Long storeId, Long userId, CartData cart, Long customerId) {
        List<PricingModels.LineInput> lines = cart.lines().stream()
                .map(line -> new PricingModels.LineInput(
                        line.productId(),
                        line.quantity(),
                        line.scannedCode(),
                        line.embeddedPrice(),
                        toPricingDiscount(line.manualDiscount())))
                .toList();
        PricingModels.DiscountSpec totalDiscount = toPricingDiscount(cart.totalDiscount());

        PricingModels.QuoteResult quote = pricingService.quote(new PricingModels.QuoteCommand(
                storeId,
                userId,
                true,
                lines,
                totalDiscount,
                customerId,
                List.of()
        ));
        return quote.totalPayable();
    }

    private BigDecimal refreshEstimatedTotal(
            Long storeId,
            Long currentUserId,
            CartData cart,
            Long customerId,
            BigDecimal fallbackTotal
    ) {
        try {
            return quoteTotalPayable(storeId, currentUserId, cart, customerId);
        } catch (BusinessException ex) {
            // Si algún producto fue desactivado o quedó sin precio tras suspender, se conserva el último estimado.
            return fallbackTotal != null ? fallbackTotal : BigDecimal.ZERO;
        }
    }

    private PricingModels.DiscountSpec toPricingDiscount(DiscountData discount) {
        if (discount == null) {
            return null;
        }
        return new PricingModels.DiscountSpec(discount.kind(), discount.value(), discount.reason());
    }

    private SuspendedCartPayload toPayload(CartData cart, BigDecimal estimatedTotal) {
        List<LinePayload> linePayloads = cart.lines().stream()
                .map(l -> new LinePayload(
                        l.productId(),
                        l.quantity(),
                        l.scannedCode(),
                        l.embeddedPrice(),
                        toDiscountPayload(l.manualDiscount())))
                .toList();
        return new SuspendedCartPayload(
                SuspendedCartPayload.CURRENT_SCHEMA_VERSION,
                linePayloads,
                toDiscountPayload(cart.totalDiscount()),
                estimatedTotal
        );
    }

    private DiscountPayload toDiscountPayload(DiscountData discount) {
        if (discount == null) {
            return null;
        }
        return new DiscountPayload(discount.kind(), discount.value(), discount.reason());
    }

    private CartData fromPayload(SuspendedCartPayload payload) {
        List<CartLineData> lines = payload.lines() == null
                ? List.of()
                : payload.lines().stream()
                        .map(l -> new CartLineData(
                                l.productId(),
                                l.quantity(),
                                l.scannedCode(),
                                l.embeddedPrice(),
                                fromDiscountPayload(l.manualDiscount())))
                        .toList();
        return new CartData(lines, fromDiscountPayload(payload.totalDiscount()));
    }

    private DiscountData fromDiscountPayload(DiscountPayload discount) {
        if (discount == null) {
            return null;
        }
        return new DiscountData(discount.kind(), discount.value(), discount.reason());
    }

    private String writePayload(SuspendedCartPayload payload) {
        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("No se pudo serializar el carrito suspendido", e);
        }
    }

    private SuspendedCartPayload readPayload(String rawJson) {
        try {
            return jsonMapper.readValue(rawJson, SuspendedCartPayload.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("No se pudo deserializar el carrito suspendido", e);
        }
    }

    private SuspendedSaleDetail toDetail(SuspendedSale entity, String userName, Long currentUserId) {
        SuspendedCartPayload payload = readPayload(entity.getCart());
        CartData cart = fromPayload(payload);
        BigDecimal estimatedTotal = refreshEstimatedTotal(
                entity.getStoreId(),
                currentUserId,
                cart,
                entity.getCustomerId(),
                payload.estimatedTotal());
        return new SuspendedSaleDetail(
                entity.getId(),
                entity.getLabel(),
                userName,
                entity.getCustomerId(),
                cart,
                cart.lines().size(),
                estimatedTotal,
                entity.getCreatedAt(),
                entity.getExpiresAt()
        );
    }

    private SuspendedSaleDetail toDetail(
            SuspendedSale entity,
            SuspendedCartPayload payload,
            String userName,
            BigDecimal estimatedTotal
    ) {
        CartData cart = fromPayload(payload);
        return new SuspendedSaleDetail(
                entity.getId(),
                entity.getLabel(),
                userName,
                entity.getCustomerId(),
                cart,
                cart.lines().size(),
                estimatedTotal,
                entity.getCreatedAt(),
                entity.getExpiresAt()
        );
    }

    private Map<Long, String> resolveUserNames(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> resolved = new HashMap<>();
        jdbcClient.sql("SELECT id, username, full_name FROM users WHERE id IN (:ids)")
                .param("ids", userIds)
                .query(rs -> {
                    long id = rs.getLong("id");
                    String fullName = rs.getString("full_name");
                    String username = rs.getString("username");
                    String display = (fullName != null && !fullName.isBlank()) ? fullName : username;
                    resolved.put(id, display);
                });
        return resolved;
    }

    private Map<String, Object> buildAuditSnapshot(SuspendedSale entity) {
        SuspendedCartPayload payload = readPayload(entity.getCart());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", entity.getId());
        snapshot.put("storeId", entity.getStoreId());
        snapshot.put("terminalId", entity.getTerminalId());
        snapshot.put("userId", entity.getUserId());
        snapshot.put("label", entity.getLabel());
        snapshot.put("customerId", entity.getCustomerId());
        snapshot.put("expiresAt", entity.getExpiresAt().toString());
        snapshot.put("cart", payload);
        return snapshot;
    }
}
