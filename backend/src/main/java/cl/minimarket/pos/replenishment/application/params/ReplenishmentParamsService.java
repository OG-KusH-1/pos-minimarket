package cl.minimarket.pos.replenishment.application.params;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Casos de uso de los parámetros de reabastecimiento por alcance ({@code API.md} F.2):
 * listado, upsert por alcance y borrado (salvo el GLOBAL, que es el default del sistema).
 *
 * <p>Los rangos y la coherencia alcance ↔ claves los valida este servicio (matriz de
 * {@code ck_replenishment_params_scope_fk}); la existencia de las claves foráneas se comprueba
 * por las APIs públicas de {@code organization} y {@code catalog}.
 */
@Service
public class ReplenishmentParamsService {

    private static final String ACTION_PARAMS_CHANGED = "REPLENISHMENT_PARAMS_CHANGED";
    private static final String ENTITY_PARAMS = "replenishment_params";

    private final ReplenishmentParamRepository repository;
    private final StoreApi storeApi;
    private final ProductCatalogApi productCatalogApi;
    private final AuditService auditService;

    public ReplenishmentParamsService(ReplenishmentParamRepository repository,
                                      StoreApi storeApi,
                                      ProductCatalogApi productCatalogApi,
                                      AuditService auditService) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.storeApi = Objects.requireNonNull(storeApi, "storeApi");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
    }

    /**
     * Lista las filas de parámetros, opcionalmente filtradas por alcance.
     *
     * @param scope alcance a listar, o {@code null} para todas las filas
     * @return filas ordenadas por id
     */
    @Transactional(readOnly = true)
    public List<ReplenishmentParam> list(ReplenishmentParamScope scope) {
        return scope == null
                ? repository.findAll()
                : repository.findByScope(scope);
    }

    /**
     * Crea o reemplaza la fila de parámetros del alcance indicado (UQ de B.11).
     *
     * <p>El PUT es un reemplazo completo de las columnas: cada parámetro que llega {@code null}
     * pasa a heredar del nivel superior. Las columnas que llegan con valor se normalizan a la
     * escala de su columna ({@code numeric(5,3)}, {@code numeric(6,2)}, {@code numeric(5,2)},
     * {@code numeric(4,3)}).
     *
     * @param command parámetros con alcance y claves
     * @return fila resultante (creada o actualizada)
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si el alcance no es coherente con
     *         sus claves o algún rango se sale del catálogo F.3; 404 {@code NOT_FOUND} si el
     *         local, la categoría o el producto no existen
     */
    @Transactional
    public ReplenishmentParam upsert(ReplenishmentParamCommand command) {
        Objects.requireNonNull(command, "command");
        validateScope(command);
        ReplenishmentParam existing = findExisting(command).orElse(null);

        ReplenishmentParam row = existing == null
                ? new ReplenishmentParam(
                        command.scope(), command.storeId(), command.categoryId(), command.productId(),
                        command.windowDays(),
                        command.minHistoryDays(),
                        scale3(command.serviceLevelZ()),
                        scale2(command.safetyMinDays()),
                        command.reviewPeriodDays(),
                        scale2(command.outlierK()),
                        scale3(command.shelfLifeFactor()),
                        command.enabled())
                : existing;

        if (existing != null) {
            row.applyValues(
                    command.windowDays(),
                    command.minHistoryDays(),
                    scale3(command.serviceLevelZ()),
                    scale2(command.safetyMinDays()),
                    command.reviewPeriodDays(),
                    scale2(command.outlierK()),
                    scale3(command.shelfLifeFactor()),
                    command.enabled());
        }

        ReplenishmentParam saved = repository.save(row);
        auditService.record(new AuditEvent(
                ACTION_PARAMS_CHANGED,
                ENTITY_PARAMS,
                String.valueOf(saved.getId()),
                existing == null ? null : snapshot(existing),
                snapshot(saved),
                existing == null ? "Creación de parámetros por alcance" : "Actualización de parámetros por alcance",
                null));
        return saved;
    }

    /**
     * Borra una fila de parámetros por su identificador.
     *
     * @param id identificador de la fila
     * @throws BusinessException 404 {@code NOT_FOUND} si no existe; 409 {@code ENTITY_IN_USE}
     *         si es la fila GLOBAL, que es el default del sistema y no se puede borrar
     */
    @Transactional
    public void delete(long id) {
        ReplenishmentParam row = repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Parámetros de reabastecimiento no encontrados."));
        if (row.getScope() == ReplenishmentParamScope.GLOBAL) {
            throw new BusinessException(ErrorCode.ENTITY_IN_USE,
                    "El parámetro GLOBAL es el default del sistema y no se puede borrar.");
        }
        repository.delete(row);
        auditService.record(new AuditEvent(
                ACTION_PARAMS_CHANGED,
                ENTITY_PARAMS,
                String.valueOf(id),
                snapshot(row),
                null,
                "Borrado de parámetros por alcance",
                null));
    }

    /** Validación de la matriz alcance ↔ claves y de la existencia de las referencias. */
    private void validateScope(ReplenishmentParamCommand command) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        switch (command.scope()) {
            case GLOBAL -> {
                requireNull(command.storeId(), "storeId", errors);
                requireNull(command.categoryId(), "categoryId", errors);
                requireNull(command.productId(), "productId", errors);
            }
            case STORE -> {
                requirePresent(command.storeId(), "storeId", errors);
                requireNull(command.categoryId(), "categoryId", errors);
                requireNull(command.productId(), "productId", errors);
            }
            case CATEGORY -> {
                requirePresent(command.categoryId(), "categoryId", errors);
                requireNull(command.productId(), "productId", errors);
            }
            case PRODUCT -> {
                requirePresent(command.productId(), "productId", errors);
                requireNull(command.categoryId(), "categoryId", errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El alcance de los parámetros no es coherente con las claves indicadas.",
                    Map.of(), errors);
        }
        // Existencia de referencias (la FK de categoría la cubre catalog con NOT_FOUND).
        if (command.storeId() != null) {
            storeApi.get(command.storeId());
        }
        if (command.categoryId() != null) {
            productCatalogApi.categoryChain(command.categoryId());
        }
        if (command.productId() != null) {
            if (productCatalogApi.findByIds(List.of(command.productId())).isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Producto no encontrado.");
            }
        }
    }

    /** Busca la fila existente del alcance (UQ con COALESCE; el empate exacto lo hace en memoria). */
    private Optional<ReplenishmentParam> findExisting(ReplenishmentParamCommand command) {
        return repository.findByScope(command.scope()).stream()
                .filter(row -> Objects.equals(row.getStoreId(), command.storeId())
                        && Objects.equals(row.getCategoryId(), command.categoryId())
                        && Objects.equals(row.getProductId(), command.productId()))
                .findFirst();
    }

    private static void requirePresent(Long value, String field, List<FieldErrorDetail> errors) {
        if (value == null) {
            errors.add(new FieldErrorDetail(field, "REQUIRED",
                    "El alcance exige indicar " + field + "."));
        }
    }

    private static void requireNull(Long value, String field, List<FieldErrorDetail> errors) {
        if (value != null) {
            errors.add(new FieldErrorDetail(field, "NOT_ALLOWED",
                    "El alcance no admite " + field + "."));
        }
    }

    /** Escala de un valor anulable, o {@code null} si no viene (hereda del nivel superior). */
    private static BigDecimal scale3(BigDecimal value) {
        return value == null ? null : value.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Vista de la fila para el JSON de auditoría (valores antes y después). */
    private static Map<String, Object> snapshot(ReplenishmentParam row) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("scope", row.getScope().name());
        data.put("storeId", row.getStoreId());
        data.put("categoryId", row.getCategoryId());
        data.put("productId", row.getProductId());
        data.put("windowDays", row.getWindowDays());
        data.put("minHistoryDays", row.getMinHistoryDays());
        data.put("serviceLevelZ", row.getServiceLevelZ());
        data.put("safetyMinDays", row.getSafetyMinDays());
        data.put("reviewPeriodDays", row.getReviewPeriodDays());
        data.put("outlierK", row.getOutlierK());
        data.put("shelfLifeFactor", row.getShelfLifeFactor());
        data.put("enabled", row.getEnabled());
        return data;
    }

    /**
     * Comando de alta o actualización de parámetros por alcance.
     *
     * @param scope             alcance (obligatorio)
     * @param storeId           local (según el alcance)
     * @param categoryId        categoría (solo CATEGORY)
     * @param productId         producto (solo PRODUCT)
     * @param windowDays        ventana W, o {@code null} para heredar
     * @param minHistoryDays    mínimo de días válidos, o {@code null} para heredar
     * @param serviceLevelZ     factor z, o {@code null} para heredar
     * @param safetyMinDays     días mínimos de seguridad, o {@code null} para heredar
     * @param reviewPeriodDays  período R explícito, o {@code null} para heredar
     * @param outlierK          multiplicador k, o {@code null} para heredar
     * @param shelfLifeFactor   fracción f de vida útil, o {@code null} para heredar
     * @param enabled           participación en corridas, o {@code null} para heredar
     */
    public record ReplenishmentParamCommand(
            ReplenishmentParamScope scope,
            Long storeId,
            Long categoryId,
            Long productId,
            Integer windowDays,
            Integer minHistoryDays,
            BigDecimal serviceLevelZ,
            BigDecimal safetyMinDays,
            Integer reviewPeriodDays,
            BigDecimal outlierK,
            BigDecimal shelfLifeFactor,
            Boolean enabled
    ) {
    }
}
