package cl.minimarket.pos.shared.persistence;

import cl.minimarket.pos.shared.context.CurrentUserId;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Proveedor del usuario para {@code created_by}/{@code updated_by} de {@link AuditableEntity}.
 *
 * <p><b>Contrato para security (T21):</b> devuelve el id del usuario autenticado según
 * {@link CurrentUserId}, es decir un principal que implemente
 * {@code UserIdentity} o un {@code Authentication.getName()} numérico (el {@code sub} del JWT).
 * Sin autenticación, con usuario anónimo o con un nombre no numérico devuelve <b>vacío</b>: la
 * columna queda {@code NULL}, que es lo correcto para cambios hechos por el sistema (jobs,
 * migraciones, el propio login). No lanza nunca: un fallo del auditor no debe impedir guardar.
 *
 * <p>Mientras no exista security (hasta T21) nunca hay usuario y todo queda {@code NULL}. Como las
 * columnas tienen FK a {@code users}, el id devuelto debe existir en esa tabla.
 */
@Component("auditorAware")
public class CurrentUserAuditor implements AuditorAware<Long> {

    /** @return id del usuario autenticado, o vacío si no hay uno */
    @Override
    public Optional<Long> getCurrentAuditor() {
        return CurrentUserId.find();
    }
}
