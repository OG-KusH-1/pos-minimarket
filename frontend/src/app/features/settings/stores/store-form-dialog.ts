import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { StoreRequest } from '../../../api/models/store-request';
import { StoreResponse } from '../../../api/models/store-response';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { applyFormApiErrors } from '../settings-api.utils';
import { StoresService } from './stores.service';

/** Datos de entrada para {@link StoreFormDialog}. */
export interface StoreFormDialogData {
  readonly store?: StoreResponse;
}

/** Patrón de código de local permitido por la API (`API.md` F.3). */
const STORE_CODE_PATTERN = /^[A-Z0-9]{2,10}$/;

/**
 * Formulario modal para crear o editar un local (`HU-CFG-02`).
 */
@Component({
  selector: 'pos-store-form-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ isEdit ? 'Editar local' : 'Nuevo local' }}</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <mat-dialog-content class="form-grid">
        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <div class="row-2">
          <mat-form-field appearance="outline">
            <mat-label>Código</mat-label>
            <input
              matInput
              formControlName="code"
              autocomplete="off"
              placeholder="ej. SUC01"
              (blur)="normalizeCode()"
            />
            <mat-hint>2 a 10 caracteres alfanuméricos en mayúscula.</mat-hint>
            @if (form.controls.code.hasError('required')) {
              <mat-error>El código es obligatorio.</mat-error>
            } @else if (form.controls.code.hasError('pattern')) {
              <mat-error>Solo letras mayúsculas y números (2 a 10).</mat-error>
            } @else if (form.controls.code.hasError('server')) {
              <mat-error>{{ form.controls.code.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Código sucursal SII</mat-label>
            <input matInput formControlName="siiBranchCode" autocomplete="off" />
            @if (form.controls.siiBranchCode.hasError('maxlength')) {
              <mat-error>Máximo 20 caracteres.</mat-error>
            } @else if (form.controls.siiBranchCode.hasError('server')) {
              <mat-error>{{ form.controls.siiBranchCode.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>

        <mat-form-field appearance="outline">
          <mat-label>Nombre del local</mat-label>
          <input matInput formControlName="name" autocomplete="off" />
          @if (form.controls.name.hasError('required')) {
            <mat-error>El nombre es obligatorio.</mat-error>
          } @else if (form.controls.name.hasError('minlength')) {
            <mat-error>Mínimo 2 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('maxlength')) {
            <mat-error>Máximo 120 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('server')) {
            <mat-error>{{ form.controls.name.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Dirección</mat-label>
          <input matInput formControlName="address" autocomplete="street-address" />
          @if (form.controls.address.hasError('required')) {
            <mat-error>La dirección es obligatoria.</mat-error>
          } @else if (form.controls.address.hasError('maxlength')) {
            <mat-error>Máximo 250 caracteres.</mat-error>
          } @else if (form.controls.address.hasError('server')) {
            <mat-error>{{ form.controls.address.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <div class="row-2">
          <mat-form-field appearance="outline">
            <mat-label>Comuna</mat-label>
            <input matInput formControlName="commune" autocomplete="address-level3" />
            @if (form.controls.commune.hasError('required')) {
              <mat-error>La comuna es obligatoria.</mat-error>
            } @else if (form.controls.commune.hasError('maxlength')) {
              <mat-error>Máximo 100 caracteres.</mat-error>
            } @else if (form.controls.commune.hasError('server')) {
              <mat-error>{{ form.controls.commune.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Ciudad</mat-label>
            <input matInput formControlName="city" autocomplete="address-level2" />
            @if (form.controls.city.hasError('required')) {
              <mat-error>La ciudad es obligatoria.</mat-error>
            } @else if (form.controls.city.hasError('maxlength')) {
              <mat-error>Máximo 100 caracteres.</mat-error>
            } @else if (form.controls.city.hasError('server')) {
              <mat-error>{{ form.controls.city.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>

        <mat-form-field appearance="outline">
          <mat-label>Teléfono</mat-label>
          <input matInput formControlName="phone" type="tel" autocomplete="tel" />
          @if (form.controls.phone.hasError('maxlength')) {
            <mat-error>Máximo 30 caracteres.</mat-error>
          } @else if (form.controls.phone.hasError('server')) {
            <mat-error>{{ form.controls.phone.getError('server') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">
          {{ isEdit ? 'Guardar cambios' : 'Crear local' }}
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
    .row-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
    }
    @media (max-width: 560px) {
      .form-grid {
        min-width: 0;
      }
      .row-2 {
        grid-template-columns: 1fr;
      }
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
export class StoreFormDialog {
  protected readonly data = inject<StoreFormDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<StoreFormDialog, StoreResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly storesService = inject(StoresService);

  protected readonly isEdit = Boolean(this.data.store?.id);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    code: [
      this.data.store?.code ?? '',
      [Validators.required, Validators.pattern(STORE_CODE_PATTERN)],
    ],
    name: [
      this.data.store?.name ?? '',
      [Validators.required, Validators.minLength(2), Validators.maxLength(120)],
    ],
    address: [this.data.store?.address ?? '', [Validators.required, Validators.maxLength(250)]],
    commune: [this.data.store?.commune ?? '', [Validators.required, Validators.maxLength(100)]],
    city: [this.data.store?.city ?? '', [Validators.required, Validators.maxLength(100)]],
    phone: [this.data.store?.phone ?? '', [Validators.maxLength(30)]],
    siiBranchCode: [this.data.store?.siiBranchCode ?? '', [Validators.maxLength(20)]],
  });

  /** Normaliza el código de local a mayúsculas al salir del campo. */
  protected normalizeCode(): void {
    const normalized = this.form.controls.code.value.trim().toUpperCase();
    if (normalized !== this.form.controls.code.value) {
      this.form.controls.code.setValue(normalized);
    }
  }

  protected submit(): void {
    this.normalizeCode();
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: StoreRequest = {
      code: raw.code.trim().toUpperCase(),
      name: raw.name.trim(),
      address: raw.address.trim(),
      commune: raw.commune.trim(),
      city: raw.city.trim(),
      phone: raw.phone.trim() || undefined,
      siiBranchCode: raw.siiBranchCode.trim() || undefined,
    };

    this.saving.set(true);
    this.serverError.set(null);

    const request$ =
      this.isEdit && this.data.store?.id !== undefined
        ? this.storesService.updateStore(this.data.store.id, request, withoutErrorNotification())
        : this.storesService.createStore(request, withoutErrorNotification());

    request$.subscribe({
      next: (store) => {
        this.saving.set(false);
        this.dialogRef.close(store);
      },
      error: (err) => {
        this.saving.set(false);
        applyFormApiErrors(this.form, err, this.serverError);
      },
    });
  }
}
