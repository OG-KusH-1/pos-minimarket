import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { Router } from '@angular/router';
import { EMPTY, Subject, catchError, switchMap } from 'rxjs';
import { DashboardResponse } from '../../api/models/dashboard-response';
import { AuthService } from '../../core/auth/auth.service';
import { isApiError } from '../../core/http/api-error';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { StoreContextService } from '../../core/store-context/store-context.service';
import { ClpPipe, QtyPipe } from '../../shared/pipes';
import {
  DashboardAlertDescriptor,
  DashboardExportFormat,
  DashboardFilter,
  DownloadedDashboardFile,
  buildAlertDescriptors,
  formatInteger,
  formatLocalDate,
  formatPctVariation,
  formatPercent,
  normalizePaymentMix,
  normalizeTopProducts,
  totalAlertsCount,
} from './dashboard.models';
import { DashboardService } from './dashboard.service';
import { HourlySalesChart } from './hourly-sales-chart';

/** Dispara la descarga en el navegador de un archivo exportado del dashboard. */
function triggerBrowserDownload(file: DownloadedDashboardFile): void {
  if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
    return;
  }
  const objectUrl = URL.createObjectURL(file.blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = file.fileName;
  link.click();
  if (typeof URL.revokeObjectURL === 'function') {
    URL.revokeObjectURL(objectUrl);
  }
}

/**
 * Pantalla ejecutiva de «Inicio» (`/inicio`, `HU-REP-01`, `HU-REP-10`).
 *
 * Presenta los indicadores diarios del negocio (ventas netas, transacciones, ticket promedio,
 * variación vs día anterior), la serie horaria de 24 horas (`HourlySalesChart`), el mix de medios
 * de pago, el ranking top 10 de productos y las 7 alertas operativas vigentes. Reacciona al
 * cambio de local activo en `StoreContextService` y permite filtrar por fecha, consolidar
 * múltiples locales autorizados y exportar a XLSX o PDF (`report:export:execute`).
 */
@Component({
  selector: 'pos-dashboard',
  imports: [
    ClpPipe,
    HourlySalesChart,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTableModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Dashboard implements OnInit {
  private readonly dashboardService = inject(DashboardService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly data = signal<DashboardResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly exporting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly selectedDate = signal<string | undefined>(undefined);
  protected readonly selectedStoreIds = signal<readonly number[]>([]);

  protected readonly filterForm = this.fb.group({
    date: [''],
    storeIds: [[] as number[]],
  });

  protected readonly topProductColumns: readonly string[] = ['rank', 'name', 'qty', 'total'];

  protected readonly availableStores = computed(() => this.storeContext.availableStores());
  protected readonly showStoreSelector = computed(() => this.availableStores().length > 1);
  protected readonly canExport = computed(() => this.auth.hasPermission('report:export:execute'));

  protected readonly consolidatedStoreLabel = computed(() => {
    const activeName = this.storeContext.activeStore()?.name ?? 'Local activo';
    const selected = this.selectedStoreIds();
    const stores = this.availableStores();
    if (selected.length === 0) {
      return activeName;
    }
    const names = selected
      .map((id) => stores.find((store) => store.id === id)?.name)
      .filter((name): name is string => Boolean(name));
    if (names.length === 0) {
      return activeName;
    }
    return names.length <= 2 ? names.join(', ') : `${names.length} locales consolidados`;
  });

  protected readonly displayDate = computed(() =>
    formatLocalDate(this.data()?.date ?? this.selectedDate()),
  );

  protected readonly topProducts = computed(() => normalizeTopProducts(this.data()?.topProducts));

  protected readonly paymentMix = computed(() => normalizePaymentMix(this.data()?.paymentMix));
  protected readonly alertDescriptors = computed(() => buildAlertDescriptors(this.data()?.alerts));
  protected readonly totalAlerts = computed(() => totalAlertsCount(this.data()?.alerts));

  private readonly reloadTrigger$ = new Subject<void>();
  private initialized = false;
  private lastLoadedStoreId: number | null = null;

  constructor() {
    effect(() => {
      const storeId = this.storeContext.storeId();
      if (this.initialized && storeId !== this.lastLoadedStoreId) {
        this.lastLoadedStoreId = storeId;
        this.syncStoreSelection(storeId);
        this.reloadTrigger$.next();
      }
    });
  }

  ngOnInit(): void {
    this.initialized = true;
    this.lastLoadedStoreId = this.storeContext.storeId();
    this.syncStoreSelection(this.lastLoadedStoreId);

    this.reloadTrigger$
      .pipe(
        switchMap(() => {
          this.loading.set(true);
          this.error.set(null);
          return this.dashboardService
            .getDashboard(this.currentApiFilter(), withoutErrorNotification())
            .pipe(
              catchError((err: unknown) => {
                this.error.set(
                  isApiError(err) ? err.message : 'No se pudo cargar el dashboard del día.',
                );
                this.loading.set(false);
                return EMPTY;
              }),
            );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.data.set(response);
        this.loading.set(false);
        if (!this.selectedDate() && response.date && !this.filterForm.controls.date.value) {
          this.filterForm.controls.date.setValue(response.date, { emitEvent: false });
        }
      });

    this.reloadTrigger$.next();
  }

  /** Aplica los filtros del formulario y recarga las métricas del dashboard. */
  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    const trimmedDate = raw.date.trim();
    this.selectedDate.set(trimmedDate || undefined);

    const activeStoreId = this.storeContext.storeId();
    const cleanedStoreIds = raw.storeIds.filter((id) => Number.isInteger(id) && id > 0);
    if (cleanedStoreIds.length > 0) {
      this.selectedStoreIds.set(cleanedStoreIds);
    } else if (activeStoreId !== null) {
      this.selectedStoreIds.set([activeStoreId]);
      this.filterForm.controls.storeIds.setValue([activeStoreId], { emitEvent: false });
    } else {
      this.selectedStoreIds.set([]);
    }

    this.reloadTrigger$.next();
  }

  /** Vuelve a consultar la fecha de negocio actual (hoy según reloj del servidor). */
  protected resetToToday(): void {
    this.selectedDate.set(undefined);
    this.filterForm.controls.date.setValue('', { emitEvent: false });
    this.reloadTrigger$.next();
  }

  /** Reintenta o actualiza manualmente el dashboard con los filtros vigentes. */
  protected reload(): void {
    this.reloadTrigger$.next();
  }

  /** Exporta el dashboard actual en Excel o PDF (`report:export:execute`). */
  protected exportAs(format: DashboardExportFormat): void {
    if (!this.canExport() || this.exporting()) {
      return;
    }
    this.exporting.set(true);
    this.dashboardService
      .exportDashboard({ ...this.currentApiFilter(), format }, withoutErrorNotification())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (file) => {
          this.exporting.set(false);
          triggerBrowserDownload(file);
          this.snackBar.open(`Dashboard exportado (${file.fileName}).`, 'Cerrar', {
            duration: 4000,
          });
        },
        error: (err: unknown) => {
          this.exporting.set(false);
          this.snackBar.open(
            isApiError(err) ? err.message : 'No se pudo exportar el dashboard.',
            'Cerrar',
            { duration: 6000 },
          );
        },
      });
  }

  /** Indica si el usuario puede navegar hacia el módulo asociado a una alerta operativa. */
  protected canNavigateToAlert(alert: DashboardAlertDescriptor): boolean {
    return this.auth.hasPermission(alert.permission);
  }

  /** Navega a la pantalla operativa correspondiente cuando el usuario tiene permiso. */
  protected navigateToAlert(alert: DashboardAlertDescriptor): void {
    if (!this.canNavigateToAlert(alert)) {
      return;
    }
    void this.router.navigate(
      [alert.route],
      alert.queryParams ? { queryParams: alert.queryParams } : undefined,
    );
  }

  protected formatInt(value: number | undefined | null): string {
    return formatInteger(value);
  }

  protected variationText(pct: number | undefined | null): string {
    return formatPctVariation(pct);
  }

  protected formatShare(pct: number | undefined | null): string {
    return formatPercent(pct);
  }

  private syncStoreSelection(storeId: number | null): void {
    const ids = storeId !== null ? [storeId] : [];
    this.selectedStoreIds.set(ids);
    this.filterForm.controls.storeIds.setValue(ids, { emitEvent: false });
  }

  private currentApiFilter(): DashboardFilter {
    const activeStoreId = this.storeContext.storeId();
    const selected = this.selectedStoreIds();
    const isSingleActiveStore =
      selected.length === 0 || (selected.length === 1 && selected[0] === activeStoreId);

    return {
      date: this.selectedDate(),
      storeIds: isSingleActiveStore ? undefined : selected,
    };
  }
}
