import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import {
  MatAutocompleteModule,
  MatAutocompleteSelectedEvent,
} from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { LotResponse } from '../../api/models/lot-response';
import { ProductResponse } from '../../api/models/product-response';
import { ProductSummary } from '../../api/models/product-summary';
import { ShrinkageRequest } from '../../api/models/shrinkage-request';
import { ShrinkageResponse } from '../../api/models/shrinkage-response';
import { AuthService } from '../../core/auth/auth.service';
import { isApiError } from '../../core/http/api-error';
import { withIdempotency, withoutErrorNotification } from '../../core/http/http-context-tokens';
import { StoreContextService } from '../../core/store-context/store-context.service';
import { QtyPipe } from '../../shared/pipes';
import {
  MANUAL_SHRINKAGE_TYPE_OPTIONS,
  ManualShrinkageTypeCode,
  requiresReasonForType,
} from './shrinkage.models';
import { ShrinkageService } from './shrinkage.service';

/** Opción de usuario responsable para el selector de merma. */
interface ResponsibleUserOption {
  readonly id: number;
  readonly label: string;
}

/** Formatea una fecha local para `<input type="datetime-local">` (`YYYY-MM-DDTHH:mm`). */
function toLocalDateTimeInputValue(date: Date): string {
  const pad = (n: number): string => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Valida que un texto recortado no esté vacío cuando el campo es obligatorio. */
function trimmedRequiredValidator(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim().length === 0 ? { required: true } : null;
}

/**
 * Diálogo para registrar una merma manual en el local activo
 * (`POST /api/v1/shrinkages`, `HU-MER-01`, `HU-MER-03`).
 *
 * - Exige seleccionar un producto (no `PACK`) y carga sus metadatos (`lotControlled`, `unitOfMeasure`).
 * - Si `lotControlled === true`, exige seleccionar un lote con saldo; si no, oculta el campo lote.
 * - Exige cantidad entera cuando el producto se vende por unidad (`UNIT`).
 * - Exige motivo obligatorio para `THEFT_LOSS` e `INTERNAL_CONSUMPTION`.
 * - Permite adjuntar una imagen o PDF como evidencia (`SHRINKAGE_EVIDENCE`).
 * - Envía la petición con `withIdempotency()` para evitar duplicados ante reintentos (`ADR-14`).
 */
@Component({
  selector: 'pos-shrinkage-create-dialog',
  imports: [
    MatAutocompleteModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Registrar merma</h2>
    @if (saving() || uploadingEvidence()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="create-content">
        <p class="helper-intro">
          Si el costo total de la merma está dentro de tu límite de autoaprobación, se aprobará y
          descontará del stock inmediatamente; en caso contrario quedará
          <strong>Pendiente de aprobación</strong>.
        </p>

        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <mat-form-field appearance="outline">
          <mat-label>Producto</mat-label>
          <input
            matInput
            formControlName="productSearch"
            [matAutocomplete]="productAuto"
            placeholder="Buscar por nombre o SKU..."
          />
          <mat-icon matPrefix>search</mat-icon>
          @if (selectedProduct(); as prod) {
            <mat-hint>
              SKU: {{ prod.sku }} · Unidad:
              {{ prod.unitOfMeasure === 'KG' ? 'Kilogramo (kg)' : 'Unidad (enteros)' }}
              @if (prod.lotControlled) {
                · Controla lotes
              }
            </mat-hint>
          }
          @if (form.controls.productId.hasError('required') && form.controls.productId.touched) {
            <mat-error>Selecciona un producto del catálogo.</mat-error>
          } @else if (form.controls.productId.hasError('server')) {
            <mat-error>{{ form.controls.productId.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-autocomplete
          #productAuto="matAutocomplete"
          [displayWith]="displayProduct"
          (optionSelected)="onProductSelected($event)"
        >
          @for (opt of productOptions(); track opt.id) {
            <mat-option [value]="opt">
              <span>{{ opt.name }}</span>
              <span class="option-sku"> ({{ opt.sku }})</span>
            </mat-option>
          }
        </mat-autocomplete>

        @if (loadingProductMeta()) {
          <mat-progress-bar mode="indeterminate" />
        }

        @if (selectedProduct()?.lotControlled) {
          <mat-form-field appearance="outline">
            <mat-label>Lote</mat-label>
            <mat-select formControlName="lotId">
              @if (lots().length === 0) {
                <mat-option [value]="null" disabled>Sin lotes con saldo disponible</mat-option>
              }
              @for (lot of lots(); track lot.id) {
                <mat-option [value]="lot.id">
                  {{ lot.lotNumber }} · Saldo: {{ lot.quantity | qty }}
                  @if (lot.expiryDate) {
                    · Vence: {{ lot.expiryDate }}
                  }
                </mat-option>
              }
            </mat-select>
            @if (form.controls.lotId.hasError('required')) {
              <mat-error>El lote es obligatorio para este producto.</mat-error>
            } @else if (form.controls.lotId.hasError('server')) {
              <mat-error>{{ form.controls.lotId.getError('server') }}</mat-error>
            }
          </mat-form-field>
        }

        <div class="two-cols">
          <mat-form-field appearance="outline">
            <mat-label>Tipo de merma</mat-label>
            <mat-select formControlName="shrinkageType">
              @for (opt of typeOptions; track opt.value) {
                <mat-option [value]="opt.value">{{ opt.label }}</mat-option>
              }
            </mat-select>
            @if (form.controls.shrinkageType.hasError('required')) {
              <mat-error>Selecciona un tipo de merma.</mat-error>
            } @else if (form.controls.shrinkageType.hasError('server')) {
              <mat-error>{{ form.controls.shrinkageType.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Cantidad</mat-label>
            <input
              matInput
              type="number"
              formControlName="quantity"
              [step]="isUnitProduct() ? '1' : '0.001'"
              min="0.001"
              placeholder="0"
            />
            @if (form.controls.quantity.hasError('required')) {
              <mat-error>Ingresa la cantidad mermada.</mat-error>
            } @else if (form.controls.quantity.hasError('positive')) {
              <mat-error>La cantidad debe ser mayor que cero.</mat-error>
            } @else if (form.controls.quantity.hasError('integerRequired')) {
              <mat-error>Los productos por unidad requieren cantidad entera.</mat-error>
            } @else if (form.controls.quantity.hasError('server')) {
              <mat-error>{{ form.controls.quantity.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>

        <div class="two-cols">
          <mat-form-field appearance="outline">
            <mat-label>Responsable</mat-label>
            <mat-select formControlName="responsibleUserId">
              @for (user of responsibleUsers(); track user.id) {
                <mat-option [value]="user.id">{{ user.label }}</mat-option>
              }
            </mat-select>
            @if (form.controls.responsibleUserId.hasError('required')) {
              <mat-error>Selecciona al usuario responsable.</mat-error>
            } @else if (form.controls.responsibleUserId.hasError('server')) {
              <mat-error>{{ form.controls.responsibleUserId.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Fecha y hora de ocurrencia</mat-label>
            <input matInput type="datetime-local" formControlName="occurredAt" />
            @if (form.controls.occurredAt.hasError('required')) {
              <mat-error>Indica la fecha de ocurrencia.</mat-error>
            } @else if (form.controls.occurredAt.hasError('futureDate')) {
              <mat-error>La fecha no puede ser futura.</mat-error>
            } @else if (form.controls.occurredAt.hasError('server')) {
              <mat-error>{{ form.controls.occurredAt.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>

        <mat-form-field appearance="outline">
          <mat-label>
            {{ reasonRequired() ? 'Motivo (obligatorio)' : 'Motivo / notas (opcional)' }}
          </mat-label>
          <textarea
            matInput
            rows="2"
            formControlName="reason"
            maxlength="500"
            placeholder="Detalle de lo ocurrido (máx. 500 caracteres)"
          ></textarea>
          @if (form.controls.reason.hasError('required')) {
            <mat-error>El motivo es obligatorio para este tipo de merma.</mat-error>
          } @else if (form.controls.reason.hasError('maxlength')) {
            <mat-error>Máximo 500 caracteres.</mat-error>
          } @else if (form.controls.reason.hasError('server')) {
            <mat-error>{{ form.controls.reason.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <div class="evidence-box">
          <div class="evidence-header">
            <span class="evidence-label">Evidencia fotográfica o PDF (opcional)</span>
            <input
              #fileInput
              type="file"
              class="file-input-hidden"
              accept="image/jpeg,image/png,image/webp,application/pdf"
              (change)="onEvidenceSelected($event)"
            />
            <button
              mat-stroked-button
              type="button"
              (click)="fileInput.click()"
              [disabled]="uploadingEvidence() || saving()"
            >
              <mat-icon>upload_file</mat-icon>
              {{ evidenceFileName() ? 'Cambiar archivo' : 'Adjuntar evidencia' }}
            </button>
          </div>
          @if (evidenceFileName(); as fileName) {
            <div class="evidence-chip">
              <mat-icon aria-hidden="true">attach_file</mat-icon>
              <span>{{ fileName }}</span>
              <button
                mat-icon-button
                type="button"
                aria-label="Quitar archivo de evidencia"
                (click)="clearEvidence(fileInput)"
                [disabled]="saving()"
              >
                <mat-icon>close</mat-icon>
              </button>
            </div>
          }
          @if (evidenceError(); as evErr) {
            <p class="evidence-error" role="alert">{{ evErr }}</p>
          }
        </div>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button
          mat-button
          type="button"
          [mat-dialog-close]="undefined"
          [disabled]="saving() || uploadingEvidence()"
        >
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving() || uploadingEvidence()">
          Registrar merma
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .create-content {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: min(560px, 88vw);
      padding-top: 8px;
    }
    .helper-intro {
      margin: 0;
      font-size: 0.875rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .server-error {
      margin: 0;
      padding: 8px 12px;
      border-radius: 6px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font-size: 0.875rem;
    }
    .two-cols {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px;
    }
    .option-sku {
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.85em;
    }
    .evidence-box {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 10px 12px;
      border: 1px dashed var(--mat-sys-outline-variant);
      border-radius: 8px;
    }
    .evidence-header {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
    }
    .evidence-label {
      font-size: 0.875rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .file-input-hidden {
      display: none;
    }
    .evidence-chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 0.85rem;
      color: var(--mat-sys-on-surface);
    }
    .evidence-error {
      margin: 0;
      font-size: 0.82rem;
      color: var(--mat-sys-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShrinkageCreateDialog {
  private readonly dialogRef = inject(MatDialogRef<ShrinkageCreateDialog, ShrinkageResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly shrinkageService = inject(ShrinkageService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly typeOptions = MANUAL_SHRINKAGE_TYPE_OPTIONS;
  protected readonly saving = signal(false);
  protected readonly loadingProductMeta = signal(false);
  protected readonly uploadingEvidence = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly evidenceError = signal<string | null>(null);
  protected readonly productOptions = signal<readonly ProductSummary[]>([]);
  protected readonly selectedProduct = signal<ProductResponse | null>(null);
  protected readonly lots = signal<readonly LotResponse[]>([]);
  protected readonly responsibleUsers = signal<readonly ResponsibleUserOption[]>([]);
  protected readonly evidenceFileId = signal<number | null>(null);
  protected readonly evidenceFileName = signal<string | null>(null);
  protected readonly reasonRequired = signal(false);

  protected readonly form = this.fb.group({
    productSearch: ['' as string | ProductSummary],
    productId: [null as number | null, [Validators.required]],
    lotId: [null as number | null],
    shrinkageType: [null as ManualShrinkageTypeCode | null, [Validators.required]],
    quantity: [
      null as number | null,
      [Validators.required, (control) => this.validateQuantity(control)],
    ],
    responsibleUserId: [this.auth.user()?.id ?? null, [Validators.required]],
    occurredAt: [
      toLocalDateTimeInputValue(new Date()),
      [Validators.required, (control) => this.validateOccurredAt(control)],
    ],
    reason: ['', [Validators.maxLength(500)]],
  });

  constructor() {
    this.initResponsibleUsers();
    this.setupProductSearch();
    this.setupTypeReasonValidation();
  }

  protected displayProduct = (value: string | ProductSummary | null): string => {
    if (!value) {
      return '';
    }
    if (typeof value === 'string') {
      return value;
    }
    return value.sku ? `${value.name ?? ''} (${value.sku})` : (value.name ?? '');
  };

  protected isUnitProduct(): boolean {
    const prod = this.selectedProduct();
    return prod ? prod.unitOfMeasure !== 'KG' : true;
  }

  protected onProductSelected(event: MatAutocompleteSelectedEvent): void {
    const summary = event.option.value as ProductSummary;
    if (!summary.id) {
      return;
    }
    this.form.controls.productId.setValue(summary.id);
    this.form.controls.lotId.setValue(null);
    this.lots.set([]);
    this.loadingProductMeta.set(true);

    this.shrinkageService
      .getProduct(summary.id, withoutErrorNotification())
      .pipe(
        switchMap((product) => {
          this.selectedProduct.set(product);
          this.updateLotValidators(Boolean(product.lotControlled));
          this.form.controls.quantity.updateValueAndValidity();
          if (!product.lotControlled) {
            return of([] as LotResponse[]);
          }
          return this.shrinkageService.listProductLots(
            summary.id!,
            true,
            withoutErrorNotification(),
          );
        }),
        catchError(() => {
          this.selectedProduct.set(null);
          this.updateLotValidators(false);
          return of([] as LotResponse[]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((lots) => {
        this.lots.set(lots);
        this.loadingProductMeta.set(false);
      });
  }

  protected onEvidenceSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.uploadingEvidence.set(true);
    this.evidenceError.set(null);

    this.shrinkageService
      .uploadEvidence(file, withoutErrorNotification())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (uploaded) => {
          this.uploadingEvidence.set(false);
          this.evidenceFileId.set(uploaded.id ?? null);
          this.evidenceFileName.set(uploaded.originalName ?? file.name);
        },
        error: (err: unknown) => {
          this.uploadingEvidence.set(false);
          this.evidenceFileId.set(null);
          this.evidenceFileName.set(null);
          this.evidenceError.set(
            isApiError(err) ? err.message : 'No se pudo subir el archivo de evidencia.',
          );
          input.value = '';
        },
      });
  }

  protected clearEvidence(fileInput: HTMLInputElement): void {
    fileInput.value = '';
    this.evidenceFileId.set(null);
    this.evidenceFileName.set(null);
    this.evidenceError.set(null);
  }

  protected submit(): void {
    if (this.form.invalid || this.saving() || this.uploadingEvidence()) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    if (
      raw.productId === null ||
      raw.shrinkageType === null ||
      raw.quantity === null ||
      raw.responsibleUserId === null
    ) {
      return;
    }

    const trimmedReason = raw.reason.trim();
    const lotControlled = Boolean(this.selectedProduct()?.lotControlled);
    const body: ShrinkageRequest = {
      productId: raw.productId,
      ...(lotControlled && raw.lotId !== null ? { lotId: raw.lotId } : {}),
      shrinkageType: raw.shrinkageType,
      quantity: Number(raw.quantity),
      ...(trimmedReason ? { reason: trimmedReason } : {}),
      ...(this.evidenceFileId() !== null ? { evidenceFileId: this.evidenceFileId()! } : {}),
      responsibleUserId: raw.responsibleUserId,
      occurredAt: new Date(raw.occurredAt).toISOString(),
    };

    this.saving.set(true);
    this.serverError.set(null);

    const ctx = withoutErrorNotification(withIdempotency());
    this.shrinkageService.createShrinkage(body, ctx).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.dialogRef.close(created);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo registrar la merma.');
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

  private initResponsibleUsers(): void {
    const currentUser = this.auth.user();
    const initialList: ResponsibleUserOption[] = currentUser?.id
      ? [
          {
            id: currentUser.id,
            label: currentUser.fullName?.trim() || currentUser.username || `#${currentUser.id}`,
          },
        ]
      : [];
    this.responsibleUsers.set(initialList);

    if (!this.auth.hasPermission('security:user:read')) {
      return;
    }

    this.shrinkageService
      .listStoreUsers(this.storeContext.storeId(), withoutErrorNotification())
      .pipe(
        catchError(() => of([])),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((users) => {
        const merged = new Map<number, ResponsibleUserOption>();
        for (const item of initialList) {
          merged.set(item.id, item);
        }
        for (const u of users) {
          if (u.id !== undefined) {
            merged.set(u.id, {
              id: u.id,
              label: u.fullName?.trim() || u.username || `#${u.id}`,
            });
          }
        }
        this.responsibleUsers.set(Array.from(merged.values()));
      });
  }

  private setupProductSearch(): void {
    this.form.controls.productSearch.valueChanges
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((value) => {
          if (typeof value !== 'string') {
            return of(this.productOptions());
          }
          // Al editar texto manualmente se invalida la selección previa del producto.
          this.form.controls.productId.setValue(null);
          this.selectedProduct.set(null);
          this.lots.set([]);
          this.updateLotValidators(false);
          const query = value.trim();
          if (!query) {
            return of([] as ProductSummary[]);
          }
          return this.shrinkageService
            .searchProducts(query, withoutErrorNotification())
            .pipe(catchError(() => of([] as ProductSummary[])));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((items) => this.productOptions.set(items));
  }

  private setupTypeReasonValidation(): void {
    this.form.controls.shrinkageType.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((type) => {
        const required = requiresReasonForType(type);
        this.reasonRequired.set(required);
        this.form.controls.reason.setValidators(
          required
            ? [trimmedRequiredValidator, Validators.maxLength(500)]
            : [Validators.maxLength(500)],
        );
        this.form.controls.reason.updateValueAndValidity();
      });
  }

  private updateLotValidators(lotControlled: boolean): void {
    if (lotControlled) {
      this.form.controls.lotId.setValidators([Validators.required]);
    } else {
      this.form.controls.lotId.clearValidators();
      this.form.controls.lotId.setValue(null);
    }
    this.form.controls.lotId.updateValueAndValidity();
  }

  private validateQuantity(control: AbstractControl<number | null>): ValidationErrors | null {
    const val = control.value;
    if (val === null || val === undefined) {
      return null;
    }
    const num = Number(val);
    if (!Number.isFinite(num) || num <= 0) {
      return { positive: true };
    }
    if (this.isUnitProduct() && !Number.isInteger(num)) {
      return { integerRequired: true };
    }
    return null;
  }

  private validateOccurredAt(control: AbstractControl<string>): ValidationErrors | null {
    const val = control.value;
    if (!val) {
      return null;
    }
    const timestamp = new Date(val).getTime();
    if (Number.isNaN(timestamp) || timestamp > Date.now()) {
      return { futureDate: true };
    }
    return null;
  }
}
