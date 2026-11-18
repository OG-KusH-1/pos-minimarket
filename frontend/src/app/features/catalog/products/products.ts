import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
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
import { Router, RouterLink } from '@angular/router';
import { Observable, filter, switchMap } from 'rxjs';
import { BrandResponse } from '../../../api/models/brand-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { PageResponse } from '../../../shared/models/page-response';
import { ClpPipe } from '../../../shared/pipes';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { ProductPriceDialog } from './product-price-dialog';
import {
  CategoryOption,
  SALE_TYPE_LABELS,
  SaleType,
  flattenCategories,
  formatMarginPct,
  formatUnitCost,
} from './product-validators';
import { ProductsService } from './products.service';

/**
 * Pantalla de listado y búsqueda del catálogo de productos (`HU-CAT-01`, `HU-CAT-04`, `HU-CAT-08`).
 *
 * Muestra los productos paginados con filtros por texto (nombre, SKU, código o atajo),
 * categoría, marca, tipo de venta, estado, restricción de edad y control por lotes. Las
 * columnas de costo promedio y margen solo se muestran cuando el usuario posee
 * `catalog:cost:read`.
 */
@Component({
  selector: 'pos-products',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    PaginatedTable,
    ReactiveFormsModule,
    RouterLink,
  ],
  templateUrl: './products.html',
  styleUrl: './products.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Products implements OnInit, OnDestroy {
  private readonly productsService = inject(ProductsService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly clpPipe = new ClpPipe();

  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<ProductSummary>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<ProductSummary>>>('actionsTpl');

  protected readonly categories = signal<readonly CategoryOption[]>([]);
  protected readonly brands = signal<readonly BrandResponse[]>([]);

  protected readonly canCreate = computed(() => this.auth.hasPermission('catalog:product:create'));
  protected readonly canUpdate = computed(() => this.auth.hasPermission('catalog:product:update'));
  protected readonly canDeactivate = computed(() =>
    this.auth.hasPermission('catalog:product:deactivate'),
  );
  protected readonly canUpdatePrice = computed(() =>
    this.auth.hasPermission('catalog:product:update-price'),
  );
  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));

  protected readonly filterForm = this.fb.group({
    q: [''],
    categoryId: [null as number | null],
    brandId: [null as number | null],
    saleType: [null as SaleType | null],
    active: [null as boolean | null],
    restricted: [null as boolean | null],
    lotControlled: [null as boolean | null],
  });

  protected readonly dataSource = new PaginatedDataSource<ProductSummary>(
    (params) =>
      this.productsService.listProducts(
        {
          q: params.get('q') ?? undefined,
          categoryId: this.parseNumberParam(params.get('categoryId')),
          brandId: this.parseNumberParam(params.get('brandId')),
          saleType: this.parseSaleTypeParam(params.get('saleType')),
          active: this.parseBooleanParam(params.get('active')),
          restricted: this.parseBooleanParam(params.get('restricted')),
          lotControlled: this.parseBooleanParam(params.get('lotControlled')),
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<ProductSummary>>,
    { sort: [{ field: 'name', direction: 'asc' }] },
  );

  /** Columnas visibles según permisos; `avgCost` y `marginPct` se omiten sin `catalog:cost:read`. */
  protected readonly columns = computed<readonly TableColumn<ProductSummary>[]>(() => {
    const cols: TableColumn<ProductSummary>[] = [
      { key: 'sku', header: 'SKU', sortField: 'sku' },
      { key: 'name', header: 'Producto', sortField: 'name' },
      {
        key: 'categoryName',
        header: 'Categoría',
        cell: (row) => row.categoryName || '—',
      },
      {
        key: 'brandName',
        header: 'Marca',
        cell: (row) => row.brandName || '—',
      },
      {
        key: 'saleType',
        header: 'Tipo',
        cell: (row) => (row.saleType ? SALE_TYPE_LABELS[row.saleType] : '—'),
      },
      {
        key: 'primaryBarcode',
        header: 'Código principal',
        cell: (row) => row.primaryBarcode || '—',
      },
      {
        key: 'priceGross',
        header: 'Precio venta',
        sortField: 'priceGross',
        align: 'end',
        cell: (row) => this.clpPipe.transform(row.priceGross) || '$0',
      },
    ];

    if (this.canReadCosts()) {
      cols.push(
        {
          key: 'avgCost',
          header: 'Costo prom.',
          align: 'end',
          cell: (row) => formatUnitCost(row.avgCost),
        },
        {
          key: 'marginPct',
          header: 'Margen',
          align: 'end',
          cell: (row) => formatMarginPct(row.marginPct),
        },
      );
    }

    cols.push(
      { key: 'active', header: 'Estado', sortField: 'active', template: this.statusTpl() },
      { key: 'actions', header: 'Acciones', template: this.actionsTpl(), align: 'end' },
    );

    return cols;
  });

  ngOnInit(): void {
    this.loadFilterCatalogs();
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
      brandId: raw.brandId,
      saleType: raw.saleType,
      active: raw.active,
      restricted: raw.restricted,
      lotControlled: raw.lotControlled,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({
      q: '',
      categoryId: null,
      brandId: null,
      saleType: null,
      active: null,
      restricted: null,
      lotControlled: null,
    });
    this.dataSource.setFilters({});
  }

  protected openDetail(row: ProductSummary): void {
    if (row.id === undefined) {
      return;
    }
    void this.router.navigate(['/catalogo/productos', row.id]);
  }

  protected openChangePrice(row: ProductSummary): void {
    if (row.id === undefined || !this.canUpdatePrice()) {
      return;
    }
    this.productsService.getProduct(row.id).subscribe((product) => {
      const ref = this.dialog.open(ProductPriceDialog, {
        data: {
          productId: product.id ?? row.id ?? 0,
          productName: product.name ?? row.name ?? '',
          currentPriceGross: product.priceGross ?? row.priceGross ?? 0,
          version: product.version ?? 0,
        },
        width: '460px',
      });
      ref.afterClosed().subscribe((updated) => {
        if (updated) {
          this.snackBar.open('Precio de venta actualizado.', 'Cerrar', { duration: 4000 });
          this.dataSource.reload();
        }
      });
    });
  }

  protected toggleActive(row: ProductSummary): void {
    if (row.id === undefined || !this.canDeactivate()) {
      return;
    }
    const productId = row.id;
    const isCurrentlyActive = row.active !== false;
    this.confirmDialog
      .confirm({
        title: isCurrentlyActive ? 'Desactivar producto' : 'Activar producto',
        message: isCurrentlyActive
          ? `¿Desactivar "${row.name}" (${row.sku})? No podrá venderse en el POS mientras esté inactivo.`
          : `¿Reactivar "${row.name}" (${row.sku})?`,
        confirmLabel: isCurrentlyActive ? 'Desactivar' : 'Activar',
        destructive: isCurrentlyActive,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          isCurrentlyActive
            ? this.productsService.deactivateProduct(productId)
            : this.productsService.activateProduct(productId),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(
          isCurrentlyActive ? 'Producto desactivado.' : 'Producto reactivado.',
          'Cerrar',
          { duration: 4000 },
        );
        this.dataSource.reload();
      });
  }

  private loadFilterCatalogs(): void {
    this.productsService.listCategories(true, withoutErrorNotification()).subscribe({
      next: (tree) => this.categories.set(flattenCategories(tree ?? [])),
      error: () => this.categories.set([]),
    });
    this.productsService
      .listBrands({ size: 200, sort: ['name,asc'] }, withoutErrorNotification())
      .subscribe({
        next: (page) => this.brands.set(page.content ?? []),
        error: () => this.brands.set([]),
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

  private parseSaleTypeParam(raw: string | null): SaleType | undefined {
    if (raw === 'UNIT' || raw === 'WEIGHT' || raw === 'PACK') {
      return raw;
    }
    return undefined;
  }
}
