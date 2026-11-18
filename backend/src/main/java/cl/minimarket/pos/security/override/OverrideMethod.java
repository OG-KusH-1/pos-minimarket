package cl.minimarket.pos.security.override;

/**
 * Método de autenticación del supervisor al autorizar un override ({@code ROLES_PERMISOS.md} D.4,
 * {@code MODELO_DATOS.md} B.2).
 */
public enum OverrideMethod {
    /** Autenticación mediante PIN personal del supervisor. */
    PIN,
    /** Autenticación mediante contraseña del supervisor. */
    PASSWORD
}
