import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  viewChildren,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorIntl, MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { concatMap, from, toArray } from 'rxjs';
import { CountLineInputEntry } from '../../../api/models/count-line-input-entry';
import { CountLineResponse } from '../../../api/models/count-line-response';
import { InventoryCountResponse } from '../../../api/models/inventory-count-response';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ClpPipe, QtyPipe, QtyUnit } from '../../../shared/pipes';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { createSpanishPaginatorIntl } from '../../../shared/ui/paginated-table/spanish-paginator-intl';
import { CountReasonDialog, CountReasonDialogData } from './count-reason-dialog';
import {
  COUNTED_FILTER_OPTIONS,
  CountedFilterMode,
  canRevealBlindValues,
  countStatusLabel,
  countTypeLabel,
  formatDateTime,
  formatUnitCost,
  toQtyUnit,
} from './counts.models';
import { CountsService } from './counts.service';

const MAX_LINES_PER_BATCH = 500;
const AUTOSAVE_DEBOUNCE_MS = 1200;

interface LineMeta {
  readonly countedQty: number | undefined;
  readonly unitOfMeasure: CountLineResponse['unitOfMeasure'];
}

interface DraftAnalysis {
  readonly entries: readonly CountLineInputEntry[];
  readonly errorsByLineId: Readonly<Record<number, string>>;
}

/**
 * Pantalla de detalle y conteo físico masivo navegable con teclado (`HU-INV-06`, `API.md` F.2).
 *
 * Características clave:
 * - Grilla rápida de conteo con foco continuo por teclado (`Enter` y `ArrowDown` avanzan a la
 *   siguiente fila; `ArrowUp` retrocede; `Ctrl+S` guarda el lote pendiente).
 * - Indicador de cambios pendientes, guardado en lotes (hasta 500 líneas por llamada) y
 *   autoguardado opcional.
 * - Manejo de concurrencia optimista (`version` y errores `409 CONCURRENT_MODIFICATION`).
 * - Respeto estricto del modo ciego (`blind`) y visibilidad de costos (`catalog:cost:read`).
 * - Flujo completo de ciclo de vida (`OPEN -> SUBMITTED -> APPROVED/OPEN/CANCELLED`) con
 *   validación de segregación de funciones (SoD).
 */
@Component({
  selector: 'pos-count-detail',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatPaginatorModule,
    MatProgressBarModule,
    MatSelectModule,
    MatSlideToggleModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  providers: [{ provide: MatPaginatorIntl, useFactory: createSpanishPaginatorIntl }],
  host: {
    '(keydown)': 'onGridKeydown($event)',
  },
  templateUrl: './count-detail.html',
  styleUrl: './count-detail.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CountDetail implements OnInit, OnDestroy {
  private readonly countsService = inject(CountsService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly qtyInputs = viewChildren<ElementRef<HTMLInputElement>>('qtyInput');

  protected readonly countedFilterOptions = COUNTED_FILTER_OPTIONS;
  protected readonly count = signal<InventoryCountResponse | null>(null);
  protected readonly lines = signal<readonly CountLineResponse[]>([]);
  protected readonly loadingCount = signal(false);
  protected readonly loadingLines = signal(false);
  protected readonly saving = signal(false);
  protected readonly actionInFlight = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly concurrencyConflict = signal<string | null>(null);
  protected readonly loadedVersion = signal<number | null>(null);
  protected readonly hasCountedInSession = signal(false);
  protected readonly autosaveEnabled = signal(false);
  protected readonly focusedRowIndex = signal<number | null>(null);

  protected readonly pageIndex = signal(0);
  protected readonly pageSize = signal(100);
  protected readonly totalLines = signal(0);

  private readonly draftInputs = signal<Readonly<Record<number, string>>>({});
  private readonly lineMetaById = signal<Readonly<Record<number, LineMeta>>>({});

  protected readonly filterForm = this.fb.group({
    q: [''],
    countedMode: ['ALL' as CountedFilterMode],
  });

  protected readonly canExecute = computed(() =>
    this.auth.hasPermission('inventory:count:execute'),
  );
  protected readonly canManage = computed(() => this.auth.hasPermission('inventory:count:manage'));
  protected readonly canApprove = computed(() =>
    this.auth.hasPermission('inventory:count:approve'),
  );
  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));

  protected readonly currentUserId = computed(() => this.auth.user()?.id ?? null);
  protected readonly isEditable = computed(
    () => this.count()?.status === 'OPEN' && this.canExecute(),
  );
  protected readonly showTheoretical = computed(() =>
    canRevealBlindValues(this.count(), this.canApprove()),
  );
  protected readonly showValuationSummary = computed(
    () => this.canReadCosts() && this.showTheoretical() && Boolean(this.count()?.differenceSummary),
  );

  protected readonly isSodBlocked = computed(() => {
    const uid = this.currentUserId();
    const current = this.count();
    if (uid === null || !current) {
      return false;
    }
    return current.startedBy?.id === uid || this.hasCountedInSession();
  });

  private readonly draftAnalysis = computed<DraftAnalysis>(() => {
    const drafts = this.draftInputs();
    const metaMap = this.lineMetaById();
    const entries: CountLineInputEntry[] = [];
    const errorsByLineId: Record<number, string> = {};

    for (const [rawId, rawValue] of Object.entries(drafts)) {
      const lineId = Number(rawId);
      if (!Number.isFinite(lineId)) {
        continue;
      }
      const meta = metaMap[lineId];
      const trimmed = rawValue.trim();
      if (trimmed === '') {
        if (meta?.countedQty !== undefined && meta?.countedQty !== null) {
          errorsByLineId[lineId] = 'Ingresa una cantidad válida o descarta el cambio.';
        }
        continue;
      }

      const normalized = trimmed.replace(',', '.');
      const parsed = Number(normalized);
      if (!Number.isFinite(parsed) || parsed < 0) {
        errorsByLineId[lineId] = 'La cantidad debe ser un número mayor o igual a 0.';
        continue;
      }
      if (meta?.unitOfMeasure === 'UNIT' && !Number.isInteger(parsed)) {
        errorsByLineId[lineId] = 'Este producto se cuenta en unidades enteras.';
        continue;
      }
      if (meta?.countedQty === parsed) {
        continue;
      }
      entries.push({ lineId, countedQty: parsed });
    }

    return { entries, errorsByLineId };
  });

  protected readonly pendingCount = computed(() => this.draftAnalysis().entries.length);
  protected readonly dirtyLineIds = computed(
    () => new Set(this.draftAnalysis().entries.map((entry) => entry.lineId)),
  );
  protected readonly validationErrorsByLineId = computed(() => this.draftAnalysis().errorsByLineId);
  protected readonly hasValidationErrors = computed(
    () => Object.keys(this.validationErrorsByLineId()).length > 0,
  );

  protected readonly countedLinesCount = computed(() => this.count()?.progress?.countedLines ?? 0);
  protected readonly totalCountLines = computed(() => this.count()?.progress?.totalLines ?? 0);
  protected readonly uncountedCount = computed(() =>
    Math.max(0, this.totalCountLines() - this.countedLinesCount()),
  );
  protected readonly progressPercent = computed(() => {
    const total = this.totalCountLines();
    if (total <= 0) {
      return 0;
    }
    return Math.min(100, Math.round((this.countedLinesCount() / total) * 100));
  });

  protected readonly canSubmit = computed(() => {
    const c = this.count();
    return (
      c?.status === 'OPEN' &&
      this.canManage() &&
      this.pendingCount() === 0 &&
      !this.hasValidationErrors() &&
      this.uncountedCount() === 0 &&
      this.totalCountLines() > 0 &&
      !this.saving() &&
      !this.actionInFlight()
    );
  });

  private countId: number | null = null;
  private initialized = false;
  private lastLoadedStoreId: number | null = null;
  private autosaveTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      const storeId = this.storeContext.storeId();
      if (this.initialized && storeId !== this.lastLoadedStoreId) {
        this.lastLoadedStoreId = storeId;
        if (this.countId !== null) {
          this.reloadAll();
        }
      }
    });
  }

  ngOnInit(): void {
    this.initialized = true;
    this.lastLoadedStoreId = this.storeContext.storeId();
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const raw = params.get('id');
      const parsed = raw ? Number(raw) : Number.NaN;
      if (!Number.isInteger(parsed) || parsed <= 0) {
        this.countId = null;
        this.count.set(null);
        this.lines.set([]);
        this.errorMessage.set('Identificador de toma de inventario inválido.');
        return;
      }
      if (this.countId !== parsed) {
        this.countId = parsed;
        this.draftInputs.set({});
        this.lineMetaById.set({});
        this.hasCountedInSession.set(false);
        this.concurrencyConflict.set(null);
        this.pageIndex.set(0);
        this.reloadAll();
      }
    });
  }

  ngOnDestroy(): void {
    this.clearAutosaveTimer();
  }

  protected goBack(): void {
    void this.router.navigate(['/inventario/tomas']);
  }

  protected reloadAll(): void {
    if (this.countId === null) {
      return;
    }
    this.concurrencyConflict.set(null);
    this.errorMessage.set(null);
    this.loadHeader(this.countId);
    this.loadLines(this.countId);
  }

  protected applyFilters(): void {
    if (this.countId === null) {
      return;
    }
    this.pageIndex.set(0);
    this.loadLines(this.countId);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ q: '', countedMode: 'ALL' });
    this.applyFilters();
  }

  protected onPageChange(event: PageEvent): void {
    if (this.countId === null) {
      return;
    }
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    this.loadLines(this.countId);
  }

  protected toggleAutosave(checked: boolean): void {
    this.autosaveEnabled.set(checked);
    if (checked && this.pendingCount() > 0 && !this.hasValidationErrors()) {
      this.scheduleAutosave();
    } else if (!checked) {
      this.clearAutosaveTimer();
    }
  }

  protected inputValueFor(line: CountLineResponse): string {
    if (line.id !== undefined) {
      const draft = this.draftInputs()[line.id];
      if (draft !== undefined) {
        return draft;
      }
    }
    return line.countedQty !== undefined && line.countedQty !== null ? String(line.countedQty) : '';
  }

  protected isRowDirty(line: CountLineResponse): boolean {
    return line.id !== undefined && this.dirtyLineIds().has(line.id);
  }

  protected rowError(line: CountLineResponse): string | null {
    if (line.id === undefined) {
      return null;
    }
    return this.validationErrorsByLineId()[line.id] ?? null;
  }

  protected onQtyInputEvent(line: CountLineResponse, event: Event): void {
    const target = event.target;
    if (target instanceof HTMLInputElement) {
      this.onQtyInput(line, target.value);
    }
  }

  protected onQtyInput(line: CountLineResponse, value: string): void {
    if (line.id === undefined || !this.isEditable()) {
      return;
    }
    const lineId = line.id;
    const savedText =
      line.countedQty !== undefined && line.countedQty !== null ? String(line.countedQty) : '';
    this.draftInputs.update((current) => {
      const next = { ...current };
      if (value === savedText) {
        delete next[lineId];
      } else {
        next[lineId] = value;
      }
      return next;
    });

    if (this.autosaveEnabled()) {
      this.scheduleAutosave();
    }
  }

  protected onQtyKeydown(event: KeyboardEvent, index: number): void {
    if (event.key === 'Enter' || event.key === 'ArrowDown') {
      event.preventDefault();
      this.focusRow(index + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.focusRow(index - 1);
    }
  }

  protected onGridKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault();
      if (this.isEditable() && this.pendingCount() > 0 && !this.hasValidationErrors()) {
        this.savePendingLines();
      }
    }
  }

  protected focusRow(targetIndex: number): void {
    const inputs = this.qtyInputs();
    if (targetIndex < 0 || targetIndex >= inputs.length) {
      return;
    }
    const element = inputs[targetIndex]?.nativeElement;
    if (!element) {
      return;
    }
    element.focus();
    element.select();
    this.focusedRowIndex.set(targetIndex);
  }

  protected discardDrafts(): void {
    this.clearAutosaveTimer();
    this.draftInputs.set({});
  }

  protected savePendingLines(): void {
    if (
      this.countId === null ||
      !this.isEditable() ||
      this.saving() ||
      this.hasValidationErrors()
    ) {
      return;
    }
    const entries = this.draftAnalysis().entries;
    if (entries.length === 0) {
      return;
    }

    this.clearAutosaveTimer();
    const targetCountId = this.countId;
    const chunks = this.chunkEntries(entries, MAX_LINES_PER_BATCH);

    this.saving.set(true);
    this.errorMessage.set(null);

    from(chunks)
      .pipe(
        concatMap((chunk) =>
          this.countsService.recordCountLines(
            targetCountId,
            { lines: chunk },
            withoutErrorNotification(),
          ),
        ),
        toArray(),
      )
      .subscribe({
        next: (batches) => {
          this.saving.set(false);
          const updatedLines = batches.flat();
          this.applyUpdatedLines(updatedLines);
          this.hasCountedInSession.set(true);
          this.loadHeader(targetCountId);
          this.snackBar.open(
            `Conteo guardado (${updatedLines.length} línea${updatedLines.length === 1 ? '' : 's'}).`,
            'Cerrar',
            { duration: 3500 },
          );
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.handleMutationError(err, 'No se pudieron guardar los conteos pendientes.');
        },
      });
  }

  protected submitForReview(): void {
    if (this.countId === null || !this.canSubmit()) {
      return;
    }
    const targetId = this.countId;
    const numberLabel = this.count()?.number ?? `#${targetId}`;

    this.confirmDialog
      .confirm({
        title: 'Enviar toma a revisión',
        message: `¿Deseas enviar la toma ${numberLabel} a revisión? Mientras esté en revisión no se podrán modificar las cantidades contadas.`,
        confirmLabel: 'Enviar a revisión',
      })
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        this.actionInFlight.set(true);
        this.errorMessage.set(null);
        this.countsService.submitCount(targetId, withoutErrorNotification()).subscribe({
          next: (updated) => {
            this.actionInFlight.set(false);
            this.applyHeaderUpdate(updated);
            this.loadLines(targetId);
            this.snackBar.open(`Toma ${updated.number ?? ''} enviada a revisión.`, 'Cerrar', {
              duration: 4000,
            });
          },
          error: (err: unknown) => {
            this.actionInFlight.set(false);
            this.handleMutationError(err, 'No se pudo enviar la toma a revisión.');
          },
        });
      });
  }

  protected approveCount(): void {
    const current = this.count();
    if (
      this.countId === null ||
      !current ||
      current.status !== 'SUBMITTED' ||
      !this.canApprove() ||
      this.isSodBlocked() ||
      this.actionInFlight()
    ) {
      return;
    }
    const targetId = this.countId;
    this.confirmDialog
      .confirm({
        title: 'Aprobar toma de inventario',
        message: `¿Confirmas la aprobación de ${current.number ?? '#' + targetId}? Se aplicarán ajustes de stock solo por las diferencias detectadas y se registrará en kardex.`,
        confirmLabel: 'Aprobar y ajustar',
      })
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        this.actionInFlight.set(true);
        this.errorMessage.set(null);
        this.countsService.approveCount(targetId, withoutErrorNotification()).subscribe({
          next: (updated) => {
            this.actionInFlight.set(false);
            this.applyHeaderUpdate(updated);
            this.loadLines(targetId);
            this.snackBar.open(
              `Toma ${updated.number ?? ''} aprobada y stock conciliado.`,
              'Cerrar',
              { duration: 4000 },
            );
          },
          error: (err: unknown) => {
            this.actionInFlight.set(false);
            this.handleMutationError(err, 'No se pudo aprobar la toma de inventario.');
          },
        });
      });
  }

  protected rejectCount(): void {
    const current = this.count();
    if (
      this.countId === null ||
      !current ||
      current.status !== 'SUBMITTED' ||
      !this.canApprove() ||
      this.actionInFlight()
    ) {
      return;
    }
    const targetId = this.countId;
    const dialogData: CountReasonDialogData = {
      title: 'Rechazar toma hacia reconteo',
      message: `La toma ${current.number ?? '#' + targetId} volverá a estado Abierta para corregir las cantidades contadas.`,
      confirmLabel: 'Rechazar a reconteo',
    };
    this.dialog
      .open<CountReasonDialog, CountReasonDialogData, string>(CountReasonDialog, {
        data: dialogData,
        width: '460px',
      })
      .afterClosed()
      .subscribe((reason) => {
        if (!reason) {
          return;
        }
        this.actionInFlight.set(true);
        this.errorMessage.set(null);
        this.countsService.rejectCount(targetId, { reason }, withoutErrorNotification()).subscribe({
          next: (updated) => {
            this.actionInFlight.set(false);
            this.applyHeaderUpdate(updated);
            this.loadLines(targetId);
            this.snackBar.open(
              `Toma ${updated.number ?? ''} devuelta a estado Abierta para reconteo.`,
              'Cerrar',
              { duration: 4000 },
            );
          },
          error: (err: unknown) => {
            this.actionInFlight.set(false);
            this.handleMutationError(err, 'No se pudo rechazar la toma.');
          },
        });
      });
  }

  protected cancelCount(): void {
    const current = this.count();
    if (
      this.countId === null ||
      !current ||
      (current.status !== 'OPEN' && current.status !== 'SUBMITTED') ||
      !this.canManage() ||
      this.actionInFlight()
    ) {
      return;
    }
    const targetId = this.countId;
    const dialogData: CountReasonDialogData = {
      title: 'Cancelar toma de inventario',
      message: `Esta acción anulará definitivamente la toma ${current.number ?? '#' + targetId} sin modificar el stock.`,
      confirmLabel: 'Cancelar toma',
      destructive: true,
    };
    this.dialog
      .open<CountReasonDialog, CountReasonDialogData, string>(CountReasonDialog, {
        data: dialogData,
        width: '460px',
      })
      .afterClosed()
      .subscribe((reason) => {
        if (!reason) {
          return;
        }
        this.actionInFlight.set(true);
        this.errorMessage.set(null);
        this.countsService.cancelCount(targetId, { reason }, withoutErrorNotification()).subscribe({
          next: () => {
            this.actionInFlight.set(false);
            this.draftInputs.set({});
            this.loadHeader(targetId);
            this.snackBar.open('Toma de inventario cancelada.', 'Cerrar', { duration: 4000 });
          },
          error: (err: unknown) => {
            this.actionInFlight.set(false);
            this.handleMutationError(err, 'No se pudo cancelar la toma.');
          },
        });
      });
  }

  protected unitFor(line: CountLineResponse): QtyUnit {
    return toQtyUnit(line.unitOfMeasure);
  }

  protected stepFor(line: CountLineResponse): string {
    return line.unitOfMeasure === 'KG' ? '0.001' : '1';
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

  protected formatCost(value: number | undefined): string {
    return formatUnitCost(value);
  }

  protected netDifference(count: InventoryCountResponse): number {
    const valueIn = count.differenceSummary?.valueIn ?? 0;
    const valueOut = count.differenceSummary?.valueOut ?? 0;
    return valueIn - valueOut;
  }

  protected lineDifferenceValue(line: CountLineResponse): number | null {
    if (
      line.difference === undefined ||
      line.difference === null ||
      line.unitCost === undefined ||
      line.unitCost === null
    ) {
      return null;
    }
    return line.difference * line.unitCost;
  }

  private loadHeader(id: number): void {
    this.loadingCount.set(true);
    this.countsService.getCount(id, withoutErrorNotification()).subscribe({
      next: (data) => {
        this.loadingCount.set(false);
        const prevVersion = this.loadedVersion();
        if (
          prevVersion !== null &&
          data.version !== undefined &&
          data.version !== prevVersion &&
          this.pendingCount() > 0
        ) {
          this.concurrencyConflict.set(
            `La toma cambió a versión v${data.version} en otra sesión mientras tenías cambios pendientes.`,
          );
        }
        this.applyHeaderUpdate(data);
      },
      error: (err: unknown) => {
        this.loadingCount.set(false);
        this.errorMessage.set(
          isApiError(err) ? err.message : 'No se pudo cargar la toma de inventario.',
        );
      },
    });
  }

  private loadLines(id: number): void {
    const raw = this.filterForm.getRawValue();
    const counted =
      raw.countedMode === 'COUNTED' ? true : raw.countedMode === 'PENDING' ? false : undefined;
    const q = raw.q.trim() || undefined;

    this.loadingLines.set(true);
    this.countsService
      .listCountLines(
        {
          id,
          counted,
          q,
          page: this.pageIndex(),
          size: this.pageSize(),
          sort: ['id,asc'],
        },
        withoutErrorNotification(),
      )
      .subscribe({
        next: (page) => {
          this.loadingLines.set(false);
          const content = page.content ?? [];
          this.lines.set(content);
          this.totalLines.set(page.totalElements ?? content.length);
          this.registerLineMetadata(content);
        },
        error: (err: unknown) => {
          this.loadingLines.set(false);
          this.errorMessage.set(
            isApiError(err) ? err.message : 'No se pudieron cargar las líneas de la toma.',
          );
        },
      });
  }

  private applyHeaderUpdate(updated: InventoryCountResponse): void {
    this.count.set(updated);
    if (updated.version !== undefined) {
      this.loadedVersion.set(updated.version);
    }
  }

  private registerLineMetadata(rows: readonly CountLineResponse[]): void {
    this.lineMetaById.update((current) => {
      const next = { ...current };
      for (const row of rows) {
        if (row.id !== undefined) {
          next[row.id] = {
            countedQty: row.countedQty,
            unitOfMeasure: row.unitOfMeasure,
          };
        }
      }
      return next;
    });
  }

  private applyUpdatedLines(updatedLines: readonly CountLineResponse[]): void {
    const byId = new Map<number, CountLineResponse>();
    for (const line of updatedLines) {
      if (line.id !== undefined) {
        byId.set(line.id, line);
      }
    }

    this.lines.update((current) =>
      current.map((line) => (line.id !== undefined ? (byId.get(line.id) ?? line) : line)),
    );
    this.registerLineMetadata(updatedLines);

    this.draftInputs.update((current) => {
      const next = { ...current };
      for (const id of byId.keys()) {
        delete next[id];
      }
      return next;
    });
  }

  private handleMutationError(err: unknown, fallbackMessage: string): void {
    if (!isApiError(err)) {
      this.errorMessage.set(fallbackMessage);
      return;
    }
    if (
      err.status === 409 ||
      err.code === 'CONCURRENT_MODIFICATION' ||
      err.code === 'INVALID_STATE_TRANSITION'
    ) {
      this.concurrencyConflict.set(err.message);
      return;
    }
    this.errorMessage.set(err.message);
  }

  private scheduleAutosave(): void {
    this.clearAutosaveTimer();
    this.autosaveTimer = setTimeout(() => {
      if (this.pendingCount() > 0 && !this.hasValidationErrors() && this.isEditable()) {
        this.savePendingLines();
      }
    }, AUTOSAVE_DEBOUNCE_MS);
  }

  private clearAutosaveTimer(): void {
    if (this.autosaveTimer !== null) {
      clearTimeout(this.autosaveTimer);
      this.autosaveTimer = null;
    }
  }

  private chunkEntries<T>(items: readonly T[], chunkSize: number): T[][] {
    const chunks: T[][] = [];
    for (let i = 0; i < items.length; i += chunkSize) {
      chunks.push(items.slice(i, i + chunkSize));
    }
    return chunks;
  }
}
