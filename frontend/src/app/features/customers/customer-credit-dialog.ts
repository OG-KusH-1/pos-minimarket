import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { CustomerCreditSettingsRequest } from '../../api/models/customer-credit-settings-request';
import { CustomerResponse } from '../../api/models/customer-response';
import { isApiError } from '../../core/http/api-error';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { ClpPipe } from '../../shared/pipes/clp.pipe';
import { CustomersService } from './customers.service';

/** Datos de entrada para {@link CustomerCreditDialog}. */
export interface CustomerCreditDialogData {
  /** Cliente cuyo fiado se configura (con cupo, deuda y habilitación actuales). */
  readonly customer: CustomerResponse;
}

/**
 * Diálogo de configuración del fiado de un cliente (`HU-POS-16`, `PUT /customers/{id}/credit`).
 *
 * Permite habilitar/deshabilitar el crédito y fijar el cupo, con un motivo obligatorio para la
 * auditoría. Un cupo sobre el máximo asignable del rol responde `CREDIT_LIMIT_EXCEEDED`.
 */
@Component({
  selector: 'pos-customer-credit-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    ClpPipe,
    ReactiveFormsModule,
  ],
  templateUrl: './customer-credit-dialog.html',
  styleUrl: './customer-credit-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerCreditDialog implements OnInit {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly customersService = inject(CustomersService);
  private readonly dialogRef = inject(MatDialogRef<CustomerCreditDialog, CustomerResponse | null>);
  protected readonly data = inject<CustomerCreditDialogData>(MAT_DIALOG_DATA);

  protected readonly saving = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly form = this.fb.group({
    creditEnabled: [this.data.customer.creditEnabled ?? false, [Validators.required]],
    creditLimit: [this.data.customer.creditLimit ?? 0, [Validators.required, Validators.min(0)]],
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });

  ngOnInit(): void {
    // Un cupo en cero no tiene sentido con el fiado habilitado: se sugiere un cupo inicial.
    if (this.form.controls.creditEnabled.value && (this.data.customer.creditLimit ?? 0) === 0) {
      this.form.controls.creditLimit.setValue(1);
    }
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    this.errorMessage.set(null);

    const raw = this.form.getRawValue();
    const customerId = this.data.customer.id;
    if (customerId === undefined || customerId === null) {
      this.saving.set(false);
      this.errorMessage.set('No se identificó al cliente.');
      return;
    }

    const body: CustomerCreditSettingsRequest = {
      creditEnabled: raw.creditEnabled,
      creditLimit: Number(raw.creditLimit),
      reason: raw.reason.trim(),
    };

    this.customersService
      .updateCreditSettings(customerId, body, withoutErrorNotification())
      .subscribe({
        next: (updated) => {
          this.saving.set(false);
          this.dialogRef.close(updated);
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.handleError(err);
        },
      });
  }

  private handleError(err: unknown): void {
    if (!isApiError(err)) {
      this.errorMessage.set('Error inesperado al guardar la configuración del fiado.');
      return;
    }

    if (err.code === 'CREDIT_LIMIT_EXCEEDED') {
      this.form.controls.creditLimit.setErrors({ limitExceeded: true });
      this.errorMessage.set(err.message || 'El cupo excede el máximo asignable para tu rol.');
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
