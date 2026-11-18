import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import {
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { Observable, catchError, filter, forkJoin, of, switchMap } from 'rxjs';
import { CashMovementResponse } from '../../../api/models/cash-movement-response';
import { CashSessionResponse } from '../../../api/models/cash-session-response';
import { CashSessionSummaryResponse } from '../../../api/models/cash-session-summary-response';
import { TerminalResponse } from '../../../api/models/terminal-response';
import { UserSummary } from '../../../api/models/user-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withIdempotency, withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PageResponse } from '../../../shared/models/page-response';
import { ClpPipe } from '../../../shared/pipes';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import {
  CashSessionStatusCode,
  DEFAULT_CASH_SETTINGS,
  formatCashDate,
  formatCashDateTime,
  formatUserDisplay,
  movementTypeLabel,
  paymentMethodLabel,
  resolveCashErrorMessage,
  sessionStatusLabel,
} from './cash.models';
import { CashService } from './cash.service';
import { CloseSessionDialog, CloseSessionDialogData } from './close-session-dialog';
import { ClosingReceiptDialog, ClosingReceiptDialogData } from './closing-receipt-dialog';
import { MovementDialog, MovementDialogData } from './movement-dialog';

/**
 * Pantalla principal de «Caja» (`HU-CAJ-01`..`HU-CAJ-07`):
 * - Turno actual de la terminal activa: apertura, resumen financiero en vivo, movimientos
 *   manuales IN/OUT (con override de supervisor si supera el límite) y cierre con arqueo y
 *   calculadora de denominaciones CLP.
 * - Historial paginado de turnos del local con filtros, cierre de turnos ajenos (según permiso),
 *   aprobación SOD de diferencias pendientes y comprobante de cierre JSON/PDF.
 */
@Component({
  selector: 'pos-sessions',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTabsModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './sessions.html',
  styleUrl: './sessions.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sessions implements OnInit, OnDestroy {
  private readonly cashService = inject(CashService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly differenceTpl =
    viewChild.required<TemplateRef<CellContext<CashSessionResponse>>>('differenceTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<CashSessionResponse>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<CashSessionResponse>>>('actionsTpl');

  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );
  protected readonly activeTerminalId = this.storeContext.terminalId;
  protected readonly currentUserId = computed(() => this.auth.user()?.id ?? null);

  protected readonly canOpen = computed(() => this.auth.hasPermission('cash:session:open'));
  protected readonly canCloseOwn = computed(() => this.auth.hasPermission('cash:session:close'));
  protected readonly canCloseOthers = computed(() =>
    this.auth.hasPermission('cash:session:close-others'),
  );
  protected readonly canCashIn = computed(() => this.auth.hasPermission('cash:movement:cash-in'));
  protected readonly canCashOut = computed(() => this.auth.hasPermission('cash:movement:cash-out'));
  protected readonly canApproveDifference = computed(() =>
    this.auth.hasPermission('cash:difference:approve'),
  );
  protected readonly hasFullReadScope = computed(
    () =>
      this.auth.hasPermission('cash:session:close-others') ||
      this.auth.hasPermission('cash:difference:approve') ||
      this.auth.hasPermission('report:cash:read'),
  );
  protected readonly canReadUsers = computed(() => this.auth.hasPermission('security:user:read'));

  protected readonly loadingCurrent = signal(false);
  protected readonly currentError = signal<string | null>(null);
  protected readonly currentSession = signal<CashSessionResponse | null>(null);
  protected readonly currentSummary = signal<CashSessionSummaryResponse | null>(null);
  protected readonly currentMovements = signal<readonly CashMovementResponse[]>([]);
  protected readonly openingBusy = signal(false);
  protected readonly openError = signal<string | null>(null);
  protected readonly cashSettings = signal(DEFAULT_CASH_SETTINGS);

  protected readonly terminals = signal<readonly TerminalResponse[]>([]);
  protected readonly storeUsers = signal<readonly UserSummary[]>([]);

  protected readonly openForm = new FormGroup({
    openingAmount: new FormControl<number | null>(DEFAULT_CASH_SETTINGS.defaultOpeningAmount, {
      validators: [Validators.required, Validators.min(0), Validators.pattern(/^\d+$/)],
    }),
  });

  protected readonly filterForm = this.fb.group({
    from: [''],
    to: [''],
    terminalId: [null as number | null],
    cashierId: [null as number | null],
    status: [null as CashSessionStatusCode | null],
    withDifference: [null as boolean | null],
  });

  protected readonly dataSource = new PaginatedDataSource<CashSessionResponse>(
    (params) =>
      this.cashService.listSessions(
        {
          from: params.get('from') ?? undefined,
          to: params.get('to') ?? undefined,
          cashierId: this.parseNumberParam(params.get('cashierId')),
          terminalId: this.parseNumberParam(params.get('terminalId')),
          status: (params.get('status') as CashSessionStatusCode | null) ?? undefined,
          withDifference: this.parseBooleanParam(params.get('withDifference')),
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<CashSessionResponse>>,
    { sort: [{ field: 'openedAt', direction: 'desc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<CashSessionResponse>[]>(() => [
    { key: 'number', header: 'Turno N°', sortField: 'number' },
    {
      key: 'businessDate',
      header: 'Fecha',
      sortField: 'businessDate',
      cell: (row) => formatCashDate(row.businessDate),
    },
    {
      key: 'terminal',
      header: 'Terminal',
      cell: (row) => row.terminal?.name ?? row.terminal?.code ?? '—',
    },
    {
      key: 'cashier',
      header: 'Cajero',
      cell: (row) => formatUserDisplay(row.cashier),
    },
    {
      key: 'openedAt',
      header: 'Apertura',
      sortField: 'openedAt',
      cell: (row) => formatCashDateTime(row.openedAt),
    },
    {
      key: 'openingAmount',
      header: 'Inicial',
      cell: (row) => this.formatClpValue(row.openingAmount),
      align: 'end',
    },
    {
      key: 'expectedTotal',
      header: 'Esperado',
      cell: (row) => this.formatClpValue(row.expectedTotal),
      align: 'end',
    },
    {
      key: 'countedTotal',
      header: 'Contado',
      cell: (row) => this.formatClpValue(row.countedTotal),
      align: 'end',
    },
    {
      key: 'differenceTotal',
      header: 'Diferencia',
      sortField: 'differenceTotal',
      template: this.differenceTpl(),
      align: 'end',
    },
    {
      key: 'status',
      header: 'Estado',
      sortField: 'status',
      template: this.statusTpl(),
    },
    {
      key: 'actions',
      header: 'Acciones',
      template: this.actionsTpl(),
      align: 'end',
    },
  ]);

  private initialized = false;
  private lastStoreId: number | null = null;
  private lastTerminalId: number | null = null;

  constructor() {
    effect(() => {
      const storeId = this.storeContext.storeId();
      const terminalId = this.storeContext.terminalId();
      if (!this.initialized) {
        return;
      }
      const storeChanged = storeId !== this.lastStoreId;
      const terminalChanged = terminalId !== this.lastTerminalId;
      if (storeChanged) {
        this.lastStoreId = storeId;
        this.lastTerminalId = terminalId;
        this.loadCatalogsAndSettings();
        this.loadCurrentSession();
        this.dataSource.reload();
      } else if (terminalChanged) {
        this.lastTerminalId = terminalId;
        this.loadCurrentSession();
      }
    });
  }

  ngOnInit(): void {
    this.initialized = true;
    this.lastStoreId = this.storeContext.storeId();
    this.lastTerminalId = this.storeContext.terminalId();
    this.loadCatalogsAndSettings();
    this.loadCurrentSession();
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected submitOpenSession(): void {
    if (this.openingBusy()) {
      return;
    }
    if (this.openForm.invalid) {
      this.openForm.markAllAsTouched();
      return;
    }
    const openingAmount = this.openForm.controls.openingAmount.value;
    if (openingAmount === null) {
      return;
    }

    this.openingBusy.set(true);
    this.openError.set(null);

    const context = withIdempotency(withoutErrorNotification());
    this.cashService.openSession({ openingAmount: Math.round(openingAmount) }, context).subscribe({
      next: (session) => {
        this.openingBusy.set(false);
        this.currentSession.set(session);
        this.snackBar.open(`Turno ${session.number ?? ''} abierto correctamente.`, 'Cerrar', {
          duration: 4000,
        });
        if (session.id !== undefined) {
          this.loadSessionDetails(session.id);
        }
        this.dataSource.reload();
      },
      error: (err: unknown) => {
        this.openingBusy.set(false);
        if (isApiError(err) && err.hasFieldErrors) {
          const firstField = err.errors[0];
          if (firstField?.field === 'openingAmount') {
            this.openForm.controls.openingAmount.setErrors({ server: firstField.message });
            this.openForm.controls.openingAmount.markAsTouched();
          }
        }
        this.openError.set(resolveCashErrorMessage(err, 'No se pudo abrir el turno de caja.'));
      },
    });
  }

  protected refreshCurrent(): void {
    const current = this.currentSession();
    if (current?.id !== undefined) {
      this.loadSessionDetails(current.id);
    } else {
      this.loadCurrentSession();
    }
  }

  protected openMovementDialog(defaultType?: 'CASH_IN' | 'CASH_OUT'): void {
    const session = this.currentSession();
    if (!session?.id) {
      return;
    }
    const data: MovementDialogData = {
      sessionId: session.id,
      sessionNumber: session.number ?? `#${session.id}`,
      canCashIn: this.canCashIn(),
      canCashOut: this.canCashOut(),
      expectedCash: this.currentSummary()?.expectedCash,
      defaultType,
    };

    this.dialog
      .open<MovementDialog, MovementDialogData, CashMovementResponse | undefined>(MovementDialog, {
        data,
        width: '480px',
      })
      .afterClosed()
      .subscribe((created) => {
        if (created && session.id !== undefined) {
          this.snackBar.open(
            `${movementTypeLabel(created.movementType)} registrado correctamente.`,
            'Cerrar',
            { duration: 4000 },
          );
          this.loadSessionDetails(session.id);
        }
      });
  }

  protected openCloseDialog(session: CashSessionResponse): void {
    if (session.id === undefined) {
      return;
    }
    const data: CloseSessionDialogData = { session };
    this.dialog
      .open<CloseSessionDialog, CloseSessionDialogData, CashSessionResponse | undefined>(
        CloseSessionDialog,
        {
          data,
          width: '820px',
          maxWidth: '95vw',
        },
      )
      .afterClosed()
      .subscribe((closed) => {
        if (!closed) {
          return;
        }
        if (this.currentSession()?.id === closed.id) {
          this.currentSession.set(null);
          this.currentSummary.set(null);
          this.currentMovements.set([]);
        }
        this.snackBar.open(
          closed.requiresDifferenceApproval
            ? `Turno ${closed.number ?? ''} cerrado (pendiente de aprobación de diferencia).`
            : `Turno ${closed.number ?? ''} cerrado correctamente.`,
          'Cerrar',
          { duration: 5000 },
        );
        this.dataSource.reload();
        if (closed.id !== undefined) {
          this.openReceiptDialog(closed);
        }
      });
  }

  protected approveRowDifference(row: CashSessionResponse): void {
    if (row.id === undefined) {
      return;
    }
    const sessionId = row.id;
    this.confirmDialog
      .confirm({
        title: 'Aprobar diferencia de cierre',
        message: `¿Aprobar la diferencia (${this.formatClpValue(row.differenceTotal)}) del turno ${row.number ?? '#' + sessionId}?`,
        confirmLabel: 'Aprobar',
      })
      .pipe(
        filter(Boolean),
        switchMap(() => this.cashService.approveDifference(sessionId, withoutErrorNotification())),
      )
      .subscribe({
        next: () => {
          this.snackBar.open('Diferencia de cierre aprobada.', 'Cerrar', { duration: 4000 });
          this.dataSource.reload();
        },
        error: (err: unknown) => {
          this.snackBar.open(
            resolveCashErrorMessage(err, 'No se pudo aprobar la diferencia de cierre.'),
            'Cerrar',
            { duration: 6000 },
          );
        },
      });
  }

  protected openReceiptDialog(row: CashSessionResponse): void {
    if (row.id === undefined) {
      return;
    }
    const data: ClosingReceiptDialogData = {
      sessionId: row.id,
      sessionNumber: row.number,
    };
    this.dialog
      .open<ClosingReceiptDialog, ClosingReceiptDialogData, boolean>(ClosingReceiptDialog, {
        data,
        width: '720px',
        maxWidth: '95vw',
      })
      .afterClosed()
      .subscribe((mutated) => {
        if (mutated) {
          this.dataSource.reload();
        }
      });
  }

  protected downloadRowPdf(row: CashSessionResponse): void {
    if (row.id === undefined) {
      return;
    }
    this.cashService.downloadClosingReceiptPdf(row.id, withoutErrorNotification()).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `cierre-caja-${row.number ?? row.id}.pdf`;
        link.rel = 'noopener';
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 10000);
      },
      error: (err: unknown) => {
        this.snackBar.open(
          resolveCashErrorMessage(err, 'No se pudo descargar el PDF del comprobante.'),
          'Cerrar',
          { duration: 6000 },
        );
      },
    });
  }

  protected canCloseSession(row: CashSessionResponse): boolean {
    if (row.status !== 'OPEN') {
      return false;
    }
    if (this.canCloseOthers()) {
      return true;
    }
    return (
      this.canCloseOwn() && row.cashier?.id !== undefined && row.cashier.id === this.currentUserId()
    );
  }

  protected isOwnSession(row: CashSessionResponse): boolean {
    return (
      row.cashier?.id !== undefined &&
      this.currentUserId() !== null &&
      row.cashier.id === this.currentUserId()
    );
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    const filters: Filters = {
      from: raw.from || null,
      to: raw.to || null,
      terminalId: raw.terminalId,
      cashierId: raw.cashierId,
      status: raw.status,
      withDifference: raw.withDifference,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({
      from: '',
      to: '',
      terminalId: null,
      cashierId: null,
      status: null,
      withDifference: null,
    });
    this.dataSource.setFilters({});
  }

  protected statusLabel(status: CashSessionResponse['status']): string {
    return sessionStatusLabel(status);
  }

  protected methodLabel(method: string | undefined): string {
    return paymentMethodLabel(method);
  }

  protected movementLabel(type: string | undefined): string {
    return movementTypeLabel(type);
  }

  protected formatDateTime(iso: string | undefined): string {
    return formatCashDateTime(iso);
  }

  protected formatDate(iso: string | undefined): string {
    return formatCashDate(iso);
  }

  protected formatUser(user: CashSessionResponse['cashier']): string {
    return formatUserDisplay(user);
  }

  protected loadCurrentSession(): void {
    if (!this.canOpen() || this.storeContext.terminalId() === null) {
      this.currentSession.set(null);
      this.currentSummary.set(null);
      this.currentMovements.set([]);
      this.currentError.set(null);
      this.loadingCurrent.set(false);
      return;
    }

    this.loadingCurrent.set(true);
    this.currentError.set(null);
    this.cashService.getCurrentSession(withoutErrorNotification()).subscribe({
      next: (session) => {
        this.currentSession.set(session);
        this.loadingCurrent.set(false);
        if (session?.id !== undefined) {
          this.loadSessionDetails(session.id);
        } else {
          this.currentSummary.set(null);
          this.currentMovements.set([]);
        }
      },
      error: (err: unknown) => {
        this.loadingCurrent.set(false);
        this.currentSession.set(null);
        this.currentError.set(
          resolveCashErrorMessage(err, 'No se pudo consultar el turno actual de la terminal.'),
        );
      },
    });
  }

  private loadSessionDetails(sessionId: number): void {
    forkJoin({
      summary: this.cashService
        .getSummary(sessionId, withoutErrorNotification())
        .pipe(catchError(() => of(null))),
      movements: this.cashService
        .listMovements(sessionId, withoutErrorNotification())
        .pipe(catchError(() => of([] as CashMovementResponse[]))),
    }).subscribe(({ summary, movements }) => {
      this.currentSummary.set(summary);
      this.currentMovements.set(movements);
    });
  }

  private loadCatalogsAndSettings(): void {
    const storeId = this.storeContext.storeId();
    if (storeId === null) {
      this.terminals.set([]);
      this.storeUsers.set([]);
      return;
    }

    this.cashService
      .getCashSettings(withoutErrorNotification())
      .pipe(catchError(() => of(DEFAULT_CASH_SETTINGS)))
      .subscribe((settings) => {
        this.cashSettings.set(settings);
        if (this.openForm.controls.openingAmount.pristine) {
          this.openForm.controls.openingAmount.setValue(settings.defaultOpeningAmount);
        }
      });

    this.cashService
      .listTerminals(storeId, withoutErrorNotification())
      .pipe(catchError(() => of([] as TerminalResponse[])))
      .subscribe((list) => this.terminals.set(list));

    if (this.hasFullReadScope() && this.canReadUsers()) {
      this.cashService
        .listStoreUsers(storeId, withoutErrorNotification())
        .pipe(catchError(() => of([] as UserSummary[])))
        .subscribe((users) => this.storeUsers.set(users));
    } else {
      this.storeUsers.set([]);
    }
  }

  private formatClpValue(value: number | undefined | null): string {
    if (value === undefined || value === null || !Number.isFinite(value)) {
      return '—';
    }
    const rounded = Math.round(Math.abs(value));
    const sign = value < 0 && rounded !== 0 ? '-' : '';
    return `${sign}$${new Intl.NumberFormat('es-CL', { maximumFractionDigits: 0 }).format(rounded)}`;
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }

  private parseBooleanParam(raw: string | null): boolean | undefined {
    if (raw === 'true') {
      return true;
    }
    if (raw === 'false') {
      return false;
    }
    return undefined;
  }
}
