import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PurchaseOrderSummary } from '../../../api/models/purchase-order-summary';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { GoodsReceiptsService } from './goods-receipts.service';

/**
 * Diálogo auxiliar para seleccionar una orden de compra (`SENT` o `PARTIALLY_RECEIVED`) al
 * iniciar una recepción desde la pantalla de Recepciones.
 */
@Component({
  selector: 'pos-receipt-order-picker-dialog',
  imports: [MatButtonModule, MatDialogModule, MatListModule, MatProgressBarModule],
  template: `
    <h2 mat-dialog-title>Seleccionar orden de compra</h2>
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" aria-label="Cargando órdenes de compra" />
    }
    <mat-dialog-content class="content">
      @if (!loading() && orders().length === 0) {
        <p class="empty">No hay órdenes enviadas o parcialmente recibidas pendientes.</p>
      } @else {
        <mat-action-list>
          @for (order of orders(); track order.id) {
            <button mat-list-item type="button" (click)="select(order.id)">
              <span matListItemTitle>{{ order.number }} — {{ order.supplierName }}</span>
              <span matListItemLine>
                Estado: {{ order.status === 'SENT' ? 'Enviada' : 'Parcialmente recibida' }}
              </span>
            </button>
          }
        </mat-action-list>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="null">Cancelar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content {
      min-width: min(460px, 85vw);
      max-height: 60vh;
    }
    .empty {
      margin: 12px 0;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReceiptOrderPickerDialog implements OnInit {
  private readonly dialogRef = inject(MatDialogRef<ReceiptOrderPickerDialog, number | null>);
  private readonly receiptsService = inject(GoodsReceiptsService);

  protected readonly loading = signal(true);
  protected readonly orders = signal<PurchaseOrderSummary[]>([]);

  ngOnInit(): void {
    this.receiptsService.listReceivableOrders(withoutErrorNotification()).subscribe({
      next: (list) => {
        this.loading.set(false);
        this.orders.set(list);
      },
      error: () => {
        this.loading.set(false);
      },
    });
  }

  protected select(orderId: number | undefined): void {
    if (orderId !== undefined) {
      this.dialogRef.close(orderId);
    }
  }
}
