import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  inject,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { filter, switchMap } from 'rxjs';
import { ProductSupplierResponse } from '../../../api/models/product-supplier-response';
import { SupplierSummary } from '../../../api/models/supplier-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { PageResponse } from '../../../shared/models/page-response';
import { ClpPipe, QtyPipe, RutPipe } from '../../../shared/pipes';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import {
  ProductSupplierFormDialog,
  ProductSupplierFormDialogData,
} from './product-supplier-form-dialog';
import { formatUnitCostNet } from './supplier-validators';
import { SuppliersService } from './suppliers.service';

/** Datos de entrada para {@link SupplierProductsDialog}. */
export interface SupplierProductsDialogData {
  readonly supplier: SupplierSummary;
}

/**
 * Diálogo de gestión de asociaciones producto-proveedor para un proveedor (`HU-PRV-02`).
 *
 * Lista los productos asociados con paginación y filtros, permite asociar nuevos productos
 * (rechazando `PACK`), editar costos/unidades de compra, marcar proveedor preferido único
 * y activar/desactivar vínculos. Oculta columnas de costo si falta `catalog:cost:read`.
 */
@Component({
  selector: 'pos-supplier-products-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    PaginatedTable,
    ReactiveFormsModule,
    RutPipe,
  ],
  templateUrl: './supplier-products-dialog.html',
  styleUrl: './supplier-products-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupplierProductsDialog implements OnInit, OnDestroy {
  private readonly suppliersService = inject(SuppliersService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly clpPipe = new ClpPipe();
  private readonly qtyPipe = new QtyPipe();
  protected readonly data = inject<SupplierProductsDialogData>(MAT_DIALOG_DATA);

  private readonly preferredTpl =
    viewChild.required<TemplateRef<CellContext<ProductSupplierResponse>>>('preferredTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<ProductSupplierResponse>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<ProductSupplierResponse>>>('actionsTpl');

  protected readonly canManage = computed(() =>
    this.auth.hasPermission('purchasing:product-supplier:manage'),
  );
  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));

  protected readonly filterForm = this.fb.group({
    q: [''],
    active: [null as boolean | null],
  });

  protected readonly dataSource = new PaginatedDataSource<ProductSupplierResponse>(
    (params) => {
      const supplierId = this.data.supplier.id ?? 0;
      const activeParam = params.get('active');
      const active = activeParam === 'true' ? true : activeParam === 'false' ? false : undefined;
      return this.suppliersService.listSupplierProducts(
        {
          id: supplierId,
          q: params.get('q') ?? undefined,
          active,
          page: Number(params.get('page') ?? 0),
          size: Number(params.get('size') ?? 20),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as ReturnType<typeof this.suppliersService.listSupplierProducts> &
        import('rxjs').Observable<PageResponse<ProductSupplierResponse>>;
    },
    { sort: [{ field: 'productName', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<ProductSupplierResponse>[]>(() => {
    const base: TableColumn<ProductSupplierResponse>[] = [
      { key: 'productSku', header: 'SKU', cell: (r) => r.productSku ?? '—' },
      {
        key: 'productName',
        header: 'Producto',
        sortField: 'productName',
        cell: (r) => r.productName ?? '—',
      },
      { key: 'supplierSku', header: 'Cód. proveedor', cell: (r) => r.supplierSku || '—' },
      {
        key: 'purchaseUnitName',
        header: 'Unidad compra',
        cell: (r) =>
          r.purchaseUnitName
            ? `${r.purchaseUnitName} (×${this.qtyPipe.transform(r.unitsPerPurchaseUnit ?? 1)})`
            : '—',
      },
    ];

    if (this.canReadCosts()) {
      base.push(
        {
          key: 'costPerPurchaseUnit',
          header: 'Costo compra',
          align: 'end',
          cell: (r) =>
            r.costPerPurchaseUnit !== undefined && r.costPerPurchaseUnit !== null
              ? this.clpPipe.transform(r.costPerPurchaseUnit)
              : '—',
        },
        {
          key: 'unitCostNet',
          header: 'Costo unit. neto',
          align: 'end',
          cell: (r) => formatUnitCostNet(r.unitCostNet),
        },
      );
    }

    base.push(
      {
        key: 'minOrderQty',
        header: 'Pedido mín.',
        align: 'end',
        cell: (r) => this.qtyPipe.transform(r.minOrderQty ?? 1),
      },
      { key: 'isPreferred', header: 'Preferido', template: this.preferredTpl() },
      { key: 'active', header: 'Estado', template: this.statusTpl() },
    );

    if (this.canManage()) {
      base.push({ key: 'actions', header: 'Acciones', template: this.actionsTpl() });
    }

    return base;
  });

  ngOnInit(): void {
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    const filters: Filters = {
      q: raw.q.trim() || null,
      active: raw.active,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ q: '', active: null });
    this.dataSource.setFilters({});
  }

  protected openCreate(): void {
    const supplierId = this.data.supplier.id;
    if (!supplierId) {
      return;
    }
    this.openForm({
      supplierId,
      supplierName: this.data.supplier.legalName ?? '',
    });
  }

  protected openEdit(row: ProductSupplierResponse): void {
    const supplierId = this.data.supplier.id;
    if (!supplierId) {
      return;
    }
    this.openForm({
      supplierId,
      supplierName: this.data.supplier.legalName ?? '',
      existing: row,
    });
  }

  protected markAsPreferred(row: ProductSupplierResponse): void {
    if (!row.id) {
      return;
    }
    this.suppliersService.markPreferredProductSupplier(row.id).subscribe(() => {
      this.snackBar.open('Proveedor marcado como preferido para el producto.', 'Cerrar', {
        duration: 3000,
      });
      this.dataSource.reload();
    });
  }

  protected toggleActive(row: ProductSupplierResponse): void {
    if (!row.id) {
      return;
    }
    const willActivate = !row.active;
    this.confirmDialog
      .confirm({
        title: willActivate ? 'Activar asociación' : 'Desactivar asociación',
        message: willActivate
          ? `¿Deseas reactivar el vínculo con "${row.productName}"?`
          : `¿Deseas desactivar el vínculo con "${row.productName}"?`,
        confirmLabel: willActivate ? 'Activar' : 'Desactivar',
        destructive: !willActivate,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          willActivate
            ? this.suppliersService.activateProductSupplier(row.id!)
            : this.suppliersService.deactivateProductSupplier(row.id!),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(
          willActivate ? 'Asociación activada.' : 'Asociación desactivada.',
          'Cerrar',
          { duration: 3000 },
        );
        this.dataSource.reload();
      });
  }

  private openForm(dialogData: ProductSupplierFormDialogData): void {
    this.dialog
      .open<
        ProductSupplierFormDialog,
        ProductSupplierFormDialogData,
        ProductSupplierResponse | null
      >(ProductSupplierFormDialog, {
        data: dialogData,
        width: '640px',
        maxWidth: '95vw',
      })
      .afterClosed()
      .pipe(filter((saved): saved is ProductSupplierResponse => Boolean(saved)))
      .subscribe(() => {
        this.snackBar.open(
          dialogData.existing ? 'Asociación actualizada.' : 'Producto asociado al proveedor.',
          'Cerrar',
          { duration: 3000 },
        );
        this.dataSource.reload();
      });
  }
}
