import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import {
  FormControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';
import { CompanyResponse } from '../../../api/models/company-response';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { RutPipe } from '../../../shared/pipes';
import { applyFormApiErrors, integerValidator } from '../settings-api.utils';
import {
  MAX_TICKET_LINE_LENGTH,
  MAX_TICKET_LINES,
  MAX_TICKET_PAPER_WIDTH_MM,
  MIN_TICKET_PAPER_WIDTH_MM,
  SettingItem,
  SettingJsonValue,
  SettingScope,
  TICKET_SETTING_KEYS,
} from './settings.metadata';
import { SettingsService } from './settings.service';

/**
 * Formulario reactivo para configurar el formato de ticket por local o global (`HU-CFG-04`).
 *
 * Gestiona `ticket.header_lines`, `ticket.footer_lines`, `ticket.show_tax_breakdown` y
 * `ticket.paper_width_mm` con validación de límites y vista previa térmica en vivo.
 */
@Component({
  selector: 'pos-ticket-settings-form',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    ReactiveFormsModule,
    RutPipe,
  ],
  templateUrl: './ticket-settings-form.html',
  styleUrl: './ticket-settings-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TicketSettingsForm {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly settingsService = inject(SettingsService);
  private readonly snackBar = inject(MatSnackBar);

  readonly storeId = input.required<number | null>();
  readonly storeName = input<string>('');
  readonly settings = input.required<readonly SettingItem[]>();
  readonly effectiveValues = input.required<Readonly<Record<string, SettingJsonValue>>>();
  readonly canUpdateStore = input(false);
  readonly canUpdateGlobal = input(false);
  readonly company = input<CompanyResponse | null>(null);
  readonly saved = output<void>();

  protected readonly maxLines = MAX_TICKET_LINES;
  protected readonly maxLineLength = MAX_TICKET_LINE_LENGTH;
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly headerLines = this.fb.array<FormControl<string>>([]);
  protected readonly footerLines = this.fb.array<FormControl<string>>([]);

  protected readonly form = this.fb.group({
    scope: ['STORE' as SettingScope],
    paperWidthMm: [
      80,
      [
        Validators.required,
        Validators.min(MIN_TICKET_PAPER_WIDTH_MM),
        Validators.max(MAX_TICKET_PAPER_WIDTH_MM),
        integerValidator,
      ],
    ],
    showTaxBreakdown: [true],
    headerLines: this.headerLines,
    footerLines: this.footerLines,
  });

  private readonly selectedScope = signal<SettingScope>('STORE');

  /** Indica si existe al menos un override `STORE` de ticket en el local seleccionado. */
  protected readonly hasAnyStoreOverride = computed(() => {
    const id = this.storeId();
    if (id === null) {
      return false;
    }
    const ticketKeys = new Set<string>(Object.values(TICKET_SETTING_KEYS));
    return this.settings().some(
      (item) => item.scope === 'STORE' && item.storeId === id && ticketKeys.has(item.key),
    );
  });

  /** Indica si el usuario puede editar el alcance actualmente seleccionado. */
  protected readonly canEditCurrentScope = computed(() => {
    const scope = this.selectedScope();
    if (scope === 'GLOBAL') {
      return this.canUpdateGlobal();
    }
    return this.canUpdateStore() && this.storeId() !== null;
  });

  constructor() {
    this.form.controls.scope.valueChanges.subscribe((scope) => {
      this.selectedScope.set(scope);
      this.syncFormFromSettings();
    });

    effect(() => {
      // Reacciona cuando cambian el local, los settings cargados o los permisos.
      this.storeId();
      this.settings();
      this.effectiveValues();
      this.canUpdateStore();
      this.canUpdateGlobal();

      if (this.storeId() === null && this.canUpdateGlobal()) {
        this.form.controls.scope.setValue('GLOBAL', { emitEvent: false });
        this.selectedScope.set('GLOBAL');
      } else if (!this.canUpdateGlobal() && this.form.controls.scope.value === 'GLOBAL') {
        this.form.controls.scope.setValue('STORE', { emitEvent: false });
        this.selectedScope.set('STORE');
      }

      this.syncFormFromSettings();
      this.syncDisabledState();
    });
  }

  protected setPaperWidth(width: number): void {
    if (!this.canEditCurrentScope()) {
      return;
    }
    this.form.controls.paperWidthMm.setValue(width);
    this.form.controls.paperWidthMm.markAsDirty();
  }

  protected addHeaderLine(): void {
    if (!this.canEditCurrentScope() || this.headerLines.length >= MAX_TICKET_LINES) {
      return;
    }
    this.headerLines.push(this.createLineControl(''));
  }

  protected removeHeaderLine(index: number): void {
    if (!this.canEditCurrentScope()) {
      return;
    }
    this.headerLines.removeAt(index);
  }

  protected addFooterLine(): void {
    if (!this.canEditCurrentScope() || this.footerLines.length >= MAX_TICKET_LINES) {
      return;
    }
    this.footerLines.push(this.createLineControl(''));
  }

  protected removeFooterLine(index: number): void {
    if (!this.canEditCurrentScope()) {
      return;
    }
    this.footerLines.removeAt(index);
  }

  /** Valida y guarda las 4 claves de formato de ticket en el alcance seleccionado. */
  submit(): void {
    if (!this.canEditCurrentScope() || this.saving()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const targetStoreId = raw.scope === 'STORE' ? this.storeId() : (this.storeId() ?? null);

    this.saving.set(true);
    this.serverError.set(null);

    forkJoin([
      this.settingsService.updateSetting(
        TICKET_SETTING_KEYS.paperWidthMm,
        raw.scope,
        Number(raw.paperWidthMm),
        targetStoreId,
        withoutErrorNotification(),
      ),
      this.settingsService.updateSetting(
        TICKET_SETTING_KEYS.showTaxBreakdown,
        raw.scope,
        Boolean(raw.showTaxBreakdown),
        targetStoreId,
        withoutErrorNotification(),
      ),
      this.settingsService.updateSetting(
        TICKET_SETTING_KEYS.headerLines,
        raw.scope,
        raw.headerLines.slice(0, MAX_TICKET_LINES),
        targetStoreId,
        withoutErrorNotification(),
      ),
      this.settingsService.updateSetting(
        TICKET_SETTING_KEYS.footerLines,
        raw.scope,
        raw.footerLines.slice(0, MAX_TICKET_LINES),
        targetStoreId,
        withoutErrorNotification(),
      ),
    ]).subscribe({
      next: () => {
        this.saving.set(false);
        this.snackBar.open('Formato de ticket actualizado correctamente.', 'Cerrar', {
          duration: 4000,
        });
        this.saved.emit();
      },
      error: (err) => {
        this.saving.set(false);
        applyFormApiErrors(this.form, err, this.serverError);
      },
    });
  }

  private syncFormFromSettings(): void {
    const scope = this.selectedScope();
    const paperWidth = this.resolveValue<number>(TICKET_SETTING_KEYS.paperWidthMm, scope, 80);
    const showTax = this.resolveValue<boolean>(TICKET_SETTING_KEYS.showTaxBreakdown, scope, true);
    const header = this.resolveLines(TICKET_SETTING_KEYS.headerLines, scope);
    const footer = this.resolveLines(TICKET_SETTING_KEYS.footerLines, scope);

    this.form.controls.paperWidthMm.setValue(Number(paperWidth), { emitEvent: false });
    this.form.controls.showTaxBreakdown.setValue(Boolean(showTax), { emitEvent: false });
    this.replaceArray(this.headerLines, header);
    this.replaceArray(this.footerLines, footer);
    this.syncDisabledState();
  }

  private syncDisabledState(): void {
    const editable = this.canEditCurrentScope();
    const controls = [
      this.form.controls.paperWidthMm,
      this.form.controls.showTaxBreakdown,
      this.headerLines,
      this.footerLines,
    ];
    for (const control of controls) {
      if (editable) {
        control.enable({ emitEvent: false });
      } else {
        control.disable({ emitEvent: false });
      }
    }
  }

  private replaceArray(target: typeof this.headerLines, values: readonly string[]): void {
    target.clear({ emitEvent: false });
    for (const line of values.slice(0, MAX_TICKET_LINES)) {
      target.push(this.createLineControl(line), { emitEvent: false });
    }
  }

  private createLineControl(initialValue: string): FormControl<string> {
    return this.fb.control(initialValue, [Validators.maxLength(MAX_TICKET_LINE_LENGTH)]);
  }

  private resolveValue<T extends SettingJsonValue>(
    key: string,
    scope: SettingScope,
    fallback: T,
  ): T {
    const storeId = this.storeId();
    const list = this.settings();

    if (scope === 'STORE' && storeId !== null) {
      const storeItem = list.find(
        (item) => item.key === key && item.scope === 'STORE' && item.storeId === storeId,
      );
      if (storeItem !== undefined) {
        return storeItem.value as T;
      }
      const effective = this.effectiveValues()[key];
      if (effective !== undefined) {
        return effective as T;
      }
    }

    const globalItem = list.find((item) => item.key === key && item.scope === 'GLOBAL');
    if (globalItem !== undefined) {
      return globalItem.value as T;
    }

    const effective = this.effectiveValues()[key];
    return effective !== undefined ? (effective as T) : fallback;
  }

  private resolveLines(key: string, scope: SettingScope): readonly string[] {
    const raw = this.resolveValue<SettingJsonValue>(key, scope, []);
    return Array.isArray(raw) ? raw.map((item) => String(item)) : [];
  }
}
