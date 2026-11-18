import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
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
import { CategoryRequest } from '../../../api/models/category-request';
import { CategoryResponse } from '../../../api/models/category-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { CategoriesService } from './categories.service';

/** Datos de entrada para {@link CategoryFormDialog}. */
export interface CategoryFormDialogData {
  readonly category?: CategoryResponse;
  readonly rootCategories: readonly CategoryResponse[];
  readonly defaultParentId?: number | null;
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
 * Formulario modal para crear o editar una categoría o subcategoría (`HU-CAT-12`).
 *
 * Respeta la jerarquía máxima de dos niveles: solo permite elegir categorías raíz como padre y
 * deshabilita el selector si la categoría editada ya tiene subcategorías.
 */
@Component({
  selector: 'pos-category-form-dialog',
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
    <h2 mat-dialog-title>{{ isEdit ? 'Editar categoría' : 'Nueva categoría' }}</h2>
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
          } @else if (form.controls.name.hasError('minlength')) {
            <mat-error>Mínimo 2 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('maxlength')) {
            <mat-error>Máximo 100 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('server')) {
            <mat-error>{{ form.controls.name.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Código (opcional)</mat-label>
          <input matInput formControlName="code" autocomplete="off" placeholder="ej. BEB" />
          <mat-hint>Hasta 20 caracteres.</mat-hint>
          @if (form.controls.code.hasError('maxlength')) {
            <mat-error>Máximo 20 caracteres.</mat-error>
          } @else if (form.controls.code.hasError('server')) {
            <mat-error>{{ form.controls.code.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Categoría padre</mat-label>
          <mat-select formControlName="parentId">
            <mat-option [value]="null">Ninguna (categoría raíz)</mat-option>
            @for (parent of selectableParents(); track parent.id) {
              <mat-option [value]="parent.id">
                {{ parent.name }}{{ parent.active === false ? ' (inactiva)' : '' }}
              </mat-option>
            }
          </mat-select>
          <mat-hint>
            {{
              hasChildren
                ? 'No puede convertirse en subcategoría porque ya tiene subcategorías.'
                : 'Máximo dos niveles: deja "Ninguna" para raíz o elige una categoría raíz.'
            }}
          </mat-hint>
          @if (form.controls.parentId.hasError('server')) {
            <mat-error>{{ form.controls.parentId.getError('server') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">
          {{ isEdit ? 'Guardar cambios' : 'Crear categoría' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 400px;
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
export class CategoryFormDialog {
  protected readonly data = inject<CategoryFormDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<CategoryFormDialog, CategoryResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly categoriesService = inject(CategoriesService);

  protected readonly isEdit = Boolean(this.data.category?.id);
  protected readonly hasChildren = (this.data.category?.children?.length ?? 0) > 0;
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly selectableParents = computed(() =>
    this.data.rootCategories.filter(
      (cat) => cat.id !== undefined && cat.id !== this.data.category?.id,
    ),
  );

  protected readonly form = this.fb.group({
    name: [this.data.category?.name ?? '', [trimmedMinLength(2), Validators.maxLength(100)]],
    code: [this.data.category?.code ?? '', [Validators.maxLength(20)]],
    parentId: [
      {
        value: this.data.category?.parentId ?? this.data.defaultParentId ?? null,
        disabled: this.hasChildren,
      },
    ],
  });

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: CategoryRequest = {
      name: raw.name.trim(),
      code: raw.code.trim() || undefined,
      parentId: raw.parentId ?? undefined,
    };

    this.saving.set(true);
    this.serverError.set(null);

    const context = withoutErrorNotification();
    const request$ =
      this.isEdit && this.data.category?.id !== undefined
        ? this.categoriesService.updateCategory(this.data.category.id, request, context)
        : this.categoriesService.createCategory(request, context);

    request$.subscribe({
      next: (category) => {
        this.saving.set(false);
        this.dialogRef.close(category);
      },
      error: (err) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo guardar la categoría.');
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
