import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CustomerAccountMovementResponse } from '../../api/models/customer-account-movement-response';
import { CustomerAdjustmentRequest } from '../../api/models/customer-adjustment-request';
import { CustomerPaymentRequest } from '../../api/models/customer-payment-request';
import { CustomerResponse } from '../../api/models/customer-response';
import { isApiError } from '../../core/http/api-error';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { ClpPipe } from '../../shared/pipes/clp.pipe';
import { SupervisorOverrideService } from '../../shared/ui/override-dialog';
import { CustomersService } from './customers.service';

/**
 * Validador de formulario: el monto de un ajuste debe ser distinto de cero (el backend responde
 * `VALIDATION_ERROR` con `NON_ZERO` para el campo `amount`). Un valor vacío queda en manos de
 * `Validators.required`.
 */
function nonZeroValidator(control: AbstractControl): ValidationErrors | null {
  const value: unknown = control.value;
  if (value === null || value === undefined || value === '') {
    return null;
  }
  return Number(value) === 0 ? { nonZero: true } : null;
}

/** Datos de entrada para {@link CustomerAccountDialog}. */
export interface CustomerAccountDialogData {
  /** ID del cliente cuya cuenta se consulta. */
  readonly customerId: number;
  /** Nombre del cliente, para el encabezado del diálogo. */
  readonly customerName: string;
}

/**
 * Validador de formulario: el monto no puede exceder la deuda actual de la cuenta.
 *
 * La deuda se lee de `getCurrentDebt` en cada validación, de modo que tras registrar un abono
 * (que recarga el estado de cuenta) el tope vigente se aplica sin reconstruir el formulario.
 * Un valor vacío es válido: combínese con `Validators.required`.
 */
function maxDebtValidator(getCurrentDebt: () => number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value: unknown = control.value;
    if (value === null || value === undefined || value === '') {
      return null;
    }
    const amount = Number(value);
    if (!Number.isFinite(amount)) {
      return { exceedsDebt: true };
    }
    return amount > getCurrentDebt() ? { exceedsDebt: true } : null;
  };
}

/**
 * Diálogo de cuenta corriente del cliente (fiado) (`HU-POS-16`, `HU-POS-17`).
 *
 * Muestra el estado de la cuenta (deuda, cupo y disponible) y la cartola de movimientos paginada
 * (`GET /customers/{id}/account`), y permite registrar abonos (`POST /payments`) y ajustes
 * manuales (`POST /adjustments`, con flujo de override de supervisor para
 * `CREDIT_ADJUSTMENT_MAX_AMOUNT`).
 *
 * El formulario de abono valida en cliente que el monto no exceda la deuda actual; si el backend
 * responde `PAYMENT_EXCEEDS_DEBT` (p. ej. por un cambio concurrente), el error se marca sobre el
 * campo monto y se muestra el mensaje de la API.
 */
@Component({
  selector: 'pos-customer-account-dialog',
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
  templateUrl: './customer-account-dialog.html',
  styleUrl: './customer-account-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerAccountDialog implements OnInit {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly customersService = inject(CustomersService);
  private readonly override = inject(SupervisorOverrideService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject(MatDialogRef<CustomerAccountDialog, boolean>);
  protected readonly data = inject<CustomerAccountDialogData>(MAT_DIALOG_DATA);
  private readonly clpPipe = new ClpPipe();

  /** Movimientos por página en la cartola (la API pagina del lado del servidor). */
  private static readonly PAGE_SIZE = 10;

  protected readonly customer = signal<CustomerResponse | undefined>(undefined);
  protected readonly movements = signal<readonly CustomerAccountMovementResponse[]>([]);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(1);
  protected readonly totalElements = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly savingPayment = signal(false);
  protected readonly paymentError = signal<string | null>(null);
  protected readonly savingAdjustment = signal(false);
  protected readonly adjustmentError = signal<string | null>(null);

  /** Deuda actual del cliente (monto positivo pendiente de abono). */
  protected readonly debt = computed(() => this.customer()?.creditBalance ?? 0);
  protected readonly creditLimit = computed(() => this.customer()?.creditLimit ?? 0);
  protected readonly availableCredit = computed(() => this.customer()?.availableCredit ?? 0);
  protected readonly creditEnabled = computed(() => this.customer()?.creditEnabled ?? false);

  protected readonly paymentMethodOptions: readonly {
    value: CustomerPaymentRequest['paymentMethod'];
    label: string;
  }[] = [
    { value: 'CASH', label: 'Efectivo' },
    { value: 'TRANSFER', label: 'Transferencia' },
  ];

  /** Formulario de abono: el monto no puede exceder la deuda actual (`HU-POS-17`). */
  protected readonly paymentForm = this.fb.group({
    amount: [
      null as number | null,
      [Validators.required, Validators.min(1), maxDebtValidator(() => this.debt())],
    ],
    paymentMethod: ['CASH' as CustomerPaymentRequest['paymentMethod'], [Validators.required]],
    reference: ['', [Validators.maxLength(100)]],
    notes: ['', [Validators.maxLength(500)]],
  });

  /** Formulario de ajuste manual de deuda (positivo aumenta la deuda, negativo la reduce). */
  protected readonly adjustmentForm = this.fb.group({
    amount: [null as number | null, [Validators.required, nonZeroValidator]],
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });

  ngOnInit(): void {
    this.loadStatement(0);
  }

  protected close(): void {
    this.dialogRef.close(true);
  }

  protected goToPage(page: number): void {
    this.loadStatement(page);
  }

  protected money(value: number | null | undefined): string {
    return this.clpPipe.transform(value);
  }

  /** Etiqueta del tipo de movimiento para la cartola. */
  protected movementTypeLabel(type: CustomerAccountMovementResponse['movementType']): string {
    switch (type) {
      case 'CHARGE':
        return 'Cargo';
      case 'PAYMENT':
        return 'Abono';
      case 'REVERSAL':
        return 'Reversa';
      case 'ADJUSTMENT':
        return 'Ajuste';
      default:
        return type ?? '—';
    }
  }

  /** Etiqueta del medio de pago de un abono (solo CASH o TRANSFER, API.md F.2). */
  protected paymentMethodLabel(method: CustomerAccountMovementResponse['paymentMethod']): string {
    switch (method) {
      case 'CASH':
        return 'Efectivo';
      case 'TRANSFER':
        return 'Transferencia';
      case 'DEBIT':
        return 'Débito';
      case 'CREDIT':
        return 'Crédito';
      case 'CUSTOMER_CREDIT':
        return 'Fiado';
      default:
        return '—';
    }
  }

  /** Fecha y hora local de un movimiento, para la columna Fecha de la cartola. */
  protected formatOccurredAt(iso: string | undefined): string {
    if (!iso) {
      return '—';
    }
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return iso;
    }
    return new Intl.DateTimeFormat('es-CL', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    }).format(date);
  }

  /**
   * Registra el abono con validación previa en el formulario y manejo del error de API
   * `PAYMENT_EXCEEDS_DEBT` (la deuda puede haber cambiado desde la última recarga).
   */
  protected submitPayment(): void {
    this.paymentForm.markAllAsTouched();
    if (this.paymentForm.invalid) {
      return;
    }

    this.savingPayment.set(true);
    this.paymentError.set(null);

    const raw = this.paymentForm.getRawValue();
    const body: CustomerPaymentRequest = {
      amount: Number(raw.amount),
      paymentMethod: raw.paymentMethod,
      reference: optionalText(raw.reference),
      notes: optionalText(raw.notes),
    };

    this.customersService
      .registerPayment(this.data.customerId, body, undefined, withoutErrorNotification())
      .subscribe({
        next: () => {
          this.savingPayment.set(false);
          this.snackBar.open('Abono registrado en la cuenta del cliente.', 'Cerrar', {
            duration: 3000,
          });
          this.paymentForm.reset({
            amount: null,
            paymentMethod: 'CASH',
            reference: '',
            notes: '',
          });
          // La cartola y la deuda se recargan: el tope del formulario se actualiza con ella.
          this.loadStatement(0);
        },
        error: (err: unknown) => {
          this.savingPayment.set(false);
          this.handlePaymentError(err);
        },
      });
  }

  /**
   * Registra el ajuste manual de deuda, protegido con el flujo de override de supervisor
   * (`SupervisorOverrideService.withSupervisorOverride`) del límite `CREDIT_ADJUSTMENT_MAX_AMOUNT`.
   *
   * Hoy el backend responde `CREDIT_LIMIT_EXCEEDED` (límite duro, sin `OVERRIDE_REQUIRED`) y el
   * cliente generado para este endpoint no declara la cabecera `X-Override-Token`; por eso el
   * token no se adjunta y el exceso se muestra como error del formulario. El wrapper queda para
   * que, si el límite migrara al flujo de override (ADR-19), el diálogo de autorización se abra
   * sin cambiar este componente.
   */
  protected submitAdjustment(): void {
    this.adjustmentForm.markAllAsTouched();
    if (this.adjustmentForm.invalid) {
      return;
    }

    this.savingAdjustment.set(true);
    this.adjustmentError.set(null);

    const raw = this.adjustmentForm.getRawValue();
    const body: CustomerAdjustmentRequest = {
      amount: Number(raw.amount),
      reason: raw.reason.trim(),
    };

    // El contexto idempotente se crea UNA vez, fuera de la función: el reintento tras el override
    // reenvía la misma `Idempotency-Key` (ADR-14).
    this.override
      .withSupervisorOverride(() =>
        this.customersService.registerAdjustment(
          this.data.customerId,
          body,
          undefined,
          withoutErrorNotification(),
        ),
      )
      .subscribe({
        next: () => {
          this.savingAdjustment.set(false);
          this.snackBar.open('Ajuste registrado en la cuenta del cliente.', 'Cerrar', {
            duration: 3000,
          });
          this.adjustmentForm.reset({ amount: null, reason: '' });
          this.loadStatement(0);
        },
        error: (err: unknown) => {
          this.savingAdjustment.set(false);
          this.handleAdjustmentError(err);
        },
      });
  }

  private loadStatement(page: number): void {
    this.loading.set(true);
    this.loadError.set(null);

    this.customersService
      .getAccount(
        { id: this.data.customerId, page, size: CustomerAccountDialog.PAGE_SIZE },
        withoutErrorNotification(),
      )
      .subscribe({
        next: (statement) => {
          this.customer.set(statement.customer ?? undefined);
          this.movements.set(statement.movements?.content ?? []);
          this.totalElements.set(statement.movements?.totalElements ?? 0);
          this.totalPages.set(Math.max(1, statement.movements?.totalPages ?? 1));
          this.page.set(page);
          this.loading.set(false);
        },
        error: (err: unknown) => {
          this.loading.set(false);
          this.loadError.set(
            isApiError(err) ? err.message : 'No se pudo cargar la cuenta del cliente.',
          );
        },
      });
  }

  private handlePaymentError(err: unknown): void {
    if (isApiError(err) && err.code === 'PAYMENT_EXCEEDS_DEBT') {
      this.paymentForm.controls.amount.setErrors({ exceedsDebt: true });
      this.paymentError.set(
        err.message || 'El abono excede la deuda actual de la cuenta del cliente.',
      );
      return;
    }
    this.paymentError.set(
      isApiError(err) ? err.message : 'Error inesperado al registrar el abono.',
    );
  }

  private handleAdjustmentError(err: unknown): void {
    if (isApiError(err) && err.code === 'CREDIT_LIMIT_EXCEEDED') {
      this.adjustmentForm.controls.amount.setErrors({ limitExceeded: true });
      this.adjustmentError.set(
        err.message ||
          'El ajuste excede el monto máximo permitido para tu rol (CREDIT_ADJUSTMENT_MAX_AMOUNT).',
      );
      return;
    }
    this.adjustmentError.set(
      isApiError(err) ? err.message : 'Error inesperado al registrar el ajuste.',
    );
  }
}

function optionalText(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}
