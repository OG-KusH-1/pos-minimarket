import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { CustomerRequest } from '../../api/models/customer-request';
import { CustomerResponse } from '../../api/models/customer-response';
import { isApiError } from '../../core/http/api-error';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { formatRut, normalizeRut, rutValidator } from '../../shared/validators';
import { CustomersService } from './customers.service';

/** Datos de entrada para {@link CustomerFormDialog}. */
export interface CustomerFormDialogData {
  /** `null` para creación; ID del cliente para edición. */
  readonly customerId: number | null;
}

/**
 * Diálogo modal de creación y edición de clientes (`HU-POS-16`).
 *
 * Valida el RUT con módulo 11 (`rutValidator`, RN-30) y maneja los errores `RUT_INVALID`,
 * `DUPLICATED_VALUE` y `CONCURRENT_MODIFICATION` (edición optimista con `version`).
 */
@Component({
  selector: 'pos-customer-form-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  templateUrl: './customer-form-dialog.html',
  styleUrl: './customer-form-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerFormDialog implements OnInit {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly customersService = inject(CustomersService);
  private readonly dialogRef = inject(MatDialogRef<CustomerFormDialog, CustomerResponse | null>);
  protected readonly data = inject<CustomerFormDialogData>(MAT_DIALOG_DATA);

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly concurrentConflict = signal(false);
  private currentVersion: number | undefined = undefined;

  protected readonly form = this.fb.group({
    rut: ['', [Validators.required, rutValidator]],
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(200)]],
    phone: ['', [Validators.maxLength(30)]],
    email: ['', [Validators.email, Validators.maxLength(150)]],
    address: ['', [Validators.maxLength(250)]],
  });

  protected get isEdit(): boolean {
    return this.data.customerId !== null;
  }

  ngOnInit(): void {
    if (this.data.customerId !== null) {
      this.loadCustomer(this.data.customerId);
    }
  }

  /** Recarga el cliente desde la API (útil tras un conflicto `409 CONCURRENT_MODIFICATION`). */
  protected reloadCustomer(): void {
    if (this.data.customerId !== null) {
      this.loadCustomer(this.data.customerId);
    }
  }

  /** Formatea el RUT al perder foco si tiene estructura válida (p. ej. `123456785` → con puntos). */
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
      this.data.customerId === null
        ? this.customersService.createCustomer(payload, withoutErrorNotification())
        : this.customersService.updateCustomer(
            this.data.customerId,
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

  private loadCustomer(id: number): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.concurrentConflict.set(false);

    this.customersService.getCustomer(id, withoutErrorNotification()).subscribe({
      next: (customer) => {
        this.loading.set(false);
        this.patchCustomer(customer);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.errorMessage.set(
          isApiError(err) ? err.message : 'No se pudo cargar la información del cliente.',
        );
      },
    });
  }

  private patchCustomer(customer: CustomerResponse): void {
    this.currentVersion = customer.version;
    this.form.patchValue({
      rut: customer.rut ? formatRut(customer.rut) : '',
      name: customer.name ?? '',
      phone: customer.phone ?? '',
      email: customer.email ?? '',
      address: customer.address ?? '',
    });
    this.form.markAsPristine();
  }

  private buildPayload(): CustomerRequest {
    const raw = this.form.getRawValue();
    return {
      rut: normalizeRut(raw.rut),
      name: raw.name.trim(),
      phone: optionalText(raw.phone),
      email: optionalText(raw.email),
      address: optionalText(raw.address),
      version: this.currentVersion,
    };
  }

  private handleError(err: unknown): void {
    if (!isApiError(err)) {
      this.errorMessage.set('Error inesperado al guardar el cliente.');
      return;
    }

    if (err.code === 'RUT_INVALID') {
      this.form.controls.rut.setErrors({ rutInvalid: true });
      this.errorMessage.set('El RUT ingresado no es válido.');
      return;
    }

    if (err.code === 'DUPLICATED_VALUE') {
      this.form.controls.rut.setErrors({ duplicated: true });
      this.errorMessage.set('Ya existe un cliente registrado con este RUT.');
      return;
    }

    if (err.code === 'CONCURRENT_MODIFICATION') {
      this.concurrentConflict.set(true);
      this.errorMessage.set(
        'El cliente fue modificado por otro usuario. Recarga los datos antes de guardar.',
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
