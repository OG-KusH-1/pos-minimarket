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
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom, map } from 'rxjs';
import { HttpParams } from '@angular/common/http';
import { ReplenishmentParamsResponse } from '../../api/models/replenishment-params-response';
import { ReplenishmentParamsRequest } from '../../api/models/replenishment-params-request';
import { AuthService } from '../../core/auth/auth.service';
import { ApiError, isApiError } from '../../core/http/api-error';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { StoreContextService } from '../../core/store-context/store-context.service';
import { PageResponse } from '../../shared/models/page-response';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../shared/ui/paginated-table';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog';
import { ParamScope, PARAM_SCOPE_OPTIONS, paramsRowKey, scopeLabel } from './replenishment.models';
import { ReplenishmentService } from './replenishment.service';
import { UpsertParamsDialog, UpsertParamsDialogData } from './upsert-params-dialog';

/**
 * Bandeja de parámetros de reabastecimiento por alcance (`HU-PRV-11`, `API.md` F.2).
 *
 * Consulta `GET /replenishment/params` con filtro de alcance y permite crear, editar y borrar
 * filas (`replenishment:params:manage`) con confirmación; el GLOBAL no se puede borrar y ese
 * error (409 `ENTITY_IN_USE`) se muestra con un mensaje claro.
 */
@Component({
  selector: 'pos-replenishment-params-panel',
  imports: [MatButtonModule, MatIconModule, MatSelectModule, PaginatedTable, ReactiveFormsModule],
  templateUrl: './params-panel.html',
  styleUrl: './params-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParamsPanel implements OnInit, OnDestroy {
  private readonly replenishmentService = inject(ReplenishmentService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly scopeTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentParamsResponse>>>('scopeTpl');
  private readonly enabledTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentParamsResponse>>>('enabledTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentParamsResponse>>>('actionsTpl');

  protected readonly scopeOptions = PARAM_SCOPE_OPTIONS;
  protected readonly canManage = computed(() =>
    this.auth.hasPermission('replenishment:params:manage'),
  );

  protected readonly filterForm = this.fb.group({
    scope: null as ParamScope | null,
  });

  /**
   * Fuente paginada de la bandeja. El endpoint devuelve un arreglo plano (sin paginación
   * server-side), así que el fetcher lo adapta al contrato `PageResponse` aplicando el filtro
   * de alcance como query param y ordenando por identificador para una vista estable.
   */
  protected readonly dataSource = new PaginatedDataSource<ReplenishmentParamsResponse>(
    (params) =>
      this.replenishmentService
        .listParams(
          (params.get('scope') as ParamScope | null) ?? undefined,
          withoutErrorNotification(),
        )
        .pipe(map((rows) => this.toPageResponse(rows, params))),
    { sort: [] },
  );

  protected readonly columns = computed<readonly TableColumn<ReplenishmentParamsResponse>[]>(() => {
    const cols: TableColumn<ReplenishmentParamsResponse>[] = [
      { key: 'scope', header: 'Alcance', template: this.scopeTpl() },
      { key: 'key', header: 'Clave', cell: (row) => paramsRowKey(row) },
      { key: 'enabled', header: 'Participa', template: this.enabledTpl() },
      {
        key: 'windowDays',
        header: 'Ventana W',
        cell: (row) => this.numOrDash(row.windowDays),
        align: 'end',
      },
      {
        key: 'minHistoryDays',
        header: 'Mín. historia',
        cell: (row) => this.numOrDash(row.minHistoryDays),
        align: 'end',
      },
      {
        key: 'safetyMinDays',
        header: 'SS (días)',
        cell: (row) => this.numOrDash(row.safetyMinDays),
        align: 'end',
      },
      {
        key: 'serviceLevelZ',
        header: 'Factor z',
        cell: (row) => this.numOrDash(row.serviceLevelZ),
        align: 'end',
      },
      {
        key: 'reviewPeriodDays',
        header: 'Período R',
        cell: (row) => this.numOrDash(row.reviewPeriodDays),
        align: 'end',
      },
      {
        key: 'shelfLifeFactor',
        header: 'Factor vida útil',
        cell: (row) => this.numOrDash(row.shelfLifeFactor),
        align: 'end',
      },
      {
        key: 'outlierK',
        header: 'k atípicos',
        cell: (row) => this.numOrDash(row.outlierK),
        align: 'end',
      },
    ];
    if (this.canManage()) {
      cols.push({ key: 'actions', header: 'Acciones', template: this.actionsTpl(), align: 'end' });
    }
    return cols;
  });

  ngOnInit(): void {
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected applyFilters(): void {
    const filters: Filters = { scope: this.filterForm.getRawValue().scope };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ scope: null });
    this.dataSource.setFilters({});
  }

  protected openCreateDialog(): void {
    if (!this.canManage()) {
      return;
    }
    const ref = this.dialog.open<
      UpsertParamsDialog,
      UpsertParamsDialogData,
      ReplenishmentParamsRequest | undefined
    >(UpsertParamsDialog, {
      width: '560px',
      data: {
        storeId: this.storeContext.storeId(),
        storeName: this.storeContext.activeStore()?.name ?? 'Local activo',
      },
    });
    ref.afterClosed().subscribe((payload) => {
      if (!payload) {
        return;
      }
      void this.upsert(payload);
    });
  }

  protected openEditDialog(row: ReplenishmentParamsResponse): void {
    if (!this.canManage()) {
      return;
    }
    const ref = this.dialog.open<
      UpsertParamsDialog,
      UpsertParamsDialogData,
      ReplenishmentParamsRequest | undefined
    >(UpsertParamsDialog, {
      width: '560px',
      data: {
        existing: row,
        storeId: this.storeContext.storeId(),
        storeName: this.storeContext.activeStore()?.name ?? 'Local activo',
      },
    });
    ref.afterClosed().subscribe((payload) => {
      if (!payload) {
        return;
      }
      void this.upsert(payload);
    });
  }

  protected deleteRow(row: ReplenishmentParamsResponse): void {
    if (!this.canManage() || row.id === undefined) {
      return;
    }
    this.confirmDialog
      .confirm({
        title: 'Borrar parámetro',
        message: `¿Borrar la fila de ${scopeLabel(row.scope)} con clave «${paramsRowKey(row) || 'sin clave'}»? Los niveles inferiores heredarán del nivel superior.`,
        confirmLabel: 'Borrar',
        destructive: true,
      })
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        this.replenishmentService
          .deleteParam(row.id as number, withoutErrorNotification())
          .subscribe({
            next: () => {
              this.snackBar.open('Parámetro borrado.', 'Cerrar', { duration: 3500 });
              this.dataSource.reload();
            },
            error: (err: unknown) =>
              this.reportMutationError(err, 'No se pudo borrar el parámetro.'),
          });
      });
  }

  protected numOrDash(value: number | undefined | null): string {
    return value === undefined || value === null ? '—' : String(value);
  }

  protected scopeText(row: ReplenishmentParamsResponse): string {
    return scopeLabel(row.scope);
  }

  /** Ejecuta el upsert y notifica; los errores se muestran en el snackbar. */
  private async upsert(payload: ReplenishmentParamsRequest): Promise<void> {
    try {
      const saved = await firstValueFrom(
        this.replenishmentService.upsertParams(payload, withoutErrorNotification()),
      );
      this.snackBar.open(`Parámetro ${scopeLabel(saved.scope)} guardado.`, 'Cerrar', {
        duration: 3500,
      });
      this.dataSource.reload();
    } catch (err) {
      this.reportMutationError(err, 'No se pudo guardar el parámetro.');
    }
  }

  /** Muestra el error de una mutación con `ApiError` en el snackbar. */
  private reportMutationError(err: unknown, fallback: string): void {
    if (isApiError(err) && err.status === 409 && err.code === 'ENTITY_IN_USE') {
      this.snackBar.open(
        'El parámetro GLOBAL no se puede borrar: es la base de herencia de todos los alcances.',
        'Cerrar',
        { duration: 6000 },
      );
      return;
    }
    this.snackBar.open(err instanceof ApiError ? err.message : fallback, 'Cerrar', {
      duration: 6000,
    });
  }

  /** Adapta el arreglo del endpoint al contrato `PageResponse` que exige `PaginatedDataSource`. */
  private toPageResponse(
    rows: readonly ReplenishmentParamsResponse[],
    params: HttpParams,
  ): PageResponse<ReplenishmentParamsResponse> {
    const sorted = [...rows].sort((a, b) => (a.id ?? 0) - (b.id ?? 0));
    return {
      content: sorted,
      page: 0,
      size: sorted.length,
      totalElements: sorted.length,
      totalPages: 1,
      sort: params.getAll('sort') ?? [],
    };
  }
}
