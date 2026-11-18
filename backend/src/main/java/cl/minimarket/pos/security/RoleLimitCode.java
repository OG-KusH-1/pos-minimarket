package cl.minimarket.pos.security;

/**
 * Códigos de límites numéricos por rol ({@code ROLES_PERMISOS.md} D.3, tabla {@code role_limits}).
 *
 * <p>Toda consulta a estos límites debe realizarse mediante {@link RoleLimitApi#limitFor(Long, RoleLimitCode)}
 * respetando la semántica <em>fail-closed</em>: una fila ausente equivale a {@code 0} y solo
 * {@code NULL} en base de datos representa ilimitado ({@link RoleLimit.Unlimited}).
 */
public enum RoleLimitCode {
    /** Porcentaje máximo de descuento por ítem. */
    DISCOUNT_ITEM_MAX_PCT,
    /** Porcentaje máximo de descuento al total de la venta. */
    DISCOUNT_TOTAL_MAX_PCT,
    /** Monto máximo de descuento al total de la venta en CLP. */
    DISCOUNT_TOTAL_MAX_AMOUNT,
    /** Monto máximo de merma auto-aprobable al reportar, en CLP. */
    SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT,
    /** Monto máximo de merma que el rol puede aprobar, en CLP. */
    SHRINKAGE_APPROVE_MAX_AMOUNT,
    /** Monto máximo de ajuste de stock que el rol puede aprobar, en CLP. */
    ADJUSTMENT_APPROVE_MAX_AMOUNT,
    /** Monto máximo de retiro de efectivo de caja, en CLP. */
    CASH_OUT_MAX_AMOUNT,
    /** Monto máximo de devolución que el rol puede autorizar, en CLP. */
    RETURN_MAX_AMOUNT,
    /** Cupo máximo de crédito que el rol puede asignar a un cliente, en CLP. */
    CREDIT_LIMIT_MAX_ASSIGN,
    /** Monto máximo (valor absoluto) de ajuste de cuenta corriente de cliente, en CLP. */
    CREDIT_ADJUSTMENT_MAX_AMOUNT
}
