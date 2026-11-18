import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import {
  MatAutocompleteModule,
  MatAutocompleteSelectedEvent,
} from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute } from '@angular/router';
import { Observable, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { StockMovementResponse } from '../../../api/models/stock-movement-response';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PageResponse } from '../../../shared/models/page-response';
import { QtyPipe, QtyUnit } from '../../../shared/pipes';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import {
  MOVEMENT_TYPE_OPTIONS,
  StockMovementTypeCode,
  formatKardexDateTime,
  formatMovementSource,
  formatUnitCost,
  movementTypeLabel,
} from './kardex.models';
import { KardexService } from './kardex.service';

/** Resumen mínimo de producto seleccionado para mostrar en la cabecera del kardex. */
interface SelectedProductInfo {
  readonly id: number;
  readonly sku: string;
  readonly name: string;
  readonly unit: QtyUnit;
}

/**
 * Pantalla de consulta de solo lectura del kardex de movimientos de stock (`HU-INV-02`).
 *
 * Exige seleccionar un producto (por búsqueda o parámetro `?productId=`) y permite filtrar por
 * rango de fechas, tipo de movimiento y lote en el local activo. No ofrece acciones de edición.
 */
@Component({
  selector: 'pos-kardex',
  imports: [
    MatAutocompleteModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    PaginatedTable,
    QtyPipe,
    ReactiveFormsModule,
  ],
  templateUrl: './kardex.html',
  styleUrl: './kardex.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Kardex implements OnInit, OnDestroy {
  private readonly kardexService = inject(KardexService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly typeTpl =
    viewChild.required<TemplateRef<CellContext<StockMovementResponse>>>('typeTpl');
  private readonly deltaTpl =
    viewChild.required<TemplateRef<CellContext<StockMovementResponse>>>('deltaTpl');
  private readonly balanceTpl =
    viewChild.required<TemplateRef<CellContext<StockMovementResponse>>>('balanceTpl');
  private readonly unitCostTpl =
    viewChild.required<TemplateRef<CellContext<StockMovementResponse>>>('unitCostTpl');

  protected readonly movementOptions = MOVEMENT_TYPE_OPTIONS;
  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );
  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));

  protected readonly productSearchControl = new FormControl<string | ProductSummary>('', {
    nonNullable: true,
  });
  protected readonly productOptions = signal<readonly ProductSummary[]>([]);
  protected readonly searchingProducts = signal(false);
  protected readonly selectedProduct = signal<SelectedProductInfo | null>(null);
  protected readonly selectedProductId = computed(() => this.selectedProduct()?.id ?? null);
  protected readonly selectedUnit = computed<QtyUnit>(() => this.selectedProduct()?.unit ?? 'u');

  protected readonly filterForm = this.fb.group({
    from: [''],
    to: [''],
    movementType: [null as StockMovementTypeCode | null],
    lotId: [null as number | null],
  });

  protected readonly dataSource = new PaginatedDataSource<StockMovementResponse>(
    (params) => {
      const productId = this.selectedProductId();
      if (productId === null) {
        return of({
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          sort: [],
        });
      }
      return this.kardexService.listMovements(
        {
          productId,
          from: params.get('from') ?? undefined,
          to: params.get('to') ?? undefined,
          movementType: (params.get('movementType') as StockMovementTypeCode | null) ?? undefined,
          lotId: this.parseNumberParam(params.get('lotId')),
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<StockMovementResponse>>;
    },
    { sort: [{ field: 'occurredAt', direction: 'desc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<StockMovementResponse>[]>(() => {
    const cols: TableColumn<StockMovementResponse>[] = [
      {
        key: 'occurredAt',
        header: 'Fecha y hora',
        sortField: 'occurredAt',
        cell: (row) => formatKardexDateTime(row.occurredAt),
      },
      {
        key: 'movementType',
        header: 'Tipo',
        sortField: 'movementType',
        template: this.typeTpl(),
      },
      {
        key: 'quantityDelta',
        header: 'Variación',
        sortField: 'quantityDelta',
        template: this.deltaTpl(),
        align: 'end',
      },
      {
        key: 'balanceAfter',
        header: 'Saldo',
        sortField: 'balanceAfter',
        template: this.balanceTpl(),
        align: 'end',
      },
    ];
    if (this.canReadCosts()) {
      cols.push({
        key: 'unitCost',
        header: 'Costo unitario',
        template: this.unitCostTpl(),
        align: 'end',
      });
    }
    cols.push(
      {
        key: 'lotNumber',
        header: 'Lote',
        cell: (row) => row.lotNumber || '—',
      },
      {
        key: 'source',
        header: 'Origen',
        cell: (row) => formatMovementSource(row.sourceType, row.sourceId),
      },
      {
        key: 'reason',
        header: 'Motivo',
        cell: (row) => row.reason || '—',
      },
      {
        key: 'username',
        header: 'Usuario',
        cell: (row) => row.username || '—',
      },
    );
    return cols;
  });

  private initialized = false;
  private lastLoadedStoreId: number | null = null;

  constructor() {
    effect(() => {
      const storeId = this.storeContext.storeId();
      if (this.initialized && storeId !== this.lastLoadedStoreId) {
        this.lastLoadedStoreId = storeId;
        if (this.selectedProductId() !== null) {
          this.dataSource.reload();
        }
      }
    });
  }

  ngOnInit(): void {
    this.initialized = true;
    this.lastLoadedStoreId = this.storeContext.storeId();
    this.setupProductSearch();
    this.listenQueryParams();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
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
    this.selectProductSummary(product);
  }

  protected clearProduct(): void {
    this.selectedProduct.set(null);
    this.productSearchControl.setValue('', { emitEvent: false });
    this.dataSource.setFilters({});
  }

  protected applyFilters(): void {
    if (this.selectedProductId() === null) {
      return;
    }
    const raw = this.filterForm.getRawValue();
    const filters: Filters = {
      from: raw.from ? this.toStartOfDayIso(raw.from) : null,
      to: raw.to ? this.toEndOfDayIso(raw.to) : null,
      movementType: raw.movementType,
      lotId: raw.lotId !== null && Number.isFinite(Number(raw.lotId)) ? Number(raw.lotId) : null,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ from: '', to: '', movementType: null, lotId: null });
    if (this.selectedProductId() !== null) {
      this.dataSource.setFilters({});
    }
  }

  protected typeLabel(type: StockMovementResponse['movementType']): string {
    return movementTypeLabel(type);
  }

  protected formatCost(value: number | undefined): string {
    return formatUnitCost(value);
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
          if (trimmed.length === 0) {
            return of([] as ProductSummary[]);
          }
          this.searchingProducts.set(true);
          return this.kardexService
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

  private listenQueryParams(): void {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((paramMap) => {
      const rawLotId = this.parseNumberParam(paramMap.get('lotId'));
      if (rawLotId !== undefined) {
        this.filterForm.controls.lotId.setValue(rawLotId);
      }

      const rawProductId = this.parseNumberParam(paramMap.get('productId'));
      if (rawProductId !== undefined && rawProductId !== this.selectedProductId()) {
        this.loadProductById(rawProductId);
      }
    });
  }

  private loadProductById(productId: number): void {
    this.selectedProduct.set({
      id: productId,
      sku: `#${productId}`,
      name: 'Cargando producto...',
      unit: 'u',
    });
    this.applyFilters();

    this.kardexService
      .getProduct(productId, withoutErrorNotification())
      .pipe(catchError(() => of(null)))
      .subscribe((product: ProductResponse | null) => {
        if (!product || product.id !== productId) {
          return;
        }
        const info: SelectedProductInfo = {
          id: productId,
          sku: product.sku ?? `#${productId}`,
          name: product.name ?? `Producto #${productId}`,
          unit: product.unitOfMeasure === 'KG' ? 'kg' : 'u',
        };
        this.selectedProduct.set(info);
        this.productSearchControl.setValue(`${info.sku} — ${info.name}`, { emitEvent: false });
      });
  }

  private selectProductSummary(product: ProductSummary): void {
    if (product.id === undefined) {
      return;
    }
    this.selectedProduct.set({
      id: product.id,
      sku: product.sku ?? `#${product.id}`,
      name: product.name ?? '',
      unit: product.saleType === 'WEIGHT' ? 'kg' : 'u',
    });
    this.applyFilters();
  }

  private toStartOfDayIso(dateStr: string): string {
    return new Date(`${dateStr}T00:00:00`).toISOString();
  }

  private toEndOfDayIso(dateStr: string): string {
    return new Date(`${dateStr}T23:59:59.999`).toISOString();
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
  }
}
