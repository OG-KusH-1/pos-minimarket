import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  effect,
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
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable, map } from 'rxjs';
import { StockAdjustmentResponse } from '../../../api/models/stock-adjustment-response';
import { AuthService } from '../../../core/auth/auth.service';
import { ErrorCodes, isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PageResponse } from '../../../shared/models/page-response';
import { ClpPipe } from '../../../shared/pipes';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { SupervisorOverrideService } from '../../../shared/ui/override-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { AdjustmentCreateDialog } from './adjustment-create-dialog';
import { AdjustmentDetailDialog, AdjustmentDetailDialogResult } from './adjustment-detail-dialog';
import { AdjustmentRejectDialog } from './adjustment-reject-dialog';
import {
  ADJUSTMENT_STATUS_OPTIONS,
  AdjustmentStatusCode,
  adjustmentReasonLabel,
  adjustmentStatusLabel,
  formatAdjustmentDateTime,
  formatUserRef,
} from './adjustments.models';
import { AdjustmentsService } from './adjustments.service';

/**
 * Pantalla de bandeja y flujo de aprobación de ajustes manuales de stock
 * (`/inventario/ajustes`, `HU-INV-05`).
 *
 * Permite listar, filtrar por estado/fechas/producto, ordenar, consultar el detalle de líneas,
 * crear solicitudes multi-línea idempotentes y aprobar (con override de supervisor si supera el
 * límite del rol) o rechazar con motivo obligatorio respetando la segregación de funciones.
 */
@Component({
  selector: 'pos-adjustments',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    MatTooltipModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './adjustments.html',
  styleUrl: './adjustments.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Adjustments implements OnInit, OnDestroy {
  private readonly adjustmentsService = inject(AdjustmentsService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly supervisorOverride = inject(SupervisorOverrideService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<StockAdjustmentResponse>>>('statusTpl');
  private readonly costTpl =
    viewChild.required<TemplateRef<CellContext<StockAdjustmentResponse>>>('costTpl');
  private readonly linesTpl =
    viewChild.required<TemplateRef<CellContext<StockAdjustmentResponse>>>('linesTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<StockAdjustmentResponse>>>('actionsTpl');

  protected readonly statusOptions = ADJUSTMENT_STATUS_OPTIONS;
  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );
  protected readonly currentUserId = computed(() => this.auth.user()?.id ?? null);
  protected readonly canCreate = computed(() =>
    this.auth.hasPermission('inventory:adjustment:create'),
  );
  protected readonly canApprove = computed(() =>
    this.auth.hasPermission('inventory:adjustment:approve'),
  );
  protected readonly canReadCosts = computed(
    () =>
      this.auth.hasPermission('catalog:cost:read') ||
      this.auth.hasPermission('inventory:adjustment:approve'),
  );

  protected readonly filterForm = this.fb.group({
    status: [null as AdjustmentStatusCode | null],
    from: [''],
    to: [''],
    productQuery: [''],
  });

  protected readonly dataSource = new PaginatedDataSource<StockAdjustmentResponse>(
    (params) => {
      const productQuery = (params.get('productQuery') ?? '').trim().toLowerCase();
      return this.adjustmentsService
        .listAdjustments(
          {
            status: (params.get('status') as AdjustmentStatusCode | null) ?? undefined,
            from: params.get('from') ?? undefined,
            to: params.get('to') ?? undefined,
            page: this.parseNumberParam(params.get('page')),
            size: this.parseNumberParam(params.get('size')),
            sort: params.getAll('sort') ?? undefined,
          },
          withoutErrorNotification(),
        )
        .pipe(
          map((page) => {
            if (!productQuery) {
              return page as unknown as PageResponse<StockAdjustmentResponse>;
            }
            const filtered = (page.content ?? []).filter((adj) =>
              (adj.lines ?? []).some(
                (line) =>
                  (line.sku ?? '').toLowerCase().includes(productQuery) ||
                  (line.name ?? '').toLowerCase().includes(productQuery),
              ),
            );
            return {
              ...page,
              content: filtered,
              totalElements:
                (page.totalPages ?? 0) <= 1 ? filtered.length : (page.totalElements ?? 0),
            } as unknown as PageResponse<StockAdjustmentResponse>;
          }),
        ) as Observable<PageResponse<StockAdjustmentResponse>>;
    },
    { sort: [{ field: 'createdAt', direction: 'desc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<StockAdjustmentResponse>[]>(() => {
    const cols: TableColumn<StockAdjustmentResponse>[] = [
      { key: 'number', header: 'Folio', sortField: 'number' },
      {
        key: 'createdAt',
        header: 'Fecha',
        sortField: 'createdAt',
        cell: (row) => formatAdjustmentDateTime(row.createdAt),
      },
      {
        key: 'reasonCode',
        header: 'Motivo',
        sortField: 'reasonCode',
        cell: (row) => adjustmentReasonLabel(row.reasonCode),
      },
      { key: 'lines', header: 'Líneas / Productos', template: this.linesTpl() },
      {
        key: 'requestedBy',
        header: 'Solicitado por',
        cell: (row) => formatUserRef(row.requestedBy),
      },
    ];
    if (this.canReadCosts()) {
      cols.push({
        key: 'totalCostImpact',
        header: 'Impacto costo',
        sortField: 'totalCostImpact',
        template: this.costTpl(),
        align: 'end',
      });
    }
    cols.push(
      { key: 'status', header: 'Estado', sortField: 'status', template: this.statusTpl() },
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
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    const filters: Filters = {
      status: raw.status,
      from: raw.from || null,
      to: raw.to || null,
      productQuery: raw.productQuery.trim() || null,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ status: null, from: '', to: '', productQuery: '' });
    this.dataSource.setFilters({});
  }

  protected openCreateDialog(): void {
    if (!this.canCreate()) {
      return;
    }
    const ref = this.dialog.open<AdjustmentCreateDialog, undefined, StockAdjustmentResponse>(
      AdjustmentCreateDialog,
      { width: '820px', maxWidth: '95vw' },
    );
    ref.afterClosed().subscribe((created) => {
      if (created) {
        this.snackBar.open(`Ajuste ${created.number ?? ''} creado en estado pendiente.`, 'Cerrar', {
          duration: 4000,
        });
        this.dataSource.reload();
      }
    });
  }

  protected openDetail(row: StockAdjustmentResponse): void {
    const ref = this.dialog.open<AdjustmentDetailDialog, unknown, AdjustmentDetailDialogResult>(
      AdjustmentDetailDialog,
      {
        data: {
          adjustment: row,
          canReadCosts: this.canReadCosts(),
          canApprove: this.canApprove(),
          currentUserId: this.currentUserId(),
        },
        width: '760px',
        maxWidth: '95vw',
      },
    );
    ref.afterClosed().subscribe((action) => {
      if (action === 'approve') {
        this.approveAdjustment(row);
      } else if (action === 'reject') {
        this.openRejectDialog(row);
      }
    });
  }

  protected approveAdjustment(row: StockAdjustmentResponse): void {
    if (!this.canResolveAdjustment(row) || row.id === undefined) {
      return;
    }
    const adjustmentId = row.id;
    const numberLabel = row.number ?? `#${adjustmentId}`;

    this.confirmDialog
      .confirm({
        title: `Aprobar ajuste ${numberLabel}`,
        message:
          'Al aprobar se aplicarán los movimientos de stock en el local activo y quedarán registrados en el kardex. ¿Deseas continuar?',
        confirmLabel: 'Aprobar ajuste',
      })
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        const context = withoutErrorNotification();
        this.supervisorOverride
          .withSupervisorOverride((headers) =>
            this.adjustmentsService.approveAdjustment(
              adjustmentId,
              headers['X-Override-Token'],
              context,
            ),
          )
          .subscribe({
            next: (approved) => {
              this.snackBar.open(
                `Ajuste ${approved.number ?? numberLabel} aprobado y aplicado al stock.`,
                'Cerrar',
                { duration: 4000 },
              );
              this.dataSource.reload();
            },
            error: (err: unknown) => {
              const message = this.resolveApproveErrorMessage(err);
              this.snackBar.open(message, 'Cerrar', { duration: 6000 });
            },
          });
      });
  }

  protected openRejectDialog(row: StockAdjustmentResponse): void {
    if (!this.canResolveAdjustment(row) || row.id === undefined) {
      return;
    }
    const ref = this.dialog.open<AdjustmentRejectDialog, unknown, StockAdjustmentResponse>(
      AdjustmentRejectDialog,
      {
        data: { adjustment: row },
        width: '480px',
      },
    );
    ref.afterClosed().subscribe((rejected) => {
      if (rejected) {
        this.snackBar.open(
          `Ajuste ${rejected.number ?? ''} rechazado sin modificar el stock.`,
          'Cerrar',
          { duration: 4000 },
        );
        this.dataSource.reload();
      }
    });
  }

  protected statusLabel(status: StockAdjustmentResponse['status']): string {
    return adjustmentStatusLabel(status);
  }

  protected summarizeLines(row: StockAdjustmentResponse): string {
    const lines = row.lines ?? [];
    if (lines.length === 0) {
      return 'Sin líneas';
    }
    const first = lines[0];
    const firstLabel = first?.name ?? first?.sku ?? 'Producto';
    return lines.length === 1 ? firstLabel : `${firstLabel} (+${lines.length - 1} más)`;
  }

  protected isPending(row: StockAdjustmentResponse): boolean {
    return row.status === 'PENDING_APPROVAL';
  }

  protected isOwnRequest(row: StockAdjustmentResponse): boolean {
    const userId = this.currentUserId();
    return userId !== null && row.requestedBy?.id === userId;
  }

  protected canResolveAdjustment(row: StockAdjustmentResponse): boolean {
    return this.canApprove() && this.isPending(row) && !this.isOwnRequest(row);
  }

  protected hasActiveProductFilter(): boolean {
    return (this.dataSource.query().filters['productQuery'] ?? '') !== '';
  }

  private resolveApproveErrorMessage(err: unknown): string {
    if (!isApiError(err)) {
      return 'No se pudo aprobar el ajuste de stock.';
    }
    if (err.code === ErrorCodes.overrideRequired) {
      return 'La aprobación requiere autorización de un supervisor con límite suficiente.';
    }
    return err.message;
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
}
