import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormArray,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import type { LineResponse } from '../../../api/models/line-response';
import { SaleReturnLineRequest } from '../../../api/models/sale-return-line-request';
import type { SaleReturnRequest } from '../../../api/models/sale-return-request';
import { SaleReturnResponse } from '../../../api/models/sale-return-response';
import type { SaleReturnableLinesResponse } from '../../../api/models/sale-returnable-lines-response';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { generateUuid } from '../../../core/http/uuid';
import { ClpPipe, QtyPipe } from '../../../shared/pipes';
import {
  OVERRIDE_TOKEN_HEADER,
  SupervisorOverrideService,
} from '../../../shared/ui/override-dialog';
import {
  ReturnReasonCode,
  RETURN_REASON_OPTIONS,
  ReturnLineRow,
  apiErrorFieldErrors,
  buildReturnLineRows,
  formatDateTime,
  resolveReturnsErrorMessage,
} from './returns.models';
import { ReturnsService } from './returns.service';

/** Datos que recibe el diálogo: la venta y sus líneas devolvibles ya consultadas. */
export interface ReturnLinesDialogData {
  readonly saleId: number;
  readonly saleNumber?: string;
  readonly returnable: SaleReturnableLinesResponse;
  /** Líneas de `SaleResponse` para saber el tipo (UNIT/WEIGHT/PACK) y validar la cantidad. */
  readonly saleLines?: readonly LineResponse[];
}

/** Validador: cantidad entera (productos UNIT y PACK; `qty` en F.3 es entero para ellos). */
const integerQuantity: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value;
  return typeof value === 'number' && !Number.isInteger(value) ? { integerQuantity: true } : null;
};

/** Validador: hasta 3 decimales (productos pesables; `qty` = decimal(14,3) en A.5). */
const maxThreeDecimals: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value;
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return null;
  }
  return Math.round(value * 1000) / 1000 !== value ? { maxDecimals: { max: 3 } } : null;
};

/**
 * Diálogo de devolución parcial o total (`HU-POS-15`, flujo 4 de `API.md` F.4).
 *
 * Muestra las líneas devolvibles que informó `GET /sales/{id}/returnable` y permite elegir la
 * cantidad a devolver de cada una con tope en la cantidad devolvible real, más el flag
 * `restock` por línea (con un conmutador global). Al confirmar arma el `SaleReturnRequest` y
 * lo envía por `POST /sales/{id}/returns` con idempotencia y override `SALE_RETURN` automático.
 *
 * `refunds` se omite a propósito: F.3 establece que el backend reembolsa por los mismos medios
 * de la venta, en proporción; si la suma no cuadra respondería `PAYMENT_MISMATCH`.
 */
@Component({
  selector: 'pos-return-lines-dialog',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  templateUrl: './return-lines-dialog.html',
  styleUrl: './return-lines-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReturnLinesDialog {
  private readonly returnsService = inject(ReturnsService);
  private readonly supervisorOverride = inject(SupervisorOverrideService);
  private readonly dialogRef =
    inject<MatDialogRef<ReturnLinesDialog, SaleReturnResponse | undefined>>(MatDialogRef);
  protected readonly data = inject<ReturnLinesDialogData>(MAT_DIALOG_DATA);

  /** Filas devolvibles ya enriquecidas (unión línea devolvible + línea de venta). */
  protected readonly rows: readonly ReturnLineRow[] = buildReturnLineRows(
    this.data.returnable,
    this.data.saleLines,
  );

  protected readonly reasonOptions = RETURN_REASON_OPTIONS;
  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  /** Formulario tipado: motivo, detalle opcional y un `FormArray` con una fila por línea. */
  protected readonly form = new FormGroup({
    reasonCode: new FormControl<ReturnReasonCode>('CUSTOMER_REQUEST', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    reasonText: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(500)],
    }),
    lines: new FormArray(this.rows.map((row) => this.buildLineGroup(row))),
  });

  /** Estado vivo de las filas (valor del `FormArray`) expuesto como signal para la plantilla. */
  private readonly linesState = toSignal(this.form.controls.lines.valueChanges, {
    initialValue: this.form.controls.lines.value,
  });

  /** `true` si hay al menos una línea con cantidad mayor que 0 (líneas incluidas en el envío). */
  protected readonly hasSelectedLines = computed(() =>
    this.linesState().some((line) => (line.quantity ?? 0) > 0),
  );

  /** Reembolso estimado según las cantidades ingresadas (informativo; lo oficial lo calcula el backend). */
  protected readonly estimatedRefund = computed(() =>
    this.rows.reduce((total, row, index) => {
      const quantity = this.linesState()[index]?.quantity ?? 0;
      return quantity > 0 ? total + Math.round(quantity * row.unitRefundAmount) : total;
    }, 0),
  );

  /** Estado del conmutador global de `restock` (columna «Reingresar al stock»). */
  protected readonly allRestock = computed(() =>
    this.linesState().every((line) => line.restock === true),
  );
  protected readonly someRestock = computed(
    () => !this.allRestock() && this.linesState().some((line) => line.restock === true),
  );

  /** Plazo de devolución informado por el backend (RN-13); se muestra solo si llegó. */
  protected readonly returnDeadline = this.data.returnable.returnDeadline ?? null;

  protected formatDateTime = formatDateTime;

  /** Cantidad pendiente por devolver tras esta operación (para el botón «Devolver todo»). */
  protected maxFillable(): number {
    return this.rows.filter((row) => row.returnableQuantity > 0).length;
  }

  /** Marca todas las filas con su cantidad máxima devolvible real («Devolver todo»). */
  protected fillMaxQuantities(): void {
    this.rows.forEach((row, index) => {
      if (row.returnableQuantity > 0) {
        this.form.controls.lines.at(index).controls.quantity.setValue(row.returnableQuantity);
      }
    });
  }

  /** Fija una fila concreta a su cantidad máxima (botón «Máx.» de la fila). */
  protected setMaxQuantity(index: number): void {
    const row = this.rows[index];
    if (row && row.returnableQuantity > 0) {
      this.form.controls.lines.at(index).controls.quantity.setValue(row.returnableQuantity);
    }
  }

  /** Conmutador global de `restock`: marca o desmarca todas las filas. */
  protected toggleAllRestock(checked: boolean): void {
    for (const group of this.form.controls.lines.controls) {
      group.controls.restock.setValue(checked);
    }
  }

  /** Reembolso estimado de una fila concreta según la cantidad ingresada. */
  protected lineRefund(index: number): number {
    const row = this.rows[index];
    const quantity = this.linesState()[index]?.quantity ?? 0;
    if (!row || quantity <= 0) {
      return 0;
    }
    return Math.round(quantity * row.unitRefundAmount);
  }

  /** Mensaje de error de la cantidad de una fila (solo si el control ya se tocó o se envió). */
  protected quantityErrorHint(index: number): string | null {
    const control = this.form.controls.lines.at(index)?.controls.quantity;
    if (!control || (!control.touched && !control.dirty)) {
      return null;
    }
    if (control.hasError('required')) {
      return 'Ingresa una cantidad (0 para no devolver esta línea).';
    }
    if (control.hasError('min')) {
      return 'La cantidad no puede ser negativa.';
    }
    if (control.hasError('max')) {
      return `Máximo ${control.getError('max').max}.`;
    }
    if (control.hasError('integerQuantity')) {
      return 'Este producto se vende por unidades: ingresa una cantidad entera.';
    }
    if (control.hasError('maxDecimals')) {
      return 'Ingresa hasta 3 decimales.';
    }
    const serverMessage = control.getError('server');
    return typeof serverMessage === 'string' ? serverMessage : null;
  }

  /** Envía la devolución (`POST /sales/{id}/returns`) con idempotencia y override `SALE_RETURN`. */
  protected submit(): void {
    if (this.submitting()) {
      return;
    }
    if (!this.hasSelectedLines()) {
      this.errorMessage.set('Ingresa una cantidad mayor que 0 en al menos una línea.');
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.errorMessage.set(
        'Revisa las cantidades: hay líneas con valores fuera de lo devolvible.',
      );
      return;
    }

    const request = this.buildSaleReturnRequest();
    // Una sola clave de idempotencia y un solo HttpContext por operación para que el reintento
    // con X-Override-Token (override SALE_RETURN) reenvíe la misma clave (ADR-14, L-04).
    const idempotencyKey = generateUuid();
    const context = withoutErrorNotification();

    this.submitting.set(true);
    this.dialogRef.disableClose = true;
    this.errorMessage.set(null);

    this.supervisorOverride
      .withSupervisorOverride((overrideHeaders) =>
        this.returnsService.createReturn(this.data.saleId, request, {
          idempotencyKey,
          overrideToken: overrideHeaders[OVERRIDE_TOKEN_HEADER],
          context,
        }),
      )
      .subscribe({
        next: (response) => this.dialogRef.close(response),
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

  /**
   * Construye el cuerpo de `POST /sales/{id}/returns`: solo las líneas con cantidad > 0,
   * cada una con `saleLineId`, `quantity` (≤ lo devolvible) y `restock`.
   */
  private buildSaleReturnRequest(): SaleReturnRequest {
    const { reasonCode, reasonText } = this.form.getRawValue();
    const lines: SaleReturnLineRequest[] = [];
    this.rows.forEach((row, index) => {
      const quantity = this.form.controls.lines.at(index).controls.quantity.value;
      if (quantity !== null && quantity > 0) {
        lines.push({
          saleLineId: row.saleLineId,
          quantity,
          restock: this.form.controls.lines.at(index).controls.restock.value === true,
        });
      }
    });
    const trimmedReason = reasonText.trim();
    return {
      reasonCode,
      lines,
      ...(trimmedReason ? { reasonText: trimmedReason } : {}),
    };
  }

  /** Construye una fila del `FormArray` con el tope de cantidad y el validador según el tipo. */
  private buildLineGroup(row: ReturnLineRow): FormGroup<{
    quantity: FormControl<number | null>;
    restock: FormControl<boolean>;
  }> {
    return new FormGroup({
      quantity: new FormControl<number | null>(0, {
        validators: [
          Validators.required,
          Validators.min(0),
          Validators.max(row.returnableQuantity),
          row.integerQuantity ? integerQuantity : maxThreeDecimals,
        ],
      }),
      // F.3: `restock = true` por defecto; desmarcado genera merma sistémica DAMAGED (HU-POS-15).
      restock: new FormControl<boolean>(true, { nonNullable: true }),
    });
  }

  /** Marca los controles afectados por un `VALIDATION_ERROR` del backend y muestra el mensaje. */
  private applyError(err: unknown): void {
    for (const fieldError of apiErrorFieldErrors(err)) {
      const control = this.form.get(fieldError.field);
      if (control) {
        control.setErrors({ server: fieldError.message });
        control.markAsTouched();
      }
    }
    this.errorMessage.set(resolveReturnsErrorMessage(err, 'No se pudo registrar la devolución.'));
  }
}
