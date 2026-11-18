import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import {
  OverrideDialogData,
  OverrideMethod,
  OverrideRequest,
  OverrideResponse,
  describeOverrideContext,
  overrideActionLabel,
} from './override.models';

const OVERRIDES_URL = '/api/v1/auth/overrides';
const PIN_PATTERN = /^\d{4,6}$/;

/** Mensajes en español por `ErrorCode` de `POST /auth/overrides` (API.md F.1/F.3). */
const ERROR_MESSAGES: Readonly<Record<string, string>> = {
  OVERRIDE_INVALID:
    'Usuario o credencial del supervisor incorrectos, o sin permiso para esta acción.',
  OVERRIDE_LIMIT_EXCEEDED:
    'El límite del supervisor no cubre esta operación. Pide a otro supervisor.',
  SEGREGATION_OF_DUTIES:
    'El supervisor debe ser una persona distinta de quien realiza la operación.',
  PIN_LOCKED: 'El PIN del supervisor está bloqueado temporalmente. Usa su clave o espera.',
  ACCOUNT_LOCKED: 'La cuenta del supervisor está bloqueada temporalmente.',
  RATE_LIMITED: 'Demasiados intentos. Espera un momento e inténtalo nuevamente.',
  NETWORK_ERROR: 'No se pudo conectar con el servidor. Revisa tu conexión.',
};

/**
 * Diálogo de autorización de supervisor (ADR-19, ROLES_PERMISOS D.4).
 *
 * Muestra la acción y su contexto en lenguaje claro y pide usuario del supervisor, método (PIN o
 * clave), credencial y un motivo opcional. Al enviar llama a `POST /api/v1/auth/overrides` con
 * `withoutErrorNotification()` y muestra cualquier error DENTRO del diálogo (sin snackbar) para
 * que el supervisor pueda reintentar sin perder el contexto.
 *
 * Resultado de `afterClosed()`: {@link OverrideResponse} si el supervisor fue autorizado, o
 * `undefined` si se canceló. Normalmente se abre mediante `SupervisorOverrideService`.
 *
 * Seguridad: la credencial vive solo en el formulario; se borra tras un fallo y nunca se
 * registra. Los bloqueos por intentos fallidos los aplica el servidor (se informan al usuario).
 * Nota: el hash del contexto lo calcula el servidor; el cliente solo reenvía el `context` recibido.
 */
@Component({
  selector: 'pos-override-dialog',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
  ],
  templateUrl: './override-dialog.html',
  styleUrl: './override-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OverrideDialog {
  private readonly http = inject(HttpClient);
  private readonly dialogRef =
    inject<MatDialogRef<OverrideDialog, OverrideResponse | undefined>>(MatDialogRef);
  protected readonly data = inject<OverrideDialogData>(MAT_DIALOG_DATA);

  protected readonly actionLabel = overrideActionLabel(this.data.action);
  protected readonly contextLines = describeOverrideContext(this.data.context);

  protected readonly form = new FormGroup({
    supervisorUsername: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    method: new FormControl<OverrideMethod>('PIN', { nonNullable: true }),
    secret: new FormControl('', { nonNullable: true }),
    reason: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(250)] }),
  });

  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  private readonly method = signal<OverrideMethod>('PIN');
  protected readonly secretLabel = computed(() =>
    this.method() === 'PIN' ? 'PIN (4 a 6 dígitos)' : 'Clave',
  );

  constructor() {
    this.applySecretValidators('PIN');
  }

  /** Cambia entre PIN y clave y ajusta la validación de la credencial. */
  protected onMethodChange(method: OverrideMethod): void {
    this.method.set(method);
    this.applySecretValidators(method);
    this.form.controls.secret.reset('');
  }

  /** Envía la autorización. Ignora el envío si el formulario es inválido o ya hay uno en curso. */
  protected submit(): void {
    if (this.submitting()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { supervisorUsername, method, secret, reason } = this.form.getRawValue();
    const trimmedReason = reason.trim();
    const body: OverrideRequest = {
      action: this.data.action,
      context: this.data.context,
      supervisorUsername: supervisorUsername.trim(),
      method,
      secret,
      ...(trimmedReason ? { reason: trimmedReason } : {}),
    };
    this.submitting.set(true);
    this.dialogRef.disableClose = true;
    this.errorMessage.set(null);
    this.http
      .post<OverrideResponse>(OVERRIDES_URL, body, { context: withoutErrorNotification() })
      .subscribe({
        next: (response) => this.dialogRef.close(response),
        error: (error: unknown) => {
          this.errorMessage.set(this.toMessage(error));
          this.form.controls.secret.reset('');
          this.submitting.set(false);
          this.dialogRef.disableClose = false;
        },
      });
  }

  protected cancel(): void {
    this.dialogRef.close(undefined);
  }

  private applySecretValidators(method: OverrideMethod): void {
    const control = this.form.controls.secret;
    control.setValidators(
      method === 'PIN'
        ? [Validators.required, Validators.pattern(PIN_PATTERN)]
        : [Validators.required, Validators.maxLength(128)],
    );
    control.updateValueAndValidity();
  }

  /** Traduce el error a un mensaje en español; nunca expone detalles internos de 5xx. */
  private toMessage(error: unknown): string {
    const apiError = error instanceof HttpErrorResponse ? ApiError.fromHttpError(error) : error;
    if (!(apiError instanceof ApiError)) {
      return 'No se pudo validar la autorización. Inténtalo nuevamente.';
    }
    if (apiError.status >= 500) {
      return 'Ocurrió un error inesperado en el servidor. Inténtalo nuevamente.';
    }
    return ERROR_MESSAGES[apiError.code] ?? apiError.message;
  }
}
