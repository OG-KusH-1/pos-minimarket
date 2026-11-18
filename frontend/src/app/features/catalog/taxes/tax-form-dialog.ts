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
import { MatSelectModule } from '@angular/material/select';
import { TaxRequest } from '../../../api/models/tax-request';
import { TaxResponse } from '../../../api/models/tax-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { TaxesService } from './taxes.service';

/** Datos de entrada para {@link TaxFormDialog}. */
export interface TaxFormDialogData {
  readonly tax?: TaxResponse;
}

/** Patrón de código de impuesto exigido por la API (`API.md` F.3). */
const TAX_CODE_PATTERN = /^[A-Z_]{2,30}$/;

/** Fecha actual en formato `YYYY-MM-DD` para inicializar vigencias nuevas. */
function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

/** Valida que el texto recortado tenga al menos `min` caracteres. */
function trimmedMinLength(min: number) {
  return (control: AbstractControl<string>): ValidationErrors | null => {
    const trimmed = control.value.trim();
    if (trimmed.length === 0) {
      return { required: true };
    }
    return trimmed.length < min
      ? { minlength: { requiredLength: min, actualLength: trimmed.length } }
      : null;
  };
}

/**
 * Formulario modal para crear o editar un impuesto configurable (`HU-CAT-07`, `HU-CFG-03`).
 *
 * Toda creación o cambio de tasa queda auditado en el servidor bajo `TAX_CHANGED`.
 */
@Component({
  selector: 'pos-tax-form-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ isEdit ? 'Editar impuesto' : 'Nuevo impuesto' }}</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="form-grid">
        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <div class="two-cols">
          <mat-form-field appearance="outline">
            <mat-label>Código</mat-label>
            <input matInput formControlName="code" autocomplete="off" placeholder="ej. IABA_18" />
            <mat-hint>Mayúsculas y "_" (2–30)</mat-hint>
            @if (form.controls.code.hasError('required')) {
              <mat-error>El código es obligatorio.</mat-error>
            } @else if (form.controls.code.hasError('pattern')) {
              <mat-error>Solo mayúsculas y "_" (2 a 30 caracteres).</mat-error>
            } @else if (form.controls.code.hasError('server')) {
              <mat-error>{{ form.controls.code.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Tipo</mat-label>
            <mat-select formControlName="taxType">
              <mat-option value="VAT">IVA</mat-option>
              <mat-option value="ADDITIONAL">Adicional (ILA / IABA)</mat-option>
            </mat-select>
            @if (form.controls.taxType.hasError('server')) {
              <mat-error>{{ form.controls.taxType.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>

        <mat-form-field appearance="outline">
          <mat-label>Nombre</mat-label>
          <input matInput formControlName="name" autocomplete="off" />
          @if (form.controls.name.hasError('required')) {
            <mat-error>El nombre es obligatorio.</mat-error>
          } @else if (form.controls.name.hasError('minlength')) {
            <mat-error>Mínimo 2 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('maxlength')) {
            <mat-error>Máximo 100 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('server')) {
            <mat-error>{{ form.controls.name.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <div class="two-cols">
          <mat-form-field appearance="outline">
            <mat-label>Tasa (%)</mat-label>
            <input matInput type="number" step="0.01" min="0" max="100" formControlName="rate" />
            <span matTextSuffix>%</span>
            @if (form.controls.rate.hasError('required')) {
              <mat-error>La tasa es obligatoria.</mat-error>
            } @else if (form.controls.rate.hasError('min')) {
              <mat-error>Mínimo 0 %.</mat-error>
            } @else if (form.controls.rate.hasError('max')) {
              <mat-error>Máximo 100 %.</mat-error>
            } @else if (form.controls.rate.hasError('server')) {
              <mat-error>{{ form.controls.rate.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Código SII (opcional)</mat-label>
            <input
              matInput
              formControlName="siiCode"
              autocomplete="off"
              placeholder="ej. 14, 271"
            />
            @if (form.controls.siiCode.hasError('maxlength')) {
              <mat-error>Máximo 10 caracteres.</mat-error>
            } @else if (form.controls.siiCode.hasError('server')) {
              <mat-error>{{ form.controls.siiCode.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>

        <div class="two-cols">
          <mat-form-field appearance="outline">
            <mat-label>Vigente desde</mat-label>
            <input matInput type="date" formControlName="validFrom" />
            @if (form.controls.validFrom.hasError('required')) {
              <mat-error>La fecha de inicio es obligatoria.</mat-error>
            } @else if (form.controls.validFrom.hasError('server')) {
              <mat-error>{{ form.controls.validFrom.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Vigente hasta (opcional)</mat-label>
            <input matInput type="date" formControlName="validTo" />
            @if (form.controls.validTo.hasError('invalidRange')) {
              <mat-error>Debe ser posterior a la fecha de inicio.</mat-error>
            } @else if (form.controls.validTo.hasError('server')) {
              <mat-error>{{ form.controls.validTo.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">
          {{ isEdit ? 'Guardar cambios' : 'Crear impuesto' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 460px;
      padding-top: 8px;
    }
    .two-cols {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
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
export class TaxFormDialog {
  protected readonly data = inject<TaxFormDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<TaxFormDialog, TaxResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly taxesService = inject(TaxesService);

  protected readonly isEdit = Boolean(this.data.tax?.id);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group(
    {
      code: [
        this.data.tax?.code ?? '',
        [Validators.required, Validators.pattern(TAX_CODE_PATTERN)],
      ],
      name: [this.data.tax?.name ?? '', [trimmedMinLength(2), Validators.maxLength(100)]],
      taxType: [
        (this.data.tax?.taxType ?? 'ADDITIONAL') as 'VAT' | 'ADDITIONAL',
        [Validators.required],
      ],
      rate: [
        this.data.tax?.rate ?? 0,
        [Validators.required, Validators.min(0), Validators.max(100)],
      ],
      siiCode: [this.data.tax?.siiCode ?? '', [Validators.maxLength(10)]],
      validFrom: [this.data.tax?.validFrom ?? todayIsoDate(), [Validators.required]],
      validTo: [this.data.tax?.validTo ?? ''],
    },
    { validators: (group) => this.validateDateRange(group) },
  );

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: TaxRequest = {
      code: raw.code.trim().toUpperCase(),
      name: raw.name.trim(),
      taxType: raw.taxType,
      rate: Number(raw.rate),
      siiCode: raw.siiCode.trim() || undefined,
      validFrom: raw.validFrom,
      validTo: raw.validTo || undefined,
    };

    this.saving.set(true);
    this.serverError.set(null);

    const context = withoutErrorNotification();
    const request$ =
      this.isEdit && this.data.tax?.id !== undefined
        ? this.taxesService.updateTax(this.data.tax.id, request, context)
        : this.taxesService.createTax(request, context);

    request$.subscribe({
      next: (tax) => {
        this.saving.set(false);
        this.dialogRef.close(tax);
      },
      error: (err) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo guardar el impuesto.');
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

  /** Valida que `validTo`, si se informa, sea estrictamente posterior a `validFrom`. */
  private validateDateRange(group: AbstractControl): ValidationErrors | null {
    const validFrom = group.get('validFrom')?.value as string | undefined;
    const validToControl = group.get('validTo');
    const validTo = validToControl?.value as string | undefined;

    if (!validToControl) {
      return null;
    }
    const existingErrors = { ...(validToControl.errors ?? {}) };
    if (validFrom && validTo && validTo <= validFrom) {
      validToControl.setErrors({ ...existingErrors, invalidRange: true });
      return { invalidDateRange: true };
    }
    if ('invalidRange' in existingErrors) {
      delete existingErrors['invalidRange'];
      validToControl.setErrors(Object.keys(existingErrors).length > 0 ? existingErrors : null);
    }
    return null;
  }
}
