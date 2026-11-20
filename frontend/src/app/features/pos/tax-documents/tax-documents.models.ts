import { TaxDocumentResponse } from '../../../api/models/tax-document-response';

/** Estado de emisión de un documento tributario (`API.md` F.2, módulo billing). */
export type TaxDocumentStatusCode = NonNullable<TaxDocumentResponse['status']>;

/** Tipo de documento tributario simulado (`API.md` F.2, módulo billing). */
export type TaxDocumentTypeCode = NonNullable<TaxDocumentResponse['documentType']>;

/** Opciones del filtro de estado, en español. */
export const TAX_DOCUMENT_STATUS_OPTIONS: readonly {
  readonly value: TaxDocumentStatusCode;
  readonly label: string;
}[] = [
  { value: 'PENDING', label: 'Pendiente' },
  { value: 'ISSUED', label: 'Emitido' },
  { value: 'REJECTED', label: 'Rechazado' },
  { value: 'ERROR', label: 'Error' },
];

/** Opciones del filtro de tipo de documento, en español. */
export const TAX_DOCUMENT_TYPE_OPTIONS: readonly {
  readonly value: TaxDocumentTypeCode;
  readonly label: string;
}[] = [
  { value: 'BOLETA', label: 'Boleta' },
  { value: 'BOLETA_EXENTA', label: 'Boleta exenta' },
  { value: 'CREDIT_NOTE', label: 'Nota de crédito' },
];

const STATUS_LABELS: Readonly<Record<TaxDocumentStatusCode, string>> = {
  PENDING: 'Pendiente',
  ISSUED: 'Emitido',
  REJECTED: 'Rechazado',
  ERROR: 'Error',
};

const TYPE_LABELS: Readonly<Record<TaxDocumentTypeCode, string>> = {
  BOLETA: 'Boleta',
  BOLETA_EXENTA: 'Boleta exenta',
  CREDIT_NOTE: 'Nota de crédito',
};

/** Etiqueta en español del estado de emisión de un documento. */
export function taxDocumentStatusLabel(status: TaxDocumentStatusCode | undefined): string {
  return status ? (STATUS_LABELS[status] ?? status) : '—';
}

/** Etiqueta en español del tipo de documento tributario. */
export function taxDocumentTypeLabel(type: TaxDocumentTypeCode | undefined): string {
  return type ? (TYPE_LABELS[type] ?? type) : '—';
}

/**
 * Indica si un documento admite reintento de emisión: solo en ERROR o REJECTED
 * (`POST /tax-documents/{id}/retry`, `API.md` F.2).
 */
export function canRetryTaxDocument(document: TaxDocumentResponse): boolean {
  return document.status === 'ERROR' || document.status === 'REJECTED';
}

/** Formateador de fecha y hora de emisión en español (es-CL, `dd/MM/yyyy HH:mm`). */
const DATETIME_FORMAT = new Intl.DateTimeFormat('es-CL', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

/** Formatea la fecha de emisión ISO-8601 del documento; `—` si aún no se emitió. */
export function formatTaxDocumentDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : DATETIME_FORMAT.format(date);
}

/** Describe el documento origen (venta o devolución) para las columnas de la tabla. */
export function taxDocumentSourceLabel(document: TaxDocumentResponse): string {
  if (document.saleId !== undefined) {
    return `Venta #${document.saleId}`;
  }
  if (document.saleReturnId !== undefined) {
    return `Devolución #${document.saleReturnId}`;
  }
  return '—';
}
