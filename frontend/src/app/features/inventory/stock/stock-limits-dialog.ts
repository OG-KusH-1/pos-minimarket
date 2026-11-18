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
import { StockLimitsRequest } from '../../../api/models/stock-limits-request';
import { StockResponse } from '../../../api/models/stock-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { QtyPipe } from '../../../shared/pipes';
import { toQtyUnit } from './stock.models';
import { StockService } from './stock.service';

/** Datos de entrada para {@link StockLimitsDialog}. */
export interface StockLimitsDialogData {
  readonly item: StockResponse;
}

/**
 * Diálogo para editar los límites de stock mínimo y máximo de un producto en el local activo
 * (`HU-INV-04`, `RN-34`).
 */
@Component({
  selector: 'pos-stock-limits-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Límites de stock</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="form-grid">
        <div class="product-summary">
          <div class="product-title">{{ data.item.name }}</div>
          <div class="product-meta">
            <span>SKU: {{ data.item.sku }}</span>
            <span>Stock actual: {{ data.item.quantity | qty: unit }}</span>
          </div>
        </div>

        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <mat-form-field appearance="outline">
          <mat-label>Stock mínimo ({{ unit }})</mat-label>
          <input
            matInput
            type="number"
            formControlName="minQty"
            min="0"
            [step]="inputStep"
            autocomplete="off"
          />
          <mat-hint>Se alerta cuando el stock es menor o igual al mínimo.</mat-hint>
          @if (form.controls.minQty.hasError('required')) {
            <mat-error>El stock mínimo es obligatorio.</mat-error>
          } @else if (form.controls.minQty.hasError('min')) {
            <mat-error>El stock mínimo debe ser mayor o igual a 0.</mat-error>
          } @else if (form.controls.minQty.hasError('server')) {
            <mat-error>{{ form.controls.minQty.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Stock máximo ({{ unit }}, opcional)</mat-label>
          <input
            matInput
            type="number"
            formControlName="maxQty"
            min="0"
            [step]="inputStep"
            autocomplete="off"
          />
          <mat-hint>Déjalo vacío si no deseas definir un límite máximo.</mat-hint>
          @if (form.controls.maxQty.hasError('min')) {
            <mat-error>El stock máximo debe ser mayor o igual a 0.</mat-error>
          } @else if (form.controls.maxQty.hasError('minMaxOrder')) {
            <mat-error>El stock máximo no puede ser menor que el mínimo.</mat-error>
          } @else if (form.controls.maxQty.hasError('server')) {
            <mat-error>{{ form.controls.maxQty.getError('server') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">Guardar límites</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 380px;
      padding-top: 8px;
    }
    .product-summary {
      padding: 10px 12px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .product-title {
      font-weight: 500;
    }
    .product-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
      margin-top: 4px;
      font-size: 0.85rem;
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
export class StockLimitsDialog {
  protected readonly data = inject<StockLimitsDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<StockLimitsDialog, StockResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly stockService = inject(StockService);

  protected readonly unit = toQtyUnit(this.data.item.unitOfMeasure);
  protected readonly inputStep = this.data.item.unitOfMeasure === 'KG' ? '0.001' : '1';
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    minQty: [
      (this.data.item.minQty ?? 0) as number | null,
      [Validators.required, Validators.min(0)],
    ],
    maxQty: [
      (this.data.item.maxQty ?? null) as number | null,
      [Validators.min(0), (control) => this.validateMaxOrder(control)],
    ],
  });

  constructor() {
    this.form.controls.minQty.valueChanges.subscribe(() => {
      this.form.controls.maxQty.updateValueAndValidity({ emitEvent: false });
    });
  }

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const productId = this.data.item.productId;
    if (productId === undefined) {
      return;
    }

    const raw = this.form.getRawValue();
    const minQty = Number(raw.minQty);
    const maxQty =
      raw.maxQty === null || raw.maxQty === ('' as unknown) ? undefined : Number(raw.maxQty);
    const request: StockLimitsRequest = {
      minQty,
      ...(maxQty !== undefined && Number.isFinite(maxQty) ? { maxQty } : {}),
    };

    this.saving.set(true);
    this.serverError.set(null);

    this.stockService.updateLimits(productId, request, withoutErrorNotification()).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.dialogRef.close(updated);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudieron guardar los límites de stock.');
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

  private validateMaxOrder(control: AbstractControl<number | null>): ValidationErrors | null {
    const maxVal = control.value;
    if (maxVal === null || maxVal === ('' as unknown)) {
      return null;
    }
    const minVal = control.parent?.get('minQty')?.value as number | null | undefined;
    if (minVal === null || minVal === undefined || !Number.isFinite(Number(minVal))) {
      return null;
    }
    return Number(maxVal) < Number(minVal) ? { minMaxOrder: true } : null;
  }
}
