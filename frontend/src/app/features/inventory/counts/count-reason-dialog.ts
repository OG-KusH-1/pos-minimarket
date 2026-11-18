import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

/** Datos de entrada para {@link CountReasonDialog}. */
export interface CountReasonDialogData {
  readonly title: string;
  readonly message: string;
  readonly confirmLabel: string;
  readonly destructive?: boolean;
}

/**
 * Diálogo reutilizable para capturar un motivo obligatorio (3 a 250 caracteres) al rechazar o
 * cancelar una toma de inventario (`API.md` F.2).
 */
@Component({
  selector: 'pos-count-reason-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="dialog-body">
        <p class="message">{{ data.message }}</p>
        <mat-form-field appearance="outline">
          <mat-label>Motivo</mat-label>
          <textarea
            matInput
            formControlName="reason"
            rows="3"
            maxlength="250"
            placeholder="Describe el motivo (entre 3 y 250 caracteres)"
            cdkFocusInitial
          ></textarea>
          @if (form.controls.reason.hasError('required')) {
            <mat-error>El motivo es obligatorio.</mat-error>
          } @else if (form.controls.reason.hasError('minlength')) {
            <mat-error>Ingresa al menos 3 caracteres.</mat-error>
          } @else if (form.controls.reason.hasError('maxlength')) {
            <mat-error>El motivo no puede superar 250 caracteres.</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined">Volver</button>
        <button mat-flat-button type="submit" [class.destructive]="data.destructive">
          {{ data.confirmLabel }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .dialog-body {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 380px;
      padding-top: 8px;
    }
    .message {
      margin: 0;
      color: var(--mat-sys-on-surface-variant);
    }
    .destructive {
      --mat-button-filled-container-color: var(--mat-sys-error);
      --mat-button-filled-label-text-color: var(--mat-sys-on-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CountReasonDialog {
  protected readonly data = inject<CountReasonDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<CountReasonDialog, string>);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly form = this.fb.group({
    reason: [
      '',
      [
        Validators.required,
        (control) => {
          const len = String(control.value ?? '').trim().length;
          return len > 0 && len < 3 ? { minlength: true } : null;
        },
        Validators.maxLength(250),
      ],
    ],
  });

  protected submit(): void {
    const trimmed = this.form.controls.reason.value.trim();
    if (trimmed.length < 3 || trimmed.length > 250) {
      this.form.controls.reason.markAsTouched();
      if (trimmed.length === 0) {
        this.form.controls.reason.setErrors({ required: true });
      }
      return;
    }
    this.dialogRef.close(trimmed);
  }
}
