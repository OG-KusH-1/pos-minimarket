import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { catchError, forkJoin, of } from 'rxjs';
import { CompanyResponse } from '../../../api/models/company-response';
import { StoreResponse } from '../../../api/models/store-response';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { integerValidator } from '../settings-api.utils';
import {
  SETTING_DEFINITIONS,
  SettingDefinition,
  SettingItem,
  SettingJsonValue,
  SettingScope,
} from './settings.metadata';
import { SettingsService } from './settings.service';
import { TicketSettingsForm } from './ticket-settings-form';

/** Controles reactivos de una fila de parámetro operativo o de seguridad. */
interface SettingRowState {
  readonly def: SettingDefinition;
  readonly form: FormGroup<{
    scope: FormControl<SettingScope>;
    booleanValue: FormControl<boolean>;
    numberValue: FormControl<number>;
  }>;
}

/**
 * Pantalla de configuración de parámetros del sistema (`HU-CFG-04`, `HU-CFG-05`).
 *
 * Agrupa los parámetros en tres pestañas:
 * 1. Ticket por local (`ticket.*`)
 * 2. Operación y caja (`pos.*`, `alerts.*`, `cash.*`, `email.*`)
 * 3. Seguridad global (`security.*`)
 */
@Component({
  selector: 'pos-settings',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTabsModule,
    ReactiveFormsModule,
    TicketSettingsForm,
  ],
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Settings implements OnInit {
  private readonly settingsService = inject(SettingsService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly savingKey = signal<string | null>(null);

  protected readonly stores = signal<readonly StoreResponse[]>([]);
  protected readonly selectedStoreId = signal<number | null>(this.storeContext.storeId());
  protected readonly settings = signal<readonly SettingItem[]>([]);
  protected readonly effectiveValues = signal<Readonly<Record<string, SettingJsonValue>>>({});
  protected readonly company = signal<CompanyResponse | null>(null);

  protected readonly canUpdateSettings = computed(() =>
    this.auth.hasPermission('config:settings:update'),
  );
  protected readonly canUpdateGlobal = computed(
    () => this.canUpdateSettings() && this.auth.hasPermission('security:store:all-access'),
  );

  protected readonly selectedStoreName = computed(() => {
    const id = this.selectedStoreId();
    if (id === null) {
      return '';
    }
    return this.stores().find((s) => s.id === id)?.name ?? '';
  });

  protected readonly operationDefinitions = SETTING_DEFINITIONS.filter(
    (d) => d.group === 'operation',
  );
  protected readonly securityDefinitions = SETTING_DEFINITIONS.filter(
    (d) => d.group === 'security',
  );

  private readonly rowStates = new Map<string, SettingRowState>();

  constructor() {
    for (const def of [...this.operationDefinitions, ...this.securityDefinitions]) {
      this.rowStates.set(def.key, this.createRowState(def));
    }
  }

  ngOnInit(): void {
    this.reloadAll();
  }

  /** Carga los locales accesibles, la empresa (para vista previa) y los parámetros del local. */
  reloadAll(): void {
    this.loading.set(true);
    this.loadError.set(null);

    forkJoin({
      stores: this.settingsService.listStores(withoutErrorNotification()),
      company: this.settingsService
        .getCompany(withoutErrorNotification())
        .pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ stores, company }) => {
        this.stores.set(stores);
        this.company.set(company);

        const current = this.selectedStoreId();
        const validCurrent = current !== null && stores.some((s) => s.id === current);
        const resolvedStoreId = validCurrent ? current : (stores[0]?.id ?? null);
        this.selectedStoreId.set(resolvedStoreId);

        if (resolvedStoreId !== null) {
          this.reloadSettings();
        } else {
          this.loading.set(false);
        }
      },
      error: (err) => {
        this.loading.set(false);
        this.loadError.set(
          isApiError(err) ? err.message : 'No se pudieron cargar los locales disponibles.',
        );
      },
    });
  }

  /** Cambia el local activo de la pantalla y recarga sus parámetros. */
  protected onStoreChange(storeId: number | null): void {
    this.selectedStoreId.set(storeId);
    if (storeId !== null) {
      this.reloadSettings();
    }
  }

  /** Recarga la lista de parámetros y valores efectivos del local seleccionado. */
  reloadSettings(): void {
    const storeId = this.selectedStoreId();
    if (storeId === null) {
      return;
    }

    this.loading.set(true);
    this.loadError.set(null);

    forkJoin({
      settings: this.settingsService.listSettings(undefined, storeId, withoutErrorNotification()),
      effective: this.settingsService.getEffectiveSettings(storeId, withoutErrorNotification()),
    }).subscribe({
      next: ({ settings, effective }) => {
        this.settings.set(settings);
        this.effectiveValues.set(effective);
        this.syncRowForms();
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.loadError.set(
          isApiError(err) ? err.message : 'No se pudieron cargar los parámetros del local.',
        );
      },
    });
  }

  protected rowFor(key: string): SettingRowState {
    const state = this.rowStates.get(key);
    if (!state) {
      throw new Error(`Definición de parámetro desconocida: ${key}`);
    }
    return state;
  }

  protected hasStoreOverride(key: string): boolean {
    const storeId = this.selectedStoreId();
    return (
      storeId !== null &&
      this.settings().some((s) => s.key === key && s.scope === 'STORE' && s.storeId === storeId)
    );
  }

  protected canSaveRow(def: SettingDefinition): boolean {
    if (!this.canUpdateSettings()) {
      return false;
    }
    const row = this.rowFor(def.key);
    if (row.form.invalid) {
      return false;
    }
    const scope = row.form.controls.scope.value;
    if (!def.storeOverrideAllowed || scope === 'GLOBAL') {
      return this.canUpdateGlobal();
    }
    return this.selectedStoreId() !== null;
  }

  protected saveRow(def: SettingDefinition): void {
    if (!this.canSaveRow(def) || this.savingKey() !== null) {
      return;
    }

    const row = this.rowFor(def.key);
    const raw = row.form.getRawValue();
    const scope: SettingScope = def.storeOverrideAllowed ? raw.scope : 'GLOBAL';
    const value: SettingJsonValue =
      def.valueType === 'BOOLEAN' ? Boolean(raw.booleanValue) : Number(raw.numberValue);
    const storeId = this.selectedStoreId();

    this.savingKey.set(def.key);
    this.settingsService
      .updateSetting(def.key, scope, value, storeId, withoutErrorNotification())
      .subscribe({
        next: () => {
          this.savingKey.set(null);
          this.snackBar.open(`Parámetro "${def.label}" actualizado.`, 'Cerrar', {
            duration: 4000,
          });
          this.reloadSettings();
        },
        error: (err) => {
          this.savingKey.set(null);
          const msg = isApiError(err) ? err.message : 'No se pudo actualizar el parámetro.';
          if (def.valueType === 'NUMBER') {
            row.form.controls.numberValue.setErrors({ server: msg });
            row.form.controls.numberValue.markAsTouched();
          } else {
            this.snackBar.open(msg, 'Cerrar', { duration: 5000 });
          }
        },
      });
  }

  private createRowState(def: SettingDefinition): SettingRowState {
    const validators = [Validators.required, integerValidator];
    if (def.min !== undefined) {
      validators.push(Validators.min(def.min));
    }
    if (def.max !== undefined) {
      validators.push(Validators.max(def.max));
    }

    const initialScope: SettingScope = def.storeOverrideAllowed ? 'STORE' : 'GLOBAL';
    const scopeControl = this.fb.control<SettingScope>(initialScope);
    const booleanControl = this.fb.control<boolean>(Boolean(def.defaultValue));
    const numberControl = this.fb.control<number>(
      typeof def.defaultValue === 'number' ? def.defaultValue : 0,
      validators,
    );

    const form = this.fb.group({
      scope: scopeControl,
      booleanValue: booleanControl,
      numberValue: numberControl,
    });

    scopeControl.valueChanges.subscribe((scope) => {
      this.applyValueToRow(def, form, scope);
      this.applyPermissionsToRow(def, form);
    });

    return { def, form };
  }

  private syncRowForms(): void {
    for (const { def, form } of this.rowStates.values()) {
      const targetScope: SettingScope = def.storeOverrideAllowed
        ? this.hasStoreOverride(def.key) || !this.canUpdateGlobal()
          ? 'STORE'
          : form.controls.scope.value
        : 'GLOBAL';
      form.controls.scope.setValue(targetScope, { emitEvent: false });
      this.applyValueToRow(def, form, targetScope);
      this.applyPermissionsToRow(def, form);
    }
  }

  private applyValueToRow(
    def: SettingDefinition,
    form: SettingRowState['form'],
    scope: SettingScope,
  ): void {
    const storeId = this.selectedStoreId();
    const list = this.settings();
    let value: SettingJsonValue = def.defaultValue;

    if (scope === 'STORE' && storeId !== null) {
      const storeItem = list.find(
        (s) => s.key === def.key && s.scope === 'STORE' && s.storeId === storeId,
      );
      if (storeItem !== undefined) {
        value = storeItem.value;
      } else {
        const eff = this.effectiveValues()[def.key];
        value =
          eff !== undefined
            ? eff
            : (list.find((s) => s.key === def.key && s.scope === 'GLOBAL')?.value ??
              def.defaultValue);
      }
    } else {
      const globalItem = list.find((s) => s.key === def.key && s.scope === 'GLOBAL');
      if (globalItem !== undefined) {
        value = globalItem.value;
      }
    }

    if (def.valueType === 'BOOLEAN') {
      form.controls.booleanValue.setValue(Boolean(value), { emitEvent: false });
    } else {
      form.controls.numberValue.setValue(Number(value), { emitEvent: false });
    }
  }

  private applyPermissionsToRow(def: SettingDefinition, form: SettingRowState['form']): void {
    const canEdit =
      this.canUpdateSettings() && (def.storeOverrideAllowed || this.canUpdateGlobal());
    if (canEdit) {
      form.enable({ emitEvent: false });
    } else {
      form.disable({ emitEvent: false });
    }
  }
}
