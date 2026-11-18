import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { QtyPipe } from '../../../../shared/pipes';
import { PosSaleType, PosUnitOfMeasure } from './pos-cart.models';

/** Datos de entrada para {@link PosQuantityDialog}. */
export interface PosQuantityDialogData {
  readonly productName: string;
  readonly sku: string;
  readonly saleType: PosSaleType;
  readonly unitOfMeasure: PosUnitOfMeasure;
  readonly currentQuantity: number;
  readonly stockOnHand?: number;
}

/**
 * Diálogo modal para editar la cantidad de una línea del carrito (`F4`, `HU-POS-03`, `HU-POS-19`).
 *
 * Exige enteros positivos en productos `UNIT` o `PACK`, y hasta 3 decimales en productos
 * pesables (`WEIGHT`), respetando el tope de `9999`.
 */
@Component({
  selector: 'pos-quantity-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Editar cantidad</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="dialog-content">
        <p class="product-label">
          <strong>{{ data.productName }}</strong>
          @if (data.sku) {
            <span class="sku">({{ data.sku }})</span>
          }
        </p>
        @if (data.stockOnHand !== undefined) {
          <p class="stock-hint">
            Stock disponible:
            {{ data.stockOnHand | qty: (data.unitOfMeasure === 'KG' ? 'kg' : 'u') }}
          </p>
        }

        <mat-form-field appearance="outline" class="qty-field">
          <mat-label> Cantidad ({{ data.unitOfMeasure === 'KG' ? 'kg' : 'unidades' }}) </mat-label>
          <input
            matInput
            type="number"
            cdkFocusInitial
            [step]="isWeight ? '0.001' : '1'"
            [min]="isWeight ? '0.001' : '1'"
            max="9999"
            [formControl]="quantityControl"
          />
          @if (quantityControl.hasError('required')) {
            <mat-error>Ingresa una cantidad.</mat-error>
          } @else if (quantityControl.hasError('min')) {
            <mat-error>La cantidad debe ser mayor que 0.</mat-error>
          } @else if (quantityControl.hasError('max')) {
            <mat-error>La cantidad máxima permitida es 9999.</mat-error>
          } @else if (quantityControl.hasError('wholeNumber')) {
            <mat-error>Este producto solo admite cantidades enteras.</mat-error>
          } @else if (quantityControl.hasError('precision')) {
            <mat-error>Ingresa como máximo 3 decimales.</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined">Cancelar</button>
        <button mat-flat-button type="submit">Aplicar</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .dialog-content {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 320px;
    }
    .product-label {
      margin: 0;
    }
    .sku {
      margin-left: 6px;
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.85rem;
    }
    .stock-hint {
      margin: 0 0 4px;
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.85rem;
    }
    .qty-field {
      width: 100%;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosQuantityDialog {
  protected readonly data = inject<PosQuantityDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef =
    inject<MatDialogRef<PosQuantityDialog, number | undefined>>(MatDialogRef);

  protected readonly isWeight = this.data.saleType === 'WEIGHT';

  protected readonly quantityControl = new FormControl<number | null>(this.data.currentQuantity, {
    validators: [
      Validators.required,
      Validators.min(this.isWeight ? 0.001 : 1),
      Validators.max(9999),
      quantityFormatValidator(this.isWeight),
    ],
  });

  protected readonly form = new FormGroup({
    quantity: this.quantityControl,
  });

  protected submit(): void {
    if (this.quantityControl.invalid) {
      this.quantityControl.markAsTouched();
      return;
    }
    const raw = this.quantityControl.value;
    if (raw === null || !Number.isFinite(raw)) {
      return;
    }
    this.dialogRef.close(Math.round(raw * 1000) / 1000);
  }
}

function quantityFormatValidator(allowDecimals: boolean): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const val = control.value as number | null;
    if (val === null || val === undefined || !Number.isFinite(val)) {
      return null;
    }
    if (!allowDecimals && !Number.isInteger(val)) {
      return { wholeNumber: true };
    }
    if (allowDecimals && Math.abs(Math.round(val * 1000) - val * 1000) > 1e-6) {
      return { precision: true };
    }
    return null;
  };
}
