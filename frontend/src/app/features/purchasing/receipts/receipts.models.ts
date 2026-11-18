import { GoodsReceiptRequest } from '../../../api/models/goods-receipt-request';
import { GoodsReceiptResponse } from '../../../api/models/goods-receipt-response';
import { GoodsReceiptSummary } from '../../../api/models/goods-receipt-summary';

/** Estados posibles de una recepción de mercadería (`API.md` F.3). */
export type ReceiptStatusCode = NonNullable<GoodsReceiptResponse['status']>;

/** Tipos de documento tributario del proveedor (`API.md` F.3). */
export type SupplierDocumentTypeCode = NonNullable<GoodsReceiptRequest['supplierDocumentType']>;

/** Opciones de filtro para estado de recepción. */
export const RECEIPT_STATUS_OPTIONS: readonly {
  readonly value: ReceiptStatusCode;
  readonly label: string;
}[] = [
  { value: 'DRAFT', label: 'Borrador' },
  { value: 'CONFIRMED', label: 'Confirmada' },
  { value: 'CANCELLED', label: 'Cancelada' },
];

/** Opciones de tipo de documento del proveedor. */
export const SUPPLIER_DOCUMENT_TYPE_OPTIONS: readonly {
  readonly value: SupplierDocumentTypeCode;
  readonly label: string;
}[] = [
  { value: 'INVOICE', label: 'Factura' },
  { value: 'DISPATCH_GUIDE', label: 'Guía de despacho' },
  { value: 'NONE', label: 'Sin documento' },
];

const STATUS_LABELS: Readonly<Record<ReceiptStatusCode, string>> = {
  DRAFT: 'Borrador',
  CONFIRMED: 'Confirmada',
  CANCELLED: 'Cancelada',
};

const DOC_TYPE_LABELS: Readonly<Record<SupplierDocumentTypeCode, string>> = {
  INVOICE: 'Factura',
  DISPATCH_GUIDE: 'Guía de despacho',
  NONE: 'Sin documento',
};

const DATETIME_FORMAT = new Intl.DateTimeFormat('es-CL', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

const DATE_FORMAT = new Intl.DateTimeFormat('es-CL', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

/** Devuelve la etiqueta en español para un estado de recepción. */
export function receiptStatusLabel(status: ReceiptStatusCode | undefined): string {
  return status ? (STATUS_LABELS[status] ?? status) : '—';
}

/** Devuelve la etiqueta en español para un tipo de documento del proveedor. */
export function supplierDocumentTypeLabel(docType: SupplierDocumentTypeCode | undefined): string {
  return docType ? (DOC_TYPE_LABELS[docType] ?? docType) : '—';
}

/** Formatea un instante ISO-8601 en formato local chileno (`dd-mm-aaaa HH:mm`). */
export function formatReceiptDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? '—' : DATETIME_FORMAT.format(parsed);
}

/** Formatea una fecha ISO (`YYYY-MM-DD`) en formato local chileno (`dd-mm-aaaa`). */
export function formatReceiptLocalDate(isoDate: string | undefined): string {
  if (!isoDate) {
    return '—';
  }
  const parts = isoDate.split('-').map(Number);
  if (parts.length !== 3 || parts.some((n) => !Number.isFinite(n))) {
    return isoDate;
  }
  const [year, month, day] = parts;
  if (year === undefined || month === undefined || day === undefined) {
    return isoDate;
  }
  return DATE_FORMAT.format(new Date(year, month - 1, day));
}

/** Indica si una recepción está en estado borrador (`DRAFT`). */
export function isReceiptDraft(
  receipt: Pick<GoodsReceiptSummary | GoodsReceiptResponse, 'status'>,
): boolean {
  return receipt.status === 'DRAFT';
}

/** Traduce códigos de error de negocio de recepciones a mensajes claros en español. */
export function mapReceiptBusinessError(code: string | undefined, fallback: string): string {
  switch (code) {
    case 'LOT_REQUIRED':
      return 'Falta registrar el número de lote o fecha de vencimiento en al menos un producto controlado por lote.';
    case 'RECEIPT_EXCEEDS_ORDER':
      return 'La cantidad recibida supera lo pendiente en la orden de compra y requiere permiso de sobre-recepción.';
    case 'SEGREGATION_OF_DUTIES':
      return 'La diferencia supera el 10 % y por segregación de funciones debe confirmarla otro usuario autorizado.';
    case 'CONCURRENT_MODIFICATION':
      return 'La recepción fue modificada por otro usuario. Cierra el diálogo y recarga el listado.';
    default:
      return fallback;
  }
}
