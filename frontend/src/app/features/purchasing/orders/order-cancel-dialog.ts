import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PurchaseOrderResponse } from '../../../api/models/purchase-order-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { orderStatusLabel } from './orders.models';
import { PurchaseOrdersService } from './purchase-orders.service';

/** Datos de entrada para {@link OrderCancelDialog}. */
export interface OrderCancelDialogData {
  readonly order: PurchaseOrderResponse;
}

function minTrimmedLength(min: number) {
  return (control: AbstractControl<string>): ValidationErrors | null => {
    const trimmed = control.value.trim();
    if (!trimmed) {
      return { required: true };
    }
    return trimmed.length < min
      ? { minlength: { requiredLength: min, actualLength: trimmed.length } }
      : null;
  };
}

/**
 * Diálogo modal para cancelar una orden de compra indicando el motivo obligatorio
 * (`POST /api/v1/purchase-orders/{id}/cancel`, `HU-PRV-04`).
 */
@Component({
  selector: 'pos-order-cancel-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Cancelar orden de compra {{ data.order.number }}</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" aria-label="Cancelando orden de compra" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="content">
        <div class="summary-box">
          <div>
            <strong>Proveedor:</strong>
            {{ data.order.supplier?.tradeName || data.order.supplier?.legalName || '—' }}
          </div>
          <div><strong>Estado actual:</strong> {{ statusLabel }}</div>
        </div>

        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <mat-form-field appearance="outline">
          <mat-label>Motivo de cancelación</mat-label>
          <textarea
            matInput
            formControlName="reason"
            rows="3"
            maxlength="250"
            placeholder="Indica por qué se cancela la orden (mínimo 3 caracteres)"
          ></textarea>
          <mat-hint align="end">{{ form.controls.reason.value.trim().length }} / 250</mat-hint>
          @if (form.controls.reason.hasError('required')) {
            <mat-error>El motivo de cancelación es obligatorio.</mat-error>
          } @else if (form.controls.reason.hasError('minlength')) {
            <mat-error>El motivo debe tener al menos 3 caracteres.</mat-error>
          } @else if (form.controls.reason.hasError('maxlength')) {
            <mat-error>El motivo no puede superar los 250 caracteres.</mat-error>
          } @else if (form.controls.reason.hasError('server')) {
            <mat-error>{{ form.controls.reason.getError('server') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="null" [disabled]="saving()">
          Volver
        </button>
        <button mat-flat-button type="submit" class="destructive" [disabled]="saving()">
          Confirmar cancelación
        </button>
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
    .server-error {
      margin: 0;
      padding: 8px 12px;
      border-radius: 6px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font-size: 0.875rem;
    }
    .destructive {
      --mat-button-filled-container-color: var(--mat-sys-error);
      --mat-button-filled-label-text-color: var(--mat-sys-on-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderCancelDialog {
  protected readonly data = inject<OrderCancelDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(
    MatDialogRef<OrderCancelDialog, PurchaseOrderResponse | null>,
  );
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly ordersService = inject(PurchaseOrdersService);

  protected readonly statusLabel = orderStatusLabel(this.data.order.status);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    reason: ['', [minTrimmedLength(3), Validators.maxLength(250)]],
  });

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const id = this.data.order.id;
    if (id === undefined) {
      return;
    }

    const reason = this.form.controls.reason.value.trim();
    this.saving.set(true);
    this.serverError.set(null);

    this.ordersService.cancelOrder(id, { reason }, withoutErrorNotification()).subscribe({
      next: (cancelled) => {
        this.saving.set(false);
        this.dialogRef.close(cancelled);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo cancelar la orden de compra.');
          return;
        }
        if (err.code === 'CONCURRENT_MODIFICATION') {
          this.serverError.set(
            'La orden fue modificada por otro usuario. Cierra este diálogo y recarga el listado.',
          );
          return;
        }
        if (err.hasFieldErrors) {
          for (const fieldErr of err.errors) {
            const control = this.form.get(fieldErr.field);
            if (control) {
              control.setErrors({ server: fieldErr.message });
              control.markAsTouched();
            }
          }
        } else {
          this.serverError.set(err.message);
        }
      },
    });
  }
}
