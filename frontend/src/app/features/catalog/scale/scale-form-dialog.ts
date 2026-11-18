import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
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
import { map, startWith } from 'rxjs';
import { ScaleBarcodeConfigRequest } from '../../../api/models/scale-barcode-config-request';
import { ScaleBarcodeConfigResponse } from '../../../api/models/scale-barcode-config-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { ScaleService } from './scale.service';

/** Datos de entrada para {@link ScaleFormDialog}. */
export interface ScaleFormDialogData {
  readonly config?: ScaleBarcodeConfigResponse;
}

/** Largo fijo de un código EAN-13 estándar. */
const EAN_13_LENGTH = 13;

/** Patrón de prefijo permitido por la API (`API.md` F.3): 2 o 3 dígitos. */
const PREFIX_PATTERN = /^\d{2,3}$/;

/** Resultado de la vista previa visual del formato EAN-13. */
export interface Ean13Preview {
  readonly pattern: string;
  readonly hasOverlap: boolean;
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

/** Valida que el número ingresado sea entero. */
function integerValidator(control: AbstractControl<number>): ValidationErrors | null {
  const val = control.value;
  if (val === null || val === undefined || (typeof val === 'string' && val === '')) {
    return null;
  }
  return Number.isInteger(Number(val)) ? null : { integer: true };
}

/**
 * Construye una cadena ilustrativa de 13 posiciones (`0..12`) para ayudar a visualizar cómo se
 * decodifica un código EAN-13 de balanza (p. ej. `20PPPPPWWWWWC`).
 */
export function buildEan13Preview(params: {
  prefix: string;
  embeddedType: 'WEIGHT' | 'PRICE';
  pluStart: number;
  pluLength: number;
  valueStart: number;
  valueLength: number;
}): Ean13Preview {
  const slots = Array.from({ length: EAN_13_LENGTH }, () => '·');
  const occupied = Array.from({ length: EAN_13_LENGTH }, () => 0);

  const mark = (start: number, len: number, charAt: (offset: number) => string): void => {
    if (!Number.isInteger(start) || !Number.isInteger(len) || start < 0 || len <= 0) {
      return;
    }
    for (let i = 0; i < len; i++) {
      const idx = start + i;
      if (idx < EAN_13_LENGTH) {
        slots[idx] = charAt(i);
        occupied[idx] = (occupied[idx] ?? 0) + 1;
      }
    }
  };

  const cleanPrefix = /^\d{1,3}$/.test(params.prefix) ? params.prefix : '';
  mark(0, cleanPrefix.length, (i) => cleanPrefix[i] ?? 'X');
  mark(params.pluStart, params.pluLength, () => 'P');
  const valChar = params.embeddedType === 'WEIGHT' ? 'W' : '$';
  mark(params.valueStart, params.valueLength, () => valChar);
  mark(EAN_13_LENGTH - 1, 1, () => 'C');

  return {
    pattern: slots.join(''),
    hasOverlap: occupied.some((count) => count > 1),
  };
}

/**
 * Formulario modal para crear o editar una configuración de códigos de barras de balanza EAN-13
 * (`HU-CAT-05`, `HU-CFG-07`).
 *
 * Valida las reglas del backend (`prefix` de 2 o 3 dígitos, rangos `0..12` y `1..6`, decimales
 * `0..3` y que `start + length <= 13`) y muestra una vista previa en tiempo real del formato.
 */
@Component({
  selector: 'pos-scale-form-dialog',
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
    <h2 mat-dialog-title>
      {{ isEdit ? 'Editar configuración de balanza' : 'Nueva configuración de balanza' }}
    </h2>
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
          <input
            matInput
            formControlName="name"
            autocomplete="off"
            placeholder="ej. Balanza peso kg"
          />
          @if (form.controls.name.hasError('required')) {
            <mat-error>El nombre es obligatorio.</mat-error>
          } @else if (form.controls.name.hasError('minlength')) {
            <mat-error>Mínimo 2 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('maxlength')) {
            <mat-error>Máximo 60 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('server')) {
            <mat-error>{{ form.controls.name.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <div class="two-cols">
          <mat-form-field appearance="outline">
            <mat-label>Prefijo</mat-label>
            <input matInput formControlName="prefix" autocomplete="off" placeholder="ej. 20" />
            <mat-hint>2 o 3 dígitos iniciales (ej. 20, 210)</mat-hint>
            @if (form.controls.prefix.hasError('required')) {
              <mat-error>El prefijo es obligatorio.</mat-error>
            } @else if (form.controls.prefix.hasError('pattern')) {
              <mat-error>Debe tener 2 o 3 dígitos numéricos.</mat-error>
            } @else if (form.controls.prefix.hasError('server')) {
              <mat-error>{{ form.controls.prefix.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Valor embebido</mat-label>
            <mat-select formControlName="embeddedType">
              <mat-option value="WEIGHT">Peso (kg)</mat-option>
              <mat-option value="PRICE">Precio ($)</mat-option>
            </mat-select>
            @if (form.controls.embeddedType.hasError('server')) {
              <mat-error>{{ form.controls.embeddedType.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>

        <div class="two-cols">
          <mat-form-field appearance="outline">
            <mat-label>Inicio PLU (0–12)</mat-label>
            <input matInput type="number" min="0" max="12" formControlName="pluStart" />
            @if (form.controls.pluStart.hasError('required')) {
              <mat-error>Obligatorio.</mat-error>
            } @else if (
              form.controls.pluStart.hasError('min') || form.controls.pluStart.hasError('max')
            ) {
              <mat-error>Debe estar entre 0 y 12.</mat-error>
            } @else if (form.controls.pluStart.hasError('integer')) {
              <mat-error>Debe ser entero.</mat-error>
            } @else if (form.controls.pluStart.hasError('server')) {
              <mat-error>{{ form.controls.pluStart.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Largo PLU (1–6)</mat-label>
            <input matInput type="number" min="1" max="6" formControlName="pluLength" />
            @if (form.controls.pluLength.hasError('required')) {
              <mat-error>Obligatorio.</mat-error>
            } @else if (
              form.controls.pluLength.hasError('min') || form.controls.pluLength.hasError('max')
            ) {
              <mat-error>Debe estar entre 1 y 6.</mat-error>
            } @else if (form.controls.pluLength.hasError('integer')) {
              <mat-error>Debe ser entero.</mat-error>
            } @else if (form.controls.pluLength.hasError('rangeExceeded')) {
              <mat-error>Inicio + largo no puede superar 13.</mat-error>
            } @else if (form.controls.pluLength.hasError('server')) {
              <mat-error>{{ form.controls.pluLength.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>

        <div class="three-cols">
          <mat-form-field appearance="outline">
            <mat-label>Inicio valor (0–12)</mat-label>
            <input matInput type="number" min="0" max="12" formControlName="valueStart" />
            @if (form.controls.valueStart.hasError('required')) {
              <mat-error>Obligatorio.</mat-error>
            } @else if (
              form.controls.valueStart.hasError('min') || form.controls.valueStart.hasError('max')
            ) {
              <mat-error>Entre 0 y 12.</mat-error>
            } @else if (form.controls.valueStart.hasError('integer')) {
              <mat-error>Debe ser entero.</mat-error>
            } @else if (form.controls.valueStart.hasError('server')) {
              <mat-error>{{ form.controls.valueStart.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Largo valor (1–6)</mat-label>
            <input matInput type="number" min="1" max="6" formControlName="valueLength" />
            @if (form.controls.valueLength.hasError('required')) {
              <mat-error>Obligatorio.</mat-error>
            } @else if (
              form.controls.valueLength.hasError('min') || form.controls.valueLength.hasError('max')
            ) {
              <mat-error>Entre 1 y 6.</mat-error>
            } @else if (form.controls.valueLength.hasError('integer')) {
              <mat-error>Debe ser entero.</mat-error>
            } @else if (form.controls.valueLength.hasError('rangeExceeded')) {
              <mat-error>Inicio + largo ≤ 13.</mat-error>
            } @else if (form.controls.valueLength.hasError('server')) {
              <mat-error>{{ form.controls.valueLength.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Decimales (0–3)</mat-label>
            <input matInput type="number" min="0" max="3" formControlName="valueDecimals" />
            @if (form.controls.valueDecimals.hasError('required')) {
              <mat-error>Obligatorio.</mat-error>
            } @else if (
              form.controls.valueDecimals.hasError('min') ||
              form.controls.valueDecimals.hasError('max')
            ) {
              <mat-error>Entre 0 y 3.</mat-error>
            } @else if (form.controls.valueDecimals.hasError('integer')) {
              <mat-error>Debe ser entero.</mat-error>
            } @else if (form.controls.valueDecimals.hasError('server')) {
              <mat-error>{{ form.controls.valueDecimals.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>

        <div class="preview-box" data-testid="ean13-preview">
          <div class="preview-label">
            Estructura EAN-13 (P = PLU, W/$ = valor, C = dígito verificador)
          </div>
          <code class="preview-code">{{ preview().pattern }}</code>
          @if (preview().hasOverlap) {
            <p class="preview-warning" role="status">
              Advertencia: algunas posiciones del prefijo, PLU, valor o dígito verificador se
              superponen.
            </p>
          }
        </div>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">
          {{ isEdit ? 'Guardar cambios' : 'Crear configuración' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 480px;
      padding-top: 8px;
    }
    .two-cols {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
    }
    .three-cols {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
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
    .preview-box {
      padding: 10px 12px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant);
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .preview-label {
      font-size: 0.78rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .preview-code {
      font-size: 1.05rem;
      letter-spacing: 0.14em;
      font-weight: 600;
    }
    .preview-warning {
      margin: 4px 0 0;
      font-size: 0.8rem;
      color: var(--mat-sys-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScaleFormDialog {
  protected readonly data = inject<ScaleFormDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<ScaleFormDialog, ScaleBarcodeConfigResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly scaleService = inject(ScaleService);

  protected readonly isEdit = Boolean(this.data.config?.id);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group(
    {
      name: [this.data.config?.name ?? '', [trimmedMinLength(2), Validators.maxLength(60)]],
      prefix: [
        this.data.config?.prefix ?? '20',
        [Validators.required, Validators.pattern(PREFIX_PATTERN)],
      ],
      embeddedType: [
        (this.data.config?.embeddedType ?? 'WEIGHT') as 'WEIGHT' | 'PRICE',
        [Validators.required],
      ],
      pluStart: [
        this.data.config?.pluStart ?? 2,
        [Validators.required, Validators.min(0), Validators.max(12), integerValidator],
      ],
      pluLength: [
        this.data.config?.pluLength ?? 5,
        [Validators.required, Validators.min(1), Validators.max(6), integerValidator],
      ],
      valueStart: [
        this.data.config?.valueStart ?? 7,
        [Validators.required, Validators.min(0), Validators.max(12), integerValidator],
      ],
      valueLength: [
        this.data.config?.valueLength ?? 5,
        [Validators.required, Validators.min(1), Validators.max(6), integerValidator],
      ],
      valueDecimals: [
        this.data.config?.valueDecimals ?? 3,
        [Validators.required, Validators.min(0), Validators.max(3), integerValidator],
      ],
    },
    { validators: (group) => this.validateEanRanges(group) },
  );

  protected readonly preview = toSignal(
    this.form.valueChanges.pipe(
      startWith(this.form.getRawValue()),
      map(() => {
        const raw = this.form.getRawValue();
        return buildEan13Preview({
          prefix: raw.prefix.trim(),
          embeddedType: raw.embeddedType,
          pluStart: Number(raw.pluStart),
          pluLength: Number(raw.pluLength),
          valueStart: Number(raw.valueStart),
          valueLength: Number(raw.valueLength),
        });
      }),
    ),
    {
      initialValue: buildEan13Preview({
        prefix: this.form.controls.prefix.value,
        embeddedType: this.form.controls.embeddedType.value,
        pluStart: this.form.controls.pluStart.value,
        pluLength: this.form.controls.pluLength.value,
        valueStart: this.form.controls.valueStart.value,
        valueLength: this.form.controls.valueLength.value,
      }),
    },
  );

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: ScaleBarcodeConfigRequest = {
      name: raw.name.trim(),
      prefix: raw.prefix.trim(),
      embeddedType: raw.embeddedType,
      pluStart: Number(raw.pluStart),
      pluLength: Number(raw.pluLength),
      valueStart: Number(raw.valueStart),
      valueLength: Number(raw.valueLength),
      valueDecimals: Number(raw.valueDecimals),
    };

    this.saving.set(true);
    this.serverError.set(null);

    const context = withoutErrorNotification();
    const request$ =
      this.isEdit && this.data.config?.id !== undefined
        ? this.scaleService.updateConfig(this.data.config.id, request, context)
        : this.scaleService.createConfig(request, context);

    request$.subscribe({
      next: (config) => {
        this.saving.set(false);
        this.dialogRef.close(config);
      },
      error: (err) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo guardar la configuración.');
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

  /** Valida que `start + length <= 13` tanto para PLU como para el valor embebido. */
  private validateEanRanges(group: AbstractControl): ValidationErrors | null {
    const pluExceeded = this.syncRangeError(group.get('pluStart'), group.get('pluLength'));
    const valueExceeded = this.syncRangeError(group.get('valueStart'), group.get('valueLength'));
    if (pluExceeded || valueExceeded) {
      return { eanRangeExceeded: true };
    }
    return null;
  }

  private syncRangeError(
    startControl: AbstractControl | null,
    lengthControl: AbstractControl | null,
  ): boolean {
    if (!startControl || !lengthControl) {
      return false;
    }
    const start = Number(startControl.value);
    const length = Number(lengthControl.value);
    const exceeded =
      Number.isFinite(start) && Number.isFinite(length) && start + length > EAN_13_LENGTH;

    const existing = { ...(lengthControl.errors ?? {}) };
    if (exceeded) {
      lengthControl.setErrors({ ...existing, rangeExceeded: true });
      return true;
    }
    if ('rangeExceeded' in existing) {
      delete existing['rangeExceeded'];
      lengthControl.setErrors(Object.keys(existing).length > 0 ? existing : null);
    }
    return false;
  }
}
