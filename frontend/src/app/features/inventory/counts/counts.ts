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
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { Observable } from 'rxjs';
import { InventoryCountResponse } from '../../../api/models/inventory-count-response';
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
import {
  COUNT_STATUS_OPTIONS,
  COUNT_TYPE_OPTIONS,
  CountStatusCode,
  CountTypeCode,
  canRevealBlindValues,
  countStatusLabel,
  countTypeLabel,
  formatDateTime,
} from './counts.models';
import { CountsService } from './counts.service';
import { CreateCountDialog } from './create-count-dialog';

/**
 * Bandeja paginada de tomas físicas de inventario por local (`HU-INV-06`, `API.md` F.2).
 *
 * Permite filtrar por estado y alcance, iniciar nuevas tomas (`inventory:count:manage`) y abrir
 * cualquier toma para registrar conteos o ejecutar el flujo de revisión y aprobación.
 */
@Component({
  selector: 'pos-counts',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './counts.html',
  styleUrl: './counts.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Counts implements OnInit, OnDestroy {
  private readonly countsService = inject(CountsService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly numberTpl =
    viewChild.required<TemplateRef<CellContext<InventoryCountResponse>>>('numberTpl');
  private readonly scopeTpl =
    viewChild.required<TemplateRef<CellContext<InventoryCountResponse>>>('scopeTpl');
  private readonly blindTpl =
    viewChild.required<TemplateRef<CellContext<InventoryCountResponse>>>('blindTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<InventoryCountResponse>>>('statusTpl');
  private readonly progressTpl =
    viewChild.required<TemplateRef<CellContext<InventoryCountResponse>>>('progressTpl');
  private readonly startedTpl =
    viewChild.required<TemplateRef<CellContext<InventoryCountResponse>>>('startedTpl');
  private readonly diffTpl =
    viewChild.required<TemplateRef<CellContext<InventoryCountResponse>>>('diffTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<InventoryCountResponse>>>('actionsTpl');

  protected readonly statusOptions = COUNT_STATUS_OPTIONS;
  protected readonly typeOptions = COUNT_TYPE_OPTIONS;
  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );

  protected readonly canManage = computed(() => this.auth.hasPermission('inventory:count:manage'));
  protected readonly canApprove = computed(() =>
    this.auth.hasPermission('inventory:count:approve'),
  );
  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));

  protected readonly filterForm = this.fb.group({
    status: [null as CountStatusCode | null],
    countType: [null as CountTypeCode | null],
  });

  protected readonly dataSource = new PaginatedDataSource<InventoryCountResponse>(
    (params) =>
      this.countsService.listCounts(
        {
          status: (params.get('status') as CountStatusCode | null) ?? undefined,
          countType: (params.get('countType') as CountTypeCode | null) ?? undefined,
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<InventoryCountResponse>>,
    { sort: [{ field: 'createdAt', direction: 'desc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<InventoryCountResponse>[]>(() => {
    const cols: TableColumn<InventoryCountResponse>[] = [
      { key: 'number', header: 'Número', sortField: 'number', template: this.numberTpl() },
      { key: 'countType', header: 'Alcance', sortField: 'countType', template: this.scopeTpl() },
      { key: 'blind', header: 'Modalidad', template: this.blindTpl() },
      { key: 'status', header: 'Estado', sortField: 'status', template: this.statusTpl() },
      { key: 'progress', header: 'Avance', template: this.progressTpl() },
      { key: 'startedAt', header: 'Inicio', sortField: 'startedAt', template: this.startedTpl() },
    ];
    if (this.canReadCosts()) {
      cols.push({
        key: 'differenceSummary',
        header: 'Diferencias valorizadas',
        template: this.diffTpl(),
        align: 'end',
      });
    }
    cols.push({
      key: 'actions',
      header: 'Acciones',
      template: this.actionsTpl(),
      align: 'end',
    });
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
      countType: raw.countType,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ status: null, countType: null });
    this.dataSource.setFilters({});
  }

  protected openCreateDialog(): void {
    if (!this.canManage()) {
      return;
    }
    const ref = this.dialog.open<CreateCountDialog, unknown, InventoryCountResponse>(
      CreateCountDialog,
      { width: '500px' },
    );
    ref.afterClosed().subscribe((created) => {
      if (!created) {
        return;
      }
      this.snackBar.open(`Toma ${created.number ?? ''} iniciada.`, 'Cerrar', { duration: 4000 });
      if (created.id !== undefined) {
        void this.router.navigate(['/inventario/tomas', created.id]);
      } else {
        this.dataSource.reload();
      }
    });
  }

  protected openDetail(count: InventoryCountResponse): void {
    if (count.id === undefined) {
      return;
    }
    void this.router.navigate(['/inventario/tomas', count.id]);
  }

  protected statusLabel(status: InventoryCountResponse['status']): string {
    return countStatusLabel(status);
  }

  protected typeLabel(countType: InventoryCountResponse['countType']): string {
    return countTypeLabel(countType);
  }

  protected formatDate(iso: string | undefined): string {
    return formatDateTime(iso);
  }

  protected progressPercent(row: InventoryCountResponse): number {
    const total = row.progress?.totalLines ?? 0;
    const counted = row.progress?.countedLines ?? 0;
    if (total <= 0) {
      return 0;
    }
    return Math.min(100, Math.round((counted / total) * 100));
  }

  protected canShowDiff(row: InventoryCountResponse): boolean {
    return this.canReadCosts() && canRevealBlindValues(row, this.canApprove());
  }

  protected netDifference(row: InventoryCountResponse): number {
    const valueIn = row.differenceSummary?.valueIn ?? 0;
    const valueOut = row.differenceSummary?.valueOut ?? 0;
    return valueIn - valueOut;
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
}
