package cl.minimarket.pos.purchasing.supplier.domain;

/**
 * Condición de pago acordada con el proveedor ({@code MODELO_DATOS.md} B.14).
 */
public enum PaymentTermsType {
    /** Pago contra entrega. */
    CASH_ON_DELIVERY,
    /** Pago a crédito según días pactados. */
    CREDIT
}
