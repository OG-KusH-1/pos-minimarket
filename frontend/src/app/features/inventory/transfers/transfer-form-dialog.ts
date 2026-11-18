import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import {
  MatAutocompleteModule,
  MatAutocompleteSelectedEvent,
} from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Observable, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { FefoSuggestionResponse } from '../../../api/models/fefo-suggestion-response';
import { LotResponse } from '../../../api/models/lot-response';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { StockTransferLineResponse } from '../../../api/models/stock-transfer-line-response';
import { StockTransferRequest } from '../../../api/models/stock-transfer-request';
import { StockTransferResponse } from '../../../api/models/stock-transfer-response';
import { StoreResponse } from '../../../api/models/store-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { QtyPipe, QtyUnit } from '../../../shared/pipes';
import { formatTransferLocalDate, toTransferQtyUnit } from './transfers.models';
import { TransfersService } from './transfers.service';

/** Datos de entrada para {@link TransferFormDialog}. */
export interface TransferFormDialogData {
  readonly mode: 'create' | 'edit';
  readonly transfer?: StockTransferResponse;
}

/** Metadatos auxiliares de cada línea del borrador para mostrar unidad, lotes y vista previa FEFO. */
interface DraftLineMeta {
  readonly productId: number;
  readonly sku: string;
  readonly name: string;
  readonly lotControlled: boolean;
  readonly unitOfMeasure: 'UNIT' | 'KG';
  readonly loadingMeta: boolean;
  readonly lots: readonly LotResponse[];
  readonly loadingFefo: boolean;
  readonly fefoSuggestion: FefoSuggestionResponse | null;
  readonly fefoError: string | null;
}

/** Grupo reactivo tipado por línea del borrador de transferencia. */
type TransferDraftLineGroup = FormGroup<{
  productId: FormControl<number>;
  originLotId: FormControl<number | null>;
  qtySent: FormControl<number | null>;
}>;

/**
 * Diálogo para crear o editar un borrador de transferencia entre locales (`DRAFT`, `HU-INV-07`).
 *
 * Permite elegir el local de destino (distinto del local activo), buscar y agregar productos no
 * `PACK`, definir cantidades según unidad de medida (`UNIT` entero o `KG` hasta 3 decimales) y
 * elegir entre un lote específico en origen (`originLotId`) o salida automática por FEFO en
 * productos controlados por lote.
 */
@Component({
  selector: 'pos-transfer-form-dialog',
  imports: [
    MatAutocompleteModule,
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
  templateUrl: './transfer-form-dialog.html',
  styleUrl: './transfer-form-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TransferFormDialog implements OnInit {
  protected readonly data = inject<TransferFormDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<TransferFormDialog, StockTransferResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly transfersService = inject(TransfersService);
  private readonly storeContext = inject(StoreContextService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly isEdit = this.data.mode === 'edit';
  protected readonly activeStoreId = this.storeContext.storeId;
  protected readonly originStoreLabel = computed(() => {
    const store = this.storeContext.activeStore();
    if (!store) {
      return 'Local activo';
    }
    return store.code ? `${store.code} — ${store.name}` : store.name;
  });

  protected readonly stores = signal<readonly StoreResponse[]>([]);
  protected readonly loadingStores = signal(false);
  protected readonly storesError = signal<string | null>(null);

  protected readonly productSearchControl = new FormControl<string | ProductSummary>('', {
    nonNullable: true,
  });
  protected readonly productOptions = signal<readonly ProductSummary[]>([]);
  protected readonly searchingProducts = signal(false);

  protected readonly lineMetas = signal<readonly DraftLineMeta[]>([]);
  protected readonly duplicateIndexes = signal<ReadonlySet<number>>(new Set());
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly linesArray = this.fb.array<TransferDraftLineGroup>(
    [],
    [Validators.required, Validators.maxLength(300), (c) => this.validateNoDuplicateLines(c)],
  );

  protected readonly form = this.fb.group({
    destinationStoreId: [
      (this.data.transfer?.destination?.id ?? null) as number | null,
      [Validators.required, (c) => this.validateDestinationStore(c)],
    ],
    notes: [this.data.transfer?.notes ?? '', [Validators.maxLength(500)]],
    lines: this.linesArray,
  });

  ngOnInit(): void {
    this.loadStores();
    this.setupProductSearch();
    this.linesArray.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.recomputeDuplicates();
    });

    if (this.isEdit && this.data.transfer) {
      this.prefillDraftLines(this.data.transfer.lines ?? []);
    }
  }

  protected displayProductOption = (value: string | ProductSummary | null): string => {
    if (!value) {
      return '';
    }
    if (typeof value === 'string') {
      return value;
    }
    return value.sku ? `${value.sku} — ${value.name ?? ''}` : (value.name ?? '');
  };

  protected onProductSelected(event: MatAutocompleteSelectedEvent): void {
    const product = event.option.value as ProductSummary | null;
    if (!product || product.id === undefined) {
      return;
    }
    this.productSearchControl.setValue('', { emitEvent: false });
    this.productOptions.set([]);

    const unit: 'UNIT' | 'KG' = product.saleType === 'WEIGHT' ? 'KG' : 'UNIT';
    const meta: DraftLineMeta = {
      productId: product.id,
      sku: product.sku ?? `#${product.id}`,
      name: product.name ?? `Producto #${product.id}`,
      lotControlled: false,
      unitOfMeasure: unit,
      loadingMeta: true,
      lots: [],
      loadingFefo: false,
      fefoSuggestion: null,
      fefoError: null,
    };

    const index = this.appendLine(meta, null, 1);
    this.fetchProductMeta(index, product.id);
  }

  protected removeLine(index: number): void {
    this.linesArray.removeAt(index);
    this.lineMetas.update((list) => list.filter((_, i) => i !== index));
    this.recomputeDuplicates();
    this.linesArray.updateValueAndValidity();
  }

  protected lineGroupAt(index: number): TransferDraftLineGroup {
    return this.linesArray.at(index);
  }

  protected unitFor(meta: DraftLineMeta): QtyUnit {
    return toTransferQtyUnit(meta.unitOfMeasure);
  }

  protected stepFor(meta: DraftLineMeta): string {
    return meta.unitOfMeasure === 'KG' ? '0.001' : '1';
  }

  protected formatLotOption(lot: LotResponse, unit: QtyUnit): string {
    const number = lot.lotNumber || `Lote #${lot.id ?? ''}`;
    const expiry = lot.expiryDate
      ? `Vence ${formatTransferLocalDate(lot.expiryDate)}`
      : 'Sin venc.';
    const qty = lot.quantity !== undefined ? `${lot.quantity} ${unit}` : '';
    return [number, expiry, qty].filter(Boolean).join(' · ');
  }

  protected formatExpiry(isoDate: string | undefined): string {
    return formatTransferLocalDate(isoDate);
  }

  protected canPreviewFefo(index: number): boolean {
    const meta = this.lineMetas()[index];
    const group = this.linesArray.at(index);
    if (!meta || !group || !meta.lotControlled || meta.loadingMeta) {
      return false;
    }
    if (group.controls.originLotId.value !== null) {
      return false;
    }
    const qty = Number(group.controls.qtySent.value);
    return Number.isFinite(qty) && qty > 0;
  }

  protected previewFefo(index: number): void {
    const meta = this.lineMetas()[index];
    const group = this.linesArray.at(index);
    if (!meta || !group) {
      return;
    }
    const qty = Number(group.controls.qtySent.value);
    if (!Number.isFinite(qty) || qty <= 0) {
      return;
    }

    this.patchLineMeta(index, { loadingFefo: true, fefoError: null });
    this.transfersService.suggestFefo(meta.productId, qty, withoutErrorNotification()).subscribe({
      next: (suggestion) => {
        this.patchLineMeta(index, {
          loadingFefo: false,
          fefoSuggestion: suggestion,
          fefoError: null,
        });
      },
      error: (err: unknown) => {
        this.patchLineMeta(index, {
          loadingFefo: false,
          fefoSuggestion: null,
          fefoError: isApiError(err) ? err.message : 'No se pudo consultar la sugerencia FEFO.',
        });
      },
    });
  }

  protected submit(): void {
    this.recomputeDuplicates();
    this.linesArray.updateValueAndValidity();
    if (this.form.invalid || this.saving() || this.linesArray.length === 0) {
      this.form.markAllAsTouched();
      return;
    }

    const destinationStoreId = this.form.controls.destinationStoreId.value;
    if (destinationStoreId === null) {
      return;
    }
    const trimmedNotes = this.form.controls.notes.value.trim();

    const request: StockTransferRequest = {
      destinationStoreId,
      ...(trimmedNotes ? { notes: trimmedNotes } : {}),
      ...(this.isEdit && this.data.transfer?.version !== undefined
        ? { version: this.data.transfer.version }
        : {}),
      lines: this.linesArray.controls.map((group) => {
        const originLotId = group.controls.originLotId.value;
        return {
          productId: group.controls.productId.value,
          qtySent: Number(group.controls.qtySent.value),
          ...(originLotId !== null ? { originLotId } : {}),
        };
      }),
    };

    this.saving.set(true);
    this.serverError.set(null);

    const request$: Observable<StockTransferResponse> =
      this.isEdit && this.data.transfer?.id !== undefined
        ? this.transfersService.updateTransfer(
            this.data.transfer.id,
            request,
            withoutErrorNotification(),
          )
        : this.transfersService.createTransfer(request, withoutErrorNotification());

    request$.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.dialogRef.close(saved);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo guardar el borrador de transferencia.');
          return;
        }
        if (err.hasFieldErrors) {
          let mappedAny = false;
          for (const fieldErr of err.errors) {
            const path = fieldErr.field.replace(/\[(\d+)\]/g, '.$1');
            const control = this.form.get(path);
            if (control) {
              control.setErrors({ server: fieldErr.message });
              control.markAsTouched();
              mappedAny = true;
            }
          }
          if (!mappedAny) {
            this.serverError.set(err.message);
          }
        } else {
          this.serverError.set(err.message);
        }
      },
    });
  }

  private loadStores(): void {
    this.loadingStores.set(true);
    this.storesError.set(null);
    this.transfersService.listStores(withoutErrorNotification()).subscribe({
      next: (items) => {
        const originId = this.activeStoreId();
        const filtered = items.filter((s) => s.id !== undefined && s.id !== originId);
        const editDest = this.data.transfer?.destination;
        if (
          this.isEdit &&
          editDest?.id !== undefined &&
          editDest.id !== originId &&
          !filtered.some((s) => s.id === editDest.id)
        ) {
          filtered.push({
            id: editDest.id,
            code: editDest.code,
            name: editDest.name,
            active: true,
          });
        }
        this.stores.set(filtered);
        this.loadingStores.set(false);
      },
      error: (err: unknown) => {
        this.storesError.set(
          isApiError(err) ? err.message : 'No se pudieron cargar los locales de destino.',
        );
        this.loadingStores.set(false);
      },
    });
  }

  private setupProductSearch(): void {
    this.productSearchControl.valueChanges
      .pipe(
        debounceTime(150),
        distinctUntilChanged(),
        switchMap((term) => {
          if (typeof term !== 'string') {
            return of([] as ProductSummary[]);
          }
          const trimmed = term.trim();
          if (!trimmed) {
            return of([] as ProductSummary[]);
          }
          this.searchingProducts.set(true);
          return this.transfersService
            .searchProducts(trimmed, withoutErrorNotification())
            .pipe(catchError(() => of([] as ProductSummary[])));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((items) => {
        this.searchingProducts.set(false);
        this.productOptions.set(items.filter((item) => item.saleType !== 'PACK'));
      });
  }

  private prefillDraftLines(lines: readonly StockTransferLineResponse[]): void {
    for (const line of lines) {
      if (line.productId === undefined) {
        continue;
      }
      const meta: DraftLineMeta = {
        productId: line.productId,
        sku: line.sku ?? `#${line.productId}`,
        name: line.name ?? `Producto #${line.productId}`,
        lotControlled: Boolean(line.lotNumber),
        unitOfMeasure: 'UNIT',
        loadingMeta: true,
        lots: [],
        loadingFefo: false,
        fefoSuggestion: null,
        fefoError: null,
      };
      const index = this.appendLine(meta, null, line.qtySent ?? 1);
      this.fetchProductMeta(index, line.productId, line.lotNumber, line.expiryDate);
    }
  }

  private appendLine(
    meta: DraftLineMeta,
    originLotId: number | null,
    qtySent: number | null,
  ): number {
    const index = this.linesArray.length;
    const group: TransferDraftLineGroup = this.fb.group({
      productId: [meta.productId],
      originLotId: [originLotId],
      qtySent: [
        qtySent,
        [Validators.required, (control) => this.validateQty(control, meta.productId)],
      ],
    });

    group.controls.originLotId.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        const currentIndex = this.linesArray.controls.indexOf(group);
        if (currentIndex >= 0) {
          this.patchLineMeta(currentIndex, { fefoSuggestion: null, fefoError: null });
        }
      });

    group.controls.qtySent.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      const currentIndex = this.linesArray.controls.indexOf(group);
      if (currentIndex >= 0) {
        this.patchLineMeta(currentIndex, { fefoSuggestion: null, fefoError: null });
      }
    });

    this.linesArray.push(group);
    this.lineMetas.update((list) => [...list, meta]);
    this.recomputeDuplicates();
    return index;
  }

  private fetchProductMeta(
    initialIndex: number,
    productId: number,
    preselectLotNumber?: string,
    preselectExpiryDate?: string,
  ): void {
    const group = this.linesArray.at(initialIndex);
    this.transfersService
      .getProduct(productId, withoutErrorNotification())
      .pipe(catchError(() => of(null as ProductResponse | null)))
      .subscribe((product) => {
        const currentIndex = this.linesArray.controls.indexOf(group);
        if (currentIndex < 0) {
          return;
        }
        if (!product) {
          this.patchLineMeta(currentIndex, { loadingMeta: false });
          return;
        }
        const lotControlled = Boolean(product.lotControlled);
        const unitOfMeasure: 'UNIT' | 'KG' = product.unitOfMeasure === 'KG' ? 'KG' : 'UNIT';
        this.patchLineMeta(currentIndex, {
          sku: product.sku ?? `#${productId}`,
          name: product.name ?? `Producto #${productId}`,
          lotControlled,
          unitOfMeasure,
          loadingMeta: lotControlled,
        });
        group.controls.qtySent.updateValueAndValidity({ emitEvent: false });

        if (!lotControlled) {
          group.controls.originLotId.setValue(null, { emitEvent: false });
          return;
        }

        this.transfersService
          .listProductLots(productId, withoutErrorNotification())
          .pipe(catchError(() => of([] as LotResponse[])))
          .subscribe((lots) => {
            const idx = this.linesArray.controls.indexOf(group);
            if (idx < 0) {
              return;
            }
            this.patchLineMeta(idx, { lots, loadingMeta: false });
            if (preselectLotNumber) {
              const matched =
                lots.find(
                  (lot) =>
                    lot.lotNumber === preselectLotNumber &&
                    (!preselectExpiryDate || lot.expiryDate === preselectExpiryDate),
                ) ?? lots.find((lot) => lot.lotNumber === preselectLotNumber);
              if (matched?.id !== undefined) {
                group.controls.originLotId.setValue(matched.id);
              }
            }
          });
      });
  }

  private patchLineMeta(index: number, patch: Partial<DraftLineMeta>): void {
    this.lineMetas.update((list) =>
      list.map((item, i) => (i === index ? { ...item, ...patch } : item)),
    );
  }

  private validateDestinationStore(
    control: AbstractControl<number | null>,
  ): ValidationErrors | null {
    const value = control.value;
    if (value === null || value === undefined) {
      return null;
    }
    const originId = this.activeStoreId();
    return originId !== null && value === originId ? { sameAsOrigin: true } : null;
  }

  private validateQty(
    control: AbstractControl<number | null>,
    productId: number,
  ): ValidationErrors | null {
    const raw = control.value;
    if (raw === null || raw === ('' as unknown)) {
      return null;
    }
    const num = Number(raw);
    if (!Number.isFinite(num) || num <= 0) {
      return { positiveRequired: true };
    }
    const group = control.parent as TransferDraftLineGroup | null;
    const index = group ? this.linesArray.controls.indexOf(group) : -1;
    const meta =
      index >= 0
        ? this.lineMetas()[index]
        : this.lineMetas().find((m) => m.productId === productId);
    if (meta?.unitOfMeasure === 'UNIT' && !Number.isInteger(num)) {
      return { integerRequired: true };
    }
    return null;
  }

  private validateNoDuplicateLines(control: AbstractControl): ValidationErrors | null {
    return this.computeDuplicateSet(control).size > 0 ? { duplicateLines: true } : null;
  }

  private recomputeDuplicates(): void {
    this.duplicateIndexes.set(this.computeDuplicateSet(this.linesArray));
  }

  private computeDuplicateSet(source: AbstractControl): ReadonlySet<number> {
    const firstIndexByKey = new Map<string, number>();
    const duplicates = new Set<number>();
    const controls = (source as { controls?: readonly TransferDraftLineGroup[] }).controls ?? [];

    controls.forEach((group, idx) => {
      const productId = group.controls.productId.value;
      const originLotId = group.controls.originLotId.value;
      const key = `${productId}:${originLotId ?? 'FEFO'}`;
      const existingIdx = firstIndexByKey.get(key);
      if (existingIdx !== undefined) {
        duplicates.add(existingIdx);
        duplicates.add(idx);
      } else {
        firstIndexByKey.set(key, idx);
      }
    });

    return duplicates;
  }
}
