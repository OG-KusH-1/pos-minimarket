package cl.minimarket.pos.inventory.application.lot;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Servicio de corrección de metadatos de lotes ({@code lot_number} y {@code expiry_date}), con
 * bloqueo optimista por {@code version} y trazabilidad en {@code audit_log}.
 */
@Service
public class LotUpdateService {

    private static final int MAX_LOT_NUMBER_LENGTH = 40;
    private static final int MIN_REASON_LENGTH = 3;
    private static final int MAX_REASON_LENGTH = 250;
    private static final String ACTION_LOT_UPDATED = "LOT_UPDATED";
    private static final String ENTITY_LOT = "lot";

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final AuditService auditService;
    private final LotQueryService lotQueryService;

    public LotUpdateService(
            JdbcClient jdbc,
            BusinessClock clock,
            AuditService auditService,
            LotQueryService lotQueryService) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.lotQueryService = Objects.requireNonNull(lotQueryService, "lotQueryService");
    }

    /**
     * Corrige {@code lotNumber} y/o {@code expiryDate} de un lote existente del local activo
     * verificando pertenencia al local bajo bloqueo {@code FOR UPDATE}, control de concurrencia
     * optimista, unicidad en {@code uq_lots_store_product_number_expiry} y registro de motivo en
     * auditoría ({@code LOT_UPDATED}).
     *
     * @param storeId identificador del local activo
     * @param lotId identificador del lote
     * @param lotNumber nuevo código de lote (obligatorio, 1 a 40 caracteres)
     * @param expiryDate nueva fecha de vencimiento (puede ser {@code null})
     * @param reason motivo de la corrección para auditoría (obligatorio, 3 a 250 caracteres)
     * @param expectedVersion versión esperada del registro para control optimista
     * @return vista actualizada del lote
     */
    @Transactional
    public LotQueryService.LotView updateLot(
            Long storeId,
            Long lotId,
            String lotNumber,
            LocalDate expiryDate,
            String reason,
            Long expectedVersion) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(lotId, "lotId");
        Objects.requireNonNull(expectedVersion, "expectedVersion");

        String normalizedNumber = lotNumber == null ? "" : lotNumber.trim();
        if (normalizedNumber.isEmpty() || normalizedNumber.length() > MAX_LOT_NUMBER_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El número de lote debe tener entre 1 y " + MAX_LOT_NUMBER_LENGTH + " caracteres");
        }

        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() < MIN_REASON_LENGTH
                || normalizedReason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El motivo de la corrección debe tener entre " + MIN_REASON_LENGTH
                            + " y " + MAX_REASON_LENGTH + " caracteres");
        }

        ExistingLot current = jdbc.sql("""
                SELECT id, store_id, product_id, lot_number, expiry_date, version
                FROM lots
                WHERE id = :lotId
                  AND store_id = :storeId
                FOR UPDATE
                """)
                .param("lotId", lotId)
                .param("storeId", storeId)
                .query((rs, rowNum) -> {
                    Date sqlExpiry = rs.getDate("expiry_date");
                    return new ExistingLot(
                            rs.getLong("id"),
                            rs.getLong("store_id"),
                            rs.getLong("product_id"),
                            rs.getString("lot_number"),
                            sqlExpiry != null ? sqlExpiry.toLocalDate() : null,
                            rs.getLong("version"));
                })
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Lote no encontrado: " + lotId));

        if (!expectedVersion.equals(current.version())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "El lote " + lotId + " fue modificado por otro usuario; recarga e intenta nuevamente");
        }

        Boolean duplicate = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM lots
                    WHERE store_id = :storeId
                      AND product_id = :productId
                      AND lot_number = :lotNumber
                      AND COALESCE(expiry_date, 'infinity'::date) = COALESCE(CAST(:expiryDate AS date), 'infinity'::date)
                      AND id <> :lotId
                )
                """)
                .param("storeId", current.storeId())
                .param("productId", current.productId())
                .param("lotNumber", normalizedNumber)
                .param("expiryDate", expiryDate, Types.DATE)
                .param("lotId", lotId)
                .query(Boolean.class)
                .single();

        if (Boolean.TRUE.equals(duplicate)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe otro lote con el mismo número y fecha de vencimiento para este producto en el local");
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        Long userId = CurrentUserId.find().orElse(null);

        jdbc.sql("""
                UPDATE lots
                SET lot_number = :lotNumber,
                    expiry_date = :expiryDate,
                    updated_by = :userId,
                    updated_at = :now,
                    version = version + 1
                WHERE id = :lotId
                  AND store_id = :storeId
                """)
                .param("lotNumber", normalizedNumber)
                .param("expiryDate", expiryDate, Types.DATE)
                .param("userId", userId, Types.BIGINT)
                .param("now", now)
                .param("lotId", lotId)
                .param("storeId", storeId)
                .update();

        Map<String, Object> oldValue = new LinkedHashMap<>();
        oldValue.put("lotNumber", current.lotNumber());
        oldValue.put("expiryDate", current.expiryDate() != null ? current.expiryDate().toString() : null);

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("lotNumber", normalizedNumber);
        newValue.put("expiryDate", expiryDate != null ? expiryDate.toString() : null);

        auditService.record(new AuditEvent(
                ACTION_LOT_UPDATED,
                ENTITY_LOT,
                lotId.toString(),
                oldValue,
                newValue,
                normalizedReason,
                null));

        return lotQueryService.getById(lotId);
    }

    private record ExistingLot(
            Long id,
            Long storeId,
            Long productId,
            String lotNumber,
            LocalDate expiryDate,
            long version) {
    }
}
