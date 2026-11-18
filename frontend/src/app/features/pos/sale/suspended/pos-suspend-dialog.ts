import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { firstValueFrom } from 'rxjs';
import { CartLineInput } from '../../../../api/models/cart-line-input';
import { DiscountInput } from '../../../../api/models/discount-input';
import { SuspendedSaleResponse } from '../../../../api/models/suspended-sale-response';
import { withoutErrorNotification } from '../../../../core/http/http-context-tokens';
import { ClpPipe } from '../../../../shared/pipes';
import { extractPosErrorMessage } from '../cart/pos-http.utils';
import { SuspendedSaleService } from './suspended-sale.service';

/** Datos de entrada para {@link PosSuspendDialog}. */
export interface PosSuspendDialogData {
  readonly lines: CartLineInput[];
  readonly totalDiscount: DiscountInput | null;
  readonly customerId: number | null;
  readonly lineCount: number;
  readonly estimatedTotal: number;
}

/**
 * Diálogo modal para suspender la venta en curso (`F8`, `HU-POS-04`) con etiqueta opcional.
 *
 * Envía `POST /api/v1/pos/suspended-sales` y muestra errores de negocio inline (como
 * `SUSPENDED_LIMIT_REACHED`) sin perder el carrito actual.
 */
@Component({
  selector: 'pos-suspend-dialog',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Suspender venta en curso</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="suspend-content">
        <p class="summary-line">
          Se guardarán <strong>{{ data.lineCount }}</strong> línea(s) en espera por un total
          estimado de <strong>{{ data.estimatedTotal | clp }}</strong>. El stock no se descuenta
          hasta completar la venta.
        </p>

        @if (errorMessage(); as error) {
          <div class="error-banner" role="alert" data-testid="suspend-error-banner">
            <mat-icon aria-hidden="true">error_outline</mat-icon>
            <span>{{ error }}</span>
          </div>
        }

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Etiqueta o referencia (opcional)</mat-label>
          <input
            matInput
            cdkFocusInitial
            maxlength="60"
            formControlName="label"
            placeholder="Ej. Cliente fue por efectivo"
            data-testid="suspend-label-input"
          />
          <mat-hint align="end">{{ form.controls.label.value.length }} / 60</mat-hint>
          @if (form.controls.label.hasError('maxlength')) {
            <mat-error>Máximo 60 caracteres.</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [disabled]="busy()" [mat-dialog-close]="undefined">
          Cancelar (Esc)
        </button>
        <button
          mat-flat-button
          type="submit"
          [disabled]="busy() || form.invalid"
          data-testid="confirm-suspend-btn"
        >
          @if (busy()) {
            <mat-spinner diameter="18" />
          }
          Suspender venta
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .suspend-content {
      display: flex;
      flex-direction: column;
      gap: 0.8rem;
      min-width: min(420px, 88vw);
    }
    .summary-line {
      margin: 0;
      font-size: 0.92rem;
    }
    .error-banner {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.55rem 0.75rem;
      border-radius: 8px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font-size: 0.88rem;
    }
    .full-width {
      width: 100%;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosSuspendDialog {
  protected readonly data = inject<PosSuspendDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef =
    inject<MatDialogRef<PosSuspendDialog, SuspendedSaleResponse | undefined>>(MatDialogRef);
  private readonly suspendedService = inject(SuspendedSaleService);

  protected readonly busy = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly form = new FormGroup({
    label: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(60)],
    }),
  });

  protected async submit(): Promise<void> {
    if (this.busy() || this.form.invalid) {
      return;
    }
    this.busy.set(true);
    this.errorMessage.set(null);

    const trimmedLabel = this.form.controls.label.value.trim();
    try {
      const response = await firstValueFrom(
        this.suspendedService.suspend(
          {
            ...(trimmedLabel ? { label: trimmedLabel } : {}),
            ...(this.data.customerId !== null ? { customerId: this.data.customerId } : {}),
            cart: {
              lines: this.data.lines,
              ...(this.data.totalDiscount ? { totalDiscount: this.data.totalDiscount } : {}),
            },
          },
          withoutErrorNotification(),
        ),
      );
      this.dialogRef.close(response);
    } catch (error: unknown) {
      this.errorMessage.set(
        extractPosErrorMessage(error, 'No se pudo suspender la venta en curso.'),
      );
    } finally {
      this.busy.set(false);
    }
  }
}
