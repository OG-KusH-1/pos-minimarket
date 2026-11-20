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
import { CustomerResponse } from '../../api/models/customer-response';
import { AuthService } from '../../core/auth/auth.service';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { PageResponse } from '../../shared/models/page-response';
import { RutPipe } from '../../shared/pipes';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../shared/ui/paginated-table';
import { ClpPipe } from '../../shared/pipes/clp.pipe';
import { CustomerAccountDialog, CustomerAccountDialogData } from './customer-account-dialog';
import { CustomerCreditDialog, CustomerCreditDialogData } from './customer-credit-dialog';
import { CustomerFormDialog, CustomerFormDialogData } from './customer-form-dialog';
import { CustomersService } from './customers.service';

/**
 * Pantalla de clientes y cuenta corriente (fiado) (`HU-POS-16`, `HU-POS-17`).
 *
 * Muestra la bandeja paginada de clientes (`/clientes`) con búsqueda por RUT o nombre y filtro de
 * estado y deuda; permite crear/editar clientes (RUT validado por módulo 11), activar/desactivar,
 * configurar el fiado (cupo y habilitación) y abrir la cuenta del cliente: cartola de movimientos,
 * registro de abonos y ajustes con override de supervisor.
 */
@Component({
  selector: 'pos-customers',
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
  templateUrl: './customers.html',
  styleUrl: './customers.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Customers implements OnInit, OnDestroy {
  private readonly customersService = inject(CustomersService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly rutPipe = new RutPipe();
  private readonly clpPipe = new ClpPipe();

  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<CustomerResponse>>>('statusTpl');
  private readonly creditTpl =
    viewChild.required<TemplateRef<CellContext<CustomerResponse>>>('creditTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<CustomerResponse>>>('actionsTpl');

  protected readonly canManageCustomers = computed(() =>
    this.auth.hasPermission('customers:customer:manage'),
  );
  protected readonly canManageCredit = computed(() =>
    this.auth.hasPermission('customers:credit:manage-limit'),
  );
  protected readonly canRegisterPayment = computed(() =>
    this.auth.hasPermission('customers:credit:payment'),
  );

  protected readonly filterForm = this.fb.group({
    q: [''],
    active: [null as boolean | null],
    withDebt: [null as boolean | null],
  });

  protected readonly dataSource = new PaginatedDataSource<CustomerResponse>(
    (params) => {
      const parseBool = (key: string): boolean | undefined => {
        const raw = params.get(key);
        return raw === 'true' ? true : raw === 'false' ? false : undefined;
      };
      return this.customersService.listCustomers(
        {
          q: params.get('q') ?? undefined,
          active: parseBool('active'),
          withDebt: parseBool('withDebt'),
          page: Number(params.get('page') ?? 0),
          size: Number(params.get('size') ?? 20),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<CustomerResponse>>;
    },
    { sort: [{ field: 'name', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<CustomerResponse>[]>(() => [
    {
      key: 'rut',
      header: 'RUT',
      sortField: 'rut',
      cell: (row) => (row.rut ? this.rutPipe.transform(row.rut) : '—'),
    },
    {
      key: 'name',
      header: 'Nombre',
      sortField: 'name',
      cell: (row) => row.name ?? '—',
    },
    {
      key: 'phone',
      header: 'Teléfono',
      cell: (row) => row.phone || '—',
    },
    {
      key: 'creditEnabled',
      header: 'Fiado',
      template: this.creditTpl(),
    },
    {
      key: 'creditLimit',
      header: 'Cupo',
      align: 'end',
      sortField: 'creditLimit',
      cell: (row) => (row.creditEnabled ? this.clpPipe.transform(row.creditLimit) : '—'),
    },
    {
      key: 'creditBalance',
      header: 'Deuda',
      align: 'end',
      sortField: 'creditBalance',
      cell: (row) => this.clpPipe.transform(row.creditBalance),
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
      withDebt: raw.withDebt,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ q: '', active: null, withDebt: null });
    this.dataSource.setFilters({});
  }

  protected openCreate(): void {
    this.openCustomerForm(null);
  }

  protected openEdit(row: CustomerResponse): void {
    if (!row.id) {
      return;
    }
    this.openCustomerForm(row.id);
  }

  protected openAccount(row: CustomerResponse): void {
    if (!row.id) {
      return;
    }
    this.dialog
      .open<CustomerAccountDialog, CustomerAccountDialogData, boolean>(CustomerAccountDialog, {
        data: {
          customerId: row.id,
          customerName: row.name ?? 'Cliente',
        },
        width: '980px',
        maxWidth: '96vw',
      })
      .afterClosed()
      .pipe(filter((changed): changed is boolean => changed === true))
      .subscribe(() => this.dataSource.reload());
  }

  protected openCreditSettings(row: CustomerResponse): void {
    if (!row.id) {
      return;
    }
    this.dialog
      .open<CustomerCreditDialog, CustomerCreditDialogData, CustomerResponse | null>(
        CustomerCreditDialog,
        {
          data: { customer: row },
          width: '560px',
          maxWidth: '96vw',
        },
      )
      .afterClosed()
      .pipe(filter((saved): saved is CustomerResponse => Boolean(saved)))
      .subscribe(() => {
        this.snackBar.open('Configuración de fiado actualizada.', 'Cerrar', { duration: 3000 });
        this.dataSource.reload();
      });
  }

  protected toggleActive(row: CustomerResponse): void {
    if (!row.id) {
      return;
    }
    const willActivate = !row.active;
    this.confirmDialog
      .confirm({
        title: willActivate ? 'Activar cliente' : 'Desactivar cliente',
        message: willActivate
          ? `¿Deseas reactivar al cliente "${row.name}"?`
          : `¿Deseas desactivar al cliente "${row.name}"? Un cliente con deuda no puede desactivarse.`,
        confirmLabel: willActivate ? 'Activar' : 'Desactivar',
        destructive: !willActivate,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          willActivate
            ? this.customersService.activateCustomer(row.id!)
            : this.customersService.deactivateCustomer(row.id!),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(willActivate ? 'Cliente activado.' : 'Cliente desactivado.', 'Cerrar', {
          duration: 3000,
        });
        this.dataSource.reload();
      });
  }

  private openCustomerForm(customerId: number | null): void {
    this.dialog
      .open<CustomerFormDialog, CustomerFormDialogData, CustomerResponse | null>(
        CustomerFormDialog,
        {
          data: { customerId },
          width: '760px',
          maxWidth: '96vw',
        },
      )
      .afterClosed()
      .pipe(filter((saved): saved is CustomerResponse => Boolean(saved)))
      .subscribe(() => {
        this.snackBar.open(
          customerId === null ? 'Cliente creado.' : 'Cliente actualizado.',
          'Cerrar',
          { duration: 3000 },
        );
        this.dataSource.reload();
      });
  }
}
