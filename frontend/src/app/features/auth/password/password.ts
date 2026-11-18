import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Api } from '../../../api/api';
import { changePassword } from '../../../api/fn/auth/change-password';
import { LOGIN_URL } from '../../../core/auth/auth.guard';
import { AuthService } from '../../../core/auth/auth.service';
import { ErrorCodes } from '../../../core/http/api-error';
import { extractApiError, resolveAuthErrorMessage } from '../auth-errors';

const PASSWORD_MIN_LENGTH = 8;

const PASSWORD_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  [ErrorCodes.invalidCredentials]: 'La contraseña actual es incorrecta.',
  PASSWORD_POLICY:
    'La nueva contraseña no cumple la política de seguridad (mínimo 8 caracteres, al menos una letra y un número, distinta de tu usuario y de la clave actual).',
  [ErrorCodes.network]: 'No se pudo conectar con el servidor. Revisa tu conexión.',
};

/** Valida que la clave contenga al menos una letra y un dígito. */
function passwordComplexityValidator(control: AbstractControl<string>): ValidationErrors | null {
  const value = control.value;
  if (!value) {
    return null;
  }
  return /[A-Za-z]/.test(value) && /\d/.test(value) ? null : { passwordPolicy: true };
}

/** Valida que `confirmPassword` coincida con `newPassword` y que la nueva sea distinta a la actual. */
function passwordGroupValidator(group: AbstractControl): ValidationErrors | null {
  const current = group.get('currentPassword')?.value as string | undefined;
  const next = group.get('newPassword')?.value as string | undefined;
  const confirm = group.get('confirmPassword')?.value as string | undefined;

  const errors: Record<string, true> = {};
  if (next && current && next === current) {
    errors['sameAsCurrent'] = true;
  }
  if (next && confirm && next !== confirm) {
    errors['passwordMismatch'] = true;
  }
  return Object.keys(errors).length > 0 ? errors : null;
}

/**
 * Pantalla de cambio de contraseña (`POST /auth/me/password`, HU-SEG-10).
 *
 * Se usa tanto para el cambio obligatorio al primer ingreso (`mustChangePassword = true`, donde el
 * `authGuard` impide navegar a cualquier otra ruta) como para el cambio voluntario.
 */
@Component({
  selector: 'pos-password',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
  ],
  templateUrl: './password.html',
  styleUrl: './password.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Password {
  private readonly auth = inject(AuthService);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly mustChange = this.auth.mustChangePassword;
  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly hideCurrent = signal(true);
  protected readonly hideNew = signal(true);
  protected readonly hideConfirm = signal(true);

  protected readonly form = new FormGroup(
    {
      currentPassword: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
      newPassword: new FormControl('', {
        nonNullable: true,
        validators: [
          Validators.required,
          Validators.minLength(PASSWORD_MIN_LENGTH),
          Validators.maxLength(128),
          passwordComplexityValidator,
        ],
      }),
      confirmPassword: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
    },
    { validators: passwordGroupValidator },
  );

  /** Envía el cambio de contraseña y desbloquea la navegación si era obligatorio. */
  protected async submit(): Promise<void> {
    if (this.loading()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const { currentPassword, newPassword } = this.form.getRawValue();
    this.loading.set(true);
    this.errorMessage.set(null);

    try {
      await this.api.invoke(changePassword, { body: { currentPassword, newPassword } });
      this.auth.markPasswordChanged();
      this.snackBar.open('Contraseña actualizada correctamente.', 'Cerrar', { duration: 4000 });
      await this.router.navigateByUrl('/');
    } catch (error: unknown) {
      const apiError = await extractApiError(error);
      this.applyFieldErrors(apiError);
      this.errorMessage.set(
        await resolveAuthErrorMessage(
          apiError ?? error,
          PASSWORD_ERROR_MESSAGES,
          'No se pudo actualizar la contraseña. Revisa los datos e inténtalo nuevamente.',
        ),
      );
    } finally {
      this.loading.set(false);
    }
  }

  /** Vuelve al inicio cuando el cambio de contraseña no es obligatorio. */
  protected async cancel(): Promise<void> {
    await this.router.navigateByUrl('/');
  }

  /** Cierra la sesión actual si el usuario decide salir sin completar el cambio obligatorio. */
  protected async logout(): Promise<void> {
    if (this.loading()) {
      return;
    }
    this.loading.set(true);
    try {
      await firstValueFrom(this.auth.logout());
      await this.router.navigateByUrl(LOGIN_URL);
    } finally {
      this.loading.set(false);
    }
  }

  private applyFieldErrors(apiError: Awaited<ReturnType<typeof extractApiError>>): void {
    if (!apiError?.hasFieldErrors) {
      return;
    }
    for (const fieldError of apiError.errors) {
      const control = this.form.get(fieldError.field);
      if (control) {
        control.setErrors({ server: fieldError.message });
      }
    }
  }
}
