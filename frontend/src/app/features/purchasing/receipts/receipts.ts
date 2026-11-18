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
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, filter, switchMap } from 'rxjs';
import { GoodsReceiptResponse } from '../../../api/models/goods-receipt-response';
import { GoodsReceiptSummary } from '../../../api/models/goods-receipt-summary';
import { SupplierSummary } from '../../../api/models/supplier-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
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
import { GoodsReceiptsService } from './goods-receipts.service';
import { ReceiptCancelDialog, ReceiptCancelDialogData } from './receipt-cancel-dialog';
import {
  ReceiptDetailDialog,
  ReceiptDetailDialogData,
  ReceiptDetailDialogResult,
} from './receipt-detail-dialog';
import { ReceiptFormDialog, ReceiptFormDialogData } from './receipt-form-dialog';
import { ReceiptOrderPickerDialog } from './receipt-order-picker-dialog';
import {
  RECEIPT_STATUS_OPTIONS,
  ReceiptStatusCode,
  formatReceiptDateTime,
  isReceiptDraft,
  mapReceiptBusinessError,
  receiptStatusLabel,
} from './receipts.models';

/**
 * Pantalla de recepciones de mercadería (`HU-PRV-05` a `HU-PRV-08`).
 *
 * Lista las recepciones del local activo filtradas por estado, proveedor y rango de fechas;
 * permite crear recepciones desde una orden de compra (`GET /purchase-orders/{id}/receipt-draft`)
 * o sin OC (`purchasing:receipt:without-order`), editar borradores, confirmar con idempotencia
 * (moviendo stock, creando lotes y actualizando costo promedio ponderado) y cancelar borradores.
 */
@Component({
  selector: 'pos-receipts',
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
  templateUrl: './receipts.html',
  styleUrl: './receipts.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Receipts implements OnInit, OnDestroy {
  private readonly receiptsService = inject(GoodsReceiptsService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly totalTpl =
    viewChild.required<TemplateRef<CellContext<GoodsReceiptSummary>>>('totalTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<GoodsReceiptSummary>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<GoodsReceiptSummary>>>('actionsTpl');

  protected readonly statusOptions = RECEIPT_STATUS_OPTIONS;
  protected readonly activeStoreId = this.storeContext.storeId;
  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );

  protected readonly canCreate = computed(() =>
    this.auth.hasPermission('purchasing:receipt:create'),
  );
  protected readonly canConfirm = computed(() =>
    this.auth.hasPermission('purchasing:receipt:confirm'),
  );
  protected readonly canWithoutOrder = computed(
    () =>
      this.auth.hasPermission('purchasing:receipt:create') &&
      this.auth.hasPermission('purchasing:receipt:without-order'),
  );
  protected readonly canReadCost = computed(() => this.auth.hasPermission('catalog:cost:read'));

  protected readonly suppliers = signal<SupplierSummary[]>([]);
  protected readonly filterError = signal<string | null>(null);

  protected readonly filterForm = this.fb.group({
    status: [null as ReceiptStatusCode | null],
    supplierId: [null as number | null],
    from: [''],
    to: [''],
  });

  protected readonly columns = computed<readonly TableColumn<GoodsReceiptSummary>[]>(() => {
    const base: TableColumn<GoodsReceiptSummary>[] = [
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
        key: 'purchaseOrderNumber',
        header: 'Orden de compra',
        cell: (row) => row.purchaseOrderNumber || 'Sin OC',
      },
      {
        key: 'receivedAt',
        header: 'Confirmación',
        sortField: 'receivedAt',
        cell: (row) => formatReceiptDateTime(row.receivedAt),
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
        key: 'netTotal',
        header: 'Total neto',
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

  readonly dataSource = new PaginatedDataSource<GoodsReceiptSummary>(
    (params) => this.fetchReceipts(params),
    { sort: [{ field: 'receivedAt', direction: 'desc' }] },
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

    this.receiptsService.listSuppliers(undefined, withoutErrorNotification()).subscribe({
      next: (list) => this.suppliers.set(list),
      error: () => {
        // Silencioso en filtro auxiliar.
      },
    });

    const poParam = this.route.snapshot.queryParamMap.get('purchaseOrderId');
    const purchaseOrderId = poParam ? Number(poParam) : NaN;
    if (Number.isFinite(purchaseOrderId) && purchaseOrderId > 0 && this.canCreate()) {
      void this.router.navigate([], { relativeTo: this.route, queryParams: {}, replaceUrl: true });
      this.openCreateFromOrder(purchaseOrderId);
    }
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
      status: raw.status,
      supplierId: raw.supplierId,
      from: raw.from || null,
      to: raw.to || null,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterError.set(null);
    this.filterForm.reset({
      status: null,
      supplierId: null,
      from: '',
      to: '',
    });
    this.dataSource.setFilters({});
  }

  protected openCreateFromOrderPicker(): void {
    const ref = this.dialog.open<ReceiptOrderPickerDialog, undefined, number | null>(
      ReceiptOrderPickerDialog,
      { width: '500px', maxWidth: '95vw' },
    );
    ref.afterClosed().subscribe((purchaseOrderId) => {
      if (purchaseOrderId !== null && purchaseOrderId !== undefined) {
        this.openCreateFromOrder(purchaseOrderId);
      }
    });
  }

  protected openCreateWithoutOrder(): void {
    const ref = this.dialog.open<
      ReceiptFormDialog,
      ReceiptFormDialogData,
      GoodsReceiptResponse | null
    >(ReceiptFormDialog, {
      width: '860px',
      maxWidth: '95vw',
      data: { withoutOrder: true },
    });
    ref.afterClosed().subscribe((saved) => this.handleSavedReceipt(saved));
  }

  protected openDetail(row: GoodsReceiptSummary): void {
    if (row.id === undefined) {
      return;
    }
    this.receiptsService.getReceipt(row.id).subscribe((receipt) => {
      const ref = this.dialog.open<
        ReceiptDetailDialog,
        ReceiptDetailDialogData,
        ReceiptDetailDialogResult | undefined
      >(ReceiptDetailDialog, { width: '860px', maxWidth: '95vw', data: { receipt } });

      ref.afterClosed().subscribe((result) => {
        if (!result) {
          return;
        }
        switch (result.action) {
          case 'edit':
            this.openEditDialog(result.receipt);
            break;
          case 'confirm':
            this.confirmReceiptFlow(result.receipt);
            break;
          case 'cancel':
            this.openCancelDialog(result.receipt);
            break;
        }
      });
    });
  }

  protected openEdit(row: GoodsReceiptSummary): void {
    if (row.id === undefined) {
      return;
    }
    this.receiptsService.getReceipt(row.id).subscribe((receipt) => this.openEditDialog(receipt));
  }

  protected confirmRow(row: GoodsReceiptSummary): void {
    if (row.id === undefined) {
      return;
    }
    this.receiptsService
      .getReceipt(row.id)
      .subscribe((receipt) => this.confirmReceiptFlow(receipt));
  }

  protected openCancel(row: GoodsReceiptSummary): void {
    if (row.id === undefined) {
      return;
    }
    this.receiptsService.getReceipt(row.id).subscribe((receipt) => this.openCancelDialog(receipt));
  }

  protected statusLabel(status: ReceiptStatusCode | undefined): string {
    return receiptStatusLabel(status);
  }

  protected isDraftRow(row: GoodsReceiptSummary): boolean {
    return isReceiptDraft(row);
  }

  private openCreateFromOrder(purchaseOrderId: number): void {
    const ref = this.dialog.open<
      ReceiptFormDialog,
      ReceiptFormDialogData,
      GoodsReceiptResponse | null
    >(ReceiptFormDialog, {
      width: '860px',
      maxWidth: '95vw',
      data: { purchaseOrderId },
    });
    ref.afterClosed().subscribe((saved) => this.handleSavedReceipt(saved));
  }

  private openEditDialog(receipt: GoodsReceiptResponse): void {
    const ref = this.dialog.open<
      ReceiptFormDialog,
      ReceiptFormDialogData,
      GoodsReceiptResponse | null
    >(ReceiptFormDialog, {
      width: '860px',
      maxWidth: '95vw',
      data: { receipt },
    });
    ref.afterClosed().subscribe((saved) => this.handleSavedReceipt(saved));
  }

  private confirmReceiptFlow(receipt: GoodsReceiptResponse): void {
    const id = receipt.id;
    if (id === undefined) {
      return;
    }
    this.confirmDialog
      .confirm({
        title: `Confirmar recepción ${receipt.number ?? ''}`,
        message:
          'Al confirmar se ingresará la mercadería aceptada al inventario, se crearán los lotes registrados y se recalculará el costo promedio ponderado.',
        confirmLabel: 'Confirmar recepción',
      })
      .pipe(
        filter(Boolean),
        switchMap(() => this.receiptsService.confirmReceipt(id, withoutErrorNotification())),
      )
      .subscribe({
        next: (confirmed) => {
          this.snackBar.open(`Recepción ${confirmed.number ?? ''} confirmada.`, 'Cerrar', {
            duration: 3500,
          });
          this.dataSource.reload();
        },
        error: (err: unknown) => {
          const message = isApiError(err)
            ? mapReceiptBusinessError(err.code, err.message)
            : 'No se pudo confirmar la recepción.';
          this.snackBar.open(message, 'Cerrar', { duration: 6000 });
        },
      });
  }

  private openCancelDialog(receipt: GoodsReceiptResponse): void {
    const ref = this.dialog.open<ReceiptCancelDialog, ReceiptCancelDialogData, boolean>(
      ReceiptCancelDialog,
      { width: '480px', maxWidth: '95vw', data: { receipt } },
    );
    ref.afterClosed().subscribe((cancelled) => {
      if (cancelled) {
        this.snackBar.open(`Recepción ${receipt.number ?? ''} cancelada.`, 'Cerrar', {
          duration: 3500,
        });
        this.dataSource.reload();
      }
    });
  }

  private handleSavedReceipt(saved: GoodsReceiptResponse | null | undefined): void {
    if (!saved) {
      return;
    }
    const label = saved.status === 'CONFIRMED' ? 'confirmada' : 'guardada en borrador';
    this.snackBar.open(`Recepción ${saved.number ?? ''} ${label}.`, 'Cerrar', {
      duration: 3500,
    });
    this.dataSource.reload();
  }

  private fetchReceipts(params: HttpParams): Observable<PageResponse<GoodsReceiptSummary>> {
    const sortList = params.getAll('sort');
    return this.receiptsService.listReceipts({
      status: (params.get('status') as ReceiptStatusCode | null) ?? undefined,
      supplierId: this.parseNumberParam(params.get('supplierId')),
      from: params.get('from') ?? undefined,
      to: params.get('to') ?? undefined,
      page: this.parseNumberParam(params.get('page')),
      size: this.parseNumberParam(params.get('size')),
      sort: sortList && sortList.length > 0 ? sortList : undefined,
    }) as unknown as Observable<PageResponse<GoodsReceiptSummary>>;
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
}
