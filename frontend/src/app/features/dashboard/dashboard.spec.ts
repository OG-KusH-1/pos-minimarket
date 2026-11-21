import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter, Router } from '@angular/router';
import { provideApiConfiguration } from '../../api/api-configuration';
import { DashboardResponse } from '../../api/models/dashboard-response';
import { AuthService } from '../../core/auth/auth.service';
import type { StoreSummary } from '../../core/auth/auth.models';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { StoreContextService } from '../../core/store-context/store-context.service';
import { Dashboard } from './dashboard';

function flushJsonBlob(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

function flushProblemBlob(req: TestRequest, status: number, body: unknown): void {
  req.flush(new Blob([JSON.stringify(body)], { type: 'application/problem+json' }), {
    status,
    statusText: 'Error',
  });
}

const SAMPLE_DASHBOARD: DashboardResponse = {
  date: '2026-11-20',
  salesTotal: 125000,
  transactions: 18,
  avgTicket: 6944,
  vsPreviousDayPct: 12.5,
  salesByHour: [
    { hour: 10, total: 45000, transactions: 6 },
    { hour: 19, total: 80000, transactions: 12 },
  ],
  topProducts: [
    { productId: 12, name: 'Pan Marraqueta 1 kg', qty: 14, total: 28000 },
    { productId: 15, name: 'Leche entera 1 L', qty: 10, total: 12500 },
  ],
  paymentMix: [
    { method: 'CASH', total: 85000 },
    { method: 'DEBIT', total: 40000 },
  ],
  alerts: {
    belowMin: 4,
    zeroStock: 2,
    expiringLots: 3,
    pendingShrinkages: 1,
    pendingAdjustments: 1,
    pendingCounts: 1,
    pendingCashDifferences: 1,
  },
};

describe('Dashboard', () => {
  let fixture: ComponentFixture<Dashboard>;
  let component: Dashboard;
  let http: HttpTestingController;
  let router: Router;
  let snackBarOpen: ReturnType<typeof vi.fn>;
  let permissions: Set<string>;
  let activeStoreId: ReturnType<typeof signal<number | null>>;
  let availableStores: ReturnType<typeof signal<readonly StoreSummary[]>>;
  let origCreateObjectUrl: PropertyDescriptor | undefined;
  let origRevokeObjectUrl: PropertyDescriptor | undefined;

  async function setup(
    grantedPermissions: readonly string[] = [
      'report:dashboard:read',
      'report:export:execute',
      'inventory:stock:read',
      'inventory:expiry:read',
      'shrinkage:record:read',
      'inventory:adjustment:create',
      'inventory:count:execute',
      'cash:session:read',
    ],
    stores: readonly StoreSummary[] = [{ id: 10, code: 'L01', name: 'Local Centro', active: true }],
    initialStoreId: number | null = 10,
  ): Promise<void> {
    permissions = new Set(grantedPermissions);
    activeStoreId = signal<number | null>(initialStoreId);
    availableStores = signal<readonly StoreSummary[]>(stores);
    snackBarOpen = vi.fn();

    await TestBed.configureTestingModule({
      imports: [Dashboard],
      providers: [
        provideRouter([]),
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => permissions.has(code),
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: activeStoreId.asReadonly(),
            availableStores: availableStores.asReadonly(),
            activeStore: () => availableStores().find((s) => s.id === activeStoreId()) ?? null,
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Dashboard);
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

  it('renderiza h1 exacto Inicio y muestra KPIs, top 10, mix de pagos y las 7 alertas', async () => {
    await setup();
    const req = http.expectOne((r) => r.url === '/api/v1/reports/dashboard');
    expect(req.request.params.keys()).toEqual([]);
    flushJsonBlob(req, SAMPLE_DASHBOARD);

    await vi.waitFor(() => {
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('[data-testid="kpi-sales-total"]')?.textContent?.trim()).toBe(
        '$125.000',
      );
      expect(el.querySelector('[data-testid="kpi-transactions"]')?.textContent?.trim()).toBe('18');
      expect(el.querySelector('[data-testid="kpi-avg-ticket"]')?.textContent?.trim()).toBe(
        '$6.944',
      );
      expect(el.querySelector('[data-testid="kpi-variation"]')?.textContent).toContain('+12,50 %');
      expect(el.querySelector('[data-testid="kpi-alerts-total"]')?.textContent?.trim()).toBe('13');
      expect(el.textContent).toContain('Pan Marraqueta 1 kg');
      expect(el.textContent).toContain('Efectivo');
      expect(el.textContent).toContain('Tarjeta de débito');
      expect(el.querySelectorAll('[data-alert]')).toHaveLength(7);
    });

    const h1 = (fixture.nativeElement as HTMLElement).querySelector('h1');
    expect(h1?.textContent).toBe('Inicio');
  });

  it('recarga reactivamente el dashboard cuando cambia el local activo en StoreContextService', async () => {
    await setup(undefined, [
      { id: 10, code: 'L01', name: 'Local Centro', active: true },
      { id: 20, code: 'L02', name: 'Local Norte', active: true },
    ]);

    const firstReq = http.expectOne('/api/v1/reports/dashboard');
    flushJsonBlob(firstReq, SAMPLE_DASHBOARD);
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('$125.000');
    });

    activeStoreId.set(20);
    fixture.detectChanges();

    const reloadReq = http.expectOne('/api/v1/reports/dashboard');
    flushJsonBlob(reloadReq, {
      ...SAMPLE_DASHBOARD,
      salesTotal: 54000,
      transactions: 7,
      avgTicket: 7714,
      vsPreviousDayPct: undefined,
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('[data-testid="kpi-sales-total"]')?.textContent?.trim()).toBe(
        '$54.000',
      );
      expect(el.querySelector('[data-testid="kpi-variation"]')?.textContent).toContain(
        'Sin referencia del día anterior',
      );
      expect(el.textContent).toContain('Local Norte');
    });
  });

  it('filtra por fecha y múltiples locales y vuelve a hoy con resetToToday', async () => {
    await setup(undefined, [
      { id: 10, code: 'L01', name: 'Local Centro', active: true },
      { id: 20, code: 'L02', name: 'Local Norte', active: true },
    ]);

    const initReq = http.expectOne('/api/v1/reports/dashboard');
    flushJsonBlob(initReq, SAMPLE_DASHBOARD);
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('$125.000');
    });

    const ctrl = component as unknown as {
      filterForm: { patchValue: (v: Record<string, unknown>) => void };
      applyFilters: () => void;
      resetToToday: () => void;
    };

    ctrl.filterForm.patchValue({ date: '2026-11-18', storeIds: [10, 20] });
    ctrl.applyFilters();

    const filteredReq = http.expectOne((r) => r.url === '/api/v1/reports/dashboard');
    expect(filteredReq.request.params.get('date')).toBe('2026-11-18');
    expect(filteredReq.request.params.getAll('storeIds')).toEqual(['10', '20']);
    flushJsonBlob(filteredReq, {
      ...SAMPLE_DASHBOARD,
      date: '2026-11-18',
      salesTotal: 210000,
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('$210.000');
    });

    ctrl.resetToToday();
    const todayReq = http.expectOne((r) => r.url === '/api/v1/reports/dashboard');
    expect(todayReq.request.params.has('date')).toBe(false);
    expect(todayReq.request.params.getAll('storeIds')).toEqual(['10', '20']);
    flushJsonBlob(todayReq, SAMPLE_DASHBOARD);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('$125.000');
    });
  });

  it('oculta botón Exportar cuando falta report:export:execute', async () => {
    await setup(['report:dashboard:read']);
    flushJsonBlob(http.expectOne('/api/v1/reports/dashboard'), SAMPLE_DASHBOARD);
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('$125.000');
    });
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Exportar');
  });

  it('descarga XLSX/PDF al exportar cuando tiene permiso report:export:execute', async () => {
    await setup(['report:dashboard:read', 'report:export:execute']);
    flushJsonBlob(http.expectOne('/api/v1/reports/dashboard'), SAMPLE_DASHBOARD);
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Exportar');
    });

    origCreateObjectUrl = Object.getOwnPropertyDescriptor(URL, 'createObjectURL');
    origRevokeObjectUrl = Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL');
    const createUrlMock = vi.fn().mockReturnValue('blob:mock-export');
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

    const ctrl = component as unknown as {
      exportAs: (format: 'xlsx' | 'pdf') => void;
    };
    ctrl.exportAs('pdf');

    const exportReq = http.expectOne((r) => r.url === '/api/v1/reports/dashboard/export');
    expect(exportReq.request.params.get('format')).toBe('pdf');
    exportReq.flush(new Blob(['pdf'], { type: 'application/pdf' }), {
      headers: { 'Content-Disposition': 'attachment; filename="reporte-dashboard.pdf"' },
    });

    await vi.waitFor(() => {
      expect(createUrlMock).toHaveBeenCalledOnce();
      expect(revokeUrlMock).toHaveBeenCalledWith('blob:mock-export');
      expect(snackBarOpen).toHaveBeenCalledWith(
        'Dashboard exportado (reporte-dashboard.pdf).',
        'Cerrar',
        { duration: 4000 },
      );
    });
  });

  it('muestra alerta de error y permite reintentar la carga', async () => {
    await setup();
    const firstReq = http.expectOne('/api/v1/reports/dashboard');
    flushProblemBlob(firstReq, 403, {
      code: 'STORE_ACCESS_DENIED',
      detail: 'No tienes acceso al local seleccionado.',
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const alertBox = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
      expect(alertBox?.textContent).toContain('No tienes acceso al local seleccionado.');
    });

    const retryBtn = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '[role="alert"] button',
    );
    retryBtn?.click();

    const retryReq = http.expectOne('/api/v1/reports/dashboard');
    flushJsonBlob(retryReq, SAMPLE_DASHBOARD);

    await vi.waitFor(() => {
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('[role="alert"]')).toBeNull();
      expect(el.textContent).toContain('$125.000');
    });
  });

  it('navega al módulo de una alerta cuando tiene permiso y bloquea enlaces sin permiso', async () => {
    await setup(['report:dashboard:read', 'inventory:expiry:read']);
    flushJsonBlob(http.expectOne('/api/v1/reports/dashboard'), SAMPLE_DASHBOARD);
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-alert="expiringLots"]'),
      ).not.toBeNull();
    });

    const navSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const el = fixture.nativeElement as HTMLElement;

    const expiringTile = el.querySelector<HTMLElement>('[data-alert="expiringLots"]');
    expect(expiringTile?.tagName).toBe('BUTTON');
    expiringTile?.click();
    expect(navSpy).toHaveBeenCalledWith(['/inventario/lotes'], {
      queryParams: { tab: 'alerts' },
    });

    const cashTile = el.querySelector<HTMLElement>('[data-alert="pendingCashDifferences"]');
    expect(cashTile?.tagName).toBe('DIV');
  });
});
