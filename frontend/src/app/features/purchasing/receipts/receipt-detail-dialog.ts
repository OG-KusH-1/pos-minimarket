import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { GoodsReceiptResponse } from '../../../api/models/goods-receipt-response';
import { AuthService } from '../../../core/auth/auth.service';
import { ClpPipe, QtyPipe } from '../../../shared/pipes';
import {
  formatReceiptDateTime,
  formatReceiptLocalDate,
  isReceiptDraft,
  receiptStatusLabel,
  supplierDocumentTypeLabel,
} from './receipts.models';

/** Datos de entrada para {@link ReceiptDetailDialog}. */
export interface ReceiptDetailDialogData {
  readonly receipt: GoodsReceiptResponse;
}

/** Acción contextual disparada desde el detalle de recepción. */
export interface ReceiptDetailDialogResult {
  readonly action: 'edit' | 'confirm' | 'cancel';
  readonly receipt: GoodsReceiptResponse;
}

/**
 * Diálogo de solo lectura con el detalle completo de una recepción de mercadería (`HU-PRV-05` a
 * `HU-PRV-08`).
 *
 * Oculta costos unitarios, totales y cambios de costo promedio cuando el usuario no posee
 * `catalog:cost:read`.
 */
@Component({
  selector: 'pos-receipt-detail-dialog',
  imports: [ClpPipe, MatButtonModule, MatDialogModule, QtyPipe],
  templateUrl: './receipt-detail-dialog.html',
  styleUrl: './receipt-detail-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReceiptDetailDialog {
  protected readonly data = inject<ReceiptDetailDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(
    MatDialogRef<ReceiptDetailDialog, ReceiptDetailDialogResult | undefined>,
  );
  private readonly auth = inject(AuthService);

  protected readonly receipt = this.data.receipt;
  protected readonly canReadCost = computed(() => this.auth.hasPermission('catalog:cost:read'));
  protected readonly canEdit = computed(
    () => this.auth.hasPermission('purchasing:receipt:create') && isReceiptDraft(this.receipt),
  );
  protected readonly canConfirm = computed(
    () => this.auth.hasPermission('purchasing:receipt:confirm') && isReceiptDraft(this.receipt),
  );
  protected readonly canCancel = computed(
    () => this.auth.hasPermission('purchasing:receipt:create') && isReceiptDraft(this.receipt),
  );

  protected readonly statusLabel = receiptStatusLabel(this.receipt.status);
  protected readonly docTypeLabel = supplierDocumentTypeLabel(this.receipt.supplierDocumentType);
  protected readonly receivedAtLabel = formatReceiptDateTime(this.receipt.receivedAt);

  protected formatExpiry(date: string | undefined): string {
    return formatReceiptLocalDate(date);
  }

  protected trigger(action: ReceiptDetailDialogResult['action']): void {
    this.dialogRef.close({ action, receipt: this.receipt });
  }
}
