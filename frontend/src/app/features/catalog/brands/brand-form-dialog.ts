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
import { BrandRequest } from '../../../api/models/brand-request';
import { BrandResponse } from '../../../api/models/brand-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { BrandsService } from './brands.service';

/** Datos de entrada para {@link BrandFormDialog}. */
export interface BrandFormDialogData {
  readonly brand?: BrandResponse;
}

/** Valida que el valor recortado no quede vacío. */
function notBlank(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim().length === 0 ? { required: true } : null;
}

/**
 * Formulario modal para crear o editar una marca (`HU-CAT-12`).
 */
@Component({
  selector: 'pos-brand-form-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ isEdit ? 'Editar marca' : 'Nueva marca' }}</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="form-grid">
        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <mat-form-field appearance="outline">
          <mat-label>Nombre</mat-label>
          <input matInput formControlName="name" autocomplete="off" />
          @if (form.controls.name.hasError('required')) {
            <mat-error>El nombre es obligatorio.</mat-error>
          } @else if (form.controls.name.hasError('maxlength')) {
            <mat-error>Máximo 100 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('server')) {
            <mat-error>{{ form.controls.name.getError('server') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">
          {{ isEdit ? 'Guardar cambios' : 'Crear marca' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 360px;
      padding-top: 8px;
    }
    .server-error {
      margin: 0 0 8px;
      padding: 8px 12px;
      border-radius: 6px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font-size: 0.875rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrandFormDialog {
  protected readonly data = inject<BrandFormDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<BrandFormDialog, BrandResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly brandsService = inject(BrandsService);

  protected readonly isEdit = Boolean(this.data.brand?.id);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    name: [this.data.brand?.name ?? '', [notBlank, Validators.maxLength(100)]],
  });

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const request: BrandRequest = {
      name: this.form.controls.name.value.trim(),
    };

    this.saving.set(true);
    this.serverError.set(null);

    const context = withoutErrorNotification();
    const request$ =
      this.isEdit && this.data.brand?.id !== undefined
        ? this.brandsService.updateBrand(this.data.brand.id, request, context)
        : this.brandsService.createBrand(request, context);

    request$.subscribe({
      next: (brand) => {
        this.saving.set(false);
        this.dialogRef.close(brand);
      },
      error: (err) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo guardar la marca.');
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
