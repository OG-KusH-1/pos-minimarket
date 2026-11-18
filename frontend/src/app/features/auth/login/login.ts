import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatRadioModule } from '@angular/material/radio';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Api } from '../../../api/api';
import { bindSessionTerminal } from '../../../api/fn/auth/bind-session-terminal';
import { myStores } from '../../../api/fn/auth/my-stores';
import { listByStore } from '../../../api/fn/organization/list-by-store';
import type { StoreSummary } from '../../../api/models/store-summary';
import type { TerminalResponse } from '../../../api/models/terminal-response';
import { CHANGE_PASSWORD_URL } from '../../../core/auth/auth.guard';
import { AuthService } from '../../../core/auth/auth.service';
import { ErrorCodes } from '../../../core/http/api-error';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { resolveAuthErrorMessage, sanitizeReturnUrl } from '../auth-errors';

/** Pasos del flujo de inicio de sesión: credenciales → local → terminal. */
export type LoginStep = 'credentials' | 'store' | 'terminal';

const LOGIN_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  [ErrorCodes.invalidCredentials]: 'Usuario o contraseña incorrectos.',
  ACCOUNT_LOCKED: 'Tu cuenta está bloqueada temporalmente por intentos fallidos.',
  RATE_LIMITED: 'Demasiados intentos. Espera un momento e inténtalo nuevamente.',
  [ErrorCodes.network]: 'No se pudo conectar con el servidor. Revisa tu conexión.',
};

const STORE_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  STORE_ACCESS_DENIED: 'No tienes acceso al local seleccionado.',
  [ErrorCodes.network]: 'No se pudo conectar con el servidor. Revisa tu conexión.',
};

const TERMINAL_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  TERMINAL_INVALID: 'La terminal seleccionada no está disponible o no pertenece al local.',
  STORE_ACCESS_DENIED: 'No tienes acceso al local seleccionado.',
  [ErrorCodes.network]: 'No se pudo conectar con el servidor. Revisa tu conexión.',
};

/**
 * Pantalla de inicio de sesión en tres pasos (D.4, HU-SEG-01):
 * 1. Credenciales (`POST /auth/login`): si `mustChangePassword` es `true`, redirige a `/cambiar-clave`.
 * 2. Selección de local (`GET /auth/me/stores`): si el usuario solo tiene un local activo, se
 *    selecciona automáticamente y avanza al paso de terminal.
 * 3. Selección de terminal (`GET /stores/{id}/terminals` → `POST /auth/session/terminal`): liga la
 *    sesión vigente a la terminal elegida y navega al `returnUrl` validado (L-06).
 */
@Component({
  selector: 'pos-login',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatRadioModule,
    ReactiveFormsModule,
  ],
  templateUrl: './login.html',
  styleUrl: './login.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly step = signal<LoginStep>('credentials');
  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly hidePassword = signal(true);

  protected readonly stores = signal<readonly StoreSummary[]>([]);
  protected readonly terminals = signal<readonly TerminalResponse[]>([]);
  protected readonly selectedStoreId = signal<number | null>(null);
  protected readonly selectedTerminalId = signal<number | null>(null);

  protected readonly selectedStore = computed(
    () => this.stores().find((store) => store.id === this.selectedStoreId()) ?? null,
  );

  protected readonly credentialsForm = new FormGroup({
    username: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(3), Validators.maxLength(50)],
    }),
    password: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(128)],
    }),
  });

  private returnUrl(): string {
    return sanitizeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
  }

  /** Alterna la visibilidad de la contraseña. */
  protected togglePasswordVisibility(): void {
    this.hidePassword.update((hidden) => !hidden);
  }

  /** Envía las credenciales e inicia el flujo de selección de local y terminal. */
  protected async submitCredentials(): Promise<void> {
    if (this.loading()) {
      return;
    }
    if (this.credentialsForm.invalid) {
      this.credentialsForm.markAllAsTouched();
      return;
    }

    const { username, password } = this.credentialsForm.getRawValue();
    this.loading.set(true);
    this.errorMessage.set(null);

    try {
      const user = await firstValueFrom(this.auth.login({ username: username.trim(), password }));
      if (this.auth.mustChangePassword()) {
        await this.router.navigateByUrl(CHANGE_PASSWORD_URL);
        return;
      }
      await this.loadStoresAndAdvance(user.defaultStoreId);
    } catch (error: unknown) {
      this.credentialsForm.controls.password.reset('');
      this.errorMessage.set(
        await resolveAuthErrorMessage(
          error,
          LOGIN_ERROR_MESSAGES,
          'No se pudo iniciar sesión. Inténtalo nuevamente.',
        ),
      );
    } finally {
      this.loading.set(false);
    }
  }

  /** Confirma el local elegido y carga sus terminales activas. */
  protected async confirmStore(): Promise<void> {
    const storeId = this.selectedStoreId();
    if (storeId === null || this.loading()) {
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(null);
    try {
      await this.selectStoreAndLoadTerminals(storeId);
    } catch (error: unknown) {
      this.errorMessage.set(
        await resolveAuthErrorMessage(
          error,
          STORE_ERROR_MESSAGES,
          'No se pudieron cargar las terminales del local.',
        ),
      );
    } finally {
      this.loading.set(false);
    }
  }

  /** Liga la sesión a la terminal elegida y redirige al destino solicitado. */
  protected async confirmTerminal(): Promise<void> {
    const terminalId = this.selectedTerminalId();
    if (terminalId === null || this.loading()) {
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(null);
    try {
      await this.api.invoke(bindSessionTerminal, { body: { terminalId } });
      this.storeContext.setTerminal(terminalId);
      await this.router.navigateByUrl(this.returnUrl());
    } catch (error: unknown) {
      this.errorMessage.set(
        await resolveAuthErrorMessage(
          error,
          TERMINAL_ERROR_MESSAGES,
          'No se pudo asociar la terminal seleccionada.',
        ),
      );
    } finally {
      this.loading.set(false);
    }
  }

  /** Permite continuar sin terminal cuando el local no tiene terminales activas configuradas. */
  protected async continueWithoutTerminal(): Promise<void> {
    if (this.loading()) {
      return;
    }
    this.storeContext.setTerminal(null);
    await this.router.navigateByUrl(this.returnUrl());
  }

  /** Vuelve al paso de selección de local cuando el usuario tiene más de uno asignado. */
  protected backToStores(): void {
    this.errorMessage.set(null);
    this.terminals.set([]);
    this.selectedTerminalId.set(null);
    this.step.set('store');
  }

  /** Cierra la sesión recién iniciada para volver al formulario de credenciales. */
  protected async cancelSession(): Promise<void> {
    if (this.loading()) {
      return;
    }
    this.loading.set(true);
    try {
      await firstValueFrom(this.auth.logout());
    } finally {
      this.stores.set([]);
      this.terminals.set([]);
      this.selectedStoreId.set(null);
      this.selectedTerminalId.set(null);
      this.credentialsForm.reset({ username: '', password: '' });
      this.errorMessage.set(null);
      this.step.set('credentials');
      this.loading.set(false);
    }
  }

  private async loadStoresAndAdvance(defaultStoreId: number | null): Promise<void> {
    const rawStores = await this.api.invoke(myStores);
    const allStores = await unwrapArrayResponse<StoreSummary>(rawStores);
    const activeStores = allStores.filter(
      (store): store is StoreSummary & { id: number } =>
        store.active !== false && store.id !== undefined,
    );
    this.stores.set(activeStores);

    if (activeStores.length === 0) {
      this.selectedStoreId.set(null);
      this.errorMessage.set('No tienes locales activos asignados. Contacta a un administrador.');
      this.step.set('store');
      return;
    }

    const [onlyStore] = activeStores;
    if (activeStores.length === 1 && onlyStore !== undefined) {
      this.selectedStoreId.set(onlyStore.id);
      await this.selectStoreAndLoadTerminals(onlyStore.id);
      return;
    }

    const preferredStoreId = this.storeContext.storeId();
    const initialStore =
      activeStores.find((s) => s.id === preferredStoreId) ??
      activeStores.find((s) => s.id === defaultStoreId) ??
      onlyStore;
    this.selectedStoreId.set(initialStore?.id ?? null);
    this.step.set('store');
  }

  private async selectStoreAndLoadTerminals(storeId: number): Promise<void> {
    this.storeContext.setStore(storeId);
    const rawTerminals = await this.api.invoke(listByStore, { storeId });
    const allTerminals = await unwrapArrayResponse<TerminalResponse>(rawTerminals);
    const activeTerminals = allTerminals.filter(
      (terminal): terminal is TerminalResponse & { id: number } =>
        terminal.active !== false && terminal.id !== undefined,
    );
    this.terminals.set(activeTerminals);

    const preferredTerminalId = this.storeContext.terminalId();
    const initialTerminal =
      activeTerminals.find((t) => t.id === preferredTerminalId) ?? activeTerminals[0] ?? null;
    this.selectedTerminalId.set(initialTerminal?.id ?? null);
    this.step.set('terminal');
  }
}

/**
 * Algunos endpoints generados por `ng-openapi-gen` con media type `* /*` declaran
 * `responseType: 'blob'`; si llega un `Blob` JSON, lo decodifica a arreglo.
 */
async function unwrapArrayResponse<T>(data: readonly T[] | Blob): Promise<readonly T[]> {
  if (data instanceof Blob) {
    return JSON.parse(await data.text()) as readonly T[];
  }
  return data;
}
