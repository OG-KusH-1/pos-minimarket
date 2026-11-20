package cl.minimarket.pos.replenishment.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Parámetros efectivos de reabastecimiento para un producto y un local.
 *
 * <p>La resolución jerárquica producto/categoría/local/global pertenece a la capa de aplicación;
 * este objeto representa el resultado ya resuelto. Un período de revisión {@code null} indica que
 * debe aplicarse el valor automático de C.2: {@code ceil(7 / díasDeDespacho)} cuando el proveedor
 * declara días de despacho, o siete días en caso contrario. Así se distingue el default automático
 * de un período configurado explícitamente.</p>
 *
 * @param windowDays cantidad de días de la ventana W
 * @param minHistoryDays mínimo de días válidos para estimar demanda
 * @param serviceLevelZ factor z del nivel de servicio
 * @param safetyMinDays mínimo de stock de seguridad expresado en días de demanda
 * @param reviewPeriodDays período R explícito, o {@code null} para calcularlo automáticamente
 * @param outlierK multiplicador k del recorte mediana/MAD
 * @param shelfLifeFactor fracción de la vida útil utilizable para limitar el objetivo
 * @param enabled si el producto participa en las corridas de reabastecimiento
 */
public record ReplenishmentParameters(
        int windowDays,
        int minHistoryDays,
        BigDecimal serviceLevelZ,
        BigDecimal safetyMinDays,
        Integer reviewPeriodDays,
        BigDecimal outlierK,
        BigDecimal shelfLifeFactor,
        boolean enabled
) {
    public ReplenishmentParameters {
        if (windowDays <= 0) {
            throw new IllegalArgumentException("windowDays debe ser mayor que cero");
        }
        if (minHistoryDays < 0) {
            throw new IllegalArgumentException("minHistoryDays no puede ser negativo");
        }
        if (reviewPeriodDays != null && reviewPeriodDays <= 0) {
            throw new IllegalArgumentException("reviewPeriodDays debe ser mayor que cero");
        }
        Objects.requireNonNull(serviceLevelZ, "serviceLevelZ");
        Objects.requireNonNull(safetyMinDays, "safetyMinDays");
        Objects.requireNonNull(outlierK, "outlierK");
        Objects.requireNonNull(shelfLifeFactor, "shelfLifeFactor");
        if (serviceLevelZ.signum() < 0 || safetyMinDays.signum() < 0 || outlierK.signum() < 0) {
            throw new IllegalArgumentException("Los factores de demanda no pueden ser negativos");
        }
        if (shelfLifeFactor.signum() < 0) {
            throw new IllegalArgumentException("shelfLifeFactor no puede ser negativo");
        }
    }

    /**
     * Devuelve los defaults globales de C.2. El período queda sin fijar para permitir el ajuste
     * automático según los días de despacho del proveedor elegido.
     *
     * @param enabled si el reabastecimiento está habilitado en la resolución efectiva
     * @return parámetros globales por defecto
     */
    public static ReplenishmentParameters defaults(boolean enabled) {
        return new ReplenishmentParameters(
                30,
                7,
                new BigDecimal("1.65"),
                BigDecimal.ONE,
                null,
                new BigDecimal("3"),
                new BigDecimal("0.7"),
                enabled);
    }
}
