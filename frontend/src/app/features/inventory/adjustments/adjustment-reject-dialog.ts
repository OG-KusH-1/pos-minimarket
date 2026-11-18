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
import { StockAdjustmentResponse } from '../../../api/models/stock-adjustment-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { AdjustmentsService } from './adjustments.service';

/** Datos de entrada para {@link AdjustmentRejectDialog}. */
export interface AdjustmentRejectDialogData {
  readonly adjustment: StockAdjustmentResponse;
}

/**
 * Diálogo para rechazar un ajuste de stock pendiente exigiendo un motivo explícito de 3 a 250
 * caracteres (`POST /api/v1/stock-adjustments/{id}/reject`, `HU-INV-05`).
 */
@Component({
  selector: 'pos-adjustment-reject-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Rechazar ajuste {{ data.adjustment.number }}</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="dialog-body">
        <p class="description">
          El rechazo deja el ajuste en estado rechazado sin modificar las existencias del local.
        </p>

        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <mat-form-field appearance="outline">
          <mat-label>Motivo del rechazo</mat-label>
          <textarea
            matInput
            rows="3"
            formControlName="reason"
            placeholder="Describe el motivo del rechazo (3 a 250 caracteres)"
          ></textarea>
          @if (
            form.controls.reason.hasError('required') ||
            form.controls.reason.hasError('trimmedMinLength')
          ) {
            <mat-error>El motivo debe tener al menos 3 caracteres.</mat-error>
          } @else if (form.controls.reason.hasError('maxlength')) {
            <mat-error>El motivo no puede superar los 250 caracteres.</mat-error>
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
        <button mat-flat-button type="submit" class="reject-btn" [disabled]="saving()">
          Confirmar rechazo
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .dialog-body {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: min(440px, 82vw);
      padding-top: 8px;
    }
    .description {
      margin: 0;
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
    .reject-btn {
      --mat-button-filled-container-color: var(--mat-sys-error);
      --mat-button-filled-label-text-color: var(--mat-sys-on-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdjustmentRejectDialog {
  protected readonly data = inject<AdjustmentRejectDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(
    MatDialogRef<AdjustmentRejectDialog, StockAdjustmentResponse>,
  );
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly adjustmentsService = inject(AdjustmentsService);

  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group({
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
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const id = this.data.adjustment.id;
    if (id === undefined) {
      return;
    }

    const reason = this.form.controls.reason.getRawValue().trim();
    this.saving.set(true);
    this.serverError.set(null);

    this.adjustmentsService.rejectAdjustment(id, { reason }, withoutErrorNotification()).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.dialogRef.close(updated);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo rechazar el ajuste de stock.');
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
