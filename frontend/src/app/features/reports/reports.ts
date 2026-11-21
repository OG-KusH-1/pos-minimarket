import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { Observable, Subscription, finalize, map } from 'rxjs';
import { AbcRow } from '../../api/models/abc-row';
import { CashDifferenceRow } from '../../api/models/cash-difference-row';
import { CategoryResponse } from '../../api/models/category-response';
import { StoreSummary } from '../../api/models/store-summary';
import { UserSummary } from '../../api/models/user-summary';
import { AuthService } from '../../core/auth/auth.service';
import { isApiError } from '../../core/http/api-error';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { StoreContextService } from '../../core/store-context/store-context.service';
import {
  ABC_METRIC_OPTIONS,
  AbcMetric,
  CategoryOption,
  ExportFormat,
  InventoryValuationGroupBy,
  MARGIN_GROUP_BY_OPTIONS,
  MAX_NO_ROTATION_DAYS,
  MAX_RANKING_LIMIT,
  MarginGroupBy,
  RANKING_METRIC_OPTIONS,
  RANKING_ORDER_OPTIONS,
  REPORT_DEFINITIONS,
  REPORT_PERMISSIONS,
  RankingMetric,
  RankingOrder,
  ReportColumnDef,
  ReportDefinition,
  ReportKey,
  ReportResultState,
  ReportRow,
  SALES_GROUP_BY_OPTIONS,
  SHRINKAGE_GROUP_BY_OPTIONS,
  SalesGroupBy,
  ShrinkageGroupBy,
  VALUATION_GROUP_BY_OPTIONS,
  WITH_DIFFERENCE_OPTIONS,
  addCalendarDays,
  buildReportColumns,
  defaultReportDateRange,
  describeDateRangeError,
  formatDateTime,
  integerRangeValidator,
  reportDateRangeValidator,
  toLocalIsoDate,
} from './reports.models';
import { ReportExportParams, ReportsService, triggerBrowserDownload } from './reports.service';

/** Aplana el árbol de categorías del catálogo para mostrar jerarquía en el selector. */
function flattenCategoryTree(
  categories: readonly CategoryResponse[],
  prefix = '',
): CategoryOption[] {
  const result: CategoryOption[] = [];
  for (const cat of categories) {
    if (cat.id === undefined || !cat.name) {
      continue;
    }
    const label = prefix ? `${prefix} › ${cat.name}` : cat.name;
    result.push({ id: cat.id, label });
    if (cat.children?.length) {
      result.push(...flattenCategoryTree(cat.children, label));
    }
  }
  return result;
}

/**
 * Pantalla de reportes operativos, análisis y exportación a Excel/PDF (`/reportes`,
 * `HU-REP-02..10`, `HU-MER-04`, `HU-MER-05`).
 *
 * Filtra las pestañas visibles, métricas sensibles (ABC por margen) y columnas de costos (valorización
 * en sin rotación) según los permisos efectivos del usuario, valida rangos de hasta 366 días con
 * `from <= to` y permite exportar cualquier reporte respetando los filtros aplicados.
 */
@Component({
  selector: 'pos-reports',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTableModule,
    MatTabsModule,
    ReactiveFormsModule,
  ],
  templateUrl: './reports.html',
  styleUrl: './reports.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Reports implements OnInit, OnDestroy {
  private readonly reportsService = inject(ReportsService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly salesGroupByOptions = SALES_GROUP_BY_OPTIONS;
  protected readonly rankingOrderOptions = RANKING_ORDER_OPTIONS;
  protected readonly rankingMetricOptions = RANKING_METRIC_OPTIONS;
  protected readonly marginGroupByOptions = MARGIN_GROUP_BY_OPTIONS;
  protected readonly valuationGroupByOptions = VALUATION_GROUP_BY_OPTIONS;
  protected readonly shrinkageGroupByOptions = SHRINKAGE_GROUP_BY_OPTIONS;
  protected readonly withDifferenceOptions = WITH_DIFFERENCE_OPTIONS;

  protected readonly canReadMargin = computed(() =>
    this.auth.hasPermission(REPORT_PERMISSIONS.marginRead),
  );
  protected readonly canReadCosts = computed(() =>
    this.auth.hasPermission(REPORT_PERMISSIONS.costRead),
  );
  protected readonly canExport = computed(() =>
    this.auth.hasPermission(REPORT_PERMISSIONS.exportExecute),
  );
  protected readonly canReadCategories = computed(() =>
    this.auth.hasPermission(REPORT_PERMISSIONS.categoryRead),
  );
  protected readonly canReadUsers = computed(() =>
    this.auth.hasPermission(REPORT_PERMISSIONS.userRead),
  );

  /** Reportes habilitados para los permisos efectivos del usuario actual. */
  protected readonly availableReports = computed<readonly ReportDefinition[]>(() =>
    REPORT_DEFINITIONS.filter((def) => this.auth.hasPermission(def.permission)),
  );

  /** Métricas ABC permitidas (`margin` solo visible con `report:margin:read`). */
  protected readonly availableAbcMetrics = computed(() =>
    ABC_METRIC_OPTIONS.filter((opt) => opt.value !== 'margin' || this.canReadMargin()),
  );

  /** Locales disponibles para filtrado multi-local (asignados al usuario). */
  protected readonly availableStores = computed<readonly StoreSummary[]>(() => {
    const stores = this.storeContext.availableStores?.();
    if (stores && stores.length > 0) {
      return stores;
    }
    const active = this.storeContext.activeStore();
    return active ? [active as StoreSummary] : [];
  });

  protected readonly selectedReportKey = signal<ReportKey | null>(null);
  protected readonly activeReport = computed<ReportDefinition | null>(() => {
    const list = this.availableReports();
    if (list.length === 0) {
      return null;
    }
    const selected = this.selectedReportKey();
    return list.find((item) => item.key === selected) ?? list[0] ?? null;
  });

  protected readonly selectedTabIndex = computed(() => {
    const current = this.activeReport();
    if (!current) {
      return 0;
    }
    const index = this.availableReports().findIndex((item) => item.key === current.key);
    return index >= 0 ? index : 0;
  });

  protected readonly loading = signal(false);
  protected readonly exportingFormat = signal<ExportFormat | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly reportResult = signal<ReportResultState | null>(null);
  protected readonly categories = signal<readonly CategoryOption[]>([]);
  protected readonly users = signal<readonly UserSummary[]>([]);
  protected readonly submitted = signal(false);

  private readonly initialRange = defaultReportDateRange();
  private readonly initialStoreId = this.storeContext.storeId();

  protected readonly filterForm = this.fb.group(
    {
      from: [this.initialRange.from],
      to: [this.initialRange.to],
      storeIds: [this.initialStoreId !== null ? [this.initialStoreId] : ([] as number[])],
      salesGroupBy: ['day' as SalesGroupBy],
      rankingOrder: ['most' as RankingOrder],
      rankingMetric: ['qty' as RankingMetric],
      rankingLimit: [20, [Validators.required, integerRangeValidator(1, MAX_RANKING_LIMIT)]],
      categoryId: [null as number | null],
      noRotationDays: [30, [Validators.required, integerRangeValidator(1, MAX_NO_ROTATION_DAYS)]],
      abcMetric: ['amount' as AbcMetric],
      marginGroupBy: ['product' as MarginGroupBy],
      valuationGroupBy: ['category' as InventoryValuationGroupBy],
      shrinkageGroupBy: ['type' as ShrinkageGroupBy],
      shrinkageTopLimit: [10, [Validators.required, integerRangeValidator(1, MAX_RANKING_LIMIT)]],
      cashierId: [null as number | null, [integerRangeValidator(1, 999_999_999)]],
      withDifference: [null as boolean | null],
    },
    {
      validators: [reportDateRangeValidator(() => this.activeReport()?.requiresDateRange ?? true)],
    },
  );

  /** Columnas activas para el reporte seleccionado y permisos de costo vigentes. */
  protected readonly activeColumns = computed<readonly ReportColumnDef[]>(() => {
    const current = this.activeReport();
    if (!current) {
      return [];
    }
    const result = this.reportResult();
    const abcMetric =
      result?.reportKey === 'abc' && result.abcMetric
        ? result.abcMetric
        : this.filterForm.controls.abcMetric.value;
    return buildReportColumns(current.key, {
      canReadCosts: this.canReadCosts(),
      abcMetric,
    });
  });

  protected readonly displayedColumns = computed<string[]>(() =>
    this.activeColumns().map((col) => col.key),
  );

  protected readonly rows = computed<readonly ReportRow[]>(() => {
    const current = this.activeReport();
    const result = this.reportResult();
    if (!current || !result || result.reportKey !== current.key) {
      return [];
    }
    return result.rows;
  });

  protected readonly totals = computed<ReportRow | null>(() => {
    const current = this.activeReport();
    const result = this.reportResult();
    if (!current || !result || result.reportKey !== current.key) {
      return null;
    }
    return result.totals;
  });

  protected readonly hasTotals = computed(() => this.totals() !== null);

  protected readonly generatedAtText = computed(() => {
    const ts = this.reportResult()?.generatedAt;
    return ts ? formatDateTime(ts) : '';
  });

  private initialized = false;
  private lastStoreId: number | null = this.initialStoreId;
  private activeQuerySub: Subscription | null = null;
  private exportSub: Subscription | null = null;

  constructor() {
    effect(() => {
      const storeId = this.storeContext.storeId();
      if (this.initialized && storeId !== this.lastStoreId) {
        this.lastStoreId = storeId;
        this.filterForm.controls.storeIds.setValue(storeId !== null ? [storeId] : []);
        if (this.activeReport() && this.filterForm.valid) {
          this.loadActiveReport();
        }
      }
    });
  }

  ngOnInit(): void {
    this.initialized = true;
    this.lastStoreId = this.storeContext.storeId();

    const first = this.availableReports()[0] ?? null;
    if (first) {
      this.selectedReportKey.set(first.key);
      this.syncReportSpecificControls(first.key);
      this.loadAuxLookups();
      this.loadActiveReport();
    }
  }

  ngOnDestroy(): void {
    this.activeQuerySub?.unsubscribe();
    this.exportSub?.unsubscribe();
  }

  /** Cambia la pestaña de reporte activa y ejecuta la consulta si los filtros son válidos. */
  protected selectReportByIndex(index: number): void {
    const target = this.availableReports()[index];
    if (!target || target.key === this.activeReport()?.key) {
      return;
    }
    this.selectedReportKey.set(target.key);
    this.reportResult.set(null);
    this.loadError.set(null);
    if (
      target.requiresDateRange &&
      !this.filterForm.controls.from.value.trim() &&
      !this.filterForm.controls.to.value.trim()
    ) {
      const range = defaultReportDateRange();
      this.filterForm.patchValue({ from: range.from, to: range.to }, { emitEvent: false });
    }
    this.syncReportSpecificControls(target.key);
    if (this.filterForm.valid) {
      this.loadActiveReport();
    }
  }

  /** Envía la consulta del reporte activo previa validación del formulario de filtros. */
  protected submitFilters(): void {
    this.submitted.set(true);
    this.filterForm.markAllAsTouched();
    this.filterForm.updateValueAndValidity();
    if (this.filterForm.invalid) {
      return;
    }
    this.loadActiveReport();
  }

  /** Aplica un atajo rápido de rango de fechas (`today`, `7d`, `30d`, `month`) y consulta. */
  protected applyDatePreset(preset: 'today' | '7d' | '30d' | 'month'): void {
    const today = toLocalIsoDate(new Date());
    let from = today;
    if (preset === '7d') {
      from = addCalendarDays(today, -6);
    } else if (preset === '30d') {
      from = addCalendarDays(today, -29);
    } else if (preset === 'month') {
      from = `${today.slice(0, 8)}01`;
    }
    this.filterForm.patchValue({ from, to: today });
    this.filterForm.markAsDirty();
    this.submitFilters();
  }

  /** Restablece los filtros del reporte activo a sus valores por defecto y recarga. */
  protected resetFilters(): void {
    const current = this.activeReport();
    const range = defaultReportDateRange();
    const activeStoreId = this.storeContext.storeId();

    this.submitted.set(false);
    this.filterForm.reset({
      from: current && !current.requiresDateRange ? '' : range.from,
      to: current && !current.requiresDateRange ? '' : range.to,
      storeIds: activeStoreId !== null ? [activeStoreId] : [],
      salesGroupBy: 'day',
      rankingOrder: 'most',
      rankingMetric: 'qty',
      rankingLimit: 20,
      categoryId: null,
      noRotationDays: 30,
      abcMetric: 'amount',
      marginGroupBy: 'product',
      valuationGroupBy: 'category',
      shrinkageGroupBy: 'type',
      shrinkageTopLimit: 10,
      cashierId: null,
      withDifference: null,
    });
    if (current) {
      this.syncReportSpecificControls(current.key);
    }
    this.loadActiveReport();
  }

  /** Limpia el rango de fechas en valorización de inventario para consultar al stock actual. */
  protected clearValuationDates(): void {
    this.filterForm.patchValue({ from: '', to: '' });
    this.submitFilters();
  }

  /** Exporta el reporte activo a `.xlsx` o `.pdf` respetando los filtros vigentes. */
  protected exportReport(format: ExportFormat): void {
    const current = this.activeReport();
    if (!current || !this.canExport() || this.exportingFormat() !== null) {
      return;
    }

    this.submitted.set(true);
    this.filterForm.markAllAsTouched();
    this.filterForm.updateValueAndValidity();
    if (this.filterForm.invalid) {
      return;
    }

    const exportParams = this.buildExportParams(current.key, format);
    this.exportingFormat.set(format);

    this.exportSub?.unsubscribe();
    this.exportSub = this.reportsService
      .exportReport(current.key, exportParams, withoutErrorNotification())
      .pipe(finalize(() => this.exportingFormat.set(null)))
      .subscribe({
        next: (file) => {
          triggerBrowserDownload(file);
          this.snackBar.open(`Reporte exportado (${file.fileName}).`, 'Cerrar', {
            duration: 3500,
          });
        },
        error: (err) => {
          this.snackBar.open(
            this.extractErrorMessage(
              err,
              `No se pudo exportar el reporte a ${format.toUpperCase()}.`,
            ),
            'Cerrar',
            { duration: 6000 },
          );
        },
      });
  }

  /** Reintenta la carga del reporte actual tras un error de red o servidor. */
  protected retryLoad(): void {
    this.submitFilters();
  }

  /** Mensaje de validación de rango de fechas visible bajo la barra de filtros. */
  protected dateRangeErrorMessage(): string | null {
    const errors = this.filterForm.errors;
    if (!errors) {
      return null;
    }
    const immediate =
      Boolean(errors['rangeInverted']) ||
      Boolean(errors['maxRangeExceeded']) ||
      Boolean(errors['incompleteRange']) ||
      Boolean(errors['invalidDateFormat']);
    const showMissing =
      this.submitted() ||
      this.filterForm.controls.from.touched ||
      this.filterForm.controls.to.touched;
    if (!immediate && !showMissing) {
      return null;
    }
    return describeDateRangeError(errors);
  }

  /** Texto de una celda de datos aplicando el descriptor de columna activo. */
  protected cellText(row: ReportRow, column: ReportColumnDef): string {
    return column.value(row);
  }

  /** Texto de una celda de totales cuando la respuesta incluye `totals`. */
  protected footerText(column: ReportColumnDef): string {
    const currentTotals = this.totals();
    if (!currentTotals || !column.totalValue) {
      return '';
    }
    return column.totalValue(currentTotals);
  }

  /** Clase ABC (`A`, `B` o `C`) normalizada para estilizar la insignia. */
  protected abcClassOf(row: ReportRow): string {
    return ((row as AbcRow).abcClass ?? '').toUpperCase();
  }

  /** Signo de diferencia de caja para resaltar descuadres en la tabla. */
  protected cashDifferenceSign(row: ReportRow): 'positive' | 'negative' | 'zero' {
    const diff = (row as CashDifferenceRow).difference ?? 0;
    if (diff > 0) {
      return 'positive';
    }
    if (diff < 0) {
      return 'negative';
    }
    return 'zero';
  }

  private loadAuxLookups(): void {
    const canAnySales = this.auth.hasPermission(REPORT_PERMISSIONS.salesRead);
    const canCash = this.auth.hasPermission(REPORT_PERMISSIONS.cashRead);

    if (canAnySales && this.canReadCategories()) {
      this.reportsService.listCategories(withoutErrorNotification()).subscribe({
        next: (tree) => this.categories.set(flattenCategoryTree(tree)),
        error: () => this.categories.set([]),
      });
    }

    if (canCash && this.canReadUsers()) {
      this.reportsService.listUsers(withoutErrorNotification()).subscribe({
        next: (list) => this.users.set(list),
        error: () => this.users.set([]),
      });
    }
  }

  private syncReportSpecificControls(reportKey: ReportKey): void {
    const c = this.filterForm.controls;
    const reportControls = [
      c.salesGroupBy,
      c.rankingOrder,
      c.rankingMetric,
      c.rankingLimit,
      c.categoryId,
      c.noRotationDays,
      c.abcMetric,
      c.marginGroupBy,
      c.valuationGroupBy,
      c.shrinkageGroupBy,
      c.shrinkageTopLimit,
      c.cashierId,
      c.withDifference,
    ];
    for (const ctrl of reportControls) {
      ctrl.disable({ emitEvent: false });
    }

    switch (reportKey) {
      case 'sales':
        c.salesGroupBy.enable({ emitEvent: false });
        break;
      case 'product-ranking':
        c.rankingOrder.enable({ emitEvent: false });
        c.rankingMetric.enable({ emitEvent: false });
        c.rankingLimit.enable({ emitEvent: false });
        c.categoryId.enable({ emitEvent: false });
        break;
      case 'no-rotation':
        c.noRotationDays.enable({ emitEvent: false });
        break;
      case 'abc':
        c.abcMetric.enable({ emitEvent: false });
        if (c.abcMetric.value === 'margin' && !this.canReadMargin()) {
          c.abcMetric.setValue('amount', { emitEvent: false });
        }
        break;
      case 'margin':
        c.marginGroupBy.enable({ emitEvent: false });
        break;
      case 'inventory-valuation':
        c.valuationGroupBy.enable({ emitEvent: false });
        break;
      case 'shrinkage':
        c.shrinkageGroupBy.enable({ emitEvent: false });
        break;
      case 'shrinkage-top-products':
        c.shrinkageTopLimit.enable({ emitEvent: false });
        break;
      case 'cash-differences':
        c.cashierId.enable({ emitEvent: false });
        c.withDifference.enable({ emitEvent: false });
        break;
      default:
        break;
    }

    this.filterForm.updateValueAndValidity({ emitEvent: false });
  }

  private loadActiveReport(): void {
    const current = this.activeReport();
    if (!current || this.filterForm.invalid) {
      return;
    }

    this.loading.set(true);
    this.loadError.set(null);

    this.activeQuerySub?.unsubscribe();
    this.activeQuerySub = this.fetchReport(current.key)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (result) => this.reportResult.set(result),
        error: (err) => {
          this.reportResult.set(null);
          this.loadError.set(
            this.extractErrorMessage(err, `No se pudo cargar el reporte «${current.label}».`),
          );
        },
      });
  }

  private fetchReport(reportKey: ReportKey): Observable<ReportResultState> {
    const raw = this.filterForm.getRawValue();
    const from = raw.from.trim() || undefined;
    const to = raw.to.trim() || undefined;
    const storeIds = raw.storeIds.length > 0 ? raw.storeIds : undefined;
    const ctx = withoutErrorNotification();

    switch (reportKey) {
      case 'sales':
        return this.reportsService
          .getSalesReport({ from, to, storeIds, groupBy: raw.salesGroupBy }, ctx)
          .pipe(
            map((res) => ({
              reportKey,
              generatedAt: res.generatedAt ?? null,
              storeIds: res.filters?.storeIds ?? [],
              rows: res.rows ?? [],
              totals: res.totals ?? null,
            })),
          );

      case 'sales-by-cashier':
        return this.reportsService.getSalesByCashier({ from, to, storeIds }, ctx).pipe(
          map((res) => ({
            reportKey,
            generatedAt: res.generatedAt ?? null,
            storeIds: res.filters?.storeIds ?? [],
            rows: res.rows ?? [],
            totals: res.totals ?? null,
          })),
        );

      case 'sales-by-payment-method':
        return this.reportsService.getSalesByPaymentMethod({ from, to, storeIds }, ctx).pipe(
          map((res) => ({
            reportKey,
            generatedAt: res.generatedAt ?? null,
            storeIds: res.filters?.storeIds ?? [],
            rows: res.rows ?? [],
            totals: res.totals ?? null,
          })),
        );

      case 'product-ranking':
        return this.reportsService
          .getProductRanking(
            {
              from,
              to,
              storeIds,
              order: raw.rankingOrder,
              metric: raw.rankingMetric,
              limit: Number(raw.rankingLimit),
              categoryId: raw.categoryId ?? undefined,
            },
            ctx,
          )
          .pipe(
            map((res) => ({
              reportKey,
              generatedAt: res.generatedAt ?? null,
              storeIds: res.filters?.storeIds ?? [],
              rows: res.rows ?? [],
              totals: null,
            })),
          );

      case 'no-rotation':
        return this.reportsService
          .getNoRotation({ from, to, storeIds, days: Number(raw.noRotationDays) }, ctx)
          .pipe(
            map((res) => ({
              reportKey,
              generatedAt: res.generatedAt ?? null,
              storeIds: res.filters?.storeIds ?? [],
              rows: res.rows ?? [],
              totals: null,
            })),
          );

      case 'abc': {
        const metric: AbcMetric =
          raw.abcMetric === 'margin' && !this.canReadMargin() ? 'amount' : raw.abcMetric;
        return this.reportsService.getAbc({ from, to, storeIds, metric }, ctx).pipe(
          map((res) => ({
            reportKey,
            generatedAt: res.generatedAt ?? null,
            storeIds: res.filters?.storeIds ?? [],
            rows: res.rows ?? [],
            totals: null,
            abcMetric: metric,
          })),
        );
      }

      case 'margin':
        return this.reportsService
          .getMarginReport(
            { from: from ?? '', to: to ?? '', storeIds, groupBy: raw.marginGroupBy },
            ctx,
          )
          .pipe(
            map((res) => ({
              reportKey,
              generatedAt: res.generatedAt ?? null,
              storeIds: res.filters?.storeIds ?? [],
              rows: res.rows ?? [],
              totals: res.totals ?? null,
            })),
          );

      case 'inventory-valuation':
        return this.reportsService
          .getInventoryValuation({ from, to, storeIds, groupBy: raw.valuationGroupBy }, ctx)
          .pipe(
            map((res) => ({
              reportKey,
              generatedAt: res.generatedAt ?? null,
              storeIds: res.filters?.storeIds ?? [],
              rows: res.rows ?? [],
              totals: res.totals ?? null,
            })),
          );

      case 'purchases-by-supplier':
        return this.reportsService
          .getPurchasesBySupplier({ from: from ?? '', to: to ?? '', storeIds }, ctx)
          .pipe(
            map((res) => ({
              reportKey,
              generatedAt: res.generatedAt ?? null,
              storeIds: res.filters?.storeIds ?? [],
              rows: res.rows ?? [],
              totals: res.totals ?? null,
            })),
          );

      case 'shrinkage':
        return this.reportsService
          .getShrinkageReport(
            { from: from ?? '', to: to ?? '', storeIds, groupBy: raw.shrinkageGroupBy },
            ctx,
          )
          .pipe(
            map((res) => ({
              reportKey,
              generatedAt: res.generatedAt ?? null,
              storeIds: res.filters?.storeIds ?? [],
              rows: res.rows ?? [],
              totals: res.totals ?? null,
            })),
          );

      case 'shrinkage-top-products':
        return this.reportsService
          .getTopShrinkageProducts(
            { from: from ?? '', to: to ?? '', storeIds, limit: Number(raw.shrinkageTopLimit) },
            ctx,
          )
          .pipe(
            map((res) => ({
              reportKey,
              generatedAt: res.generatedAt ?? null,
              storeIds: res.filters?.storeIds ?? [],
              rows: res.rows ?? [],
              totals: res.totals ?? null,
            })),
          );

      case 'cash-differences':
        return this.reportsService
          .getCashDifferences(
            {
              from: from ?? '',
              to: to ?? '',
              storeIds,
              cashierId: raw.cashierId !== null ? Number(raw.cashierId) : undefined,
              withDifference: raw.withDifference ?? undefined,
            },
            ctx,
          )
          .pipe(
            map((res) => ({
              reportKey,
              generatedAt: res.generatedAt ?? null,
              storeIds: res.filters?.storeIds ?? [],
              rows: res.rows ?? [],
              totals: res.totals ?? null,
            })),
          );
    }
  }

  private buildExportParams(reportKey: ReportKey, format: ExportFormat): ReportExportParams {
    const raw = this.filterForm.getRawValue();
    const from = raw.from.trim() || undefined;
    const to = raw.to.trim() || undefined;
    const storeIds = raw.storeIds.length > 0 ? raw.storeIds : undefined;
    const abcMetric: AbcMetric =
      raw.abcMetric === 'margin' && !this.canReadMargin() ? 'amount' : raw.abcMetric;

    return {
      format,
      from,
      to,
      storeIds,
      salesGroupBy: raw.salesGroupBy,
      rankingOrder: raw.rankingOrder,
      rankingMetric: raw.rankingMetric,
      limit:
        reportKey === 'shrinkage-top-products'
          ? Number(raw.shrinkageTopLimit)
          : Number(raw.rankingLimit),
      categoryId: raw.categoryId ?? undefined,
      days: Number(raw.noRotationDays),
      abcMetric,
      marginGroupBy: raw.marginGroupBy,
      valuationGroupBy: raw.valuationGroupBy,
      shrinkageGroupBy: raw.shrinkageGroupBy,
      cashierId: raw.cashierId !== null ? Number(raw.cashierId) : undefined,
      withDifference: raw.withDifference ?? undefined,
    };
  }

  private extractErrorMessage(err: unknown, fallback: string): string {
    if (isApiError(err)) {
      return err.message || fallback;
    }
    if (err instanceof Error && err.message) {
      return err.message;
    }
    return fallback;
  }
}
