import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { Observable } from 'rxjs';
import { CategoryResponse } from '../../../api/models/category-response';
import { StockResponse } from '../../../api/models/stock-response';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PageResponse } from '../../../shared/models/page-response';
import { ClpPipe, QtyPipe } from '../../../shared/pipes';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { StockAlerts } from './stock-alerts';
import { StockByStoreDialog } from './stock-by-store-dialog';
import { StockLimitsDialog } from './stock-limits-dialog';
import {
  STOCK_STATUS_OPTIONS,
  StockStatusCode,
  formatDateTime,
  stockStatusLabel,
  toQtyUnit,
} from './stock.models';
import { StockService } from './stock.service';

/** Opción plana de categoría para el selector de filtro. */
interface CategoryOption {
  readonly id: number;
  readonly label: string;
}

/**
 * Pantalla de existencias por local, alertas de quiebre/vencimiento y edición de límites
 * mínimo/máximo (`HU-INV-01`, `HU-INV-04`).
 */
@Component({
  selector: 'pos-stock',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    PaginatedTable,
    QtyPipe,
    ReactiveFormsModule,
    StockAlerts,
  ],
  templateUrl: './stock.html',
  styleUrl: './stock.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Stock implements OnInit, OnDestroy {
  private readonly stockService = inject(StockService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly alertsPanel = viewChild(StockAlerts);
  private readonly quantityTpl =
    viewChild.required<TemplateRef<CellContext<StockResponse>>>('quantityTpl');
  private readonly limitsTpl =
    viewChild.required<TemplateRef<CellContext<StockResponse>>>('limitsTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<StockResponse>>>('statusTpl');
  private readonly valuationTpl =
    viewChild.required<TemplateRef<CellContext<StockResponse>>>('valuationTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<StockResponse>>>('actionsTpl');

  protected readonly statusOptions = STOCK_STATUS_OPTIONS;
  protected readonly categories = signal<readonly CategoryOption[]>([]);
  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );

  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));
  protected readonly canUpdateLimits = computed(() =>
    this.auth.hasPermission('inventory:stock-limits:update'),
  );
  protected readonly canReadKardex = computed(() =>
    this.auth.hasPermission('inventory:kardex:read'),
  );

  protected readonly filterForm = this.fb.group({
    q: [''],
    categoryId: [null as number | null],
    status: [null as StockStatusCode | null],
    lotControlled: [null as boolean | null],
  });

  protected readonly dataSource = new PaginatedDataSource<StockResponse>(
    (params) =>
      this.stockService.listStock(
        {
          q: params.get('q') ?? undefined,
          categoryId: this.parseNumberParam(params.get('categoryId')),
          status: (params.get('status') as StockStatusCode | null) ?? undefined,
          lotControlled: this.parseBooleanParam(params.get('lotControlled')),
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<StockResponse>>,
    { sort: [{ field: 'name', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<StockResponse>[]>(() => {
    const cols: TableColumn<StockResponse>[] = [
      { key: 'sku', header: 'SKU', sortField: 'sku' },
      { key: 'name', header: 'Producto', sortField: 'name' },
      { key: 'categoryName', header: 'Categoría' },
      {
        key: 'quantity',
        header: 'Stock',
        sortField: 'quantity',
        template: this.quantityTpl(),
        align: 'end',
      },
      { key: 'limits', header: 'Mín / Máx', template: this.limitsTpl(), align: 'end' },
      { key: 'status', header: 'Estado', template: this.statusTpl() },
    ];
    if (this.canReadCosts()) {
      cols.push({
        key: 'valuation',
        header: 'Valorización',
        template: this.valuationTpl(),
        align: 'end',
      });
    }
    cols.push(
      {
        key: 'lastMovementAt',
        header: 'Último movimiento',
        sortField: 'lastMovementAt',
        cell: (row) => formatDateTime(row.lastMovementAt),
      },
      { key: 'actions', header: 'Acciones', template: this.actionsTpl(), align: 'end' },
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
        this.dataSource.reload();
      }
    });
  }

  ngOnInit(): void {
    this.initialized = true;
    this.lastLoadedStoreId = this.storeContext.storeId();
    this.loadCategories();
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    const filters: Filters = {
      q: raw.q.trim() || null,
      categoryId: raw.categoryId,
      status: raw.status,
      lotControlled: raw.lotControlled,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ q: '', categoryId: null, status: null, lotControlled: null });
    this.dataSource.setFilters({});
  }

  protected filterByAlertStatus(status: 'BELOW_MIN' | 'ZERO'): void {
    this.filterForm.controls.status.setValue(status);
    this.applyFilters();
  }

  protected openByStore(item: StockResponse): void {
    if (item.productId === undefined) {
      return;
    }
    this.dialog.open(StockByStoreDialog, {
      data: { item },
      width: '560px',
    });
  }

  protected openEditLimits(item: StockResponse): void {
    if (!this.canUpdateLimits() || item.productId === undefined) {
      return;
    }
    const ref = this.dialog.open<StockLimitsDialog, unknown, StockResponse>(StockLimitsDialog, {
      data: { item },
      width: '460px',
    });
    ref.afterClosed().subscribe((updated) => {
      if (updated) {
        this.snackBar.open('Límites de stock actualizados.', 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
        this.alertsPanel()?.reload();
      }
    });
  }

  protected goToKardex(target: number | StockResponse | undefined): void {
    const productId = typeof target === 'number' ? target : target?.productId;
    if (productId === undefined) {
      return;
    }
    void this.router.navigate(['/inventario/kardex'], { queryParams: { productId } });
  }

  protected unitFor(item: StockResponse) {
    return toQtyUnit(item.unitOfMeasure);
  }

  protected statusLabel(status: StockResponse['status']): string {
    return stockStatusLabel(status);
  }

  private loadCategories(): void {
    this.stockService.listCategories(withoutErrorNotification()).subscribe({
      next: (tree) => this.categories.set(this.flattenCategories(tree ?? [])),
      error: () => this.categories.set([]),
    });
  }

  private flattenCategories(tree: readonly CategoryResponse[]): CategoryOption[] {
    const options: CategoryOption[] = [];
    for (const root of tree) {
      if (root.id !== undefined && root.name) {
        options.push({ id: root.id, label: root.name });
      }
      for (const child of root.children ?? []) {
        if (child.id !== undefined && child.name) {
          options.push({
            id: child.id,
            label: root.name ? `${root.name} / ${child.name}` : child.name,
          });
        }
      }
    }
    return options;
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }

  private parseBooleanParam(raw: string | null): boolean | undefined {
    if (raw === 'true') {
      return true;
    }
    if (raw === 'false') {
      return false;
    }
    return undefined;
  }
}
