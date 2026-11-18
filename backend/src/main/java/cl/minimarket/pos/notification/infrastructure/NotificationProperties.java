package cl.minimarket.pos.notification.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Propiedades de configuración del módulo {@code notification} ({@code pos.notification.*}).
 */
@Component
@ConfigurationProperties(prefix = "pos.notification")
public class NotificationProperties {

    /** Dirección remitente por defecto para los correos salientes. */
    private String from = "noreply@minimarket.local";

    /** Máximo de intentos antes de marcar un correo como {@code FAILED}. */
    private int maxAttempts = 5;

    /** Cantidad máxima de correos pendientes procesados por ciclo del job. */
    private int batchSize = 20;

    /** Habilita el job programado de despacho de la outbox. */
    private boolean schedulerEnabled = true;

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }
}
