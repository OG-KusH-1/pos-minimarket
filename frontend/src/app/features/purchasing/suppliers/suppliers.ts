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
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, filter, switchMap } from 'rxjs';
import { SupplierResponse } from '../../../api/models/supplier-response';
import { SupplierSummary } from '../../../api/models/supplier-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { PageResponse } from '../../../shared/models/page-response';
import { RutPipe } from '../../../shared/pipes';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { SupplierFormDialog, SupplierFormDialogData } from './supplier-form-dialog';
import { SupplierProductsDialog, SupplierProductsDialogData } from './supplier-products-dialog';
import { SuppliersService } from './suppliers.service';

/**
 * Pantalla de gestión de proveedores y sus asociaciones producto-proveedor (`HU-PRV-01`, `HU-PRV-02`).
 *
 * Muestra la bandeja paginada de proveedores (`/compras/proveedores`), permite filtrar por texto
 * (RUT, razón social o nombre de fantasía) y estado, crear/editar proveedores con validación de
 * RUT y control optimista, activar/desactivar y abrir la gestión de productos por proveedor.
 */
@Component({
  selector: 'pos-suppliers',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './suppliers.html',
  styleUrl: './suppliers.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Suppliers implements OnInit, OnDestroy {
  private readonly suppliersService = inject(SuppliersService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly rutPipe = new RutPipe();

  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<SupplierSummary>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<SupplierSummary>>>('actionsTpl');

  protected readonly canManageSuppliers = computed(() =>
    this.auth.hasPermission('purchasing:supplier:manage'),
  );

  protected readonly filterForm = this.fb.group({
    q: [''],
    active: [null as boolean | null],
  });

  protected readonly dataSource = new PaginatedDataSource<SupplierSummary>(
    (params) => {
      const activeParam = params.get('active');
      const active = activeParam === 'true' ? true : activeParam === 'false' ? false : undefined;
      return this.suppliersService.listSuppliers(
        {
          q: params.get('q') ?? undefined,
          active,
          page: Number(params.get('page') ?? 0),
          size: Number(params.get('size') ?? 20),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<SupplierSummary>>;
    },
    { sort: [{ field: 'legalName', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<SupplierSummary>[]>(() => [
    {
      key: 'rut',
      header: 'RUT',
      sortField: 'rut',
      cell: (row) => (row.rut ? this.rutPipe.transform(row.rut) : '—'),
    },
    {
      key: 'legalName',
      header: 'Razón social',
      sortField: 'legalName',
      cell: (row) => row.legalName ?? '—',
    },
    {
      key: 'tradeName',
      header: 'Nombre de fantasía',
      sortField: 'tradeName',
      cell: (row) => row.tradeName || '—',
    },
    {
      key: 'leadTimeDays',
      header: 'Lead time',
      align: 'end',
      sortField: 'leadTimeDays',
      cell: (row) => `${row.leadTimeDays ?? 0} d`,
    },
    {
      key: 'active',
      header: 'Estado',
      template: this.statusTpl(),
    },
    {
      key: 'actions',
      header: 'Acciones',
      template: this.actionsTpl(),
    },
  ]);

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
    this.openSupplierForm(null);
  }

  protected openEdit(row: SupplierSummary): void {
    if (!row.id) {
      return;
    }
    this.openSupplierForm(row.id);
  }

  protected openProducts(row: SupplierSummary): void {
    if (!row.id) {
      return;
    }
    this.dialog.open<SupplierProductsDialog, SupplierProductsDialogData>(SupplierProductsDialog, {
      data: { supplier: row },
      width: '1040px',
      maxWidth: '96vw',
    });
  }

  protected toggleActive(row: SupplierSummary): void {
    if (!row.id) {
      return;
    }
    const willActivate = !row.active;
    this.confirmDialog
      .confirm({
        title: willActivate ? 'Activar proveedor' : 'Desactivar proveedor',
        message: willActivate
          ? `¿Deseas reactivar al proveedor "${row.legalName}"?`
          : `¿Deseas desactivar al proveedor "${row.legalName}"?`,
        confirmLabel: willActivate ? 'Activar' : 'Desactivar',
        destructive: !willActivate,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          willActivate
            ? this.suppliersService.activateSupplier(row.id!)
            : this.suppliersService.deactivateSupplier(row.id!),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(
          willActivate ? 'Proveedor activado.' : 'Proveedor desactivado.',
          'Cerrar',
          { duration: 3000 },
        );
        this.dataSource.reload();
      });
  }

  private openSupplierForm(supplierId: number | null): void {
    this.dialog
      .open<SupplierFormDialog, SupplierFormDialogData, SupplierResponse | null>(
        SupplierFormDialog,
        {
          data: { supplierId },
          width: '820px',
          maxWidth: '96vw',
        },
      )
      .afterClosed()
      .pipe(filter((saved): saved is SupplierResponse => Boolean(saved)))
      .subscribe(() => {
        this.snackBar.open(
          supplierId === null ? 'Proveedor creado.' : 'Proveedor actualizado.',
          'Cerrar',
          { duration: 3000 },
        );
        this.dataSource.reload();
      });
  }
}
