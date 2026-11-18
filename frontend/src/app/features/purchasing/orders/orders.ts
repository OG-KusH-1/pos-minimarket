import { HttpParams } from '@angular/common/http';
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
import { PurchaseOrderResponse } from '../../../api/models/purchase-order-response';
import { PurchaseOrderSummary } from '../../../api/models/purchase-order-summary';
import { SupplierSummary } from '../../../api/models/supplier-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PageResponse } from '../../../shared/models/page-response';
import { ClpPipe } from '../../../shared/pipes';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { OrderCancelDialog, OrderCancelDialogData } from './order-cancel-dialog';
import {
  OrderDetailDialog,
  OrderDetailDialogData,
  OrderDetailDialogResult,
} from './order-detail-dialog';
import { OrderFormDialog, OrderFormDialogData } from './order-form-dialog';
import { OrderSendDialog, OrderSendDialogData } from './order-send-dialog';
import {
  ORDER_ORIGIN_OPTIONS,
  ORDER_STATUS_OPTIONS,
  OrderOriginCode,
  OrderStatusCode,
  canCancelOrderStatus,
  canReceiveFromOrder,
  formatOrderDateTime,
  formatOrderLocalDate,
  isOrderDraft,
  orderOriginLabel,
  orderStatusLabel,
} from './orders.models';
import { PurchaseOrdersService } from './purchase-orders.service';

/**
 * Pantalla de órdenes de compra (`HU-PRV-03`, `HU-PRV-04`).
 *
 * Lista las órdenes del local activo filtradas por número/proveedor, estado, origen, proveedor y
 * rango de fechas; permite crear y editar borradores, aprobar y enviar órdenes (con opción de
 * correo al proveedor), descargar el PDF, cancelar con motivo y saltar a recepcionar una OC.
 */
@Component({
  selector: 'pos-orders',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './orders.html',
  styleUrl: './orders.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Orders implements OnInit, OnDestroy {
  private readonly ordersService = inject(PurchaseOrdersService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly totalTpl =
    viewChild.required<TemplateRef<CellContext<PurchaseOrderSummary>>>('totalTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<PurchaseOrderSummary>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<PurchaseOrderSummary>>>('actionsTpl');

  protected readonly statusOptions = ORDER_STATUS_OPTIONS;
  protected readonly originOptions = ORDER_ORIGIN_OPTIONS;
  protected readonly activeStoreId = this.storeContext.storeId;
  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );

  protected readonly canCreate = computed(() => this.auth.hasPermission('purchasing:order:create'));
  protected readonly canSend = computed(() => this.auth.hasPermission('purchasing:order:send'));
  protected readonly canCancel = computed(() => this.auth.hasPermission('purchasing:order:cancel'));
  protected readonly canReceive = computed(() =>
    this.auth.hasPermission('purchasing:receipt:create'),
  );
  protected readonly canReadCost = computed(() => this.auth.hasPermission('catalog:cost:read'));

  protected readonly suppliers = signal<SupplierSummary[]>([]);
  protected readonly filterError = signal<string | null>(null);

  protected readonly filterForm = this.fb.group({
    q: [''],
    status: [null as OrderStatusCode | null],
    origin: [null as OrderOriginCode | null],
    supplierId: [null as number | null],
    from: [''],
    to: [''],
  });

  protected readonly columns = computed<readonly TableColumn<PurchaseOrderSummary>[]>(() => {
    const base: TableColumn<PurchaseOrderSummary>[] = [
      {
        key: 'number',
        header: 'Número',
        sortField: 'number',
        cell: (row) => row.number ?? '—',
      },
      {
        key: 'supplierName',
        header: 'Proveedor',
        cell: (row) => row.supplierName ?? '—',
      },
      {
        key: 'origin',
        header: 'Origen',
        cell: (row) => orderOriginLabel(row.origin),
      },
      {
        key: 'createdAt',
        header: 'Creación',
        sortField: 'createdAt',
        cell: (row) => formatOrderDateTime(row.createdAt),
      },
      {
        key: 'expectedDate',
        header: 'Entrega esperada',
        sortField: 'expectedDate',
        cell: (row) => formatOrderLocalDate(row.expectedDate),
      },
      {
        key: 'status',
        header: 'Estado',
        sortField: 'status',
        template: this.statusTpl(),
      },
    ];

    if (this.canReadCost()) {
      base.push({
        key: 'total',
        header: 'Total',
        align: 'end',
        template: this.totalTpl(),
      });
    }

    base.push({
      key: 'actions',
      header: 'Acciones',
      align: 'end',
      template: this.actionsTpl(),
    });

    return base;
  });

  readonly dataSource = new PaginatedDataSource<PurchaseOrderSummary>(
    (params) => this.fetchOrders(params),
    { sort: [{ field: 'createdAt', direction: 'desc' }] },
  );

  private initialized = false;
  private lastLoadedStoreId: number | null = null;

  constructor() {
    effect(() => {
      const storeId = this.activeStoreId();
      if (this.initialized && storeId !== this.lastLoadedStoreId) {
        this.lastLoadedStoreId = storeId;
        this.dataSource.reload();
      }
    });
  }

  ngOnInit(): void {
    this.initialized = true;
    this.lastLoadedStoreId = this.storeContext.storeId();
    this.dataSource.reload();

    this.ordersService.listSuppliers(undefined, withoutErrorNotification()).subscribe({
      next: (list) => this.suppliers.set(list),
      error: () => {
        // Silencioso: el listado de órdenes sigue operando sin filtro por proveedor.
      },
    });
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    if (raw.from && raw.to && raw.from > raw.to) {
      this.filterError.set('La fecha «Desde» no puede ser posterior a «Hasta».');
      return;
    }
    this.filterError.set(null);
    const filters: Filters = {
      q: raw.q.trim() || null,
      status: raw.status,
      origin: raw.origin,
      supplierId: raw.supplierId,
      from: raw.from || null,
      to: raw.to || null,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterError.set(null);
    this.filterForm.reset({
      q: '',
      status: null,
      origin: null,
      supplierId: null,
      from: '',
      to: '',
    });
    this.dataSource.setFilters({});
  }

  protected openCreate(): void {
    const ref = this.dialog.open<
      OrderFormDialog,
      OrderFormDialogData,
      PurchaseOrderResponse | null
    >(OrderFormDialog, { width: '820px', maxWidth: '95vw', data: {} });
    ref.afterClosed().subscribe((saved) => {
      if (saved) {
        this.snackBar.open(`Orden ${saved.number ?? ''} creada como borrador.`, 'Cerrar', {
          duration: 3500,
        });
        this.dataSource.reload();
      }
    });
  }

  protected openDetail(row: PurchaseOrderSummary): void {
    if (row.id === undefined) {
      return;
    }
    this.ordersService.getOrder(row.id).subscribe((order) => {
      const ref = this.dialog.open<
        OrderDetailDialog,
        OrderDetailDialogData,
        OrderDetailDialogResult | undefined
      >(OrderDetailDialog, { width: '840px', maxWidth: '95vw', data: { order } });

      ref.afterClosed().subscribe((result) => {
        if (!result) {
          return;
        }
        switch (result.action) {
          case 'edit':
            this.openEditDialog(result.order);
            break;
          case 'send':
            this.openSendDialog(result.order);
            break;
          case 'cancel':
            this.openCancelDialog(result.order);
            break;
          case 'pdf':
            this.downloadPdf(result.order.id, result.order.number);
            break;
          case 'receive':
            this.goToReceiveOrder(result.order.id);
            break;
        }
      });
    });
  }

  protected openEdit(row: PurchaseOrderSummary): void {
    if (row.id === undefined) {
      return;
    }
    this.ordersService.getOrder(row.id).subscribe((order) => this.openEditDialog(order));
  }

  protected openSend(row: PurchaseOrderSummary): void {
    if (row.id === undefined) {
      return;
    }
    this.ordersService.getOrder(row.id).subscribe((order) => this.openSendDialog(order));
  }

  protected openCancel(row: PurchaseOrderSummary): void {
    if (row.id === undefined) {
      return;
    }
    this.ordersService.getOrder(row.id).subscribe((order) => this.openCancelDialog(order));
  }

  protected downloadPdf(id: number | undefined, orderNumber?: string): void {
    if (id === undefined) {
      return;
    }
    this.ordersService.getOrderPdf(id).subscribe((blob) => {
      const url = URL.createObjectURL(blob);
      const opened = window.open(url, '_blank', 'noopener');
      if (!opened) {
        const link = document.createElement('a');
        link.href = url;
        link.download = `${orderNumber || 'orden-compra-' + id}.pdf`;
        link.click();
      }
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    });
  }

  protected goToReceiveOrder(id: number | undefined): void {
    if (id === undefined) {
      return;
    }
    void this.router.navigate(['/compras/recepciones'], {
      queryParams: { purchaseOrderId: id },
    });
  }

  protected statusLabel(status: OrderStatusCode | undefined): string {
    return orderStatusLabel(status);
  }

  protected isDraftRow(row: PurchaseOrderSummary): boolean {
    return isOrderDraft(row);
  }

  protected canReceiveRow(row: PurchaseOrderSummary): boolean {
    return this.canReceive() && canReceiveFromOrder(row);
  }

  protected canCancelRow(row: PurchaseOrderSummary): boolean {
    return this.canCancel() && canCancelOrderStatus(row);
  }

  private openEditDialog(order: PurchaseOrderResponse): void {
    const ref = this.dialog.open<
      OrderFormDialog,
      OrderFormDialogData,
      PurchaseOrderResponse | null
    >(OrderFormDialog, { width: '820px', maxWidth: '95vw', data: { order } });
    ref.afterClosed().subscribe((saved) => {
      if (saved) {
        this.snackBar.open(`Orden ${saved.number ?? ''} actualizada.`, 'Cerrar', {
          duration: 3500,
        });
        this.dataSource.reload();
      }
    });
  }

  private openSendDialog(order: PurchaseOrderResponse): void {
    const ref = this.dialog.open<
      OrderSendDialog,
      OrderSendDialogData,
      PurchaseOrderResponse | null
    >(OrderSendDialog, { width: '480px', maxWidth: '95vw', data: { order } });
    ref.afterClosed().subscribe((sent) => {
      if (sent) {
        this.snackBar.open(`Orden ${sent.number ?? ''} enviada.`, 'Cerrar', {
          duration: 3500,
        });
        this.dataSource.reload();
      }
    });
  }

  private openCancelDialog(order: PurchaseOrderResponse): void {
    const ref = this.dialog.open<
      OrderCancelDialog,
      OrderCancelDialogData,
      PurchaseOrderResponse | null
    >(OrderCancelDialog, { width: '480px', maxWidth: '95vw', data: { order } });
    ref.afterClosed().subscribe((cancelled) => {
      if (cancelled) {
        this.snackBar.open(`Orden ${cancelled.number ?? ''} cancelada.`, 'Cerrar', {
          duration: 3500,
        });
        this.dataSource.reload();
      }
    });
  }

  private fetchOrders(params: HttpParams): Observable<PageResponse<PurchaseOrderSummary>> {
    const sortList = params.getAll('sort');
    return this.ordersService.listOrders({
      q: params.get('q') ?? undefined,
      status: (params.get('status') as OrderStatusCode | null) ?? undefined,
      origin: (params.get('origin') as OrderOriginCode | null) ?? undefined,
      supplierId: this.parseNumberParam(params.get('supplierId')),
      from: params.get('from') ?? undefined,
      to: params.get('to') ?? undefined,
      page: this.parseNumberParam(params.get('page')),
      size: this.parseNumberParam(params.get('size')),
      sort: sortList && sortList.length > 0 ? sortList : undefined,
    }) as unknown as Observable<PageResponse<PurchaseOrderSummary>>;
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
}
