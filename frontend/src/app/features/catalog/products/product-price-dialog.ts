import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ProductResponse } from '../../../api/models/product-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { ClpPipe } from '../../../shared/pipes';
import { integerValidator, trimmedMinLength } from './product-validators';
import { ProductsService } from './products.service';

/** Datos de entrada para {@link ProductPriceDialog}. */
export interface ProductPriceDialogData {
  readonly productId: number;
  readonly productName: string;
  readonly currentPriceGross: number;
  readonly version: number;
}

/**
 * Diálogo rápido para actualizar el precio de venta bruto (CLP) de un producto con motivo
 * obligatorio (`HU-CAT-03`, `RN-35`).
 */
@Component({
  selector: 'pos-product-price-dialog',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Cambiar precio de venta</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="form-grid">
        <p class="product-summary">
          <strong>{{ data.productName }}</strong> — Precio actual:
          {{ data.currentPriceGross | clp }}
        </p>

        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <mat-form-field appearance="outline">
          <mat-label>Nuevo precio de venta (CLP)</mat-label>
          <span matTextPrefix>$&nbsp;</span>
          <input matInput type="number" min="1" step="1" formControlName="newPriceGross" />
          @if (form.controls.newPriceGross.hasError('required')) {
            <mat-error>El precio es obligatorio.</mat-error>
          } @else if (form.controls.newPriceGross.hasError('min')) {
            <mat-error>Debe ser al menos $1.</mat-error>
          } @else if (form.controls.newPriceGross.hasError('integer')) {
            <mat-error>El precio en CLP debe ser un número entero.</mat-error>
          } @else if (form.controls.newPriceGross.hasError('server')) {
            <mat-error>{{ form.controls.newPriceGross.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Motivo del cambio</mat-label>
          <textarea
            matInput
            rows="2"
            formControlName="reason"
            placeholder="ej. Ajuste por lista del proveedor"
          ></textarea>
          <mat-hint>Entre 3 y 250 caracteres (queda registrado en auditoría).</mat-hint>
          @if (form.controls.reason.hasError('required')) {
            <mat-error>El motivo es obligatorio.</mat-error>
          } @else if (form.controls.reason.hasError('minlength')) {
            <mat-error>Mínimo 3 caracteres.</mat-error>
          } @else if (form.controls.reason.hasError('maxlength')) {
            <mat-error>Máximo 250 caracteres.</mat-error>
          } @else if (form.controls.reason.hasError('server')) {
            <mat-error>{{ form.controls.reason.getError('server') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">Guardar precio</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 10px;
      min-width: 400px;
      padding-top: 8px;
    }
    .product-summary {
      margin: 0 0 4px;
      font-size: 0.9rem;
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
export class ProductPriceDialog {
  protected readonly data = inject<ProductPriceDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<ProductPriceDialog, ProductResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly productsService = inject(ProductsService);

  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    newPriceGross: [
      this.data.currentPriceGross || 1,
      [Validators.required, Validators.min(1), integerValidator],
    ],
    reason: ['', [trimmedMinLength(3), Validators.maxLength(250)]],
  });

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    this.saving.set(true);
    this.serverError.set(null);

    this.productsService
      .changePrice(
        this.data.productId,
        {
          newPriceGross: Math.round(Number(raw.newPriceGross)),
          reason: raw.reason.trim(),
          version: this.data.version,
        },
        withoutErrorNotification(),
      )
      .subscribe({
        next: (updated) => {
          this.saving.set(false);
          this.dialogRef.close(updated);
        },
        error: (err) => {
          this.saving.set(false);
          if (!isApiError(err)) {
            this.serverError.set('No se pudo actualizar el precio.');
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
