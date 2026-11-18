import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PurchaseOrderResponse } from '../../../api/models/purchase-order-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { PurchaseOrdersService } from './purchase-orders.service';

/** Datos de entrada para {@link OrderSendDialog}. */
export interface OrderSendDialogData {
  readonly order: PurchaseOrderResponse;
}

/**
 * Diálogo modal para aprobar y enviar una orden de compra (`DRAFT` → `SENT`, `HU-PRV-04`).
 *
 * Permite elegir si se encola el envío de la orden en PDF por correo electrónico al proveedor y
 * envía la versión optimista del documento.
 */
@Component({
  selector: 'pos-order-send-dialog',
  imports: [
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Enviar orden de compra {{ data.order.number }}</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" aria-label="Enviando orden de compra" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="content">
        <div class="summary-box">
          <div>
            <strong>Proveedor:</strong>
            {{ data.order.supplier?.tradeName || data.order.supplier?.legalName || '—' }}
          </div>
          <div>
            <strong>Correo registrado:</strong>
            {{ data.order.supplier?.email || 'Sin correo configurado' }}
          </div>
          <div><strong>Líneas:</strong> {{ data.order.lines?.length ?? 0 }}</div>
        </div>

        <p class="hint">
          Al confirmar, la orden pasará a estado <strong>Enviada</strong> y ya no podrá editarse.
        </p>

        <mat-checkbox formControlName="emailToSupplier">
          Enviar orden en PDF por correo electrónico al proveedor
        </mat-checkbox>

        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="null" [disabled]="saving()">
          Volver
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">Enviar orden</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .content {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 400px;
      padding-top: 8px;
    }
    .summary-box {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 10px 12px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant);
      font-size: 0.9rem;
    }
    .hint {
      margin: 0;
      font-size: 0.875rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .server-error {
      margin: 0;
      padding: 8px 12px;
      border-radius: 6px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font-size: 0.875rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderSendDialog {
  protected readonly data = inject<OrderSendDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<OrderSendDialog, PurchaseOrderResponse | null>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly ordersService = inject(PurchaseOrdersService);

  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    emailToSupplier: [Boolean(this.data.order.supplier?.email)],
  });

  protected submit(): void {
    const id = this.data.order.id;
    const version = this.data.order.version;
    if (id === undefined || version === undefined || this.saving()) {
      return;
    }

    this.saving.set(true);
    this.serverError.set(null);

    this.ordersService
      .sendOrder(
        id,
        { emailToSupplier: this.form.controls.emailToSupplier.value, version },
        withoutErrorNotification(),
      )
      .subscribe({
        next: (updated) => {
          this.saving.set(false);
          this.dialogRef.close(updated);
        },
        error: (err: unknown) => {
          this.saving.set(false);
          if (!isApiError(err)) {
            this.serverError.set('No se pudo enviar la orden de compra.');
            return;
          }
          if (err.code === 'CONCURRENT_MODIFICATION') {
            this.serverError.set(
              'La orden fue modificada por otro usuario. Cierra este diálogo y recarga el detalle.',
            );
            return;
          }
          this.serverError.set(err.message);
        },
      });
  }
}
