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
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ProductSupplierResponse } from '../../../api/models/product-supplier-response';
import { PurchaseOrderLineRequest } from '../../../api/models/purchase-order-line-request';
import { PurchaseOrderLineResponse } from '../../../api/models/purchase-order-line-response';
import { PurchaseOrderRequest } from '../../../api/models/purchase-order-request';
import { PurchaseOrderResponse } from '../../../api/models/purchase-order-response';
import { SupplierSummary } from '../../../api/models/supplier-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { ClpPipe } from '../../../shared/pipes';
import { todayIsoDate } from './orders.models';
import { PurchaseOrdersService } from './purchase-orders.service';

/** Datos de entrada para {@link OrderFormDialog}. */
export interface OrderFormDialogData {
  readonly order?: PurchaseOrderResponse;
}

/** Opción de producto seleccionable en una línea de orden de compra. */
export interface OrderProductOption {
  readonly productId: number;
  readonly sku: string;
  readonly name: string;
  readonly purchaseUnitName: string;
  readonly unitsPerPurchaseUnit: number;
  readonly defaultUnitCostNet?: number;
}

type OrderLineFormGroup = FormGroup<{
  productId: FormControl<number | null>;
  qtyPurchaseUnits: FormControl<number | null>;
  unitCostNet: FormControl<number | null>;
}>;

function positiveNumberValidator(control: AbstractControl<number | null>): ValidationErrors | null {
  const value = control.value;
  if (value === null || value === undefined || Number.isNaN(value)) {
    return { required: true };
  }
  return value > 0 ? null : { positive: true };
}

function minDateTodayValidator(today: string) {
  return (control: AbstractControl<string>): ValidationErrors | null => {
    const val = control.value.trim();
    if (!val) {
      return null;
    }
    return val < today ? { minDate: true } : null;
  };
}

function noDuplicateProductsValidator(
  control: AbstractControl<OrderLineFormGroup['value'][]>,
): ValidationErrors | null {
  const seen = new Set<number>();
  for (const row of control.value ?? []) {
    if (row.productId === null || row.productId === undefined) {
      continue;
    }
    if (seen.has(row.productId)) {
      return { duplicateProducts: true };
    }
    seen.add(row.productId);
  }
  return null;
}

/**
 * Diálogo modal para crear o editar una orden de compra en borrador (`DRAFT`, `HU-PRV-03`).
 *
 * Carga los proveedores activos y sus productos asociados (`GET /suppliers/{id}/products`),
 * oculta los costos unitarios si el usuario no posee `catalog:cost:read` y envía `version` para
 * control de concurrencia optimista al editar.
 */
@Component({
  selector: 'pos-order-form-dialog',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    ReactiveFormsModule,
  ],
  templateUrl: './order-form-dialog.html',
  styleUrl: './order-form-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderFormDialog implements OnInit {
  protected readonly data = inject<OrderFormDialogData>(MAT_DIALOG_DATA, { optional: true }) ?? {};
  private readonly dialogRef = inject(MatDialogRef<OrderFormDialog, PurchaseOrderResponse | null>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly ordersService = inject(PurchaseOrdersService);
  private readonly auth = inject(AuthService);

  protected readonly existing = this.data.order;
  protected readonly isEdit = Boolean(this.existing?.id);
  protected readonly canReadCost = computed(() => this.auth.hasPermission('catalog:cost:read'));
  protected readonly minExpectedDate = todayIsoDate();

  protected readonly suppliers = signal<SupplierSummary[]>([]);
  protected readonly productOptions = signal<OrderProductOption[]>([]);
  protected readonly loadingCatalog = signal(false);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly linesArray = this.fb.array<OrderLineFormGroup>(
    [],
    [Validators.required, noDuplicateProductsValidator],
  );

  protected readonly form = this.fb.group({
    supplierId: [this.existing?.supplier?.id ?? (null as number | null), Validators.required],
    expectedDate: [
      this.existing?.expectedDate ?? '',
      [minDateTodayValidator(this.minExpectedDate)],
    ],
    notes: [this.existing?.notes ?? '', Validators.maxLength(2000)],
    lines: this.linesArray,
  });

  constructor() {
    const initialLines = this.existing?.lines ?? [];
    if (initialLines.length > 0) {
      for (const line of initialLines) {
        this.linesArray.push(this.createLineGroup(line));
      }
    } else {
      this.linesArray.push(this.createLineGroup());
    }

    if (this.existing?.supplier?.id) {
      this.suppliers.set([
        {
          id: this.existing.supplier.id,
          legalName: this.existing.supplier.legalName,
          tradeName: this.existing.supplier.tradeName,
          rut: this.existing.supplier.rut,
          active: true,
        },
      ]);
    }
  }

  ngOnInit(): void {
    this.ordersService.listSuppliers(undefined, withoutErrorNotification()).subscribe({
      next: (list) => {
        const merged = [...list];
        if (
          this.existing?.supplier?.id &&
          !merged.some((s) => s.id === this.existing?.supplier?.id)
        ) {
          merged.unshift({
            id: this.existing.supplier.id,
            legalName: this.existing.supplier.legalName,
            tradeName: this.existing.supplier.tradeName,
            rut: this.existing.supplier.rut,
            active: true,
          });
        }
        this.suppliers.set(merged);
      },
      error: () => {
        // Si falla la carga auxiliar, se mantiene la lista actual o vacía.
      },
    });

    const initialSupplierId = this.form.controls.supplierId.value;
    if (initialSupplierId !== null) {
      this.loadSupplierProducts(initialSupplierId);
    }

    this.form.controls.supplierId.valueChanges.subscribe((supplierId) => {
      if (supplierId !== null) {
        this.loadSupplierProducts(supplierId);
      } else {
        this.productOptions.set([]);
      }
    });
  }

  protected addLine(): void {
    if (this.linesArray.length >= 500) {
      return;
    }
    this.linesArray.push(this.createLineGroup());
  }

  protected removeLine(index: number): void {
    if (this.linesArray.length <= 1) {
      return;
    }
    this.linesArray.removeAt(index);
  }

  protected onProductSelected(index: number, productId: number | null): void {
    if (productId === null) {
      return;
    }
    const option = this.productOptions().find((opt) => opt.productId === productId);
    const group = this.linesArray.at(index);
    if (option && this.canReadCost() && option.defaultUnitCostNet !== undefined) {
      group.controls.unitCostNet.setValue(option.defaultUnitCostNet);
    }
  }

  protected optionForLine(productId: number | null): OrderProductOption | undefined {
    if (productId === null) {
      return undefined;
    }
    return this.productOptions().find((opt) => opt.productId === productId);
  }

  protected estimatedLineNet(index: number): number | null {
    if (!this.canReadCost()) {
      return null;
    }
    const group = this.linesArray.at(index);
    const qty = group.controls.qtyPurchaseUnits.value;
    const unitCost = group.controls.unitCostNet.value;
    if (qty === null || unitCost === null) {
      return null;
    }
    const option = this.optionForLine(group.controls.productId.value);
    const factor = option?.unitsPerPurchaseUnit ?? 1;
    return Math.round(qty * factor * unitCost);
  }

  protected estimatedOrderNet(): number {
    let sum = 0;
    for (let i = 0; i < this.linesArray.length; i++) {
      sum += this.estimatedLineNet(i) ?? 0;
    }
    return sum;
  }

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const supplierId = this.form.controls.supplierId.value;
    if (supplierId === null) {
      return;
    }

    const lines: PurchaseOrderLineRequest[] = this.linesArray.controls.map((group) => {
      const line: PurchaseOrderLineRequest = {
        productId: group.controls.productId.value as number,
        qtyPurchaseUnits: group.controls.qtyPurchaseUnits.value as number,
      };
      const cost = group.controls.unitCostNet.value;
      if (this.canReadCost() && cost !== null && cost !== undefined) {
        line.unitCostNet = cost;
      }
      return line;
    });

    const expectedDate = this.form.controls.expectedDate.value.trim();
    const notes = this.form.controls.notes.value.trim();

    const body: PurchaseOrderRequest = {
      supplierId,
      expectedDate: expectedDate || undefined,
      notes: notes || undefined,
      lines,
      ...(this.isEdit ? { version: this.existing?.version } : {}),
    };

    this.saving.set(true);
    this.serverError.set(null);

    const request$ =
      this.isEdit && this.existing?.id !== undefined
        ? this.ordersService.updateOrder(this.existing.id, body, withoutErrorNotification())
        : this.ordersService.createOrder(body, withoutErrorNotification());

    request$.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.dialogRef.close(saved);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo guardar la orden de compra.');
          return;
        }
        if (err.code === 'CONCURRENT_MODIFICATION') {
          this.serverError.set(
            'La orden fue modificada por otro usuario. Cierra este diálogo y recarga el detalle.',
          );
          return;
        }
        if (err.hasFieldErrors) {
          for (const fieldErr of err.errors) {
            const control = this.form.get(fieldErr.field);
            if (control) {
              control.setErrors({ server: fieldErr.message });
              control.markAsTouched();
            } else {
              this.serverError.set(fieldErr.message);
            }
          }
        } else {
          this.serverError.set(err.message);
        }
      },
    });
  }

  private createLineGroup(line?: PurchaseOrderLineResponse): OrderLineFormGroup {
    return this.fb.group({
      productId: [line?.productId ?? (null as number | null), Validators.required],
      qtyPurchaseUnits: [line?.qtyPurchaseUnits ?? (1 as number | null), [positiveNumberValidator]],
      unitCostNet: [line?.unitCostNet ?? (null as number | null), [Validators.min(0)]],
    });
  }

  private loadSupplierProducts(supplierId: number): void {
    this.loadingCatalog.set(true);
    this.ordersService
      .listSupplierProducts(supplierId, undefined, withoutErrorNotification())
      .subscribe({
        next: (items) => {
          this.loadingCatalog.set(false);
          const options: OrderProductOption[] = items
            .filter(
              (item): item is ProductSupplierResponse & { productId: number } =>
                typeof item.productId === 'number',
            )
            .map((item) => ({
              productId: item.productId,
              sku: item.productSku ?? '',
              name: item.productName ?? `Producto #${item.productId}`,
              purchaseUnitName: item.purchaseUnitName ?? 'u',
              unitsPerPurchaseUnit: item.unitsPerPurchaseUnit ?? 1,
              defaultUnitCostNet: item.unitCostNet,
            }));

          // Preserva productos ya presentes en el borrador que no estén en la página inicial
          for (const existingLine of this.existing?.lines ?? []) {
            if (
              existingLine.productId !== undefined &&
              !options.some((o) => o.productId === existingLine.productId)
            ) {
              options.push({
                productId: existingLine.productId,
                sku: existingLine.sku ?? '',
                name: existingLine.name ?? `Producto #${existingLine.productId}`,
                purchaseUnitName: existingLine.purchaseUnitName ?? 'u',
                unitsPerPurchaseUnit: existingLine.unitsPerPurchaseUnit ?? 1,
                defaultUnitCostNet: existingLine.unitCostNet,
              });
            }
          }

          this.productOptions.set(options);
        },
        error: () => {
          this.loadingCatalog.set(false);
        },
      });
  }
}
