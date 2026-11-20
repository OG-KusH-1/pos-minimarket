import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { ClpPipe } from '../../../shared/pipes';
import { TaxDocumentResponse } from '../../../api/models/tax-document-response';
import {
  canRetryTaxDocument,
  formatTaxDocumentDateTime,
  taxDocumentSourceLabel,
  taxDocumentStatusLabel,
  taxDocumentTypeLabel,
} from './tax-documents.models';

/**
 * Diálogo de solo lectura con el detalle completo de un documento tributario.
 *
 * Recibe la fila ya cargada de la lista (`GET /tax-documents` y `GET /tax-documents/{id}`
 * comparten el mismo DTO `TaxDocumentResponse`), de modo que no requiere una petición extra.
 * El reintento de emisión se mantiene en la pantalla principal para conservar una única
 * orquestación de confirmación y recarga.
 */
@Component({
  selector: 'pos-tax-document-detail-dialog',
  imports: [ClpPipe, MatButtonModule, MatDialogModule, MatIconModule],
  templateUrl: './tax-document-detail-dialog.html',
  styleUrl: './tax-document-detail-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TaxDocumentDetailDialog {
  protected readonly data = inject<TaxDocumentResponse>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<TaxDocumentDetailDialog, void>);

  /** Título legible del documento (tipo + folio). */
  protected get title(): string {
    const label = this.data.typeLabel ?? taxDocumentTypeLabel(this.data.documentType);
    return this.data.folio !== undefined ? `${label} folio ${this.data.folio}` : label;
  }

  /** Estado de emisión en español. */
  protected get statusText(): string {
    return taxDocumentStatusLabel(this.data.status);
  }

  /** Clase de color del chip de estado. */
  protected get statusClass(): string {
    switch (this.data.status) {
      case 'ISSUED':
        return 'status-chip--issued';
      case 'PENDING':
        return 'status-chip--pending';
      default:
        return 'status-chip--failed';
    }
  }

  /** Documento origen (venta o devolución/anulación). */
  protected get sourceText(): string {
    return taxDocumentSourceLabel(this.data);
  }

  /** Fecha de emisión formateada. */
  protected get issuedAtText(): string {
    return formatTaxDocumentDateTime(this.data.issuedAt);
  }

  /** Indica si el documento quedó en ERROR o REJECTED (informativo en el detalle). */
  protected get retryableText(): string | null {
    return canRetryTaxDocument(this.data)
      ? 'Admite reintento de emisión desde la lista de documentos.'
      : null;
  }

  /** Cierra el diálogo. */
  protected close(): void {
    this.dialogRef.close();
  }
}
