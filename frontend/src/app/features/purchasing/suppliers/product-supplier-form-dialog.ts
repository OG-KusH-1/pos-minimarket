import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import {
  MatAutocompleteModule,
  MatAutocompleteSelectedEvent,
} from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Subscription, debounceTime, distinctUntilChanged, startWith, switchMap } from 'rxjs';
import { ProductSummary } from '../../../api/models/product-summary';
import { ProductSupplierRequest } from '../../../api/models/product-supplier-request';
import { ProductSupplierResponse } from '../../../api/models/product-supplier-response';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import {
  computeUnitCostNet,
  formatUnitCostNet,
  integerRangeValidator,
  positiveNumberValidator,
  trimmedMinLength,
} from './supplier-validators';
import { SuppliersService } from './suppliers.service';

/** Datos de entrada para {@link ProductSupplierFormDialog}. */
export interface ProductSupplierFormDialogData {
  readonly supplierId: number;
  readonly supplierName: string;
  /** Si se informa, el diálogo abre en modo edición de esta asociación. */
  readonly existing?: ProductSupplierResponse;
}

/**
 * Diálogo modal para crear o editar una asociación producto-proveedor (`HU-PRV-02`).
 *
 * Calcula en vivo el costo neto por unidad base (`unitCostNet = costPerPurchaseUnit /
 * unitsPerPurchaseUnit`), rechaza productos de tipo `PACK` y respeta `catalog:cost:read`.
 */
@Component({
  selector: 'pos-product-supplier-form-dialog',
  imports: [
    MatAutocompleteModule,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  templateUrl: './product-supplier-form-dialog.html',
  styleUrl: './product-supplier-form-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductSupplierFormDialog implements OnInit, OnDestroy {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly suppliersService = inject(SuppliersService);
  private readonly auth = inject(AuthService);
  private readonly dialogRef = inject(
    MatDialogRef<ProductSupplierFormDialog, ProductSupplierResponse | null>,
  );
  protected readonly data = inject<ProductSupplierFormDialogData>(MAT_DIALOG_DATA);

  private searchSub: Subscription | null = null;

  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));
  protected readonly saving = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly productOptions = signal<readonly ProductSummary[]>([]);
  protected readonly selectedProduct = signal<ProductSummary | null>(null);

  protected readonly productSearchControl = this.fb.control('');

  protected readonly form = this.fb.group({
    productId: [null as number | null, [Validators.required]],
    supplierSku: ['', [Validators.maxLength(40)]],
    purchaseUnitName: ['', [Validators.required, trimmedMinLength(1), Validators.maxLength(30)]],
    unitsPerPurchaseUnit: [1, [Validators.required, positiveNumberValidator()]],
    costPerPurchaseUnit: [0, [Validators.required, Validators.min(0)]],
    minOrderQty: [1, [Validators.required, Validators.min(1)]],
    leadTimeDaysOverride: [null as number | null, [integerRangeValidator(0, 90)]],
    isPreferred: [false],
  });

  private readonly formValues = toSignal(
    this.form.valueChanges.pipe(startWith(this.form.getRawValue())),
    { initialValue: this.form.getRawValue() },
  );

  /** Costo neto por unidad base calculado en vivo (p. ej. caja de 12 a $6.000 -> $500). */
  protected readonly previewUnitCostNet = computed(() => {
    const val = this.formValues();
    return computeUnitCostNet(val.costPerPurchaseUnit, val.unitsPerPurchaseUnit);
  });

  protected readonly formattedPreviewUnitCostNet = computed(() =>
    formatUnitCostNet(this.previewUnitCostNet()),
  );

  protected get isEdit(): boolean {
    return Boolean(this.data.existing?.id);
  }

  ngOnInit(): void {
    const existing = this.data.existing;
    if (existing) {
      this.form.patchValue({
        productId: existing.productId ?? null,
        supplierSku: existing.supplierSku ?? '',
        purchaseUnitName: existing.purchaseUnitName ?? '',
        unitsPerPurchaseUnit: existing.unitsPerPurchaseUnit ?? 1,
        costPerPurchaseUnit: existing.costPerPurchaseUnit ?? 0,
        minOrderQty: existing.minOrderQty ?? 1,
        leadTimeDaysOverride: existing.leadTimeDaysOverride ?? null,
        isPreferred: existing.isPreferred ?? false,
      });
      this.productSearchControl.setValue(
        `${existing.productSku ?? ''} — ${existing.productName ?? ''}`.trim(),
      );
      this.productSearchControl.disable();
      return;
    }

    this.searchSub = this.productSearchControl.valueChanges
      .pipe(
        startWith(''),
        debounceTime(200),
        distinctUntilChanged(),
        switchMap((term) => {
          const query = typeof term === 'string' ? term.trim() : '';
          return this.suppliersService.searchProducts(
            { q: query || undefined, active: true, size: 20 },
            withoutErrorNotification(),
          );
        }),
      )
      .subscribe({
        next: (page) => this.productOptions.set(page.content ?? []),
        error: () => this.productOptions.set([]),
      });
  }

  ngOnDestroy(): void {
    this.searchSub?.unsubscribe();
  }

  /** Selecciona un producto del autocompletado, rechazando productos tipo `PACK` (`HU-PRV-02`). */
  protected onProductSelected(event: MatAutocompleteSelectedEvent): void {
    const product = event.option.value as ProductSummary;
    this.selectProduct(product);
  }

  /** Aplica la selección de un producto y valida que no sea de tipo `PACK`. */
  selectProduct(product: ProductSummary): void {
    if (product.saleType === 'PACK') {
      this.selectedProduct.set(null);
      this.form.controls.productId.setValue(null);
      this.form.controls.productId.setErrors({ packNotAllowed: true });
      this.form.controls.productId.markAsTouched();
      this.productSearchControl.setErrors({ packNotAllowed: true });
      this.productSearchControl.markAsTouched();
      this.errorMessage.set('Los productos de tipo PACK no pueden asociarse a un proveedor.');
      return;
    }

    this.errorMessage.set(null);
    this.selectedProduct.set(product);
    this.form.controls.productId.setValue(product.id ?? null);
    this.form.controls.productId.setErrors(null);
    this.productSearchControl.setErrors(null);
    this.productSearchControl.setValue(`${product.sku ?? ''} — ${product.name ?? ''}`.trim(), {
      emitEvent: false,
    });
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      if (this.form.controls.productId.hasError('required')) {
        this.productSearchControl.setErrors({ required: true });
        this.productSearchControl.markAsTouched();
      }
      return;
    }

    const raw = this.form.getRawValue();
    if (raw.productId === null) {
      return;
    }

    this.saving.set(true);
    this.errorMessage.set(null);

    const payload: ProductSupplierRequest = {
      productId: raw.productId,
      supplierId: this.data.supplierId,
      supplierSku: raw.supplierSku.trim() || undefined,
      purchaseUnitName: raw.purchaseUnitName.trim(),
      unitsPerPurchaseUnit: Number(raw.unitsPerPurchaseUnit),
      costPerPurchaseUnit: Number(raw.costPerPurchaseUnit),
      minOrderQty: Number(raw.minOrderQty),
      leadTimeDaysOverride:
        raw.leadTimeDaysOverride === null ? undefined : Number(raw.leadTimeDaysOverride),
      isPreferred: raw.isPreferred,
      version: this.data.existing?.version,
    };

    const existingId = this.data.existing?.id;
    const request$ = existingId
      ? this.suppliersService.updateProductSupplier(existingId, payload, withoutErrorNotification())
      : this.suppliersService.createProductSupplier(payload, withoutErrorNotification());

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

  private handleError(err: unknown): void {
    if (!isApiError(err)) {
      this.errorMessage.set('Error inesperado al guardar la asociación producto-proveedor.');
      return;
    }

    if (err.code === 'DUPLICATED_VALUE') {
      this.errorMessage.set('Este producto ya se encuentra asociado a este proveedor.');
      return;
    }

    if (err.code === 'CONCURRENT_MODIFICATION') {
      this.errorMessage.set(
        'La asociación fue modificada por otro usuario. Cierra y vuelve a abrir para recargar.',
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
