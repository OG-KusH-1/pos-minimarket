import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
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
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CompanyRequest } from '../../../api/models/company-request';
import { CompanyResponse } from '../../../api/models/company-response';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { RutPipe } from '../../../shared/pipes';
import { formatRut, isValidRut, rutValidator } from '../../../shared/validators';
import { applyFormApiErrors } from '../settings-api.utils';
import { CompanyService } from './company.service';

/** Zonas horarias IANA habituales para operación en Chile y la región. */
const COMMON_TIMEZONES: readonly string[] = [
  'America/Santiago',
  'America/Punta_Arenas',
  'Pacific/Easter',
  'America/Buenos_Aires',
  'America/Lima',
  'UTC',
];

/**
 * Pantalla de administración de los datos de la empresa (`HU-CFG-01`).
 *
 * Permite editar razón social, nombre de fantasía, RUT (validado por módulo 11), giro,
 * dirección, zona horaria IANA y logo corporativo, previsualizando cómo se imprime en el ticket.
 */
@Component({
  selector: 'pos-company',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    ReactiveFormsModule,
    RutPipe,
  ],
  templateUrl: './company.html',
  styleUrl: './company.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Company implements OnInit, OnDestroy {
  private readonly companyService = inject(CompanyService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly uploadingLogo = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly serverError = signal<string | null>(null);
  protected readonly logoPreviewUrl = signal<string | null>(null);
  private readonly loadedTimezone = signal<string | null>(null);

  protected readonly canUpdate = computed(() => this.auth.hasPermission('config:company:update'));

  protected readonly timezoneOptions = computed<readonly string[]>(() => {
    const tz = this.loadedTimezone();
    if (tz && !COMMON_TIMEZONES.includes(tz)) {
      return [tz, ...COMMON_TIMEZONES];
    }
    return COMMON_TIMEZONES;
  });

  protected readonly form = this.fb.group({
    rut: ['', [Validators.required, Validators.maxLength(15), rutValidator]],
    legalName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(200)]],
    tradeName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(200)]],
    businessActivity: [
      '',
      [Validators.required, Validators.minLength(2), Validators.maxLength(200)],
    ],
    address: ['', [Validators.required, Validators.maxLength(250)]],
    commune: ['', [Validators.required, Validators.maxLength(100)]],
    city: ['', [Validators.required, Validators.maxLength(100)]],
    phone: ['', [Validators.maxLength(30)]],
    email: ['', [Validators.email, Validators.maxLength(254)]],
    timezone: ['America/Santiago', [Validators.required, Validators.maxLength(40)]],
    logoFileId: new FormControl<number | null>(null),
  });

  ngOnInit(): void {
    if (!this.canUpdate()) {
      this.form.disable({ emitEvent: false });
    }
    this.loadCompany();
  }

  ngOnDestroy(): void {
    this.revokeLogoPreview();
  }

  /** Carga los datos actuales de la empresa desde el servidor. */
  loadCompany(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.serverError.set(null);

    this.companyService.getCompany(withoutErrorNotification()).subscribe({
      next: (company) => {
        this.applyCompany(company);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.loadError.set(
          isApiError(err) ? err.message : 'No se pudieron cargar los datos de la empresa.',
        );
      },
    });
  }

  /** Formatea el RUT si es válido al perder el foco para mejorar la legibilidad. */
  protected onRutBlur(): void {
    const control = this.form.controls.rut;
    const raw = control.value.trim();
    if (raw && isValidRut(raw)) {
      control.setValue(formatRut(raw));
    }
  }

  /** Sube un nuevo archivo de logo y asocia su `logoFileId` al formulario. */
  protected onLogoFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0];
    if (!file || !this.canUpdate() || this.uploadingLogo()) {
      return;
    }

    this.uploadingLogo.set(true);
    this.serverError.set(null);
    const storeId = this.resolveStoreIdForFiles();

    this.companyService.uploadLogo(file, storeId, withoutErrorNotification()).subscribe({
      next: (uploaded) => {
        this.uploadingLogo.set(false);
        if (uploaded.id !== undefined) {
          this.form.controls.logoFileId.setValue(uploaded.id);
          this.loadLogoPreview(uploaded.id);
        }
        this.snackBar.open('Logo subido. Guarda los cambios para aplicarlo.', 'Cerrar', {
          duration: 4000,
        });
        if (input) {
          input.value = '';
        }
      },
      error: (err) => {
        this.uploadingLogo.set(false);
        this.serverError.set(isApiError(err) ? err.message : 'No se pudo subir el logo.');
        if (input) {
          input.value = '';
        }
      },
    });
  }

  /** Desvincula el logo actual del formulario. */
  protected removeLogo(): void {
    this.form.controls.logoFileId.setValue(null);
    this.revokeLogoPreview();
  }

  /** Valida y envía los cambios de la empresa al backend. */
  submit(): void {
    if (!this.canUpdate() || this.saving()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: CompanyRequest = {
      rut: raw.rut.trim(),
      legalName: raw.legalName.trim(),
      tradeName: raw.tradeName.trim(),
      businessActivity: raw.businessActivity.trim(),
      address: raw.address.trim(),
      commune: raw.commune.trim(),
      city: raw.city.trim(),
      phone: raw.phone.trim() || undefined,
      email: raw.email.trim() || undefined,
      timezone: raw.timezone.trim(),
      logoFileId: raw.logoFileId ?? undefined,
    };

    this.saving.set(true);
    this.serverError.set(null);

    this.companyService.updateCompany(request, withoutErrorNotification()).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.applyCompany(updated);
        this.snackBar.open('Datos de la empresa actualizados correctamente.', 'Cerrar', {
          duration: 4000,
        });
      },
      error: (err) => {
        this.saving.set(false);
        applyFormApiErrors(this.form, err, this.serverError);
      },
    });
  }

  private applyCompany(company: CompanyResponse): void {
    const timezone = company.timezone ?? 'America/Santiago';
    this.loadedTimezone.set(timezone);
    this.form.reset(
      {
        rut: company.rut ? formatRut(company.rut) : '',
        legalName: company.legalName ?? '',
        tradeName: company.tradeName ?? '',
        businessActivity: company.businessActivity ?? '',
        address: company.address ?? '',
        commune: company.commune ?? '',
        city: company.city ?? '',
        phone: company.phone ?? '',
        email: company.email ?? '',
        timezone,
        logoFileId: company.logoFileId ?? null,
      },
      { emitEvent: false },
    );
    if (company.logoFileId !== undefined && company.logoFileId !== null) {
      this.loadLogoPreview(company.logoFileId);
    } else {
      this.revokeLogoPreview();
    }
  }

  private loadLogoPreview(fileId: number): void {
    const storeId = this.resolveStoreIdForFiles();
    this.companyService.downloadLogo(fileId, storeId, withoutErrorNotification()).subscribe({
      next: (blob) => {
        this.revokeLogoPreview();
        if (typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function') {
          this.logoPreviewUrl.set(URL.createObjectURL(blob));
        }
      },
      error: () => {
        this.revokeLogoPreview();
      },
    });
  }

  private revokeLogoPreview(): void {
    const current = this.logoPreviewUrl();
    if (current && typeof URL !== 'undefined' && typeof URL.revokeObjectURL === 'function') {
      URL.revokeObjectURL(current);
    }
    this.logoPreviewUrl.set(null);
  }

  private resolveStoreIdForFiles(): number | null {
    return this.storeContext.storeId() ?? this.storeContext.availableStores()[0]?.id ?? null;
  }
}
