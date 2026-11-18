import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  effect,
  inject,
  output,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable } from 'rxjs';
import { ExpiryAlertResponse } from '../../../api/models/expiry-alert-response';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PageResponse } from '../../../shared/models/page-response';
import { ClpPipe, QtyPipe } from '../../../shared/pipes';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { formatDaysToExpiry, formatLotLocalDate } from './lots.models';
import { LotsService } from './lots.service';

/**
 * Vista paginada de alertas de lotes vencidos o próximos a vencer (`GET /api/v1/expiry-alerts`,
 * `HU-INV-08`).
 *
 * Permite ajustar la ventana en días (`days >= 0`, o usar el valor configurado en el local por
 * defecto) y oculta la columna de valorización cuando el usuario no posee `catalog:cost:read`.
 */
@Component({
  selector: 'pos-expiry-alerts-tab',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTooltipModule,
    PaginatedTable,
    QtyPipe,
    ReactiveFormsModule,
  ],
  template: `
    <div class="alerts-tab">
      <form class="filters" [formGroup]="filterForm" (ngSubmit)="applyFilters()">
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="days-field">
          <mat-label>Ventana (días)</mat-label>
          <input
            matInput
            type="number"
            min="0"
            step="1"
            formControlName="days"
            placeholder="Configurado en local"
          />
          <mat-hint>Déjalo vacío para usar el parámetro del local.</mat-hint>
          @if (filterForm.controls.days.hasError('min')) {
            <mat-error>Debe ser mayor o igual a 0.</mat-error>
          }
        </mat-form-field>

        <div class="filter-actions">
          <button mat-stroked-button type="submit">Aplicar ventana</button>
          <button mat-button type="button" (click)="clearFilters()">Usar por defecto</button>
        </div>
      </form>

      <pos-paginated-table
        [dataSource]="dataSource"
        [columns]="columns()"
        label="Alertas de vencimiento de lotes"
        emptyMessage="No hay lotes vencidos ni próximos a vencer en la ventana indicada."
      />

      <ng-template #expiryTpl let-row>
        {{ formatLocalDate(row.expiryDate) }}
      </ng-template>

      <ng-template #daysTpl let-row>
        <span
          class="badge"
          [class.badge--expired]="(row.daysToExpiry ?? 0) < 0"
          [class.badge--urgent]="(row.daysToExpiry ?? 0) >= 0 && (row.daysToExpiry ?? 0) <= 7"
          [class.badge--soon]="(row.daysToExpiry ?? 0) > 7"
        >
          {{ formatDays(row.daysToExpiry) }}
        </span>
      </ng-template>

      <ng-template #qtyTpl let-row>
        <span class="qty-value">{{ row.quantity | qty }}</span>
      </ng-template>

      <ng-template #valuationTpl let-row>
        {{ row.valuation !== undefined && row.valuation !== null ? (row.valuation | clp) : '—' }}
      </ng-template>

      <ng-template #actionsTpl let-row>
        @if (canReadKardex() && row.productId !== undefined) {
          <button
            mat-icon-button
            type="button"
            matTooltip="Ver movimientos en kardex"
            [attr.aria-label]="'Ver kardex de lote ' + (row.lotNumber || row.lotId)"
            (click)="viewKardex.emit({ productId: row.productId, lotId: row.lotId })"
          >
            <mat-icon>history</mat-icon>
          </button>
        }
      </ng-template>
    </div>
  `,
  styles: `
    .alerts-tab {
      display: flex;
      flex-direction: column;
      gap: 16px;
      padding-top: 16px;
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: 12px;
    }
    .days-field {
      width: 220px;
    }
    .filter-actions {
      display: flex;
      gap: 8px;
      padding-top: 4px;
    }
    .qty-value {
      font-weight: 500;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 999px;
      font-size: 0.75rem;
      font-weight: 500;

      &--expired {
        background: var(--mat-sys-error-container);
        color: var(--mat-sys-on-error-container);
      }
      &--urgent {
        background: var(--mat-sys-tertiary-container);
        color: var(--mat-sys-on-tertiary-container);
      }
      &--soon {
        background: var(--mat-sys-secondary-container);
        color: var(--mat-sys-on-secondary-container);
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExpiryAlertsTab implements OnInit, OnDestroy {
  private readonly lotsService = inject(LotsService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly fb = inject(NonNullableFormBuilder);

  /** Se emite al solicitar navegar al kardex del producto/lote alertado. */
  readonly viewKardex = output<{ readonly productId: number; readonly lotId?: number }>();

  private readonly expiryTpl =
    viewChild.required<TemplateRef<CellContext<ExpiryAlertResponse>>>('expiryTpl');
  private readonly daysTpl =
    viewChild.required<TemplateRef<CellContext<ExpiryAlertResponse>>>('daysTpl');
  private readonly qtyTpl =
    viewChild.required<TemplateRef<CellContext<ExpiryAlertResponse>>>('qtyTpl');
  private readonly valuationTpl =
    viewChild.required<TemplateRef<CellContext<ExpiryAlertResponse>>>('valuationTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<ExpiryAlertResponse>>>('actionsTpl');

  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));
  protected readonly canReadKardex = computed(() =>
    this.auth.hasPermission('inventory:kardex:read'),
  );

  protected readonly filterForm = this.fb.group({
    days: [null as number | null, [Validators.min(0)]],
  });

  protected readonly dataSource = new PaginatedDataSource<ExpiryAlertResponse>(
    (params) =>
      this.lotsService.listExpiryAlerts(
        {
          days: this.parseNumberParam(params.get('days')),
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<ExpiryAlertResponse>>,
    { sort: [{ field: 'expiryDate', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<ExpiryAlertResponse>[]>(() => {
    const cols: TableColumn<ExpiryAlertResponse>[] = [
      { key: 'sku', header: 'SKU', sortField: 'sku' },
      { key: 'name', header: 'Producto', sortField: 'name' },
      { key: 'lotNumber', header: 'Lote', sortField: 'lotNumber' },
      {
        key: 'expiryDate',
        header: 'Vencimiento',
        sortField: 'expiryDate',
        template: this.expiryTpl(),
      },
      {
        key: 'daysToExpiry',
        header: 'Estado',
        sortField: 'daysToExpiry',
        template: this.daysTpl(),
      },
      {
        key: 'quantity',
        header: 'Saldo',
        sortField: 'quantity',
        template: this.qtyTpl(),
        align: 'end',
      },
    ];
    if (this.canReadCosts()) {
      cols.push({
        key: 'valuation',
        header: 'Valorización',
        template: this.valuationTpl(),
        align: 'end',
      });
    }
    if (this.canReadKardex()) {
      cols.push({ key: 'actions', header: 'Acciones', template: this.actionsTpl(), align: 'end' });
    }
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

  /** Recarga las alertas de vencimiento (p. ej. tras editar un lote en la otra pestaña). */
  reload(): void {
    this.dataSource.reload();
  }

  protected applyFilters(): void {
    if (this.filterForm.invalid) {
      this.filterForm.markAllAsTouched();
      return;
    }
    const rawDays = this.filterForm.controls.days.getRawValue();
    const filters: Filters = {
      days: rawDays !== null && rawDays !== ('' as unknown) ? Math.trunc(Number(rawDays)) : null,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ days: null });
    this.dataSource.setFilters({});
  }

  protected formatLocalDate(iso: string | undefined): string {
    return formatLotLocalDate(iso);
  }

  protected formatDays(days: number | undefined): string {
    return formatDaysToExpiry(days);
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
}
