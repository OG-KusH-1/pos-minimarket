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
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ProductSupplierResponse } from '../../api/models/product-supplier-response';
import { ReplenishmentLineResponse } from '../../api/models/replenishment-line-response';
import { ReplenishmentRunResponse } from '../../api/models/replenishment-run-response';
import { AuthService } from '../../core/auth/auth.service';
import { isApiError } from '../../core/http/api-error';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { StoreContextService } from '../../core/store-context/store-context.service';
import { ClpPipe, QtyPipe } from '../../shared/pipes';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../shared/ui/paginated-table';
import { GeneratedOrdersDialog, GeneratedOrdersDialogData } from './generated-orders-dialog';
import {
  LINE_FLAG_OPTIONS,
  LINE_STATUS_OPTIONS,
  LineFlag,
  LineStatusCode,
  countsSummary,
  flagLabel,
  formatDateOnly,
  formatDateTime,
  isDraftCandidate,
  lineStatusLabel,
  runStatusLabel,
} from './replenishment.models';
import { ReplenishmentService } from './replenishment.service';
import { LineEditDialogData, LineEditResult, RunLineEditDialog } from './run-line-edit-dialog';

/**
 * Detalle de una corrida de reabastecimiento: revisión y edición de sugerencias y generación
 * de borradores de OC por proveedor (`HU-PRV-09`, `HU-PRV-10`, `API.md` F.2).
 *
 * - Lista las líneas (`GET /runs/{id}/lines`) con flags del cálculo y filtros de estado.
 * - Edita unidades finales / proveedor / estado (`PATCH /replenishment/lines/{id}`) con
 *   validación de MOQ en el diálogo {@link RunLineEditDialog}.
 * - Genera borradores (`POST /runs/{id}/purchase-orders`, [I]): con líneas seleccionadas envía
 *   `lineIds`; sin selección envía el cuerpo vacío (comportamiento por defecto del backend).
 * - Los costos (`unitCostNet`, `estimatedCost`) solo se muestran con `catalog:cost:read`; el
 *   backend los enmascara como nulos para el resto de los usuarios y se pinta «—».
 */
@Component({
  selector: 'pos-replenishment-run-detail',
  imports: [
    QtyPipe,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './run-detail.html',
  styleUrl: './run-detail.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunDetail implements OnInit, OnDestroy {
  private readonly replenishmentService = inject(ReplenishmentService);
  private readonly auth = inject(AuthService);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly storeContext = inject(StoreContextService);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly selectTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentLineResponse>>>('selectTpl');
  private readonly productTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentLineResponse>>>('productTpl');
  private readonly supplierTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentLineResponse>>>('supplierTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentLineResponse>>>('statusTpl');
  private readonly flagsTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentLineResponse>>>('flagsTpl');
  private readonly finalUnitsTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentLineResponse>>>('finalUnitsTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentLineResponse>>>('actionsTpl');

  protected readonly run = signal<ReplenishmentRunResponse | null>(null);
  protected readonly lineStatusOptions = LINE_STATUS_OPTIONS;
  protected readonly lineFlagOptions = LINE_FLAG_OPTIONS;

  protected readonly selectedLineIds = signal<ReadonlySet<number>>(new Set());
  protected readonly mutating = signal(false);

  protected readonly canExecute = computed(() =>
    this.auth.hasPermission('replenishment:run:execute'),
  );
  protected readonly canCreateOrders = computed(() =>
    this.auth.hasPermission('purchasing:order:create'),
  );
  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));

  protected readonly canGenerate = computed(() => this.canExecute() && this.canCreateOrders());
  protected readonly selectedCount = computed(() => this.selectedLineIds().size);

  protected readonly runStatusText = computed(() => runStatusLabel(this.run()?.status));
  protected readonly runCountsText = computed(() => countsSummary(this.run()?.countsByStatus));

  protected readonly filterForm = this.fb.group({
    status: null as LineStatusCode | null,
    flag: null as LineFlag | null,
  });

  /** Fuente paginada de líneas de la corrida; orden fijo por identificador. */
  protected readonly dataSource = new PaginatedDataSource<ReplenishmentLineResponse>(
    (params) =>
      this.replenishmentService.listLines(
        {
          id: this.runId as number,
          status: (params.get('status') as LineStatusCode | null) ?? undefined,
          flag: (params.get('flag') as LineFlag | null) ?? undefined,
          page: Number(params.get('page') ?? 0),
          size: Number(params.get('size') ?? 20),
          sort: params.getAll('sort') ?? ['id,asc'],
        },
        withoutErrorNotification(),
      ),
    { sort: [{ field: 'id', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<ReplenishmentLineResponse>[]>(() => {
    const cols: TableColumn<ReplenishmentLineResponse>[] = [];
    if (this.canGenerate()) {
      cols.push({ key: 'select', header: '', template: this.selectTpl() });
    }
    cols.push(
      { key: 'product', header: 'Producto', template: this.productTpl() },
      { key: 'supplier', header: 'Proveedor', template: this.supplierTpl() },
      { key: 'status', header: 'Estado', template: this.statusTpl() },
      { key: 'flags', header: 'Flags', template: this.flagsTpl() },
      { key: 'onHand', header: 'Stock', cell: (row) => this.qtyText(row.onHand), align: 'end' },
      {
        key: 'inTransit',
        header: 'Tránsito',
        cell: (row) => this.qtyText(row.inTransit),
        align: 'end',
      },
      {
        key: 'avgDailyDemand',
        header: 'D media',
        cell: (row) => this.qtyText(row.avgDailyDemand),
        align: 'end',
      },
      {
        key: 'suggestedPurchaseUnits',
        header: 'Sugerido',
        cell: (row) => this.qtyText(row.suggestedPurchaseUnits),
        align: 'end',
      },
      {
        key: 'finalPurchaseUnits',
        header: 'Unid. finales',
        template: this.finalUnitsTpl(),
        align: 'end',
      },
    );
    if (this.canReadCosts()) {
      cols.push(
        {
          key: 'unitCostNet',
          header: 'Costo unit.',
          cell: (row) => this.costText(row.unitCostNet),
          align: 'end',
        },
        {
          key: 'estimatedCost',
          header: 'Costo línea',
          cell: (row) => this.costText(row.estimatedCost),
          align: 'end',
        },
      );
    }
    cols.push({ key: 'actions', header: 'Acciones', template: this.actionsTpl(), align: 'end' });
    return cols;
  });

  private runId: number | null = null;

  /** Pipes instanciados para formatear cantidades y costos dentro de las celdas (texto). */
  private readonly qtyPipe = new QtyPipe();
  private readonly clpPipe = new ClpPipe();

  /** Helper de etiqueta de flag expuesto para la plantilla. */
  protected readonly flagLabel = flagLabel;

  constructor() {
    effect(() => {
      const storeId = this.storeContext.storeId();
      if (this.runId !== null && storeId !== null) {
        this.reloadAll();
      }
    });
  }

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const raw = params.get('id');
      const parsed = raw ? Number(raw) : Number.NaN;
      if (!Number.isInteger(parsed) || parsed <= 0) {
        this.runId = null;
        this.run.set(null);
        return;
      }
      if (this.runId !== parsed) {
        this.runId = parsed;
        this.selectedLineIds.set(new Set());
        this.reloadAll();
      }
    });
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected goBack(): void {
    void this.router.navigate(['/reabastecimiento']);
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    const filters: Filters = { status: raw.status, flag: raw.flag };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ status: null, flag: null });
    this.dataSource.setFilters({});
  }

  /** Indica si la línea admite edición de unidades finales (`SUGGESTED`, `ACCEPTED` o `DISCARDED`). */
  protected isEditable(line: ReplenishmentLineResponse): boolean {
    return line.status === 'SUGGESTED' || line.status === 'ACCEPTED' || line.status === 'DISCARDED';
  }

  protected isRowSelected(line: ReplenishmentLineResponse): boolean {
    return line.id !== undefined && this.selectedLineIds().has(line.id);
  }

  protected toggleSelection(line: ReplenishmentLineResponse, checked: boolean): void {
    if (line.id === undefined || !isDraftCandidate(line)) {
      return;
    }
    this.selectedLineIds.update((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(line.id as number);
      } else {
        next.delete(line.id as number);
      }
      return next;
    });
  }

  protected isSelectable(line: ReplenishmentLineResponse): boolean {
    return isDraftCandidate(line);
  }

  /**
   * Genera borradores de OC con confirmación. Con líneas seleccionadas envía `lineIds`;
   * sin selección envía el cuerpo vacío y el backend agrupa las `SUGGESTED`/`ACCEPTED`
   * por proveedor (comportamiento por defecto, `PLAN.md` C.5).
   */
  protected generateDrafts(): void {
    if (!this.canGenerate() || this.runId === null || this.mutating()) {
      return;
    }
    const runId = this.runId;
    const selected = [...this.selectedLineIds()];
    const useSelection = selected.length > 0;
    const selectionText = useSelection
      ? `las ${selected.length} línea(s) seleccionada(s)`
      : 'todas las líneas sugeridas y aceptadas (comportamiento por defecto)';

    this.confirmDialog
      .confirm({
        title: 'Generar borradores de OC',
        message: `Se crearán órdenes de compra DRAFT por proveedor con ${selectionText}.`,
        confirmLabel: 'Generar borradores',
      })
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        this.mutating.set(true);
        // Cuerpo vacío = `lineIds` omitido = default del backend (PLAN.md C.5).
        const body = useSelection ? { lineIds: selected } : undefined;
        this.replenishmentService
          .generateDrafts(runId, body, withoutErrorNotification())
          .subscribe({
            next: (result) => {
              this.mutating.set(false);
              this.selectedLineIds.set(new Set());
              this.dialog.open<GeneratedOrdersDialog, GeneratedOrdersDialogData, unknown>(
                GeneratedOrdersDialog,
                { width: '480px', data: { orders: result.purchaseOrders ?? [] } },
              );
              this.reloadAll();
            },
            error: (err: unknown) => {
              this.mutating.set(false);
              this.reportMutationError(err, 'No se pudieron generar los borradores de OC.');
            },
          });
      });
  }

  /**
   * Abre el diálogo de edición de la línea. Antes consulta los candidatos activos
   * producto-proveedor para permitir cambiar el proveedor y validar el MOQ.
   */
  protected async openLineEditDialog(line: ReplenishmentLineResponse): Promise<void> {
    if (!this.canExecute() || line.id === undefined || this.mutating()) {
      return;
    }
    if (line.product?.id === undefined) {
      return;
    }
    this.mutating.set(true);
    let suppliers: readonly ProductSupplierResponse[] = [];
    try {
      suppliers = await firstValueFrom(
        this.replenishmentService.listProductSuppliers(
          { id: line.product.id },
          withoutErrorNotification(),
        ),
      );
    } catch {
      // Sin candidatos la edición solo permitirá descartar la línea o dejar 0 unidades.
    }
    this.mutating.set(false);

    const ref = this.dialog.open<RunLineEditDialog, LineEditDialogData, LineEditResult | undefined>(
      RunLineEditDialog,
      {
        width: '480px',
        data: { line, suppliers },
      },
    );
    ref.afterClosed().subscribe((payload) => {
      if (!payload) {
        return;
      }
      void this.applyLineUpdate(line.id as number, payload);
    });
  }

  protected runHeaderText(): string {
    const run = this.run();
    if (!run) {
      return '—';
    }
    return `Corrida #${run.id ?? ''} · corte ${formatDateOnly(run.asOfDate)} · ${formatDateTime(run.startedAt)}`;
  }

  protected statusLabelFor(line: ReplenishmentLineResponse): string {
    return lineStatusLabel(line.status);
  }

  protected qtyText(value: number | undefined | null): string {
    if (value === undefined || value === null) {
      return '—';
    }
    return this.qtyPipe.transform(value);
  }

  protected costText(value: number | undefined | null): string {
    if (value === undefined || value === null) {
      return '—';
    }
    return this.clpPipe.transform(value);
  }

  private async applyLineUpdate(lineId: number, payload: LineEditResult): Promise<void> {
    this.mutating.set(true);
    try {
      const updated = await firstValueFrom(
        this.replenishmentService.updateLine(lineId, payload, withoutErrorNotification()),
      );
      this.snackBar.open(`Sugerencia actualizada (${lineStatusLabel(updated.status)}).`, 'Cerrar', {
        duration: 3500,
      });
      this.reloadAll();
    } catch (err) {
      this.reportMutationError(err, 'No se pudo actualizar la sugerencia.');
    } finally {
      this.mutating.set(false);
    }
  }

  private reloadAll(): void {
    if (this.runId === null) {
      return;
    }
    void firstValueFrom(
      this.replenishmentService.getRun(this.runId, withoutErrorNotification()),
    ).then(
      (run) => this.run.set(run),
      () => undefined,
    );
    this.dataSource.reload();
  }

  private reportMutationError(err: unknown, fallback: string): void {
    if (isApiError(err) && err.status === 409) {
      // La línea ya está incluida en una OC: el servidor responde 409 INVALID_STATE_TRANSITION.
      this.snackBar.open(err.message, 'Cerrar', { duration: 6000 });
      return;
    }
    this.snackBar.open(isApiError(err) ? err.message : fallback, 'Cerrar', {
      duration: 6000,
    });
  }
}
