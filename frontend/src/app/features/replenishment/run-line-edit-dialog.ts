import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { ErrorStateMatcher } from '@angular/material/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { ProductSupplierResponse } from '../../api/models/product-supplier-response';
import { ReplenishmentLineResponse } from '../../api/models/replenishment-line-response';
import { ReplenishmentLineUpdateRequest } from '../../api/models/replenishment-line-update-request';
import { QtyPipe } from '../../shared/pipes';
import { lineStatusLabel } from './replenishment.models';

/** Datos de apertura del diálogo de edición de una línea de sugerencia. */
export interface LineEditDialogData {
  /** Línea de sugerencia a editar (viene del detalle de la corrida). */
  readonly line: ReplenishmentLineResponse;
  /**
   * Candidatos activos producto-proveedor (con MOQ y unidad de compra) para cambiar el proveedor;
   * los obtiene el llamador con `GET /products/{id}/suppliers`.
   */
  readonly suppliers: readonly ProductSupplierResponse[];
}

/** Resultado del diálogo: payload exacto del `PATCH /replenishment/lines/{id}`. */
export type LineEditResult = ReplenishmentLineUpdateRequest;

/** Validador de entero ≥ 0 para unidades de compra (no se compran fracciones de caja). */
const nonNegativeIntegerValidator: ValidatorFn = (control): ValidationErrors | null => {
  const value = control.value;
  if (value === null || value === undefined) {
    return null;
  }
  return Number.isInteger(value) && value >= 0 ? null : { integer: true };
};

/**
 * Validador de grupo del diálogo: garantiza el invariante del modelo (`API.md` F.3) — unidades
 * finales `> 0` solo con proveedor candidato activo elegido y respetando su MOQ. Con estado
 * `DISCARDED` el envío fuerza `finalPurchaseUnits = 0`, por lo que no exige proveedor ni MOQ.
 */
export function lineEditGroupValidator(suppliers: readonly ProductSupplierResponse[]): ValidatorFn {
  return (group) => {
    if (group.get('status')?.value === 'DISCARDED') {
      return null;
    }
    const units = Number(group.get('finalPurchaseUnits')?.value ?? 0);
    const psId = group.get('productSupplierId')?.value;
    const selected = suppliers.find((ps) => ps.id === psId && ps.active !== false) ?? null;
    if (units > 0 && !selected) {
      return { supplierRequired: true };
    }
    const moq = selected?.minOrderQty ?? 1;
    if (units > 0 && units < moq) {
      return { moq: { min: moq } };
    }
    return null;
  };
}

/**
 * Diálogo de edición de unidades finales de una sugerencia de reabastecimiento
 * (`HU-PRV-10`, `API.md` F.2 `PATCH /replenishment/lines/{id}`).
 *
 * Valida según el modelo (`API.md` F.3):
 * - `finalPurchaseUnits` es un entero `≥ 0`; si es mayor que 0 debe cumplir el MOQ del
 *   proveedor elegido y el producto debe tener proveedor candidato.
 * - Con estado `DISCARDED` las unidades finales se envían en 0.
 * - El proveedor elegido debe ser un candidato activo del producto.
 *
 * Accesible con teclado: foco estándar de Material y `Enter` envía el formulario válido.
 */
@Component({
  selector: 'pos-replenishment-line-edit-dialog',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatRadioModule,
    MatSelectModule,
    MatDialogModule,
    ReactiveFormsModule,
    QtyPipe,
  ],
  template: `
    <h2 mat-dialog-title>Editar sugerencia</h2>
    <mat-dialog-content>
      <p class="line-product">
        {{ line.product?.sku ?? '' }} — {{ line.product?.name ?? 'Producto' }}
        <span class="line-status">({{ lineStatusLabel(line.status) }})</span>
      </p>
      @if (line.suggestedPurchaseUnits !== undefined && line.suggestedPurchaseUnits !== null) {
        <p class="line-hint">
          Sugerencia del cálculo: {{ line.suggestedPurchaseUnits | qty }} unidades de compra
        </p>
      }

      <form [formGroup]="form" (ngSubmit)="submit()">
        <mat-form-field appearance="outline">
          <mat-label>Proveedor elegido</mat-label>
          <mat-select formControlName="productSupplierId">
            <mat-option [value]="null">— Sin proveedor —</mat-option>
            @for (ps of suppliers(); track ps.id) {
              <mat-option [value]="ps.id">
                {{ ps.supplierName ?? 'Proveedor' }} (MOQ {{ ps.minOrderQty ?? 1 }})
              </mat-option>
            }
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Unidades finales de compra</mat-label>
          <input
            matInput
            type="number"
            formControlName="finalPurchaseUnits"
            min="0"
            step="1"
            [errorStateMatcher]="unitsErrorStateMatcher"
          />
          <mat-hint>
            @if (selectedSupplier(); as ps) {
              Mínimo del proveedor (MOQ): {{ ps.minOrderQty ?? 1 }}
            } @else {
              0 = no comprar; con unidades &gt; 0 se exige el MOQ del proveedor.
            }
          </mat-hint>
          @if (form.hasError('moq')) {
            <mat-error>
              Con unidades &gt; 0 se exige el MOQ del proveedor ({{ form.errors?.['moq']?.min }}).
            </mat-error>
          }
          @if (form.hasError('supplierRequired')) {
            <mat-error>Elige un proveedor para comprar unidades.</mat-error>
          }
          @if (form.controls.finalPurchaseUnits.hasError('required')) {
            <mat-error>Ingresa las unidades finales (0 para no comprar).</mat-error>
          }
          @if (form.controls.finalPurchaseUnits.hasError('integer')) {
            <mat-error>Las unidades de compra deben ser un entero mayor o igual a 0.</mat-error>
          }
        </mat-form-field>

        <mat-radio-group
          formControlName="status"
          class="status-group"
          aria-label="Estado de la línea"
        >
          <mat-radio-button value="ACCEPTED">Aceptar</mat-radio-button>
          <mat-radio-button value="DISCARDED">Descartar</mat-radio-button>
        </mat-radio-group>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" (click)="cancel()">Cancelar</button>
      <button mat-flat-button type="button" [disabled]="form.invalid" (click)="submit()">
        Guardar
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .line-product {
      font-weight: 600;
      margin: 0 0 4px;
    }

    .line-status,
    .line-hint {
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.85rem;
    }

    form {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 320px;
    }

    .status-group {
      margin: 8px 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunLineEditDialog {
  private readonly dialogRef =
    inject<MatDialogRef<RunLineEditDialog, LineEditResult>>(MatDialogRef);
  private readonly fb = inject(FormBuilder);
  readonly data = inject<LineEditDialogData>(MAT_DIALOG_DATA);

  /** Línea en edición (atajo de lectura para la plantilla). */
  protected readonly line = this.data.line;

  /** Helper de etiqueta de estado expuesto para la plantilla. */
  protected readonly lineStatusLabel = lineStatusLabel;

  /** Candidatos activos producto-proveedor; sin candidato activo la compra no es posible. */
  protected readonly suppliers = computed(() =>
    this.data.suppliers.filter((ps) => ps.active !== false),
  );

  /**
   * Formulario tipado del diálogo. La validación de MOQ cruza los controles (unidades y
   * proveedor), por lo que vive como validador del grupo y no del control aislado. El valor
   * inicial de `productSupplierId` se resuelve buscando la asociación activa del proveedor
   * que vino en la línea (la línea expone el id del proveedor, no el de la asociación).
   */
  protected readonly form = this.fb.group(
    {
      productSupplierId: this.fb.control<number | null>(this.initialProductSupplierId()),
      finalPurchaseUnits: this.fb.control<number>(
        this.data.line.finalPurchaseUnits ?? this.data.line.suggestedPurchaseUnits ?? 0,
        { validators: [Validators.required, nonNegativeIntegerValidator] },
      ),
      status: this.fb.control<'ACCEPTED' | 'DISCARDED'>(
        this.data.line.status === 'DISCARDED' ? 'DISCARDED' : 'ACCEPTED',
      ),
    },
    { validators: [lineEditGroupValidator(this.data.suppliers)] },
  );

  private readonly selectedProductSupplierId = toSignal(
    this.form.controls.productSupplierId.valueChanges,
    { initialValue: this.form.controls.productSupplierId.value },
  );

  /** Proveedor actualmente elegido en el formulario (para mostrar su MOQ). */
  protected readonly selectedSupplier = computed(() => {
    const id = this.selectedProductSupplierId();
    return this.suppliers().find((ps) => ps.id === id) ?? null;
  });

  /**
   * Expone en el campo de unidades los errores de grupo (`moq` y `supplierRequired`) además de
   * los propios del control, para que `<mat-form-field>` muestre `<mat-error>` en pantalla.
   */
  protected readonly unitsErrorStateMatcher: ErrorStateMatcher = {
    isErrorState: (control, form) => {
      const interacted = Boolean(
        control?.touched || control?.dirty || form?.submitted || this.form.touched,
      );
      if (!interacted) {
        return false;
      }
      return Boolean(
        control?.invalid || this.form.hasError('moq') || this.form.hasError('supplierRequired'),
      );
    },
  };

  /**
   * Envía el payload del `PATCH` si el formulario es válido: con `DISCARDED` las unidades
   * finales van en 0 según el modelo (`API.md` F.3).
   */
  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    const status = raw.status ?? 'ACCEPTED';
    const payload: LineEditResult = {
      status,
      finalPurchaseUnits: status === 'DISCARDED' ? 0 : (raw.finalPurchaseUnits ?? 0),
      productSupplierId: raw.productSupplierId ?? undefined,
    };
    this.dialogRef.close(payload);
  }

  /** Cierra sin cambios. */
  protected cancel(): void {
    this.dialogRef.close(undefined);
  }

  /**
   * Resuelve el id de la asociación producto-proveedor activa que corresponde al proveedor
   * actual de la línea (o `null` si no hay coincidencia activa).
   */
  private initialProductSupplierId(): number | null {
    const supplierId = this.data.line.supplier?.id;
    if (supplierId === undefined || supplierId === null) {
      return null;
    }
    const match = this.data.suppliers.find(
      (ps) => ps.supplierId === supplierId && ps.active !== false,
    );
    return match?.id ?? null;
  }
}
