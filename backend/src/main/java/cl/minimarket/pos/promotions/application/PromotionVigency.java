package cl.minimarket.pos.promotions.application;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Utilidad compartida para verificar la vigencia de una promoción por estado, rango de fechas,
 * días de la semana y franja horaria en la zona horaria del negocio ({@code MODELO_DATOS.md} B.10).
 */
final class PromotionVigency {

    private PromotionVigency() {
    }

    /**
     * Evalúa si una promoción está vigente en el instante indicado para la zona horaria del negocio.
     *
     * @param active     indica si la promoción está activa
     * @param validFrom  inicio de vigencia (inclusive)
     * @param validTo    fin de vigencia (exclusivo; puede ser {@code null})
     * @param daysOfWeek días ISO-8601 (1=lunes..7=domingo); {@code null} o vacío significa todos los días
     * @param timeFrom   hora inicial en zona de negocio (inclusive; opcional junto a {@code timeTo})
     * @param timeTo     hora final en zona de negocio (inclusive; opcional junto a {@code timeFrom})
     * @param at         instante UTC a evaluar
     * @param zone       zona horaria del negocio
     * @return {@code true} si la promoción está vigente en ese instante
     */
    static boolean isVigentAt(
            boolean active,
            Instant validFrom,
            Instant validTo,
            short[] daysOfWeek,
            LocalTime timeFrom,
            LocalTime timeTo,
            Instant at,
            ZoneId zone
    ) {
        if (!active) {
            return false;
        }
        if (validFrom == null || at.isBefore(validFrom)) {
            return false;
        }
        if (validTo != null && !at.isBefore(validTo)) {
            return false;
        }
        ZonedDateTime zdt = at.atZone(zone);
        if (daysOfWeek != null && daysOfWeek.length > 0) {
            int currentDow = zdt.getDayOfWeek().getValue();
            boolean matchesDay = false;
            for (short day : daysOfWeek) {
                if (day == currentDow) {
                    matchesDay = true;
                    break;
                }
            }
            if (!matchesDay) {
                return false;
            }
        }
        if (timeFrom != null && timeTo != null) {
            LocalTime currentTime = zdt.toLocalTime();
            if (currentTime.isBefore(timeFrom) || currentTime.isAfter(timeTo)) {
                return false;
            }
        }
        return true;
    }
}
