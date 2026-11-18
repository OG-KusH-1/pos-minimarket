import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RoleRequest } from '../../../api/models/role-request';
import { RoleResponse } from '../../../api/models/role-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { RolesService } from './roles.service';

/** Datos de entrada para {@link RoleFormDialog}. */
export interface RoleFormDialogData {
  readonly role?: RoleResponse;
}

/** Patrón de código de rol permitido por la API (`API.md` F.3). */
const ROLE_CODE_PATTERN = /^[A-Z_]{3,30}$/;

/**
 * Formulario modal para crear o editar un rol (`HU-SEG-05`).
 *
 * En modo edición, `code` se mantiene de solo lectura porque es inmutable en el servidor.
 */
@Component({
  selector: 'pos-role-form-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ isEdit ? 'Editar rol' : 'Nuevo rol' }}</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="form-grid">
        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <mat-form-field appearance="outline">
          <mat-label>Código</mat-label>
          <input
            matInput
            formControlName="code"
            autocomplete="off"
            placeholder="ej. BODEGUERO"
            [readonly]="isEdit"
          />
          <mat-hint>Mayúsculas y guion bajo (3–30). No se puede cambiar después.</mat-hint>
          @if (form.controls.code.hasError('required')) {
            <mat-error>El código es obligatorio.</mat-error>
          } @else if (form.controls.code.hasError('pattern')) {
            <mat-error>Solo mayúsculas y "_" (3 a 30 caracteres).</mat-error>
          } @else if (form.controls.code.hasError('server')) {
            <mat-error>{{ form.controls.code.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Nombre</mat-label>
          <input matInput formControlName="name" autocomplete="off" />
          @if (form.controls.name.hasError('required')) {
            <mat-error>El nombre es obligatorio.</mat-error>
          } @else if (form.controls.name.hasError('minlength')) {
            <mat-error>Mínimo 2 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('server')) {
            <mat-error>{{ form.controls.name.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Descripción</mat-label>
          <textarea matInput formControlName="description" rows="3"></textarea>
          @if (form.controls.description.hasError('maxlength')) {
            <mat-error>Máximo 250 caracteres.</mat-error>
          } @else if (form.controls.description.hasError('server')) {
            <mat-error>{{ form.controls.description.getError('server') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">
          {{ isEdit ? 'Guardar cambios' : 'Crear rol' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 420px;
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
export class RoleFormDialog {
  protected readonly data = inject<RoleFormDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<RoleFormDialog, RoleResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly rolesService = inject(RolesService);

  protected readonly isEdit = Boolean(this.data.role?.id);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    code: [
      this.data.role?.code ?? '',
      [Validators.required, Validators.pattern(ROLE_CODE_PATTERN)],
    ],
    name: [
      this.data.role?.name ?? '',
      [Validators.required, Validators.minLength(2), Validators.maxLength(80)],
    ],
    description: [this.data.role?.description ?? '', [Validators.maxLength(250)]],
  });

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: RoleRequest = {
      code: raw.code.trim().toUpperCase(),
      name: raw.name.trim(),
      description: raw.description.trim() || undefined,
    };

    this.saving.set(true);
    this.serverError.set(null);

    const context = withoutErrorNotification();
    const request$ =
      this.isEdit && this.data.role?.id !== undefined
        ? this.rolesService.updateRole(this.data.role.id, request, context)
        : this.rolesService.createRole(request, context);

    request$.subscribe({
      next: (role) => {
        this.saving.set(false);
        this.dialogRef.close(role);
      },
      error: (err) => {
        this.saving.set(false);
        if (!isApiError(err)) {
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
