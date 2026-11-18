import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { SupplierRequest } from '../../../api/models/supplier-request';
import { SupplierResponse } from '../../../api/models/supplier-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { formatRut, normalizeRut, rutValidator } from '../../../shared/validators';
import {
  ISO_WEEKDAYS,
  PAYMENT_TERMS_LABELS,
  PaymentTermsType,
  integerRangeValidator,
  trimmedMinLength,
} from './supplier-validators';
import { SuppliersService } from './suppliers.service';

/** Datos de entrada para {@link SupplierFormDialog}. */
export interface SupplierFormDialogData {
  /** `null` para creación; ID del proveedor para edición. */
  readonly supplierId: number | null;
}

/**
 * Diálogo modal de creación y edición de proveedores (`HU-PRV-01`).
 *
 * Valida RUT (módulo 11), condiciones de pago, `leadTimeDays` (0..90), días ISO de despacho
 * (`deliveryWeekdays`) y maneja concurrencia optimista (`version`, `409 CONCURRENT_MODIFICATION`),
 * `RUT_INVALID` y `DUPLICATED_VALUE`.
 */
@Component({
  selector: 'pos-supplier-form-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    ReactiveFormsModule,
  ],
  templateUrl: './supplier-form-dialog.html',
  styleUrl: './supplier-form-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupplierFormDialog implements OnInit {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly suppliersService = inject(SuppliersService);
  private readonly dialogRef = inject(MatDialogRef<SupplierFormDialog, SupplierResponse | null>);
  protected readonly data = inject<SupplierFormDialogData>(MAT_DIALOG_DATA);

  protected readonly weekdays = ISO_WEEKDAYS;
  protected readonly paymentTermsOptions: readonly { value: PaymentTermsType; label: string }[] = [
    { value: 'CASH_ON_DELIVERY', label: PAYMENT_TERMS_LABELS.CASH_ON_DELIVERY },
    { value: 'CREDIT', label: PAYMENT_TERMS_LABELS.CREDIT },
  ];

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly concurrentConflict = signal(false);
  protected readonly currentVersion = signal<number | undefined>(undefined);

  protected readonly form = this.fb.group({
    rut: ['', [Validators.required, rutValidator]],
    legalName: ['', [Validators.required, trimmedMinLength(2), Validators.maxLength(200)]],
    tradeName: ['', [Validators.maxLength(200)]],
    businessActivity: ['', [Validators.maxLength(200)]],
    paymentTermsType: ['CASH_ON_DELIVERY' as PaymentTermsType, [Validators.required]],
    paymentTermsDays: [0, [integerRangeValidator(0, 180)]],
    leadTimeDays: [1, [Validators.required, integerRangeValidator(0, 90)]],
    deliveryWeekdays: [[] as number[]],
    phone: ['', [Validators.maxLength(30)]],
    email: ['', [Validators.email, Validators.maxLength(150)]],
    address: ['', [Validators.maxLength(250)]],
    commune: ['', [Validators.maxLength(100)]],
    city: ['', [Validators.maxLength(100)]],
    contactName: ['', [Validators.maxLength(150)]],
    contactPhone: ['', [Validators.maxLength(30)]],
    contactEmail: ['', [Validators.email, Validators.maxLength(150)]],
    notes: ['', [Validators.maxLength(2000)]],
  });

  protected get isEdit(): boolean {
    return this.data.supplierId !== null;
  }

  ngOnInit(): void {
    this.syncPaymentTermsDaysState(this.form.controls.paymentTermsType.value);
    this.form.controls.paymentTermsType.valueChanges.subscribe((type) => {
      this.syncPaymentTermsDaysState(type);
    });

    if (this.data.supplierId !== null) {
      this.loadSupplier(this.data.supplierId);
    }
  }

  /** Recarga el proveedor desde la API (útil tras un conflicto `409 CONCURRENT_MODIFICATION`). */
  protected reloadSupplier(): void {
    if (this.data.supplierId !== null) {
      this.loadSupplier(this.data.supplierId);
    }
  }

  /** Formatea el RUT al perder foco si tiene estructura válida. */
  protected onRutBlur(): void {
    const raw = this.form.controls.rut.value.trim();
    if (!raw) {
      return;
    }
    const formatted = formatRut(raw);
    if (formatted !== raw) {
      this.form.controls.rut.setValue(formatted);
    }
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    this.errorMessage.set(null);
    this.concurrentConflict.set(false);

    const payload = this.buildPayload();
    const request$ =
      this.data.supplierId === null
        ? this.suppliersService.createSupplier(payload, withoutErrorNotification())
        : this.suppliersService.updateSupplier(
            this.data.supplierId,
            payload,
            withoutErrorNotification(),
          );

    request$.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.dialogRef.close(saved);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.handleError(err);
      },
    });
  }

  private loadSupplier(id: number): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.concurrentConflict.set(false);

    this.suppliersService.getSupplier(id, withoutErrorNotification()).subscribe({
      next: (supplier) => {
        this.loading.set(false);
        this.patchSupplier(supplier);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.errorMessage.set(
          isApiError(err) ? err.message : 'No se pudo cargar la información del proveedor.',
        );
      },
    });
  }

  private patchSupplier(supplier: SupplierResponse): void {
    this.currentVersion.set(supplier.version);
    const termsType: PaymentTermsType = supplier.paymentTermsType ?? 'CASH_ON_DELIVERY';
    this.form.patchValue({
      rut: supplier.rut ? formatRut(supplier.rut) : '',
      legalName: supplier.legalName ?? '',
      tradeName: supplier.tradeName ?? '',
      businessActivity: supplier.businessActivity ?? '',
      paymentTermsType: termsType,
      paymentTermsDays: supplier.paymentTermsDays ?? 0,
      leadTimeDays: supplier.leadTimeDays ?? 1,
      deliveryWeekdays: [...(supplier.deliveryWeekdays ?? [])].sort((a, b) => a - b),
      phone: supplier.phone ?? '',
      email: supplier.email ?? '',
      address: supplier.address ?? '',
      commune: supplier.commune ?? '',
      city: supplier.city ?? '',
      contactName: supplier.contactName ?? '',
      contactPhone: supplier.contactPhone ?? '',
      contactEmail: supplier.contactEmail ?? '',
      notes: supplier.notes ?? '',
    });
    this.syncPaymentTermsDaysState(termsType);
    this.form.markAsPristine();
  }

  private syncPaymentTermsDaysState(type: PaymentTermsType): void {
    const daysCtrl = this.form.controls.paymentTermsDays;
    if (type === 'CASH_ON_DELIVERY') {
      daysCtrl.setValue(0, { emitEvent: false });
      daysCtrl.disable({ emitEvent: false });
    } else {
      daysCtrl.enable({ emitEvent: false });
    }
  }

  private buildPayload(): SupplierRequest {
    const raw = this.form.getRawValue();
    return {
      rut: normalizeRut(raw.rut),
      legalName: raw.legalName.trim(),
      tradeName: optionalText(raw.tradeName),
      businessActivity: optionalText(raw.businessActivity),
      paymentTermsType: raw.paymentTermsType,
      paymentTermsDays: raw.paymentTermsType === 'CASH_ON_DELIVERY' ? 0 : raw.paymentTermsDays,
      leadTimeDays: Number(raw.leadTimeDays),
      deliveryWeekdays: [...new Set(raw.deliveryWeekdays)].sort((a, b) => a - b),
      phone: optionalText(raw.phone),
      email: optionalText(raw.email),
      address: optionalText(raw.address),
      commune: optionalText(raw.commune),
      city: optionalText(raw.city),
      contactName: optionalText(raw.contactName),
      contactPhone: optionalText(raw.contactPhone),
      contactEmail: optionalText(raw.contactEmail),
      notes: optionalText(raw.notes),
      version: this.currentVersion(),
    };
  }

  private handleError(err: unknown): void {
    if (!isApiError(err)) {
      this.errorMessage.set('Error inesperado al guardar el proveedor.');
      return;
    }

    if (err.code === 'RUT_INVALID') {
      this.form.controls.rut.setErrors({ rutInvalid: true });
      this.errorMessage.set('El RUT ingresado no es válido.');
      return;
    }

    if (err.code === 'DUPLICATED_VALUE') {
      this.form.controls.rut.setErrors({ duplicated: true });
      this.errorMessage.set('Ya existe un proveedor registrado con este RUT.');
      return;
    }

    if (err.code === 'CONCURRENT_MODIFICATION') {
      this.concurrentConflict.set(true);
      this.errorMessage.set(
        'El proveedor fue modificado por otro usuario. Recarga los datos antes de guardar.',
      );
      return;
    }

    if (err.hasFieldErrors) {
      for (const fieldError of err.errors) {
        const control = this.form.get(fieldError.field);
        if (control) {
          control.setErrors({ server: fieldError.message });
          control.markAsTouched();
        }
      }
    }

    this.errorMessage.set(err.message);
  }
}

function optionalText(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}
