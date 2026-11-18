import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { DiscountInput } from '../../../../api/models/discount-input';
import { ClpPipe } from '../../../../shared/pipes';

/** Ámbito al que se aplica el descuento en el diálogo (`LINE` o `TOTAL`). */
export type DiscountScope = 'LINE' | 'TOTAL';

/** Datos de entrada para {@link PosDiscountDialog}. */
export interface PosDiscountDialogData {
  readonly initialScope: DiscountScope;
  readonly allowLineScope: boolean;
  readonly lineLabel?: string;
  readonly lineMaxAmount?: number;
  readonly totalMaxAmount: number;
  readonly currentLineDiscount?: DiscountInput | null;
  readonly currentTotalDiscount?: DiscountInput | null;
}

/** Resultado emitido por {@link PosDiscountDialog} al confirmar o quitar un descuento. */
export interface PosDiscountDialogResult {
  readonly scope: DiscountScope;
  readonly discount: DiscountInput | null;
}

/**
 * Diálogo modal para aplicar o quitar descuentos manuales por línea o globales (`F6`,
 * `HU-POS-05`, `HU-POS-06`).
 */
@Component({
  selector: 'pos-discount-dialog',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Aplicar descuento</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="dialog-content">
        @if (data.allowLineScope) {
          <div class="toggle-group">
            <span class="toggle-label">Aplicar a</span>
            <mat-button-toggle-group
              formControlName="scope"
              aria-label="Alcance del descuento"
              (change)="onScopeChange($event.value)"
            >
              <mat-button-toggle value="LINE">Línea seleccionada</mat-button-toggle>
              <mat-button-toggle value="TOTAL">Total de la venta</mat-button-toggle>
            </mat-button-toggle-group>
          </div>
        }

        <p class="target-info">
          @if (scope() === 'LINE') {
            Línea: <strong>{{ data.lineLabel }}</strong>
            @if (data.lineMaxAmount !== undefined) {
              (máx. {{ data.lineMaxAmount | clp }})
            }
          } @else {
            Descuento global sobre el total (máx. {{ data.totalMaxAmount | clp }})
          }
        </p>

        <div class="toggle-group">
          <span class="toggle-label">Tipo</span>
          <mat-button-toggle-group
            formControlName="kind"
            aria-label="Tipo de descuento"
            (change)="onKindChange($event.value)"
          >
            <mat-button-toggle value="PERCENT">Porcentaje (%)</mat-button-toggle>
            <mat-button-toggle value="AMOUNT">Monto fijo ($)</mat-button-toggle>
          </mat-button-toggle-group>
        </div>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>
            {{ kind() === 'PERCENT' ? 'Porcentaje de descuento (%)' : 'Monto de descuento ($)' }}
          </mat-label>
          <input
            matInput
            type="number"
            cdkFocusInitial
            [step]="kind() === 'PERCENT' ? '0.5' : '1'"
            [min]="kind() === 'PERCENT' ? '0.01' : '1'"
            [max]="kind() === 'PERCENT' ? '100' : null"
            formControlName="value"
          />
          @if (form.controls.value.hasError('required')) {
            <mat-error>Ingresa un valor de descuento.</mat-error>
          } @else if (form.controls.value.hasError('min')) {
            <mat-error>El valor debe ser mayor que 0.</mat-error>
          } @else if (form.controls.value.hasError('max')) {
            <mat-error>El porcentaje no puede superar 100 %.</mat-error>
          } @else if (form.controls.value.hasError('wholeAmount')) {
            <mat-error>El monto en pesos debe ser un número entero.</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Motivo (opcional)</mat-label>
          <input matInput formControlName="reason" maxlength="250" />
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        @if (hasExistingDiscount()) {
          <button mat-button type="button" color="warn" (click)="clearDiscount()">
            Quitar descuento
          </button>
        }
        <button mat-button type="button" [mat-dialog-close]="undefined">Cancelar</button>
        <button mat-flat-button type="submit">Aplicar</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .dialog-content {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 340px;
    }
    .toggle-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .toggle-label {
      font-size: 0.8rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .target-info {
      margin: 0;
      font-size: 0.9rem;
    }
    .full-width {
      width: 100%;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosDiscountDialog {
  protected readonly data = inject<PosDiscountDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef =
    inject<MatDialogRef<PosDiscountDialog, PosDiscountDialogResult | undefined>>(MatDialogRef);

  private readonly initialDiscount =
    this.data.initialScope === 'LINE'
      ? (this.data.currentLineDiscount ?? null)
      : (this.data.currentTotalDiscount ?? null);

  protected readonly scope = signal<DiscountScope>(this.data.initialScope);
  protected readonly kind = signal<'PERCENT' | 'AMOUNT'>(this.initialDiscount?.kind ?? 'PERCENT');

  protected readonly form = new FormGroup({
    scope: new FormControl<DiscountScope>(this.data.initialScope, { nonNullable: true }),
    kind: new FormControl<'PERCENT' | 'AMOUNT'>(this.initialDiscount?.kind ?? 'PERCENT', {
      nonNullable: true,
    }),
    value: new FormControl<number | null>(this.initialDiscount?.value ?? null),
    reason: new FormControl(this.initialDiscount?.reason ?? '', {
      nonNullable: true,
      validators: [Validators.maxLength(250)],
    }),
  });

  constructor() {
    this.applyValueValidators(this.kind());
  }

  protected hasExistingDiscount(): boolean {
    return this.scope() === 'LINE'
      ? Boolean(this.data.currentLineDiscount)
      : Boolean(this.data.currentTotalDiscount);
  }

  protected onScopeChange(nextScope: DiscountScope): void {
    this.scope.set(nextScope);
    const existing =
      nextScope === 'LINE' ? this.data.currentLineDiscount : this.data.currentTotalDiscount;
    const nextKind = existing?.kind ?? 'PERCENT';
    this.kind.set(nextKind);
    this.form.patchValue({
      kind: nextKind,
      value: existing?.value ?? null,
      reason: existing?.reason ?? '',
    });
    this.applyValueValidators(nextKind);
  }

  protected onKindChange(nextKind: 'PERCENT' | 'AMOUNT'): void {
    this.kind.set(nextKind);
    this.applyValueValidators(nextKind);
  }

  protected clearDiscount(): void {
    this.dialogRef.close({ scope: this.scope(), discount: null });
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    if (raw.value === null || !Number.isFinite(raw.value)) {
      return;
    }
    const trimmedReason = raw.reason.trim();
    const discount: DiscountInput = {
      kind: raw.kind,
      value: raw.kind === 'AMOUNT' ? Math.round(raw.value) : raw.value,
      ...(trimmedReason ? { reason: trimmedReason } : {}),
    };
    this.dialogRef.close({ scope: raw.scope, discount });
  }

  private applyValueValidators(kind: 'PERCENT' | 'AMOUNT'): void {
    const control = this.form.controls.value;
    if (kind === 'PERCENT') {
      control.setValidators([Validators.required, Validators.min(0.01), Validators.max(100)]);
    } else {
      control.setValidators([Validators.required, Validators.min(1), integerAmountValidator]);
    }
    control.updateValueAndValidity();
  }
}

function integerAmountValidator(control: AbstractControl): ValidationErrors | null {
  const value = control.value as number | null;
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return null;
  }
  return Number.isInteger(value) ? null : { wholeAmount: true };
}
