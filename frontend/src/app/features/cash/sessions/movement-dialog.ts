import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CashMovementResponse } from '../../../api/models/cash-movement-response';
import { isApiError } from '../../../core/http/api-error';
import { withIdempotency, withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { generateUuid } from '../../../core/http/uuid';
import { ClpPipe } from '../../../shared/pipes';
import {
  OVERRIDE_TOKEN_HEADER,
  SupervisorOverrideService,
} from '../../../shared/ui/override-dialog';
import { CashMovementTypeCode, resolveCashErrorMessage } from './cash.models';
import { CashService } from './cash.service';

/** Datos de entrada para registrar un ingreso o retiro de efectivo. */
export interface MovementDialogData {
  readonly sessionId: number;
  readonly sessionNumber: string;
  readonly canCashIn: boolean;
  readonly canCashOut: boolean;
  readonly expectedCash?: number;
  readonly defaultType?: CashMovementTypeCode;
}

/**
 * Diálogo modal para registrar ingresos (`CASH_IN`) o retiros (`CASH_OUT`) de efectivo
 * (`HU-CAJ-02`, `RN-24`).
 *
 * Orquesta el override de supervisor mediante {@link SupervisorOverrideService.withSupervisorOverride}
 * cuando un retiro supera el límite del rol (`CASH_OUT_OVER_LIMIT`), reutilizando la misma
 * `Idempotency-Key` en ambos intentos.
 */
@Component({
  selector: 'pos-movement-dialog',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
  ],
  templateUrl: './movement-dialog.html',
  styleUrl: './movement-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MovementDialog {
  private readonly cashService = inject(CashService);
  private readonly supervisorOverride = inject(SupervisorOverrideService);
  private readonly dialogRef =
    inject<MatDialogRef<MovementDialog, CashMovementResponse | undefined>>(MatDialogRef);
  protected readonly data = inject<MovementDialogData>(MAT_DIALOG_DATA);

  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly form = new FormGroup({
    movementType: new FormControl<CashMovementTypeCode>(this.resolveInitialType(), {
      nonNullable: true,
      validators: [Validators.required],
    }),
    amount: new FormControl<number | null>(null, {
      validators: [Validators.required, Validators.min(1), Validators.pattern(/^\d+$/)],
    }),
    reason: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(3), Validators.maxLength(250)],
    }),
  });

  protected submit(): void {
    if (this.submitting()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const { movementType, amount, reason } = this.form.getRawValue();
    if (amount === null) {
      return;
    }

    this.submitting.set(true);
    this.dialogRef.disableClose = true;
    this.errorMessage.set(null);

    // Una sola clave de idempotencia y un solo HttpContext por operación para que el reintento con
    // X-Override-Token conserve la misma clave (ADR-14, L-04).
    const idempotencyKey = generateUuid();
    const context = withIdempotency(withoutErrorNotification());

    this.supervisorOverride
      .withSupervisorOverride((overrideHeaders) =>
        this.cashService.registerMovement(
          this.data.sessionId,
          {
            movementType,
            amount: Math.round(amount),
            reason: reason.trim(),
          },
          {
            idempotencyKey,
            overrideToken: overrideHeaders[OVERRIDE_TOKEN_HEADER],
            context,
          },
        ),
      )
      .subscribe({
        next: (movement) => {
          this.dialogRef.close(movement);
        },
        error: (err: unknown) => {
          this.applyError(err);
          this.submitting.set(false);
          this.dialogRef.disableClose = false;
        },
      });
  }

  protected cancel(): void {
    this.dialogRef.close(undefined);
  }

  private resolveInitialType(): CashMovementTypeCode {
    if (this.data.defaultType === 'CASH_OUT' && this.data.canCashOut) {
      return 'CASH_OUT';
    }
    if (this.data.defaultType === 'CASH_IN' && this.data.canCashIn) {
      return 'CASH_IN';
    }
    return this.data.canCashIn ? 'CASH_IN' : 'CASH_OUT';
  }

  private applyError(err: unknown): void {
    if (isApiError(err) && err.hasFieldErrors) {
      for (const fieldError of err.errors) {
        const ctrl = this.form.get(fieldError.field);
        if (ctrl) {
          ctrl.setErrors({ server: fieldError.message });
          ctrl.markAsTouched();
        }
      }
      this.errorMessage.set(err.message);
      return;
    }
    this.errorMessage.set(
      resolveCashErrorMessage(err, 'No se pudo registrar el movimiento de caja.'),
    );
  }
}
