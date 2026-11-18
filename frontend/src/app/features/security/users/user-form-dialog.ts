import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { RoleResponse } from '../../../api/models/role-response';
import { StoreResponse } from '../../../api/models/store-response';
import { UserCreatedResponse } from '../../../api/models/user-created-response';
import { UserRequest } from '../../../api/models/user-request';
import { UserResponse } from '../../../api/models/user-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { rutValidator } from '../../../shared/validators';
import { UsersService } from './users.service';

/** Datos de entrada para {@link UserFormDialog}. */
export interface UserFormDialogData {
  readonly user?: UserResponse;
  readonly roles: readonly RoleResponse[];
  readonly stores: readonly StoreResponse[];
}

/** Resultado devuelto al cerrar {@link UserFormDialog} tras guardar. */
export type UserFormDialogResult =
  | { readonly mode: 'created'; readonly user: UserCreatedResponse }
  | { readonly mode: 'updated'; readonly user: UserResponse };

/** Patrón de nombre de usuario permitido por la API (`API.md` F.3). */
const USERNAME_PATTERN = /^[a-z0-9._-]{3,50}$/;

/**
 * Formulario modal para crear o editar un usuario, sus roles y locales asignados (`HU-SEG-04`).
 */
@Component({
  selector: 'pos-user-form-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    ReactiveFormsModule,
  ],
  templateUrl: './user-form-dialog.html',
  styleUrl: './user-form-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserFormDialog {
  protected readonly data = inject<UserFormDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<UserFormDialog, UserFormDialogResult>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly usersService = inject(UsersService);

  protected readonly isEdit = Boolean(this.data.user?.id);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    username: [
      this.data.user?.username ?? '',
      [Validators.required, Validators.pattern(USERNAME_PATTERN)],
    ],
    fullName: [
      this.data.user?.fullName ?? '',
      [Validators.required, Validators.minLength(2), Validators.maxLength(150)],
    ],
    email: [this.data.user?.email ?? '', [Validators.email, Validators.maxLength(150)]],
    rut: [this.data.user?.rut ?? '', [rutValidator]],
    roleIds: [
      this.data.user?.roles?.map((r) => r.id).filter((id): id is number => id !== undefined) ?? [],
      [Validators.required, Validators.minLength(1)],
    ],
    storeIds: [
      this.data.user?.stores?.map((s) => s.id).filter((id): id is number => id !== undefined) ?? [],
    ],
    defaultStoreId: [this.data.user?.defaultStoreId ?? (null as number | null)],
  });

  private readonly selectedStoreIds = signal<readonly number[]>(this.form.controls.storeIds.value);

  /** Locales entre los que se puede elegir el local por defecto (debe pertenecer a `storeIds`). */
  protected readonly availableDefaultStores = computed(() => {
    const selected = new Set(this.selectedStoreIds());
    return this.data.stores.filter((store) => store.id !== undefined && selected.has(store.id));
  });

  constructor() {
    this.form.controls.storeIds.valueChanges.subscribe((ids) => {
      this.selectedStoreIds.set(ids);
      const currentDefault = this.form.controls.defaultStoreId.value;
      if (ids.length === 0) {
        this.form.controls.defaultStoreId.setValue(null);
      } else if (currentDefault === null || !ids.includes(currentDefault)) {
        this.form.controls.defaultStoreId.setValue(ids[0] ?? null);
      }
    });
  }

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: UserRequest = {
      username: raw.username.trim(),
      fullName: raw.fullName.trim(),
      email: raw.email.trim() || undefined,
      rut: raw.rut.trim() || undefined,
      roleIds: raw.roleIds,
      storeIds: raw.storeIds,
      defaultStoreId: raw.defaultStoreId ?? undefined,
      ...(this.isEdit ? { version: this.data.user?.version } : {}),
    };

    this.saving.set(true);
    this.serverError.set(null);

    if (this.isEdit && this.data.user?.id !== undefined) {
      this.usersService
        .updateUser(this.data.user.id, request, withoutErrorNotification())
        .subscribe({
          next: (user) => {
            this.saving.set(false);
            this.dialogRef.close({ mode: 'updated', user });
          },
          error: (err) => this.handleError(err),
        });
    } else {
      this.usersService.createUser(request, withoutErrorNotification()).subscribe({
        next: (user) => {
          this.saving.set(false);
          this.dialogRef.close({ mode: 'created', user });
        },
        error: (err) => this.handleError(err),
      });
    }
  }

  private handleError(error: unknown): void {
    this.saving.set(false);
    if (!isApiError(error)) {
      return;
    }
    if (error.hasFieldErrors) {
      const unmatched: string[] = [];
      for (const fieldErr of error.errors) {
        const control = this.form.get(fieldErr.field);
        if (control) {
          control.setErrors({ server: fieldErr.message });
          control.markAsTouched();
        } else {
          unmatched.push(fieldErr.message);
        }
      }
      if (unmatched.length > 0) {
        this.serverError.set(unmatched.join(' '));
      }
    } else {
      this.serverError.set(error.message);
    }
  }
}
