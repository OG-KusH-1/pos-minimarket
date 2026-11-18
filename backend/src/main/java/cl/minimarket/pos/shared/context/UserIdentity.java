package cl.minimarket.pos.shared.context;

/**
 * Contrato mínimo que debe cumplir el <i>principal</i> autenticado para que shared conozca el id
 * del usuario sin depender del módulo security.
 *
 * <p>Security (T21) puede implementarlo en su {@code CurrentUser}/principal. Alternativamente
 * basta con que {@code Authentication.getName()} sea el id numérico (el {@code sub} del JWT);
 * ver {@link CurrentUserId}.
 */
public interface UserIdentity {

    /** @return id del usuario en la tabla {@code users}; nunca nulo */
    Long userId();
}
