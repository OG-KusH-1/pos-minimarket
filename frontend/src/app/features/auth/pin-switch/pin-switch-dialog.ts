import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { firstValueFrom } from 'rxjs';
import type { MeResponse } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { ErrorCodes } from '../../../core/http/api-error';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { resolveAuthErrorMessage } from '../auth-errors';

const PIN_PATTERN = /^\d{4,6}$/;

const PIN_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  [ErrorCodes.invalidCredentials]: 'Usuario o PIN incorrectos.',
  PIN_LOCKED: 'El PIN está bloqueado temporalmente por intentos fallidos.',
  TERMINAL_INVALID: 'La sesión no está asociada a una terminal válida. Inicia sesión nuevamente.',
  RATE_LIMITED: 'Demasiados intentos. Espera un momento e inténtalo nuevamente.',
  [ErrorCodes.network]: 'No se pudo conectar con el servidor. Revisa tu conexión.',
};

/**
 * Diálogo de cambio rápido de cajero mediante PIN en la terminal activa (`POST /auth/pin-login`,
 * HU-SEG-03).
 *
 * Emite una nueva sesión con `scope = POS` asociada a la terminal vigente (`X-Terminal-Id`) y
 * devuelve el nuevo {@link MeResponse} al cerrar, o `undefined` si se cancela.
 */
@Component({
  selector: 'pos-pin-switch-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
  ],
  templateUrl: './pin-switch-dialog.html',
  styleUrl: './pin-switch-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PinSwitchDialog {
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialogRef =
    inject<MatDialogRef<PinSwitchDialog, MeResponse | undefined>>(MatDialogRef);

  protected readonly currentUser = this.auth.user;
  protected readonly hasTerminal = () => this.storeContext.terminalId() !== null;
  protected readonly hidePin = signal(true);
  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly form = new FormGroup({
    username: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(3), Validators.maxLength(50)],
    }),
    pin: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(PIN_PATTERN)],
    }),
  });

  /** Envía el cambio de cajero por PIN. */
  protected async submit(): Promise<void> {
    if (this.submitting()) {
      return;
    }
    if (!this.hasTerminal()) {
      this.errorMessage.set('No hay una terminal activa asociada a esta sesión.');
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const { username, pin } = this.form.getRawValue();
    this.submitting.set(true);
    this.errorMessage.set(null);

    try {
      const user = await firstValueFrom(this.auth.pinLogin({ username: username.trim(), pin }));
      this.dialogRef.close(user);
    } catch (error: unknown) {
      this.form.controls.pin.reset('');
      this.errorMessage.set(
        await resolveAuthErrorMessage(
          error,
          PIN_ERROR_MESSAGES,
          'No se pudo cambiar de cajero. Inténtalo nuevamente.',
        ),
      );
    } finally {
      this.submitting.set(false);
    }
  }
}
