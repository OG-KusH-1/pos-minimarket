import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { PurchaseOrderResponse } from '../../../api/models/purchase-order-response';
import { AuthService } from '../../../core/auth/auth.service';
import { ClpPipe, QtyPipe } from '../../../shared/pipes';
import {
  canCancelOrderStatus,
  canReceiveFromOrder,
  formatOrderDateTime,
  formatOrderLocalDate,
  isOrderDraft,
  orderOriginLabel,
  orderStatusLabel,
} from './orders.models';

/** Datos de entrada para {@link OrderDetailDialog}. */
export interface OrderDetailDialogData {
  readonly order: PurchaseOrderResponse;
}

/** Acción contextual disparada desde el detalle de la orden de compra. */
export interface OrderDetailDialogResult {
  readonly action: 'edit' | 'send' | 'cancel' | 'pdf' | 'receive';
  readonly order: PurchaseOrderResponse;
}

/**
 * Diálogo de solo lectura con el detalle completo de una orden de compra (`HU-PRV-03`, `HU-PRV-04`).
 *
 * Oculta los costos unitarios y totales cuando el usuario no posee `catalog:cost:read` y expone
 * botones contextuales según el estado de la orden y los permisos vigentes.
 */
@Component({
  selector: 'pos-order-detail-dialog',
  imports: [ClpPipe, MatButtonModule, MatDialogModule, MatIconModule, QtyPipe],
  templateUrl: './order-detail-dialog.html',
  styleUrl: './order-detail-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderDetailDialog {
  protected readonly data = inject<OrderDetailDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(
    MatDialogRef<OrderDetailDialog, OrderDetailDialogResult | undefined>,
  );
  private readonly auth = inject(AuthService);

  protected readonly order = this.data.order;
  protected readonly canReadCost = computed(() => this.auth.hasPermission('catalog:cost:read'));
  protected readonly canEdit = computed(
    () => this.auth.hasPermission('purchasing:order:create') && isOrderDraft(this.order),
  );
  protected readonly canSend = computed(
    () => this.auth.hasPermission('purchasing:order:send') && isOrderDraft(this.order),
  );
  protected readonly canCancel = computed(
    () => this.auth.hasPermission('purchasing:order:cancel') && canCancelOrderStatus(this.order),
  );
  protected readonly canReceive = computed(
    () => this.auth.hasPermission('purchasing:receipt:create') && canReceiveFromOrder(this.order),
  );

  protected readonly statusLabel = orderStatusLabel(this.order.status);
  protected readonly originLabel = orderOriginLabel(this.order.origin);
  protected readonly expectedDateLabel = formatOrderLocalDate(this.order.expectedDate);
  protected readonly sentAtLabel = formatOrderDateTime(this.order.sentAt);
  protected readonly cancelledAtLabel = formatOrderDateTime(this.order.cancelledAt);

  protected trigger(action: OrderDetailDialogResult['action']): void {
    this.dialogRef.close({ action, order: this.order });
  }
}
