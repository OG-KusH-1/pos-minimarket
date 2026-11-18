import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { Audit } from './audit';
import { AuditDetailDialog } from './audit-detail-dialog';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('Audit', () => {
  let fixture: ComponentFixture<Audit>;
  let component: Audit;
  let http: HttpTestingController;
  let permissions: Set<string>;

  async function setup(options?: {
    permissions?: readonly string[];
    initialContent?: readonly Record<string, unknown>[];
    users?: readonly Record<string, unknown>[];
  }): Promise<void> {
    permissions = new Set(
      options?.permissions ?? [
        'security:audit:read',
        'security:audit:verify',
        'security:user:read',
      ],
    );

    const availableStores = signal([
      { id: 1, code: 'L01', name: 'Local Centro' },
      { id: 2, code: 'L02', name: 'Sucursal Norte' },
    ]);

    await TestBed.configureTestingModule({
      imports: [Audit],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => permissions.has(code),
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            availableStores: availableStores.asReadonly(),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Audit);
    component = fixture.componentInstance;
    fixture.detectChanges();

    if (permissions.has('security:user:read')) {
      const usersReq = http.expectOne((r) => r.url === '/api/v1/users');
      flushJson(usersReq, {
        content: options?.users ?? [
          { id: 10, username: 'admin', fullName: 'Administrador' },
          { id: 11, username: 'gerente1', fullName: 'Gerente Uno' },
        ],
        page: 0,
        size: 200,
        totalElements: 2,
        totalPages: 1,
      });
    }

    const listReq = http.expectOne((r) => r.url === '/api/v1/audit-log');
    expect(listReq.request.params.getAll('sort')).toEqual(['occurredAt,desc']);
    const content = options?.initialContent ?? [
      {
        id: 101,
        occurredAt: '2026-11-14T14:30:00Z',
        userId: 10,
        username: 'admin',
        storeId: 1,
        action: 'USER_UPDATED',
        entityType: 'user',
        entityId: '11',
        reason: 'Cambio de rol',
        hash: 'hash-101',
      },
    ];
    flushJson(listReq, {
      content,
      page: 0,
      size: 20,
      totalElements: content.length,
      totalPages: content.length > 0 ? 1 : 0,
      sort: ['occurredAt,desc'],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      if (content.length > 0) {
        expect(fixture.nativeElement.textContent).toContain('#101');
      }
    });
  }

  afterEach(() => {
    try {
      TestBed.inject(MatDialog).closeAll();
    } catch {
      // Sin diálogo abierto o TestBed ya limpiado.
    }
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('renderiza el título exacto «Auditoría» y la bitácora inicial', async () => {
    await setup();

    const h1 = fixture.nativeElement.querySelector('h1') as HTMLElement | null;
    expect(h1?.textContent).toBe('Auditoría');

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('#101');
    expect(text).toContain('Usuario actualizado');
    expect(text).toContain('USER_UPDATED');
    expect(text).toContain('Local Centro');
    expect(text).toContain('admin');
    expect(text).toContain('Cambio de rol');
  });

  it('aplica y limpia los filtros de acción, entidad, usuario, local y rango de fechas', async () => {
    await setup({ permissions: ['security:audit:read'] });

    const ctrl = component as unknown as {
      filterForm: { patchValue: (v: Record<string, unknown>) => void };
      applyFilters: () => void;
      clearFilters: () => void;
    };

    // Validación de rango inválido: no debe disparar petición HTTP
    ctrl.filterForm.patchValue({ from: '2026-11-15', to: '2026-11-10' });
    ctrl.applyFilters();
    fixture.detectChanges();
    http.expectNone((r) => r.url === '/api/v1/audit-log');
    expect(fixture.nativeElement.textContent).toContain(
      'La fecha «Desde» no puede ser posterior a «Hasta».',
    );

    // Filtros válidos
    ctrl.filterForm.patchValue({
      from: '2026-11-01',
      to: '2026-11-14',
      action: 'SALE_VOIDED',
      entityType: 'sale',
      entityId: '500',
      userId: 11,
      storeId: 2,
    });
    ctrl.applyFilters();

    const filteredReq = http.expectOne((r) => r.url === '/api/v1/audit-log');
    expect(filteredReq.request.params.get('from')).toBeTruthy();
    expect(filteredReq.request.params.get('to')).toBeTruthy();
    expect(filteredReq.request.params.get('action')).toBe('SALE_VOIDED');
    expect(filteredReq.request.params.get('entityType')).toBe('sale');
    expect(filteredReq.request.params.get('entityId')).toBe('500');
    expect(filteredReq.request.params.get('userId')).toBe('11');
    expect(filteredReq.request.params.get('storeId')).toBe('2');
    expect(filteredReq.request.params.get('page')).toBe('0');
    flushJson(filteredReq, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: ['occurredAt,desc'],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain(
        'No hay registros de auditoría que coincidan con los filtros.',
      );
    });

    // Limpiar filtros vuelve a consultar sin query params de filtro
    ctrl.clearFilters();
    const resetReq = http.expectOne((r) => r.url === '/api/v1/audit-log');
    expect(resetReq.request.params.get('action')).toBeNull();
    expect(resetReq.request.params.get('entityType')).toBeNull();
    expect(resetReq.request.params.get('userId')).toBeNull();
    flushJson(resetReq, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: ['occurredAt,desc'],
    });
  });

  it('oculta el panel de verificación cuando el usuario no tiene security:audit:verify', async () => {
    await setup({ permissions: ['security:audit:read'] });

    const verifyCard = fixture.nativeElement.querySelector('.verify-card');
    expect(verifyCard).toBeNull();
  });

  it('verifica la cadena intacta (valid=true) y detecta anomalías con firstBrokenId (HU-SEG-09)', async () => {
    await setup();

    const ctrl = component as unknown as {
      verifyForm: { patchValue: (v: Record<string, unknown>) => void };
      verifyChain: () => void;
    };

    // Validación local de rango inválido
    ctrl.verifyForm.patchValue({ fromId: 50, toId: 10 });
    ctrl.verifyChain();
    fixture.detectChanges();
    http.expectNone('/api/v1/audit-log/verify');
    expect(
      fixture.nativeElement.querySelector('[data-testid="verify-error"]')?.textContent,
    ).toContain('El ID inicial no puede ser mayor que el ID final.');

    // 1) Cadena íntegra
    ctrl.verifyForm.patchValue({ fromId: null, toId: null });
    ctrl.verifyChain();

    const okReq = http.expectOne('/api/v1/audit-log/verify');
    expect(okReq.request.method).toBe('POST');
    expect(okReq.request.body).toEqual({});
    flushJson(okReq, { checked: 42, valid: true });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const validBanner = fixture.nativeElement.querySelector(
        '[data-testid="verify-status-valid"]',
      );
      expect(validBanner).not.toBeNull();
      expect(validBanner?.textContent).toContain('Cadena íntegra');
      expect(validBanner?.textContent).toContain('42');
    });

    // 2) Cadena rota con anomalía en #17 e inspección del registro roto
    ctrl.verifyForm.patchValue({ fromId: 10, toId: 42 });
    ctrl.verifyChain();

    const brokenReq = http.expectOne('/api/v1/audit-log/verify');
    expect(brokenReq.request.body).toEqual({ fromId: 10, toId: 42 });
    flushJson(brokenReq, { checked: 8, valid: false, firstBrokenId: 17 });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const brokenBanner = fixture.nativeElement.querySelector(
        '[data-testid="verify-status-broken"]',
      );
      expect(brokenBanner).not.toBeNull();
      expect(brokenBanner?.textContent).toContain('Anomalía detectada en la cadena');
      expect(brokenBanner?.textContent).toContain('#17');
    });

    const inspectBtn = fixture.nativeElement.querySelector(
      '[data-testid="verify-status-broken"] button',
    ) as HTMLButtonElement | null;
    expect(inspectBtn).not.toBeNull();
    inspectBtn?.click();
    TestBed.tick();

    await vi.waitFor(() =>
      expect(document.querySelector('pos-audit-detail-dialog')).not.toBeNull(),
    );
    const detailReq = http.expectOne('/api/v1/audit-log/17');
    flushJson(detailReq, {
      id: 17,
      occurredAt: '2026-11-14T11:00:00Z',
      action: 'PRODUCT_PRICE_CHANGED',
      entityType: 'product',
      entityId: '5',
      oldValue: { priceGross: 1000 },
      newValue: { priceGross: 9999 },
      hash: 'tampered-hash-17',
    });

    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.body.textContent).toContain('tampered-hash-17');
    });
  });

  it('muestra error de servidor ProblemDetail al fallar la verificación de cadena', async () => {
    await setup();

    const ctrl = component as unknown as {
      verifyChain: () => void;
    };
    ctrl.verifyChain();

    const req = http.expectOne('/api/v1/audit-log/verify');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            status: 400,
            code: 'VALIDATION_ERROR',
            detail: 'Rango inválido en el servidor.',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 400, statusText: 'Bad Request' },
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      const errBanner = fixture.nativeElement.querySelector('[data-testid="verify-error"]');
      expect(errBanner?.textContent).toContain('Rango inválido en el servidor.');
    });
  });
});

describe('AuditDetailDialog', () => {
  let fixture: ComponentFixture<AuditDetailDialog>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AuditDetailDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            logId: 55,
            storeNames: new Map([[1, 'Local Centro']]),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AuditDetailDialog);
    fixture.detectChanges();
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('carga el detalle, renderiza metadatos y compara oldValue vs newValue', async () => {
    const req = http.expectOne('/api/v1/audit-log/55');
    expect(req.request.method).toBe('GET');
    flushJson(req, {
      id: 55,
      occurredAt: '2026-11-14T16:00:00Z',
      userId: 3,
      username: 'supervisor1',
      storeId: 1,
      terminalId: 2,
      ip: '192.168.1.25',
      action: 'SALE_VOIDED',
      entityType: 'sale',
      entityId: '900',
      oldValue: { status: 'COMPLETED', total: 15990 },
      newValue: { status: 'VOIDED', total: 15990, voidReason: 'Error cajero' },
      reason: 'Anulación autorizada',
      authorizationId: 77,
      traceId: 'trace-55',
      hash: 'sha256-deadbeef',
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('sha256-deadbeef');
    });

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Detalle de evento de auditoría #55');
    expect(text).toContain('Venta anulada');
    expect(text).toContain('Local Centro');
    expect(text).toContain('#2');
    expect(text).toContain('192.168.1.25');
    expect(text).toContain('#77');
    expect(text).toContain('trace-55');
    expect(text).toContain('Anulación autorizada');
    expect(text).toContain('COMPLETED');
    expect(text).toContain('VOIDED');
    expect(text).toContain('Error cajero');
  });

  it('muestra estado vacío cuando no hay oldValue ni newValue', async () => {
    const req = http.expectOne('/api/v1/audit-log/55');
    flushJson(req, {
      id: 55,
      occurredAt: '2026-11-14T16:00:00Z',
      action: 'AUTH_LOGIN_SUCCESS',
      entityType: 'user',
      entityId: '3',
      username: 'supervisor1',
      hash: 'sha256-login',
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain(
        'Este evento no registró valores anteriores ni posteriores.',
      );
    });
  });
});
