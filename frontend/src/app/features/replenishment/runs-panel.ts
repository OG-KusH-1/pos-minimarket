import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { ReplenishmentRunResponse } from '../../api/models/replenishment-run-response';
import { AuthService } from '../../core/auth/auth.service';
import { isApiError } from '../../core/http/api-error';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { StoreContextService } from '../../core/store-context/store-context.service';
import {
  CellContext,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../shared/ui/paginated-table';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog';
import {
  countsSummary,
  formatDateTime,
  formatDateOnly,
  runStatusLabel,
} from './replenishment.models';
import { ReplenishmentService } from './replenishment.service';

/**
 * Bandeja de corridas de reabastecimiento del local activo (`HU-PRV-09`, `API.md` F.2).
 *
 * Lista `GET /replenishment/runs` con paginación y permite ejecutar una nueva corrida
 * (`POST /replenishment/runs`, [I] con `withIdempotency`) previa confirmación; al terminar se
 * navega al detalle para revisar y editar las sugerencias.
 */
@Component({
  selector: 'pos-replenishment-runs-panel',
  imports: [MatButtonModule, MatIconModule, PaginatedTable],
  templateUrl: './runs-panel.html',
  styleUrl: './runs-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunsPanel implements OnInit, OnDestroy {
  private readonly replenishmentService = inject(ReplenishmentService);
  private readonly auth = inject(AuthService);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  protected readonly storeContext = inject(StoreContextService);

  private readonly startedTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentRunResponse>>>('startedTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentRunResponse>>>('statusTpl');
  private readonly countsTpl =
    viewChild.required<TemplateRef<CellContext<ReplenishmentRunResponse>>>('countsTpl');

  protected readonly canExecute = computed(() =>
    this.auth.hasPermission('replenishment:run:execute'),
  );
  protected readonly executing = signal(false);

  /** Fuente paginada de corridas; solo `id` es ordenable (orden estable del servidor). */
  protected readonly dataSource = new PaginatedDataSource<ReplenishmentRunResponse>(
    (params) =>
      this.replenishmentService.listRuns(
        {
          page: Number(params.get('page') ?? 0),
          size: Number(params.get('size') ?? 20),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ),
    { sort: [{ field: 'id', direction: 'desc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<ReplenishmentRunResponse>[]>(() => [
    { key: 'id', header: 'Corrida', sortField: 'id' },
    { key: 'asOfDate', header: 'Corte', cell: (row) => formatDateOnly(row.asOfDate) },
    { key: 'startedAt', header: 'Inicio', sortField: 'startedAt', template: this.startedTpl() },
    { key: 'status', header: 'Estado', template: this.statusTpl() },
    {
      key: 'linesCount',
      header: 'Líneas',
      cell: (row) => String(row.linesCount ?? 0),
      align: 'end',
    },
    { key: 'counts', header: 'Resumen', template: this.countsTpl() },
  ]);

  ngOnInit(): void {
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected executeRun(): void {
    if (!this.canExecute() || this.executing()) {
      return;
    }
    this.confirmDialog
      .confirm({
        title: 'Ejecutar corrida de reabastecimiento',
        message: `Se recalcularán las sugerencias para ${this.storeContext.activeStore()?.name ?? 'el local activo'} con la fecha de corte de hoy. La corrida se ejecuta en el servidor y puede tardar unos segundos.`,
        confirmLabel: 'Ejecutar corrida',
      })
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        this.executing.set(true);
        this.replenishmentService.executeRun(undefined, withoutErrorNotification()).subscribe({
          next: (created) => {
            this.executing.set(false);
            this.snackBar.open(
              `Corrida #${created.id ?? ''} completada con ${created.linesCount ?? 0} línea(s).`,
              'Cerrar',
              { duration: 4000 },
            );
            if (created.id !== undefined) {
              void this.router.navigate(['/reabastecimiento', created.id]);
            } else {
              this.dataSource.reload();
            }
          },
          error: (err: unknown) => {
            this.executing.set(false);
            this.snackBar.open(
              isApiError(err) ? err.message : 'No se pudo ejecutar la corrida.',
              'Cerrar',
              { duration: 6000 },
            );
          },
        });
      });
  }

  protected openDetail(row: ReplenishmentRunResponse): void {
    if (row.id !== undefined) {
      void this.router.navigate(['/reabastecimiento', row.id]);
    }
  }

  protected statusText(row: ReplenishmentRunResponse): string {
    return runStatusLabel(row.status);
  }

  protected startedText(row: ReplenishmentRunResponse): string {
    return formatDateTime(row.startedAt);
  }

  protected countsText(row: ReplenishmentRunResponse): string {
    return countsSummary(row.countsByStatus);
  }
}
