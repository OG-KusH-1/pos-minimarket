import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ExpiryAlertResponse } from '../../../api/models/expiry-alert-response';
import { StockAlertResponse } from '../../../api/models/stock-alert-response';
import { StockResponse } from '../../../api/models/stock-response';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ClpPipe, QtyPipe } from '../../../shared/pipes';
import { formatLocalDate, toQtyUnit } from './stock.models';
import { StockService } from './stock.service';

/**
 * Panel de alertas de inventario del local activo (`GET /api/v1/stock/alerts`, `HU-INV-04`).
 *
 * Presenta los indicadores de productos bajo mínimo, productos en cero y lotes próximos a vencer,
 * junto con las tablas de detalle y accesos rápidos para filtrar la grilla, editar límites o
 * consultar el kardex.
 */
@Component({
  selector: 'pos-stock-alerts',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule,
    QtyPipe,
  ],
  templateUrl: './stock-alerts.html',
  styleUrl: './stock-alerts.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StockAlerts implements OnInit {
  private readonly stockService = inject(StockService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);

  /** Si es `true`, las tablas de detalle de alertas se muestran expandidas inicialmente. */
  readonly expandedByDefault = input(false);

  /** Se emite cuando el usuario pulsa un resumen para filtrar el listado principal de stock. */
  readonly filterStatus = output<'BELOW_MIN' | 'ZERO'>();
  /** Se emite cuando el usuario solicita editar los límites de un producto bajo mínimo. */
  readonly editLimits = output<StockResponse>();
  /** Se emite cuando el usuario solicita navegar al kardex de un producto. */
  readonly viewKardex = output<number>();

  protected readonly alerts = signal<StockAlertResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly expanded = signal(false);

  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));
  protected readonly canUpdateLimits = computed(() =>
    this.auth.hasPermission('inventory:stock-limits:update'),
  );
  protected readonly canReadKardex = computed(() =>
    this.auth.hasPermission('inventory:kardex:read'),
  );

  protected readonly belowMin = computed<readonly StockResponse[]>(
    () => this.alerts()?.belowMin ?? [],
  );
  protected readonly expiring = computed<readonly ExpiryAlertResponse[]>(
    () => this.alerts()?.expiring ?? [],
  );
  protected readonly belowMinCount = computed(() => this.belowMin().length);
  protected readonly zeroCount = computed(() => this.alerts()?.zeroCount ?? 0);
  protected readonly expiringCount = computed(() => this.alerts()?.expiringCount ?? 0);
  protected readonly hasDetailItems = computed(
    () => this.belowMinCount() > 0 || this.expiring().length > 0,
  );

  protected readonly belowMinColumns = computed<readonly string[]>(() => {
    const cols = ['sku', 'name', 'categoryName', 'quantity', 'minQty'];
    if (this.canReadCosts()) {
      cols.push('valuation');
    }
    if (this.canUpdateLimits() || this.canReadKardex()) {
      cols.push('actions');
    }
    return cols;
  });

  protected readonly expiringColumns = computed<readonly string[]>(() => {
    const cols = ['sku', 'name', 'lotNumber', 'expiryDate', 'daysToExpiry', 'quantity'];
    if (this.canReadCosts()) {
      cols.push('valuation');
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
        this.reload();
      }
    });
  }

  ngOnInit(): void {
    this.expanded.set(this.expandedByDefault());
    this.initialized = true;
    this.lastLoadedStoreId = this.storeContext.storeId();
    this.reload();
  }

  /** Recarga las alertas de stock del local activo. */
  reload(): void {
    this.loading.set(true);
    this.error.set(null);

    this.stockService.getAlerts(withoutErrorNotification()).subscribe({
      next: (data) => {
        this.alerts.set(data);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(
          isApiError(err) ? err.message : 'No se pudieron cargar las alertas de inventario.',
        );
        this.loading.set(false);
      },
    });
  }

  protected toggleExpanded(): void {
    this.expanded.update((open) => !open);
  }

  protected openExpiringDetail(): void {
    this.expanded.set(true);
  }

  protected unitFor(item: StockResponse) {
    return toQtyUnit(item.unitOfMeasure);
  }

  protected formatDate(isoDate: string | undefined): string {
    return formatLocalDate(isoDate);
  }

  protected formatDaysToExpiry(days: number | undefined): string {
    if (days === undefined || days === null) {
      return '—';
    }
    if (days < 0) {
      return `Vencido (${Math.abs(days)} d)`;
    }
    if (days === 0) {
      return 'Vence hoy';
    }
    return days === 1 ? '1 día' : `${days} días`;
  }
}
