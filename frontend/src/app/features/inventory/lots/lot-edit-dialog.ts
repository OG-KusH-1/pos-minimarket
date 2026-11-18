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
import { LotResponse } from '../../../api/models/lot-response';
import { LotUpdateRequest } from '../../../api/models/lot-update-request';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { QtyPipe } from '../../../shared/pipes';
import { toLotQtyUnit } from './lots.models';
import { LotsService } from './lots.service';

/** Datos de entrada para {@link LotEditDialog}. */
export interface LotEditDialogData {
  readonly lot: LotResponse;
}

/** Resultado del diálogo de edición: lote actualizado o solicitud de recarga por conflicto 409. */
export type LotEditDialogResult =
  { readonly updated: LotResponse } | { readonly reloadRequired: true } | undefined;

/**
 * Diálogo para corregir el número de lote y/o la fecha de vencimiento con motivo obligatorio y
 * control de concurrencia optimista (`PUT /api/v1/lots/{id}`, `409 CONCURRENT_MODIFICATION`).
 */
@Component({
  selector: 'pos-lot-edit-dialog',
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
    <h2 mat-dialog-title>Corregir datos del lote</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="form-grid">
        <div class="lot-summary">
          <div class="lot-product">{{ data.lot.name }}</div>
          <div class="lot-meta">
            <span>SKU: {{ data.lot.sku }}</span>
            <span>Stock: {{ data.lot.quantity | qty: unit }}</span>
            <span>Versión: v{{ data.lot.version ?? 0 }}</span>
          </div>
        </div>

        @if (serverError(); as err) {
          <div class="server-error" role="alert">
            <span>{{ err }}</span>
            @if (concurrentConflict()) {
              <button mat-button type="button" (click)="closeForReload()">Recargar listado</button>
            }
          </div>
        }

        <mat-form-field appearance="outline">
          <mat-label>Número de lote</mat-label>
          <input matInput formControlName="lotNumber" maxlength="40" autocomplete="off" />
          @if (
            form.controls.lotNumber.hasError('required') ||
            form.controls.lotNumber.hasError('trimmedRequired')
          ) {
            <mat-error>El número de lote es obligatorio.</mat-error>
          } @else if (form.controls.lotNumber.hasError('maxlength')) {
            <mat-error>Máximo 40 caracteres.</mat-error>
          } @else if (form.controls.lotNumber.hasError('server')) {
            <mat-error>{{ form.controls.lotNumber.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Fecha de vencimiento (opcional)</mat-label>
          <input matInput type="date" formControlName="expiryDate" />
          <mat-hint>Déjalo vacío si el lote no tiene fecha de vencimiento.</mat-hint>
          @if (form.controls.expiryDate.hasError('server')) {
            <mat-error>{{ form.controls.expiryDate.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Motivo de la corrección</mat-label>
          <textarea
            matInput
            rows="3"
            formControlName="reason"
            placeholder="Describe el motivo para auditoría (3 a 250 caracteres)"
          ></textarea>
          @if (
            form.controls.reason.hasError('required') ||
            form.controls.reason.hasError('trimmedMinLength')
          ) {
            <mat-error>El motivo debe tener al menos 3 caracteres.</mat-error>
          } @else if (form.controls.reason.hasError('maxlength')) {
            <mat-error>Máximo 250 caracteres.</mat-error>
          } @else if (form.controls.reason.hasError('server')) {
            <mat-error>{{ form.controls.reason.getError('server') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button
          mat-button
          type="button"
          [mat-dialog-close]="undefined"
          [disabled]="saving()"
          cdkFocusInitial
        >
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving() || concurrentConflict()">
          Guardar cambios
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: min(440px, 84vw);
      padding-top: 8px;
    }
    .lot-summary {
      padding: 10px 12px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .lot-product {
      font-weight: 500;
    }
    .lot-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 14px;
      margin-top: 4px;
      font-size: 0.85rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .server-error {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
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
export class LotEditDialog {
  protected readonly data = inject<LotEditDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<LotEditDialog, LotEditDialogResult>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly lotsService = inject(LotsService);

  protected readonly unit = toLotQtyUnit(this.data.lot.unitOfMeasure);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly concurrentConflict = signal(false);

  protected readonly form = this.fb.group({
    lotNumber: [
      this.data.lot.lotNumber ?? '',
      [
        Validators.required,
        (control: AbstractControl<string>): ValidationErrors | null =>
          control.value.trim().length === 0 ? { trimmedRequired: true } : null,
        Validators.maxLength(40),
      ],
    ],
    expiryDate: [this.data.lot.expiryDate ?? ''],
    reason: [
      '',
      [
        Validators.required,
        (control: AbstractControl<string>): ValidationErrors | null =>
          control.value.trim().length < 3 ? { trimmedMinLength: true } : null,
        Validators.maxLength(250),
      ],
    ],
  });

  protected submit(): void {
    if (this.form.invalid || this.saving() || this.concurrentConflict()) {
      this.form.markAllAsTouched();
      return;
    }
    const lotId = this.data.lot.id;
    if (lotId === undefined) {
      return;
    }

    const raw = this.form.getRawValue();
    const expiryDate = raw.expiryDate.trim();
    const request: LotUpdateRequest = {
      lotNumber: raw.lotNumber.trim(),
      ...(expiryDate ? { expiryDate } : {}),
      reason: raw.reason.trim(),
      version: this.data.lot.version ?? 0,
    };

    this.saving.set(true);
    this.serverError.set(null);

    this.lotsService.updateLot(lotId, request, withoutErrorNotification()).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.dialogRef.close({ updated });
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo actualizar el lote.');
          return;
        }
        if (err.status === 409 && err.code === 'CONCURRENT_MODIFICATION') {
          this.concurrentConflict.set(true);
          this.serverError.set(
            err.message ||
              'El lote fue modificado por otro usuario. Recarga la lista e inténtalo de nuevo.',
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

  protected closeForReload(): void {
    this.dialogRef.close({ reloadRequired: true });
  }
}
