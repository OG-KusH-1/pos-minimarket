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
import { catchError, forkJoin, of, switchMap } from 'rxjs';
import { GoodsReceiptLineRequest } from '../../../api/models/goods-receipt-line-request';
import { GoodsReceiptRequest } from '../../../api/models/goods-receipt-request';
import { GoodsReceiptResponse } from '../../../api/models/goods-receipt-response';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { SupplierSummary } from '../../../api/models/supplier-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { QtyPipe } from '../../../shared/pipes';
import { GoodsReceiptsService } from './goods-receipts.service';
import {
  SUPPLIER_DOCUMENT_TYPE_OPTIONS,
  SupplierDocumentTypeCode,
  mapReceiptBusinessError,
} from './receipts.models';

/** Datos de entrada para {@link ReceiptFormDialog}. */
export interface ReceiptFormDialogData {
  /** Si se indica, inicializa el borrador desde una orden de compra (`receipt-draft`). */
  readonly purchaseOrderId?: number;
  /** Si es true, habilita recepción sin orden de compra. */
  readonly withoutOrder?: boolean;
  /** Si se indica, edita un borrador `DRAFT` existente. */
  readonly receipt?: GoodsReceiptResponse;
}

/** Metadatos de producto y línea usados para validación y presentación en el formulario. */
export interface ReceiptLineMeta {
  readonly productId: number;
  readonly purchaseOrderLineId?: number;
  readonly sku: string;
  readonly name: string;
  readonly qtyExpected: number | null;
  readonly lotControlled: boolean;
  readonly shelfLifeDays?: number;
}

/** Grupo reactivo tipado por línea de recepción. */
export type ReceiptLineFormGroup = FormGroup<{
  productId: FormControl<number>;
  purchaseOrderLineId: FormControl<number | null>;
  qtyReceived: FormControl<number | null>;
  qtyRejected: FormControl<number | null>;
  differenceReason: FormControl<string>;
  unitCostNet: FormControl<number | null>;
  lotNumber: FormControl<string>;
  expiryDate: FormControl<string>;
}>;

/**
 * Diálogo modal para crear o editar una recepción de mercadería (`DRAFT`) y opcionalmente
 * confirmarla de manera transaccional (`HU-PRV-05` a `HU-PRV-08`).
 *
 * Exige número de lote (`lotNumber`) y fecha de vencimiento (`expiryDate`) cuando el producto es
 * controlado por lote (`lotControlled == true`), motivo de diferencia cuando `qtyReceived`
 * difiere de lo esperado en la OC, y oculta costos si el usuario no posee `catalog:cost:read`.
 */
@Component({
  selector: 'pos-receipt-form-dialog',
  imports: [
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
  templateUrl: './receipt-form-dialog.html',
  styleUrl: './receipt-form-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReceiptFormDialog implements OnInit {
  protected readonly data =
    inject<ReceiptFormDialogData>(MAT_DIALOG_DATA, { optional: true }) ?? {};
  private readonly dialogRef = inject(MatDialogRef<ReceiptFormDialog, GoodsReceiptResponse | null>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly receiptsService = inject(GoodsReceiptsService);
  private readonly auth = inject(AuthService);

  protected readonly docTypeOptions = SUPPLIER_DOCUMENT_TYPE_OPTIONS;
  protected readonly canReadCost = computed(() => this.auth.hasPermission('catalog:cost:read'));
  protected readonly canConfirm = computed(() =>
    this.auth.hasPermission('purchasing:receipt:confirm'),
  );

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly suppliers = signal<SupplierSummary[]>([]);
  protected readonly productResults = signal<ProductSummary[]>([]);
  protected readonly lineMetas = signal<ReceiptLineMeta[]>([]);
  protected readonly orderNumber = signal<string | null>(
    this.data.receipt?.purchaseOrderNumber ?? null,
  );
  protected readonly supplierLabel = signal<string | null>(
    this.data.receipt?.supplier?.tradeName || this.data.receipt?.supplier?.legalName || null,
  );

  private purchaseOrderId: number | undefined =
    this.data.receipt?.purchaseOrderId ?? this.data.purchaseOrderId;
  private draftId: number | undefined = this.data.receipt?.id;
  private draftVersion: number | undefined = this.data.receipt?.version;

  protected readonly searchControl = this.fb.control('');
  protected readonly linesArray = this.fb.array<ReceiptLineFormGroup>([], Validators.required);

  protected readonly form = this.fb.group({
    supplierId: [this.data.receipt?.supplier?.id ?? (null as number | null)],
    supplierDocumentType: [
      (this.data.receipt?.supplierDocumentType ?? 'INVOICE') as SupplierDocumentTypeCode,
      Validators.required,
    ],
    supplierDocumentNumber: [
      this.data.receipt?.supplierDocumentNumber ?? '',
      Validators.maxLength(30),
    ],
    notes: [this.data.receipt?.notes ?? '', Validators.maxLength(2000)],
    lines: this.linesArray,
  });

  constructor() {
    this.form.controls.supplierDocumentType.valueChanges.subscribe((docType) => {
      this.updateDocumentNumberValidation(docType);
    });
    this.updateDocumentNumberValidation(this.form.controls.supplierDocumentType.value);
  }

  protected get hasPurchaseOrder(): boolean {
    return this.purchaseOrderId !== undefined;
  }

  protected get isEdit(): boolean {
    return this.draftId !== undefined;
  }

  ngOnInit(): void {
    if (this.data.receipt) {
      this.initFromExistingReceipt(this.data.receipt);
      return;
    }

    if (this.data.purchaseOrderId !== undefined) {
      this.initFromPurchaseOrder(this.data.purchaseOrderId);
      return;
    }

    // Recepción sin OC
    this.form.controls.supplierId.addValidators(Validators.required);
    this.form.controls.supplierId.updateValueAndValidity();
    this.loadSuppliers();
  }

  protected searchCatalogProducts(): void {
    const q = this.searchControl.value.trim();
    if (!q) {
      return;
    }
    this.receiptsService.searchProducts(q, withoutErrorNotification()).subscribe({
      next: (list) => this.productResults.set(list),
      error: () => {
        // Silencioso en búsqueda auxiliar.
      },
    });
  }

  protected addProductLine(product: ProductSummary): void {
    if (product.id === undefined) {
      return;
    }
    const productId = product.id;
    this.loading.set(true);
    this.receiptsService.getProduct(productId, withoutErrorNotification()).subscribe({
      next: (detail) => {
        this.loading.set(false);
        const meta: ReceiptLineMeta = {
          productId,
          sku: detail.sku ?? product.sku ?? '',
          name: detail.name ?? product.name ?? `Producto #${productId}`,
          qtyExpected: null,
          lotControlled: Boolean(detail.lotControlled),
          shelfLifeDays: detail.shelfLifeDays,
        };
        this.appendLine(meta, {
          productId,
          qtyReceived: 1,
          qtyRejected: 0,
          unitCostNet: detail.avgCost ?? product.avgCost ?? 0,
        });
      },
      error: () => {
        this.loading.set(false);
      },
    });
  }

  protected removeLine(index: number): void {
    if (this.linesArray.length <= 1 && this.hasPurchaseOrder) {
      return;
    }
    this.linesArray.removeAt(index);
    const updated = [...this.lineMetas()];
    updated.splice(index, 1);
    this.lineMetas.set(updated);
  }

  protected metaAt(index: number): ReceiptLineMeta | undefined {
    return this.lineMetas()[index];
  }

  protected saveDraft(): void {
    this.persist(false);
  }

  protected saveAndConfirm(): void {
    this.persist(true);
  }

  private persist(andConfirm: boolean): void {
    this.linesArray.controls.forEach((group) => group.updateValueAndValidity());
    if (this.form.invalid || this.linesArray.length === 0 || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const lines: GoodsReceiptLineRequest[] = this.linesArray.controls.map((group) => {
      const val = group.getRawValue();
      const line: GoodsReceiptLineRequest = {
        productId: val.productId,
        purchaseOrderLineId: val.purchaseOrderLineId ?? undefined,
        qtyReceived: val.qtyReceived ?? 0,
        qtyRejected: val.qtyRejected ?? 0,
        differenceReason: val.differenceReason.trim() || undefined,
        unitCostNet: val.unitCostNet ?? 0,
        lotNumber: val.lotNumber.trim() || undefined,
        expiryDate: val.expiryDate.trim() || undefined,
      };
      return line;
    });

    const body: GoodsReceiptRequest = {
      purchaseOrderId: this.purchaseOrderId,
      supplierId: this.purchaseOrderId === undefined ? (raw.supplierId ?? undefined) : undefined,
      supplierDocumentType: raw.supplierDocumentType,
      supplierDocumentNumber:
        raw.supplierDocumentType === 'NONE'
          ? undefined
          : raw.supplierDocumentNumber.trim() || undefined,
      notes: raw.notes.trim() || undefined,
      lines,
      ...(this.draftVersion !== undefined ? { version: this.draftVersion } : {}),
    };

    this.saving.set(true);
    this.serverError.set(null);

    const save$ =
      this.draftId !== undefined
        ? this.receiptsService.updateReceipt(this.draftId, body, withoutErrorNotification())
        : this.receiptsService.createReceipt(body, withoutErrorNotification());

    if (!andConfirm) {
      save$.subscribe({
        next: (saved) => {
          this.saving.set(false);
          this.dialogRef.close(saved);
        },
        error: (err: unknown) => this.handleSaveError(err),
      });
      return;
    }

    save$
      .pipe(
        switchMap((saved) => {
          this.draftId = saved.id;
          this.draftVersion = saved.version;
          if (saved.id === undefined) {
            return of(saved);
          }
          return this.receiptsService.confirmReceipt(saved.id, withoutErrorNotification());
        }),
      )
      .subscribe({
        next: (confirmed) => {
          this.saving.set(false);
          this.dialogRef.close(confirmed);
        },
        error: (err: unknown) => this.handleSaveError(err),
      });
  }

  private handleSaveError(err: unknown): void {
    this.saving.set(false);
    if (!isApiError(err)) {
      this.serverError.set('No se pudo guardar o confirmar la recepción.');
      return;
    }
    if (err.hasFieldErrors) {
      for (const fieldErr of err.errors) {
        const control = this.form.get(fieldErr.field);
        if (control) {
          control.setErrors({ server: fieldErr.message });
          control.markAsTouched();
        } else {
          this.serverError.set(mapReceiptBusinessError(err.code, fieldErr.message));
        }
      }
      return;
    }
    this.serverError.set(mapReceiptBusinessError(err.code, err.message));
  }

  private initFromPurchaseOrder(purchaseOrderId: number): void {
    this.loading.set(true);
    forkJoin({
      draft: this.receiptsService.getOrderReceiptDraft(purchaseOrderId, withoutErrorNotification()),
      order: this.receiptsService.getOrder(purchaseOrderId, withoutErrorNotification()),
    })
      .pipe(
        switchMap(({ draft, order }) => {
          this.orderNumber.set(order.number ?? `#${purchaseOrderId}`);
          this.supplierLabel.set(order.supplier?.tradeName || order.supplier?.legalName || null);
          this.form.patchValue({
            supplierId: draft.supplierId ?? order.supplier?.id ?? null,
            supplierDocumentType: draft.supplierDocumentType ?? 'INVOICE',
            supplierDocumentNumber: draft.supplierDocumentNumber ?? '',
            notes: draft.notes ?? '',
          });

          const productCalls = draft.lines.map((line) =>
            this.receiptsService
              .getProduct(line.productId, withoutErrorNotification())
              .pipe(catchError(() => of(null as ProductResponse | null))),
          );

          return forkJoin({
            draft: of(draft),
            order: of(order),
            products: productCalls.length > 0 ? forkJoin(productCalls) : of([]),
          });
        }),
      )
      .subscribe({
        next: ({ draft, order, products }) => {
          this.loading.set(false);
          const orderLines = order.lines ?? [];
          draft.lines.forEach((line, idx) => {
            const product = products[idx] ?? null;
            const orderLine = orderLines.find(
              (ol) => ol.id === line.purchaseOrderLineId || ol.productId === line.productId,
            );
            const meta: ReceiptLineMeta = {
              productId: line.productId,
              purchaseOrderLineId: line.purchaseOrderLineId,
              sku: product?.sku ?? orderLine?.sku ?? '',
              name: product?.name ?? orderLine?.name ?? `Producto #${line.productId}`,
              qtyExpected: line.qtyReceived,
              lotControlled: Boolean(product?.lotControlled),
              shelfLifeDays: product?.shelfLifeDays,
            };
            this.appendLine(meta, line);
          });
        },
        error: (err: unknown) => {
          this.loading.set(false);
          this.serverError.set(
            isApiError(err) ? err.message : 'No se pudo cargar el borrador de la orden de compra.',
          );
        },
      });
  }

  private initFromExistingReceipt(receipt: GoodsReceiptResponse): void {
    const lines = receipt.lines ?? [];
    if (lines.length === 0) {
      return;
    }
    this.loading.set(true);
    const productCalls = lines.map((line) =>
      line.productId !== undefined
        ? this.receiptsService
            .getProduct(line.productId, withoutErrorNotification())
            .pipe(catchError(() => of(null as ProductResponse | null)))
        : of(null as ProductResponse | null),
    );
    forkJoin(productCalls).subscribe({
      next: (products) => {
        this.loading.set(false);
        lines.forEach((line, idx) => {
          if (line.productId === undefined) {
            return;
          }
          const product = products[idx] ?? null;
          const meta: ReceiptLineMeta = {
            productId: line.productId,
            purchaseOrderLineId: line.purchaseOrderLineId,
            sku: line.sku ?? product?.sku ?? '',
            name: line.name ?? product?.name ?? `Producto #${line.productId}`,
            qtyExpected: line.qtyExpected ?? null,
            lotControlled: Boolean(product?.lotControlled),
            shelfLifeDays: product?.shelfLifeDays,
          };
          this.appendLine(meta, {
            productId: line.productId,
            purchaseOrderLineId: line.purchaseOrderLineId,
            qtyReceived: line.qtyReceived ?? 0,
            qtyRejected: line.qtyRejected ?? 0,
            differenceReason: line.differenceReason,
            unitCostNet: line.unitCostNet ?? 0,
            lotNumber: line.lotNumber,
            expiryDate: line.expiryDate,
          });
        });
      },
      error: () => {
        this.loading.set(false);
      },
    });
  }

  private loadSuppliers(): void {
    this.receiptsService.listSuppliers(undefined, withoutErrorNotification()).subscribe({
      next: (list) => this.suppliers.set(list),
      error: () => {
        // Silencioso en carga auxiliar.
      },
    });
  }

  private updateDocumentNumberValidation(docType: SupplierDocumentTypeCode): void {
    const ctrl = this.form.controls.supplierDocumentNumber;
    if (docType === 'NONE') {
      ctrl.setValidators([Validators.maxLength(30)]);
    } else {
      ctrl.setValidators([Validators.required, Validators.maxLength(30)]);
    }
    ctrl.updateValueAndValidity({ emitEvent: false });
  }

  private appendLine(meta: ReceiptLineMeta, initial: Partial<GoodsReceiptLineRequest>): void {
    const group = this.createLineGroup(meta, initial);
    this.linesArray.push(group);
    this.lineMetas.set([...this.lineMetas(), meta]);
  }

  private createLineGroup(
    meta: ReceiptLineMeta,
    initial: Partial<GoodsReceiptLineRequest>,
  ): ReceiptLineFormGroup {
    const lotValidators = meta.lotControlled
      ? [Validators.required, Validators.maxLength(40)]
      : [Validators.maxLength(40)];
    const expiryValidators = meta.lotControlled ? [Validators.required] : [];

    const group = this.fb.group(
      {
        productId: [meta.productId, Validators.required],
        purchaseOrderLineId: [meta.purchaseOrderLineId ?? (null as number | null)],
        qtyReceived: [
          initial.qtyReceived ?? (0 as number | null),
          [Validators.required, Validators.min(0)],
        ],
        qtyRejected: [
          initial.qtyRejected ?? (0 as number | null),
          [Validators.required, Validators.min(0)],
        ],
        differenceReason: [initial.differenceReason ?? '', Validators.maxLength(250)],
        unitCostNet: [
          initial.unitCostNet ?? (0 as number | null),
          [Validators.required, Validators.min(0)],
        ],
        lotNumber: [initial.lotNumber ?? '', lotValidators],
        expiryDate: [initial.expiryDate ?? '', expiryValidators],
      },
      {
        validators: [this.lineCrossValidator(meta)],
      },
    );

    return group;
  }

  private lineCrossValidator(meta: ReceiptLineMeta) {
    return (control: AbstractControl): ValidationErrors | null => {
      const group = control as ReceiptLineFormGroup;
      const received = group.controls.qtyReceived.value ?? 0;
      const rejected = group.controls.qtyRejected.value ?? 0;
      const reason = group.controls.differenceReason.value.trim();

      if (rejected > received) {
        group.controls.qtyRejected.setErrors({
          ...(group.controls.qtyRejected.errors ?? {}),
          rejectedExceedsReceived: true,
        });
      } else if (group.controls.qtyRejected.hasError('rejectedExceedsReceived')) {
        const rest = { ...(group.controls.qtyRejected.errors ?? {}) };
        delete rest['rejectedExceedsReceived'];
        group.controls.qtyRejected.setErrors(Object.keys(rest).length ? rest : null);
      }

      if (this.hasPurchaseOrder && meta.qtyExpected !== null && received !== meta.qtyExpected) {
        if (!reason) {
          group.controls.differenceReason.setErrors({
            ...(group.controls.differenceReason.errors ?? {}),
            differenceReasonRequired: true,
          });
        } else if (group.controls.differenceReason.hasError('differenceReasonRequired')) {
          const rest = { ...(group.controls.differenceReason.errors ?? {}) };
          delete rest['differenceReasonRequired'];
          group.controls.differenceReason.setErrors(Object.keys(rest).length ? rest : null);
        }
      } else if (group.controls.differenceReason.hasError('differenceReasonRequired')) {
        const rest = { ...(group.controls.differenceReason.errors ?? {}) };
        delete rest['differenceReasonRequired'];
        group.controls.differenceReason.setErrors(Object.keys(rest).length ? rest : null);
      }

      return null;
    };
  }
}
