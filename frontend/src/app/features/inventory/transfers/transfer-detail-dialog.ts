import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { StockTransferLineResponse } from '../../../api/models/stock-transfer-line-response';
import { StockTransferResponse } from '../../../api/models/stock-transfer-response';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { QtyPipe } from '../../../shared/pipes';
import {
  formatStoreRef,
  formatTransferDateTime,
  formatTransferLocalDate,
  formatUserRef,
  isTransferDestination,
  isTransferOrigin,
  lineMissingQty,
  transferDirectionLabel,
  transferHasDifferences,
  transferStatusLabel,
} from './transfers.models';

/** Datos de entrada para {@link TransferDetailDialog}. */
export interface TransferDetailDialogData {
  readonly transfer: StockTransferResponse;
  readonly justDispatched?: boolean;
  readonly justReceived?: boolean;
  readonly canSend?: boolean;
  readonly canReceive?: boolean;
  readonly canCancel?: boolean;
}

/** Acción solicitada desde el detalle para que la pantalla padre la ejecute. */
export interface TransferDetailDialogResult {
  readonly action: 'edit' | 'send' | 'receive' | 'cancel';
}

/**
 * Diálogo de detalle completo de una transferencia entre locales (`HU-INV-07`).
 *
 * Presenta las líneas reales registradas (incluyendo el desdoblamiento FEFO por lote tras
 * despachar), las cantidades recibidas, diferencias y motivos, y acciones contextuales según
 * el estado, el local activo y los permisos del usuario.
 */
@Component({
  selector: 'pos-transfer-detail-dialog',
  imports: [MatButtonModule, MatDialogModule, MatIconModule, MatTableModule, QtyPipe],
  templateUrl: './transfer-detail-dialog.html',
  styleUrl: './transfer-detail-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TransferDetailDialog {
  protected readonly data = inject<TransferDetailDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(
    MatDialogRef<TransferDetailDialog, TransferDetailDialogResult | undefined>,
  );
  private readonly storeContext = inject(StoreContextService);

  protected readonly transfer = this.data.transfer;
  protected readonly lines = this.transfer.lines ?? [];
  protected readonly activeStoreId = this.storeContext.storeId;

  protected readonly isOrigin = computed(() =>
    isTransferOrigin(this.transfer, this.activeStoreId()),
  );
  protected readonly isDestination = computed(() =>
    isTransferDestination(this.transfer, this.activeStoreId()),
  );
  protected readonly directionLabel = computed(() =>
    transferDirectionLabel(this.transfer, this.activeStoreId()),
  );
  protected readonly hasDifferences = transferHasDifferences(this.transfer);

  protected readonly canEdit = computed(
    () => this.transfer.status === 'DRAFT' && this.isOrigin() && Boolean(this.data.canSend),
  );
  protected readonly canDispatch = computed(
    () => this.transfer.status === 'DRAFT' && this.isOrigin() && Boolean(this.data.canSend),
  );
  protected readonly canReceive = computed(
    () => this.transfer.status === 'SENT' && this.isDestination() && Boolean(this.data.canReceive),
  );
  protected readonly canCancel = computed(
    () => this.transfer.status === 'DRAFT' && this.isOrigin() && Boolean(this.data.canCancel),
  );

  protected readonly displayedColumns = computed<readonly string[]>(() => {
    const cols = ['sku', 'name', 'lotNumber', 'expiryDate', 'qtySent'];
    if (this.transfer.status === 'RECEIVED') {
      cols.push('qtyReceived', 'missing', 'differenceReason');
    }
    return cols;
  });

  protected statusLabel(): string {
    return transferStatusLabel(this.transfer.status);
  }

  protected originLabel(): string {
    return formatStoreRef(this.transfer.origin);
  }

  protected destinationLabel(): string {
    return formatStoreRef(this.transfer.destination);
  }

  protected sentAtLabel(): string {
    return formatTransferDateTime(this.transfer.sentAt);
  }

  protected sentByLabel(): string {
    return formatUserRef(this.transfer.sentBy);
  }

  protected receivedAtLabel(): string {
    return formatTransferDateTime(this.transfer.receivedAt);
  }

  protected receivedByLabel(): string {
    return formatUserRef(this.transfer.receivedBy);
  }

  protected formatExpiry(date: string | undefined): string {
    return formatTransferLocalDate(date);
  }

  protected missingOf(line: StockTransferLineResponse): number {
    return lineMissingQty(line);
  }

  protected lotLabel(line: StockTransferLineResponse): string {
    if (line.lotNumber) {
      return line.lotNumber;
    }
    return this.transfer.status === 'DRAFT' ? 'Automático (FEFO) / sin lote' : '—';
  }

  protected triggerAction(action: TransferDetailDialogResult['action']): void {
    this.dialogRef.close({ action });
  }
}
