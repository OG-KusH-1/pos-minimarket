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
import {
  FormControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import {
  MatAutocompleteModule,
  MatAutocompleteSelectedEvent,
} from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { LotResponse } from '../../../api/models/lot-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PageResponse } from '../../../shared/models/page-response';
import { QtyPipe } from '../../../shared/pipes';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { ExpiryAlertsTab } from './expiry-alerts-tab';
import { FefoSimulatorTab } from './fefo-simulator-tab';
import { LotEditDialog, LotEditDialogResult } from './lot-edit-dialog';
import {
  LOT_STATUS_OPTIONS,
  LotStatusCode,
  formatDaysToExpiry,
  formatLotDateTime,
  formatLotLocalDate,
  lotStatusLabel,
  toLotQtyUnit,
} from './lots.models';
import { LotsService } from './lots.service';

/**
 * Pantalla de gestión de lotes, alertas de vencimiento y consulta FEFO
 * (`/inventario/lotes`, `HU-INV-08`, `HU-INV-09`).
 *
 * Agrupa en pestañas:
 * 1. Listado paginado de lotes (`GET /api/v1/lots`) con edición optimista (`PUT /api/v1/lots/{id}`).
 * 2. Alertas de vencimiento (`GET /api/v1/expiry-alerts`, valorización solo con `catalog:cost:read`).
 * 3. Simulador de salida FEFO (`GET /api/v1/lots/fefo`).
 */
@Component({
  selector: 'pos-lots',
  imports: [
    ExpiryAlertsTab,
    FefoSimulatorTab,
    MatAutocompleteModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    MatTabsModule,
    PaginatedTable,
    QtyPipe,
    ReactiveFormsModule,
  ],
  templateUrl: './lots.html',
  styleUrl: './lots.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Lots implements OnInit, OnDestroy {
  private readonly lotsService = inject(LotsService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly alertsTab = viewChild(ExpiryAlertsTab);
  private readonly expiryTpl =
    viewChild.required<TemplateRef<CellContext<LotResponse>>>('expiryTpl');
  private readonly daysTpl = viewChild.required<TemplateRef<CellContext<LotResponse>>>('daysTpl');
  private readonly qtyTpl = viewChild.required<TemplateRef<CellContext<LotResponse>>>('qtyTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<LotResponse>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<LotResponse>>>('actionsTpl');

  protected readonly statusOptions = LOT_STATUS_OPTIONS;
  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );

  protected readonly canReadStock = computed(() => this.auth.hasPermission('inventory:stock:read'));
  protected readonly canReadExpiry = computed(() =>
    this.auth.hasPermission('inventory:expiry:read'),
  );
  protected readonly canUpdateLots = computed(() =>
    this.auth.hasPermission('inventory:lot:update'),
  );
  protected readonly canReadKardex = computed(() =>
    this.auth.hasPermission('inventory:kardex:read'),
  );

  protected readonly selectedTabIndex = signal(0);
  protected readonly productSearchControl = new FormControl<string | ProductSummary>('', {
    nonNullable: true,
  });
  protected readonly productOptions = signal<readonly ProductSummary[]>([]);

  protected readonly filterForm = this.fb.group({
    q: [''],
    productId: [null as number | null],
    status: [null as LotStatusCode | null],
    withStock: [null as boolean | null],
    expiringWithinDays: [null as number | null, [Validators.min(0)]],
  });

  protected readonly dataSource = new PaginatedDataSource<LotResponse>(
    (params) =>
      this.lotsService.listLots(
        {
          q: params.get('q') ?? undefined,
          productId: this.parseNumberParam(params.get('productId')),
          status: params.get('status') ?? undefined,
          withStock: this.parseBooleanParam(params.get('withStock')),
          expiringWithinDays: this.parseNumberParam(params.get('expiringWithinDays')),
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<LotResponse>>,
    { sort: [{ field: 'expiryDate', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<LotResponse>[]>(() => {
    const cols: TableColumn<LotResponse>[] = [
      { key: 'sku', header: 'SKU', sortField: 'sku' },
      { key: 'name', header: 'Producto', sortField: 'name' },
      { key: 'lotNumber', header: 'Lote', sortField: 'lotNumber' },
      {
        key: 'expiryDate',
        header: 'Vencimiento',
        sortField: 'expiryDate',
        template: this.expiryTpl(),
      },
      { key: 'daysToExpiry', header: 'Vigencia', template: this.daysTpl() },
      {
        key: 'quantity',
        header: 'Stock',
        sortField: 'quantity',
        template: this.qtyTpl(),
        align: 'end',
      },
      {
        key: 'receivedAt',
        header: 'Recepción',
        sortField: 'receivedAt',
        cell: (row) => formatLotDateTime(row.receivedAt),
      },
      { key: 'status', header: 'Estado', sortField: 'status', template: this.statusTpl() },
    ];
    if (this.canUpdateLots() || this.canReadKardex()) {
      cols.push({ key: 'actions', header: 'Acciones', template: this.actionsTpl(), align: 'end' });
    }
    return cols;
  });

  private initialized = false;
  private lastLoadedStoreId: number | null = null;

  constructor() {
    effect(() => {
      const storeId = this.storeContext.storeId();
      if (this.initialized && storeId !== this.lastLoadedStoreId) {
        this.lastLoadedStoreId = storeId;
        if (this.canReadStock()) {
          this.dataSource.reload();
        }
      }
    });
  }

  ngOnInit(): void {
    this.initialized = true;
    this.lastLoadedStoreId = this.storeContext.storeId();
    this.setupProductSearch();
    this.syncStatusAndWithStock();
    this.listenQueryParams();
    if (this.canReadStock()) {
      this.dataSource.reload();
    }
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
    const prod = event.option.value as ProductSummary | null;
    if (!prod || prod.id === undefined) {
      return;
    }
    this.filterForm.controls.productId.setValue(prod.id);
    this.applyFilters();
  }

  protected clearProductFilter(): void {
    this.filterForm.controls.productId.setValue(null);
    this.productSearchControl.setValue('', { emitEvent: false });
    this.applyFilters();
  }

  protected applyFilters(): void {
    if (this.filterForm.invalid) {
      this.filterForm.markAllAsTouched();
      return;
    }
    const raw = this.filterForm.getRawValue();
    const filters: Filters = {
      q: raw.q.trim() || null,
      productId: raw.productId,
      status: raw.status,
      withStock: raw.withStock,
      expiringWithinDays:
        raw.expiringWithinDays !== null && raw.expiringWithinDays !== ('' as unknown)
          ? Math.trunc(Number(raw.expiringWithinDays))
          : null,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.productSearchControl.setValue('', { emitEvent: false });
    this.filterForm.reset({
      q: '',
      productId: null,
      status: null,
      withStock: null,
      expiringWithinDays: null,
    });
    this.dataSource.setFilters({});
  }

  protected openEditLot(lot: LotResponse): void {
    if (!this.canUpdateLots() || lot.id === undefined) {
      return;
    }
    const ref = this.dialog.open<LotEditDialog, unknown, LotEditDialogResult>(LotEditDialog, {
      data: { lot },
      width: '480px',
    });
    ref.afterClosed().subscribe((result) => {
      if (!result) {
        return;
      }
      if ('updated' in result) {
        this.snackBar.open(
          `Lote ${result.updated.lotNumber ?? ''} actualizado correctamente.`,
          'Cerrar',
          { duration: 4000 },
        );
      }
      this.dataSource.reload();
      this.alertsTab()?.reload();
    });
  }

  protected goToKardex(productId: number | undefined, lotId?: number): void {
    if (productId === undefined) {
      return;
    }
    void this.router.navigate(['/inventario/kardex'], {
      queryParams: {
        productId,
        ...(lotId !== undefined ? { lotId } : {}),
      },
    });
  }

  protected unitFor(lot: LotResponse) {
    return toLotQtyUnit(lot.unitOfMeasure);
  }

  protected formatLocalDate(iso: string | undefined): string {
    return formatLotLocalDate(iso);
  }

  protected formatDays(days: number | undefined): string {
    return formatDaysToExpiry(days);
  }

  protected statusLabel(status: string | undefined): string {
    return lotStatusLabel(status);
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
          return this.lotsService
            .searchProducts(trimmed, withoutErrorNotification())
            .pipe(catchError(() => of([] as ProductSummary[])));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((items) => this.productOptions.set(items));
  }

  private syncStatusAndWithStock(): void {
    this.filterForm.controls.status.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((status) => {
        const withStock = this.filterForm.controls.withStock.value;
        if (
          (status === 'ACTIVE' && withStock === false) ||
          (status === 'DEPLETED' && withStock === true)
        ) {
          this.filterForm.controls.withStock.setValue(null, { emitEvent: false });
        }
      });

    this.filterForm.controls.withStock.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((withStock) => {
        const status = this.filterForm.controls.status.value;
        if (
          (withStock === true && status === 'DEPLETED') ||
          (withStock === false && status === 'ACTIVE')
        ) {
          this.filterForm.controls.status.setValue(null, { emitEvent: false });
        }
      });
  }

  private listenQueryParams(): void {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((paramMap) => {
      const tab = paramMap.get('tab');
      if (tab === 'alerts' && this.canReadExpiry()) {
        this.selectedTabIndex.set(this.canReadStock() ? 1 : 0);
      } else if (tab === 'fefo' && this.canReadStock()) {
        this.selectedTabIndex.set(this.canReadExpiry() ? 2 : 1);
      } else if (tab === 'lots' && this.canReadStock()) {
        this.selectedTabIndex.set(0);
      }
    });
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
