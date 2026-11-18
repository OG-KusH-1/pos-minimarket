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
import { ShrinkageResponse } from '../../api/models/shrinkage-response';
import { AuthService } from '../../core/auth/auth.service';
import { ErrorCodes, isApiError } from '../../core/http/api-error';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { StoreContextService } from '../../core/store-context/store-context.service';
import { PageResponse } from '../../shared/models/page-response';
import { ClpPipe, QtyPipe } from '../../shared/pipes';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog';
import { SupervisorOverrideService } from '../../shared/ui/override-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../shared/ui/paginated-table';
import { ShrinkageCreateDialog } from './shrinkage-create-dialog';
import { ShrinkageDetailDialog, ShrinkageDetailDialogResult } from './shrinkage-detail-dialog';
import { ShrinkageRejectDialog } from './shrinkage-reject-dialog';
import {
  SHRINKAGE_STATUS_OPTIONS,
  SHRINKAGE_TYPE_OPTIONS,
  ShrinkageStatusCode,
  ShrinkageTypeCode,
  formatShrinkageDateTime,
  formatShrinkageUserRef,
  isSystemicShrinkage,
  shrinkageStatusLabel,
  shrinkageTypeLabel,
} from './shrinkage.models';
import { ShrinkageService } from './shrinkage.service';

/**
 * Pantalla de bandeja, registro y aprobación de mermas (`/mermas`, `HU-MER-01..03`).
 *
 * Permite listar, filtrar por estado/tipo/fechas/producto, ordenar, distinguir mermas sistémicas
 * (`TRANSFER_DIFFERENCE`, `DAMAGED` de devolución, `affectsStock = false`), registrar mermas
 * manuales idempotentes con evidencia adjunta y aprobar (con override de supervisor si supera el
 * límite del rol) o rechazar con motivo obligatorio respetando la segregación de funciones.
 */
@Component({
  selector: 'pos-shrinkage',
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
    QtyPipe,
    ReactiveFormsModule,
  ],
  templateUrl: './shrinkage.html',
  styleUrl: './shrinkage.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shrinkage implements OnInit, OnDestroy {
  private readonly shrinkageService = inject(ShrinkageService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly supervisorOverride = inject(SupervisorOverrideService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly typeTpl =
    viewChild.required<TemplateRef<CellContext<ShrinkageResponse>>>('typeTpl');
  private readonly productTpl =
    viewChild.required<TemplateRef<CellContext<ShrinkageResponse>>>('productTpl');
  private readonly qtyTpl =
    viewChild.required<TemplateRef<CellContext<ShrinkageResponse>>>('qtyTpl');
  private readonly costTpl =
    viewChild.required<TemplateRef<CellContext<ShrinkageResponse>>>('costTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<ShrinkageResponse>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<ShrinkageResponse>>>('actionsTpl');

  protected readonly statusOptions = SHRINKAGE_STATUS_OPTIONS;
  protected readonly typeOptions = SHRINKAGE_TYPE_OPTIONS;
  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );
  protected readonly currentUserId = computed(() => this.auth.user()?.id ?? null);
  protected readonly canCreate = computed(() => this.auth.hasPermission('shrinkage:record:create'));
  protected readonly canApprove = computed(() =>
    this.auth.hasPermission('shrinkage:record:approve'),
  );
  protected readonly canReadCosts = computed(
    () =>
      this.auth.hasPermission('catalog:cost:read') ||
      this.auth.hasPermission('shrinkage:record:approve'),
  );

  protected readonly filterForm = this.fb.group({
    status: [null as ShrinkageStatusCode | null],
    shrinkageType: [null as ShrinkageTypeCode | null],
    from: [''],
    to: [''],
    productQuery: [''],
  });

  protected readonly dataSource = new PaginatedDataSource<ShrinkageResponse>(
    (params) => {
      const productQuery = (params.get('productQuery') ?? '').trim().toLowerCase();
      return this.shrinkageService
        .listShrinkages(
          {
            status: (params.get('status') as ShrinkageStatusCode | null) ?? undefined,
            shrinkageType: (params.get('shrinkageType') as ShrinkageTypeCode | null) ?? undefined,
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
              return page as unknown as PageResponse<ShrinkageResponse>;
            }
            const filtered = (page.content ?? []).filter(
              (item) =>
                (item.product?.sku ?? '').toLowerCase().includes(productQuery) ||
                (item.product?.name ?? '').toLowerCase().includes(productQuery),
            );
            return {
              ...page,
              content: filtered,
              totalElements:
                (page.totalPages ?? 0) <= 1 ? filtered.length : (page.totalElements ?? 0),
            } as unknown as PageResponse<ShrinkageResponse>;
          }),
        ) as Observable<PageResponse<ShrinkageResponse>>;
    },
    { sort: [{ field: 'occurredAt', direction: 'desc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<ShrinkageResponse>[]>(() => {
    const cols: TableColumn<ShrinkageResponse>[] = [
      { key: 'number', header: 'Folio', sortField: 'number' },
      {
        key: 'occurredAt',
        header: 'Fecha',
        sortField: 'occurredAt',
        cell: (row) => formatShrinkageDateTime(row.occurredAt),
      },
      {
        key: 'shrinkageType',
        header: 'Tipo',
        sortField: 'shrinkageType',
        template: this.typeTpl(),
      },
      { key: 'product', header: 'Producto / Lote', template: this.productTpl() },
      {
        key: 'quantity',
        header: 'Cantidad',
        sortField: 'quantity',
        template: this.qtyTpl(),
        align: 'end',
      },
      {
        key: 'responsible',
        header: 'Responsable',
        cell: (row) => formatShrinkageUserRef(row.responsible),
      },
    ];
    if (this.canReadCosts()) {
      cols.push({
        key: 'totalCost',
        header: 'Costo total',
        sortField: 'totalCost',
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
      shrinkageType: raw.shrinkageType,
      from: raw.from || null,
      to: raw.to || null,
      productQuery: raw.productQuery.trim() || null,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({
      status: null,
      shrinkageType: null,
      from: '',
      to: '',
      productQuery: '',
    });
    this.dataSource.setFilters({});
  }

  protected openCreateDialog(): void {
    if (!this.canCreate()) {
      return;
    }
    const ref = this.dialog.open<ShrinkageCreateDialog, undefined, ShrinkageResponse>(
      ShrinkageCreateDialog,
      { width: '640px', maxWidth: '95vw' },
    );
    ref.afterClosed().subscribe((created) => {
      if (created) {
        const message = created.autoApproved
          ? `Merma ${created.number ?? ''} registrada y autoaprobada.`
          : `Merma ${created.number ?? ''} registrada en estado pendiente de aprobación.`;
        this.snackBar.open(message, 'Cerrar', { duration: 4500 });
        this.dataSource.reload();
      }
    });
  }

  protected openDetail(row: ShrinkageResponse): void {
    const ref = this.dialog.open<ShrinkageDetailDialog, unknown, ShrinkageDetailDialogResult>(
      ShrinkageDetailDialog,
      {
        data: {
          shrinkage: row,
          canReadCosts: this.canReadCosts(),
          canApprove: this.canApprove(),
          currentUserId: this.currentUserId(),
        },
        width: '660px',
        maxWidth: '95vw',
      },
    );
    ref.afterClosed().subscribe((action) => {
      if (action === 'approve') {
        this.approveShrinkage(row);
      } else if (action === 'reject') {
        this.openRejectDialog(row);
      }
    });
  }

  protected approveShrinkage(row: ShrinkageResponse): void {
    if (!this.canResolveShrinkage(row) || row.id === undefined) {
      return;
    }
    const shrinkageId = row.id;
    const numberLabel = row.number ?? `#${shrinkageId}`;
    const affectsStock = row.affectsStock !== false;

    this.confirmDialog
      .confirm({
        title: `Aprobar merma ${numberLabel}`,
        message: affectsStock
          ? 'Al aprobar se descontará el stock en el local activo y quedará registrado en el kardex. ¿Deseas continuar?'
          : 'Esta es una merma sistémica (sin descuento adicional de stock). ¿Deseas aprobarla?',
        confirmLabel: 'Aprobar merma',
      })
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        const context = withoutErrorNotification();
        this.supervisorOverride
          .withSupervisorOverride((headers) =>
            this.shrinkageService.approveShrinkage(
              shrinkageId,
              headers['X-Override-Token'],
              context,
            ),
          )
          .subscribe({
            next: (approved) => {
              const msg =
                approved.affectsStock === false
                  ? `Merma sistémica ${approved.number ?? numberLabel} aprobada.`
                  : `Merma ${approved.number ?? numberLabel} aprobada y descontada del stock.`;
              this.snackBar.open(msg, 'Cerrar', { duration: 4000 });
              this.dataSource.reload();
            },
            error: (err: unknown) => {
              this.snackBar.open(this.resolveApproveErrorMessage(err), 'Cerrar', {
                duration: 6000,
              });
            },
          });
      });
  }

  protected openRejectDialog(row: ShrinkageResponse): void {
    if (!this.canResolveShrinkage(row) || row.id === undefined) {
      return;
    }
    const ref = this.dialog.open<ShrinkageRejectDialog, unknown, ShrinkageResponse>(
      ShrinkageRejectDialog,
      {
        data: { shrinkage: row },
        width: '480px',
      },
    );
    ref.afterClosed().subscribe((rejected) => {
      if (rejected) {
        this.snackBar.open(
          `Merma ${rejected.number ?? ''} rechazada sin modificar el stock.`,
          'Cerrar',
          { duration: 4000 },
        );
        this.dataSource.reload();
      }
    });
  }

  protected statusLabel(status: ShrinkageResponse['status']): string {
    return shrinkageStatusLabel(status);
  }

  protected typeLabel(type: ShrinkageResponse['shrinkageType']): string {
    return shrinkageTypeLabel(type);
  }

  protected isSystemic(row: ShrinkageResponse): boolean {
    return isSystemicShrinkage(row);
  }

  protected isPending(row: ShrinkageResponse): boolean {
    return row.status === 'PENDING_APPROVAL';
  }

  protected isOwnReport(row: ShrinkageResponse): boolean {
    const userId = this.currentUserId();
    return userId !== null && row.reportedBy?.id === userId;
  }

  protected canResolveShrinkage(row: ShrinkageResponse): boolean {
    return this.canApprove() && this.isPending(row) && !this.isOwnReport(row);
  }

  protected hasActiveProductFilter(): boolean {
    return (this.dataSource.query().filters['productQuery'] ?? '') !== '';
  }

  private resolveApproveErrorMessage(err: unknown): string {
    if (!isApiError(err)) {
      return 'No se pudo aprobar la merma.';
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
