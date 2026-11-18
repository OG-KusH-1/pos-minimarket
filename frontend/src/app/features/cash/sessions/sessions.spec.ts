import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { CashClosingReceiptResponse } from '../../../api/models/cash-closing-receipt-response';
import { CashSessionResponse } from '../../../api/models/cash-session-response';
import { CashSessionSummaryResponse } from '../../../api/models/cash-session-summary-response';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { OVERRIDE_TOKEN_HEADER } from '../../../shared/ui/override-dialog';
import { CloseSessionDialog, CloseSessionDialogData } from './close-session-dialog';
import { ClosingReceiptDialog, ClosingReceiptDialogData } from './closing-receipt-dialog';
import { MovementDialog, MovementDialogData } from './movement-dialog';
import { Sessions } from './sessions';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

function flushBlobError(req: TestRequest, status: number, body: unknown): void {
  req.flush(new Blob([JSON.stringify(body)], { type: 'application/json' }), {
    status,
    statusText: 'Error',
  });
}

const SAMPLE_SESSION_OPEN: CashSessionResponse = {
  id: 101,
  number: 'L01-TU-0001',
  businessDate: '2026-11-14',
  status: 'OPEN',
  openingAmount: 50000,
  openedAt: '2026-11-14T09:00:00Z',
  requiresDifferenceApproval: false,
  store: { id: 1, code: 'L01', name: 'Local Centro' },
  terminal: { id: 2, code: 'T01', name: 'Caja 1' },
  cashier: { id: 5, username: 'cajero1', fullName: 'Cajero Uno' },
};

const SAMPLE_SESSION_PENDING_APPROVAL: CashSessionResponse = {
  id: 102,
  number: 'L01-TU-0002',
  businessDate: '2026-11-14',
  status: 'CLOSED',
  openingAmount: 50000,
  expectedTotal: 75000,
  countedTotal: 73000,
  differenceTotal: -2000,
  openedAt: '2026-11-14T09:00:00Z',
  closedAt: '2026-11-14T18:00:00Z',
  requiresDifferenceApproval: true,
  store: { id: 1, code: 'L01', name: 'Local Centro' },
  terminal: { id: 2, code: 'T01', name: 'Caja 1' },
  cashier: { id: 5, username: 'cajero1', fullName: 'Cajero Uno' },
};

const SAMPLE_SUMMARY: CashSessionSummaryResponse = {
  sessionId: 101,
  openingAmount: 50000,
  cashIn: 10000,
  cashOut: 5000,
  expectedCash: 75000,
  salesCount: 3,
  voidCount: 0,
  returnCount: 0,
  byMethod: [
    {
      method: 'CASH',
      sales: 20000,
      refunds: 0,
      creditPayments: 0,
      expected: 75000,
    },
    {
      method: 'DEBIT',
      sales: 15000,
      refunds: 0,
      creditPayments: 0,
      expected: 15000,
    },
  ],
};

describe('Sessions', () => {
  let fixture: ComponentFixture<Sessions>;
  let component: Sessions;
  let http: HttpTestingController;
  let snackBar: { open: ReturnType<typeof vi.fn> };
  let confirmSpy: ReturnType<typeof vi.fn>;
  let permissions: Set<string>;
  let currentUserId: ReturnType<typeof signal<number>>;
  let activeStoreId: ReturnType<typeof signal<number | null>>;
  let activeTerminalId: ReturnType<typeof signal<number | null>>;

  async function setup(options?: {
    permissions?: readonly string[];
    userId?: number;
    storeId?: number | null;
    terminalId?: number | null;
  }): Promise<void> {
    permissions = new Set(
      options?.permissions ?? [
        'cash:session:open',
        'cash:session:close',
        'cash:session:close-others',
        'cash:movement:cash-in',
        'cash:movement:cash-out',
        'cash:difference:approve',
        'security:user:read',
      ],
    );
    currentUserId = signal(options?.userId ?? 5);
    activeStoreId = signal<number | null>(options?.storeId === undefined ? 1 : options.storeId);
    activeTerminalId = signal<number | null>(
      options?.terminalId === undefined ? 2 : options.terminalId,
    );
    snackBar = { open: vi.fn() };
    confirmSpy = vi.fn(() => of(true));

    await TestBed.configureTestingModule({
      imports: [Sessions],
      providers: [
        provideRouter([]),
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: snackBar },
        { provide: ConfirmDialogService, useValue: { confirm: confirmSpy } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => permissions.has(code),
            user: () => ({ id: currentUserId(), username: 'user', fullName: 'Usuario Test' }),
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: activeStoreId.asReadonly(),
            terminalId: activeTerminalId.asReadonly(),
            activeStore: () => ({ id: activeStoreId(), name: 'Local Centro' }),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Sessions);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function flushCatalogs(options?: { expectUsers?: boolean }): void {
    const settingsReq = http.expectOne('/api/v1/settings/effective');
    flushJson(settingsReq, {
      values: {
        'cash.default_opening_amount': '50000',
        'cash.difference_tolerance': '500',
      },
    });

    const terminalsReq = http.expectOne('/api/v1/stores/1/terminals');
    flushJson(terminalsReq, [{ id: 2, code: 'T01', name: 'Caja 1', active: true }]);

    if (options?.expectUsers ?? true) {
      const usersReq = http.expectOne((r) => r.url === '/api/v1/users');
      flushJson(usersReq, {
        content: [{ id: 5, username: 'cajero1', fullName: 'Cajero Uno' }],
      });
    } else {
      http.expectNone((r) => r.url === '/api/v1/users');
    }
  }

  function flushHistory(items: CashSessionResponse[] = []): void {
    const listReq = http.expectOne((r) => r.url === '/api/v1/cash-sessions' && r.method === 'GET');
    flushJson(listReq, {
      content: items,
      page: 0,
      size: 20,
      totalElements: items.length,
      totalPages: items.length > 0 ? 1 : 0,
      sort: ['openedAt,desc'],
    });
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
      vi.restoreAllMocks();
    }
  });

  it('renderiza el título exacto Caja, carga catálogos, maneja 204 sin turno y permite abrir turno', async () => {
    await setup();
    const h1 = fixture.nativeElement.querySelector('h1') as HTMLElement | null;
    expect(h1?.textContent?.trim()).toBe('Caja');

    flushCatalogs({ expectUsers: true });

    const currentReq = http.expectOne('/api/v1/cash-sessions/current');
    currentReq.flush(null, { status: 204, statusText: 'No Content' });
    flushHistory([]);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Abrir turno de caja');
    });

    const compInternal = component as unknown as {
      openForm: { controls: { openingAmount: { setValue: (v: number | null) => void } } };
      submitOpenSession: () => void;
    };
    compInternal.openForm.controls.openingAmount.setValue(60000);
    compInternal.submitOpenSession();

    const openReq = http.expectOne((r) => r.url === '/api/v1/cash-sessions' && r.method === 'POST');
    expect(openReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(openReq.request.body).toEqual({ openingAmount: 60000 });
    flushJson(openReq, SAMPLE_SESSION_OPEN);

    let summaryReq!: TestRequest;
    await vi.waitFor(() => {
      summaryReq = http.expectOne('/api/v1/cash-sessions/101/summary');
    });
    flushJson(summaryReq, SAMPLE_SUMMARY);
    const movementsReq = http.expectOne('/api/v1/cash-sessions/101/movements');
    flushJson(movementsReq, []);
    flushHistory([SAMPLE_SESSION_OPEN]);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L01-TU-0001');
      expect(fixture.nativeElement.textContent).toContain('Registrar movimiento');
    });
  });

  it('muestra mensaje en español cuando abrir turno falla con Blob CASH_SESSION_ALREADY_OPEN bajo errorInterceptor', async () => {
    await setup();
    flushCatalogs({ expectUsers: true });
    http
      .expectOne('/api/v1/cash-sessions/current')
      .flush(null, { status: 204, statusText: 'No Content' });
    flushHistory([]);

    const compInternal = component as unknown as {
      openError: () => string | null;
      submitOpenSession: () => void;
    };
    compInternal.submitOpenSession();

    const openReq = http.expectOne((r) => r.url === '/api/v1/cash-sessions' && r.method === 'POST');
    flushBlobError(openReq, 409, {
      code: 'CASH_SESSION_ALREADY_OPEN',
      detail: 'Terminal already has an open cash session',
      status: 409,
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(compInternal.openError()).toContain('Ya existe un turno de caja abierto');
    });
  });

  it('no consulta /api/v1/users sin security:user:read ni /cash-sessions/current sin permiso de apertura', async () => {
    await setup({
      permissions: ['report:cash:read'],
      terminalId: null,
    });

    flushCatalogs({ expectUsers: false });
    http.expectNone('/api/v1/cash-sessions/current');
    flushHistory([SAMPLE_SESSION_PENDING_APPROVAL]);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain(
        'Tu rol no tiene permiso para abrir o gestionar el turno activo de una terminal.',
      );
    });

    const tabs = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('[role="tab"]'),
    );
    const historyTab = tabs.find((el) => el.textContent?.includes('Historial de turnos'));
    expect(historyTab).toBeDefined();
    historyTab?.click();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L01-TU-0002');
    });
  });

  it('aplica filtros del historial de turnos como query params y aprueba diferencia con manejo SOD Blob/string', async () => {
    await setup({ userId: 99 });
    flushCatalogs({ expectUsers: true });
    http
      .expectOne('/api/v1/cash-sessions/current')
      .flush(null, { status: 204, statusText: 'No Content' });
    flushHistory([SAMPLE_SESSION_PENDING_APPROVAL]);

    const compInternal = component as unknown as {
      filterForm: {
        patchValue: (v: Record<string, unknown>) => void;
      };
      applyFilters: () => void;
      approveRowDifference: (row: CashSessionResponse) => void;
    };

    compInternal.filterForm.patchValue({
      from: '2026-11-01',
      to: '2026-11-14',
      terminalId: 2,
      cashierId: 5,
      status: 'CLOSED',
      withDifference: true,
    });
    compInternal.applyFilters();

    const filteredReq = http.expectOne(
      (r) => r.url === '/api/v1/cash-sessions' && r.method === 'GET' && r.params.has('from'),
    );
    expect(filteredReq.request.params.get('from')).toBe('2026-11-01');
    expect(filteredReq.request.params.get('to')).toBe('2026-11-14');
    expect(filteredReq.request.params.get('terminalId')).toBe('2');
    expect(filteredReq.request.params.get('cashierId')).toBe('5');
    expect(filteredReq.request.params.get('status')).toBe('CLOSED');
    expect(filteredReq.request.params.get('withDifference')).toBe('true');
    flushJson(filteredReq, {
      content: [SAMPLE_SESSION_PENDING_APPROVAL],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['openedAt,desc'],
    });

    compInternal.approveRowDifference(SAMPLE_SESSION_PENDING_APPROVAL);
    const approveErrReq = http.expectOne('/api/v1/cash-sessions/102/approve-difference');
    flushBlobError(approveErrReq, 403, {
      code: 'SEGREGATION_OF_DUTIES',
      detail: 'Same user cannot approve own difference',
      status: 403,
    });

    await vi.waitFor(() => {
      expect(snackBar.open).toHaveBeenCalledWith(
        expect.stringContaining('usuario distinto del cajero dueño del turno'),
        'Cerrar',
        expect.any(Object),
      );
    });
  });
});

describe('MovementDialog', () => {
  let fixture: ComponentFixture<MovementDialog>;
  let component: MovementDialog;
  let http: HttpTestingController;
  let dialogRefSpy: { close: ReturnType<typeof vi.fn>; disableClose: boolean };
  let dialogOpenSpy: ReturnType<typeof vi.fn>;

  async function setup(data?: Partial<MovementDialogData>): Promise<void> {
    dialogRefSpy = { close: vi.fn(), disableClose: false };
    dialogOpenSpy = vi.fn(() => ({
      afterClosed: () =>
        of({
          overrideToken: 'ovr-token-cash-1',
          authorizationId: 44,
          expiresAt: '2026-11-14T16:00:00Z',
        }),
    }));

    await TestBed.configureTestingModule({
      imports: [MovementDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            sessionId: 101,
            sessionNumber: 'L01-TU-0001',
            canCashIn: true,
            canCashOut: true,
            expectedCash: 75000,
            defaultType: 'CASH_OUT',
            ...data,
          } satisfies MovementDialogData,
        },
        { provide: MatDialogRef, useValue: dialogRefSpy },
      ],
    })
      .overrideProvider(MatDialog, { useValue: { open: dialogOpenSpy } })
      .compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MovementDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
      vi.restoreAllMocks();
    }
  });

  it('reintenta con X-Override-Token y la misma Idempotency-Key cuando CASH_OUT_OVER_LIMIT llega como Blob bajo errorInterceptor', async () => {
    await setup({ defaultType: 'CASH_OUT' });

    const compInternal = component as unknown as {
      form: {
        patchValue: (v: Record<string, unknown>) => void;
      };
      submit: () => void;
    };

    compInternal.form.patchValue({
      movementType: 'CASH_OUT',
      amount: 60000,
      reason: 'Retiro a caja fuerte por exceso de efectivo',
    });
    compInternal.submit();

    const firstReq = http.expectOne('/api/v1/cash-sessions/101/movements');
    const firstKey = firstReq.request.headers.get('Idempotency-Key');
    expect(firstKey).toBeTruthy();
    expect(firstReq.request.headers.get(OVERRIDE_TOKEN_HEADER)).toBeNull();
    expect(firstReq.request.body).toEqual({
      movementType: 'CASH_OUT',
      amount: 60000,
      reason: 'Retiro a caja fuerte por exceso de efectivo',
    });

    flushBlobError(firstReq, 422, {
      code: 'OVERRIDE_REQUIRED',
      detail: 'Supervisor override required',
      status: 422,
      action: 'CASH_OUT_OVER_LIMIT',
      context: { cashSessionId: 101, amount: 60000 },
    });

    await vi.waitFor(() => {
      expect(dialogOpenSpy).toHaveBeenCalledTimes(1);
    });

    const retryReq = http.expectOne('/api/v1/cash-sessions/101/movements');
    expect(retryReq.request.headers.get('Idempotency-Key')).toBe(firstKey);
    expect(retryReq.request.headers.get(OVERRIDE_TOKEN_HEADER)).toBe('ovr-token-cash-1');

    const createdMovement = {
      id: 501,
      movementType: 'CASH_OUT',
      amount: 60000,
      reason: 'Retiro a caja fuerte por exceso de efectivo',
    };
    flushJson(retryReq, createdMovement);

    await vi.waitFor(() => {
      expect(dialogRefSpy.close).toHaveBeenCalledWith(createdMovement);
    });
  });

  it('muestra error legible en el diálogo cuando CASH_OUT_EXCEEDS_AVAILABLE llega como Blob', async () => {
    await setup({ defaultType: 'CASH_OUT' });

    const compInternal = component as unknown as {
      form: { patchValue: (v: Record<string, unknown>) => void };
      errorMessage: () => string | null;
      submit: () => void;
    };

    compInternal.form.patchValue({
      movementType: 'CASH_OUT',
      amount: 999999,
      reason: 'Retiro mayor al disponible',
    });
    compInternal.submit();

    const req = http.expectOne('/api/v1/cash-sessions/101/movements');
    flushBlobError(req, 422, {
      code: 'CASH_OUT_EXCEEDS_AVAILABLE',
      detail: 'Insufficient cash',
      status: 422,
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(compInternal.errorMessage()).toContain('excede el efectivo disponible');
      expect(dialogRefSpy.close).not.toHaveBeenCalled();
    });
  });
});

describe('CloseSessionDialog', () => {
  let fixture: ComponentFixture<CloseSessionDialog>;
  let component: CloseSessionDialog;
  let http: HttpTestingController;
  let dialogRefSpy: { close: ReturnType<typeof vi.fn>; disableClose: boolean };
  let dialogOpenSpy: ReturnType<typeof vi.fn>;

  async function setup(): Promise<void> {
    dialogRefSpy = { close: vi.fn(), disableClose: false };
    dialogOpenSpy = vi.fn(() => ({
      afterClosed: () =>
        of({
          overrideToken: 'ovr-close-token-9',
          authorizationId: 88,
          expiresAt: '2026-11-14T19:00:00Z',
        }),
    }));

    await TestBed.configureTestingModule({
      imports: [CloseSessionDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: MAT_DIALOG_DATA,
          useValue: { session: SAMPLE_SESSION_OPEN } satisfies CloseSessionDialogData,
        },
        { provide: MatDialogRef, useValue: dialogRefSpy },
      ],
    })
      .overrideProvider(MatDialog, { useValue: { open: dialogOpenSpy } })
      .compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CloseSessionDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function flushSummaryAndSettings(): void {
    flushJson(http.expectOne('/api/v1/cash-sessions/101/summary'), SAMPLE_SUMMARY);
    flushJson(http.expectOne('/api/v1/settings/effective'), {
      values: { 'cash.difference_tolerance': '500' },
    });
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
      vi.restoreAllMocks();
    }
  });

  it('calcula denominaciones en vivo, detecta diferencia sobre tolerancia y cierra con OverrideDialog CASH_DIFFERENCE_APPROVAL', async () => {
    await setup();
    flushSummaryAndSettings();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Arqueo por medio de pago');
    });

    const compInternal = component as unknown as {
      onDenominationChange: (denomValue: number, rawValue: string) => void;
      onManualCountInput: (method: 'CASH' | 'DEBIT', rawValue: string) => void;
      denominationTotal: () => number;
      countedTotal: () => number;
      differenceTotal: () => number;
      exceedsTolerance: () => boolean;
      submitWithSupervisorOverride: () => void;
    };

    // Efectivo esperado = 75.000; ingresamos 2x20.000 + 4x500 = 42.000 (diferencia -33.000 > 500)
    compInternal.onDenominationChange(20000, '2');
    compInternal.onDenominationChange(500, '4');
    compInternal.onManualCountInput('DEBIT', '15000');

    expect(compInternal.denominationTotal()).toBe(42000);
    expect(compInternal.countedTotal()).toBe(57000);
    expect(compInternal.differenceTotal()).toBe(-33000);
    expect(compInternal.exceedsTolerance()).toBe(true);

    compInternal.submitWithSupervisorOverride();
    expect(dialogOpenSpy).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        data: {
          action: 'CASH_DIFFERENCE_APPROVAL',
          context: {
            cashSessionId: 101,
            differenceTotal: -33000,
          },
        },
      }),
    );

    const closeReq = http.expectOne('/api/v1/cash-sessions/101/close');
    expect(closeReq.request.headers.get(OVERRIDE_TOKEN_HEADER)).toBe('ovr-close-token-9');
    expect(closeReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(closeReq.request.body).toEqual({
      counts: [
        {
          paymentMethod: 'CASH',
          countedAmount: 42000,
          denominationDetail: { '20000': 2, '500': 4 },
        },
        { paymentMethod: 'DEBIT', countedAmount: 15000 },
      ],
    });

    const closed = {
      ...SAMPLE_SESSION_PENDING_APPROVAL,
      id: 101,
      requiresDifferenceApproval: false,
    };
    flushJson(closeReq, closed);

    await vi.waitFor(() => {
      expect(dialogRefSpy.close).toHaveBeenCalledWith(closed);
    });
  });

  it('permite cerrar directamente dejando diferencia pendiente de aprobación SOD', async () => {
    await setup();
    flushSummaryAndSettings();

    await vi.waitFor(() => {
      fixture.detectChanges();
    });

    const compInternal = component as unknown as {
      onDenominationChange: (denomValue: number, rawValue: string) => void;
      onManualCountInput: (method: 'CASH' | 'DEBIT', rawValue: string) => void;
      submitDirect: () => void;
    };
    compInternal.onDenominationChange(20000, '3');
    compInternal.onManualCountInput('DEBIT', '15000');
    compInternal.submitDirect();

    const closeReq = http.expectOne('/api/v1/cash-sessions/101/close');
    expect(closeReq.request.headers.get(OVERRIDE_TOKEN_HEADER)).toBeNull();
    flushJson(closeReq, { ...SAMPLE_SESSION_PENDING_APPROVAL, id: 101 });

    await vi.waitFor(() => {
      expect(dialogRefSpy.close).toHaveBeenCalled();
    });
  });
});

describe('ClosingReceiptDialog', () => {
  let fixture: ComponentFixture<ClosingReceiptDialog>;
  let component: ClosingReceiptDialog;
  let http: HttpTestingController;
  let dialogRefSpy: { close: ReturnType<typeof vi.fn> };
  let confirmSpy: ReturnType<typeof vi.fn>;

  const SAMPLE_RECEIPT: CashClosingReceiptResponse = {
    company: {
      rut: '76.123.456-7',
      legalName: 'Minimarket Central SpA',
      tradeName: 'Minimarket Central',
    },
    store: {
      id: 1,
      code: 'L01',
      name: 'Local Centro',
      address: 'Av. Principal 123',
      commune: 'Santiago',
    },
    session: {
      ...SAMPLE_SESSION_PENDING_APPROVAL,
      counts: [
        {
          paymentMethod: 'CASH',
          expectedAmount: 75000,
          countedAmount: 73000,
          difference: -2000,
        },
      ],
    },
    summary: {
      sessionId: 102,
      openingAmount: 50000,
      cashIn: 10000,
      cashOut: 5000,
      expectedCash: 75000,
      salesCount: 4,
      voidCount: 0,
      returnCount: 0,
    },
    printedAt: '2026-11-14T18:05:00Z',
  };

  let origCreateObjectUrl: PropertyDescriptor | undefined;
  let origRevokeObjectUrl: PropertyDescriptor | undefined;

  async function setup(): Promise<void> {
    dialogRefSpy = { close: vi.fn() };
    confirmSpy = vi.fn(() => of(true));

    await TestBed.configureTestingModule({
      imports: [ClosingReceiptDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: ConfirmDialogService, useValue: { confirm: confirmSpy } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => code === 'cash:difference:approve',
            user: () => ({ id: 99, username: 'supervisor', fullName: 'Supervisor' }),
          },
        },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            sessionId: 102,
            sessionNumber: 'L01-TU-0002',
          } satisfies ClosingReceiptDialogData,
        },
        { provide: MatDialogRef, useValue: dialogRefSpy },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClosingReceiptDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      if (origCreateObjectUrl) {
        Object.defineProperty(URL, 'createObjectURL', origCreateObjectUrl);
        origCreateObjectUrl = undefined;
      } else {
        delete (URL as unknown as Record<string, unknown>)['createObjectURL'];
      }
      if (origRevokeObjectUrl) {
        Object.defineProperty(URL, 'revokeObjectURL', origRevokeObjectUrl);
        origRevokeObjectUrl = undefined;
      } else {
        delete (URL as unknown as Record<string, unknown>)['revokeObjectURL'];
      }
      TestBed.resetTestingModule();
      vi.restoreAllMocks();
    }
  });

  it('carga comprobante JSON, descarga PDF oficial y aprueba diferencia pendiente', async () => {
    await setup();

    const jsonReq = http.expectOne(
      (r) =>
        r.url === '/api/v1/cash-sessions/102/closing-receipt' && r.params.get('format') === 'json',
    );
    jsonReq.flush(SAMPLE_RECEIPT);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Minimarket Central');
      expect(fixture.nativeElement.textContent).toContain('L01-TU-0002');
      expect(fixture.nativeElement.textContent).toContain(
        'Diferencia pendiente de aprobación por supervisor.',
      );
    });

    origCreateObjectUrl = Object.getOwnPropertyDescriptor(URL, 'createObjectURL');
    origRevokeObjectUrl = Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL');
    const createUrlMock = vi.fn().mockReturnValue('blob:mock-pdf');
    const revokeUrlMock = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: createUrlMock,
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      writable: true,
      value: revokeUrlMock,
    });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    const compInternal = component as unknown as {
      downloadPdf: () => void;
      approveDifference: () => void;
    };

    compInternal.downloadPdf();
    const pdfReq = http.expectOne(
      (r) =>
        r.url === '/api/v1/cash-sessions/102/closing-receipt' && r.params.get('format') === 'pdf',
    );
    expect(pdfReq.request.responseType).toBe('blob');
    pdfReq.flush(new Blob(['%PDF-1.4'], { type: 'application/pdf' }));

    await vi.waitFor(() => {
      expect(createUrlMock).toHaveBeenCalled();
    });

    compInternal.approveDifference();
    const approveReq = http.expectOne('/api/v1/cash-sessions/102/approve-difference');
    flushJson(approveReq, {
      ...SAMPLE_SESSION_PENDING_APPROVAL,
      requiresDifferenceApproval: false,
    });

    let reloadReq!: TestRequest;
    await vi.waitFor(() => {
      reloadReq = http.expectOne(
        (r) =>
          r.url === '/api/v1/cash-sessions/102/closing-receipt' &&
          r.params.get('format') === 'json',
      );
    });
    reloadReq.flush({
      ...SAMPLE_RECEIPT,
      session: { ...SAMPLE_SESSION_PENDING_APPROVAL, requiresDifferenceApproval: false },
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).not.toContain(
        'Diferencia pendiente de aprobación por supervisor.',
      );
    });
  });
});
