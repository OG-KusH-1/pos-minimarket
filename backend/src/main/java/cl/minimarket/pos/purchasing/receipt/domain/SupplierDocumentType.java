package cl.minimarket.pos.purchasing.receipt.domain;

/**
 * Tipo de documento tributario del proveedor asociado a una recepción de mercadería
 * ({@code goods_receipts.supplier_document_type}).
 */
public enum SupplierDocumentType {
    /** Factura de compra. */
    INVOICE,
    /** Guía de despacho. */
    DISPATCH_GUIDE,
    /** Sin documento de respaldo. */
    NONE
}
