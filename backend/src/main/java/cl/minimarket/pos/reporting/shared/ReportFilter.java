package cl.minimarket.pos.reporting.shared;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Filtros comunes que aceptan todos los reportes (API.md F.3): rango de fechas y locales.
 *
 * <p>Modelado de la opcionalidad de {@code from}/{@code to}: F.3 los declara <b>obligatorios</b>
 * en la mayoría de los reportes pero <b>opcionales</b> en {@code /reports/dashboard} (que usa su
 * propio parámetro {@code date}) y en {@code /reports/inventory-valuation} (que valoriza el stock
 * actual, sin rango). Como el registro es común, la exigencia no se decide al construirlo sino al
 * <b>contexto de llamada</b>: el servicio de cada reporte invoca {@link #requireRange()} o
 * {@link #optionalRange()} según su fila de F.3 antes de consultar.
 *
 * <ul>
 *   <li>{@link #requireRange()}: {@code from} y {@code to} obligatorios, {@code from ≤ to} y rango
 *       de a lo más {@value #MAX_RANGE_DAYS} días (inclusive). Es el modo de los reportes de
 *       venta, cajero, medio de pago, ranking, sin rotación, ABC, margen, compras, merma y
 *       diferencias de caja.</li>
 *   <li>{@link #optionalRange()}: acepta ambos ausentes (dashboard e inventory-valuation); si viene
 *       uno, debe venir el otro, y si vienen ambos se validan igual que en el modo obligatorio.</li>
 * </ul>
 *
 * <p>Cualquier rechazo lanza {@code VALIDATION_ERROR} del catálogo existente (F.1), con el campo
 * responsable en {@code errors[]}, de modo que el controlador lo traduzca a 400 tal como lo hace el
 * resto de la API.
 *
 * <p>{@code storeIds}: F.3 define "por defecto el local activo; se validan contra los asignados".
 * Esa resolución es del controlador (header {@code X-Store-Id} y {@code StoreAccessValidator}), no
 * de este registro: una lista vacía significa "usar el local del contexto" y los ids explícitos se
 * validan contra los locales asignados en la capa web. El constructor compacto copia la lista con
 * {@link List#copyOf}, que además rechaza elementos nulos (error del llamador, no del usuario).
 *
 * @param from     primera fecha de negocio del rango (inclusive), o {@code null} si el contexto lo
 *                 permite
 * @param to       última fecha de negocio del rango (inclusive), o {@code null} si el contexto lo
 *                 permite
 * @param storeIds ids de locales del filtro; vacío = local activo del contexto (nunca {@code null})
 */
public record ReportFilter(LocalDate from, LocalDate to, List<Long> storeIds) {

    /** Tope de amplitud del rango según F.3: "≥ from y rango ≤ 366 días". */
    public static final int MAX_RANGE_DAYS = 366;

    /** Nombre del campo de la fecha inicial, para los {@code errors[]} de validación. */
    public static final String FIELD_FROM = "from";
    /** Nombre del campo de la fecha final, para los {@code errors[]} de validación. */
    public static final String FIELD_TO = "to";

    public ReportFilter {
        storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
    }

    /**
     * Valida el filtro para un reporte con rango obligatorio (todos salvo dashboard e
     * inventory-valuation, según F.3) y devuelve {@code this} para uso fluido
     * ({@code filter.requireRange()}).
     *
     * @return este mismo filtro, ya validado
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si falta alguna fecha, viene solo
     *                            una, {@code from > to} o el rango supera {@value #MAX_RANGE_DAYS}
     *                            días
     */
    public ReportFilter requireRange() {
        return validate(true);
    }

    /**
     * Valida el filtro para un contexto que admite el rango ausente: {@code /reports/dashboard}
     * (usa su parámetro {@code date}) y {@code /reports/inventory-valuation} (stock actual, sin
     * rango). Si ambas fechas vienen, se validan como en {@link #requireRange()}.
     *
     * @return este mismo filtro, ya validado
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si viene solo una de las fechas,
     *                            {@code from > to} o el rango supera {@value #MAX_RANGE_DAYS} días
     */
    public ReportFilter optionalRange() {
        return validate(false);
    }

    /**
     * Indica si el filtro trae un rango explícito. Los contextos que admiten rango ausente
     * (dashboard e inventory-valuation) pueden usarlo para decidir su consulta por defecto.
     *
     * @return {@code true} solo si {@code from} y {@code to} están ambos presentes
     */
    public boolean hasRange() {
        return from != null && to != null;
    }

    /**
     * Validación común a ambos modos.
     *
     * @param rangeRequired {@code true} para el modo obligatorio
     * @return este mismo filtro
     */
    private ReportFilter validate(boolean rangeRequired) {
        if (from == null && to == null) {
            if (rangeRequired) {
                throw validationError(
                        "El reporte exige un rango de fechas (from y to).",
                        new FieldErrorDetail(FIELD_FROM, "NOT_NULL", "La fecha inicial es obligatoria."),
                        new FieldErrorDetail(FIELD_TO, "NOT_NULL", "La fecha final es obligatoria."));
            }
            return this;
        }

        if (from == null || to == null) {
            FieldErrorDetail missing = from == null
                    ? new FieldErrorDetail(FIELD_FROM, "NOT_NULL", "La fecha inicial es obligatoria.")
                    : new FieldErrorDetail(FIELD_TO, "NOT_NULL", "La fecha final es obligatoria.");
            throw validationError("Las fechas del rango deben indicarse juntas.", missing);
        }

        Objects.requireNonNull(from, FIELD_FROM);
        Objects.requireNonNull(to, FIELD_TO);
        if (from.isAfter(to)) {
            throw validationError(
                    "La fecha inicial del rango no puede ser posterior a la final.",
                    new FieldErrorDetail(FIELD_FROM, "RANGE_INVALID", "from no puede ser posterior a to."));
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw validationError(
                    "El rango del reporte no puede superar " + MAX_RANGE_DAYS + " días (rango pedido: "
                            + days + " días).",
                    new FieldErrorDetail(FIELD_TO, "MAX_RANGE_DAYS",
                            "El rango admite a lo más " + MAX_RANGE_DAYS + " días."));
        }
        return this;
    }

    /**
     * Excepción de validación con el detalle y los campos responsables, para que
     * {@code GlobalExceptionHandler} la traduzca a 400 con {@code errors[]}.
     */
    private static BusinessException validationError(String detail, FieldErrorDetail... errors) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, detail, Map.of(), List.of(errors));
    }
}
