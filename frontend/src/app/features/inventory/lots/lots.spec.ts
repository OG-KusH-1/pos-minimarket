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
import { LotResponse } from '../../../api/models/lot-response';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { FefoSimulatorTab } from './fefo-simulator-tab';
import { LotEditDialog } from './lot-edit-dialog';
import { Lots } from './lots';

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

const SAMPLE_LOT: LotResponse = {
  id: 30,
  productId: 5,
  sku: 'LEC-01',
  name: 'Leche entera 1L',
  unitOfMeasure: 'UNIT',
  lotNumber: 'L2026-01',
  expiryDate: '2026-12-01',
  quantity: 12,
  receivedAt: '2026-11-01T10:00:00Z',
  status: 'ACTIVE',
  daysToExpiry: 13,
  expired: false,
  version: 2,
};

describe('Lots', () => {
  let fixture: ComponentFixture<Lots>;
  let component: Lots;
  let http: HttpTestingController;
  let permissions: Set<string>;
  let activeStoreId: ReturnType<typeof signal<number | null>>;

  async function setup(
    grantedPermissions: readonly string[] = [
      'inventory:stock:read',
      'inventory:expiry:read',
      'inventory:lot:update',
      'inventory:kardex:read',
      'catalog:cost:read',
    ],
  ): Promise<void> {
    permissions = new Set(grantedPermissions);
    activeStoreId = signal<number | null>(1);

    await TestBed.configureTestingModule({
      imports: [Lots],
      providers: [
        provideRouter([]),
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: AuthService,
          useValue: { hasPermission: (code: string) => permissions.has(code) },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: activeStoreId.asReadonly(),
            activeStore: () => ({ id: activeStoreId(), name: 'Local Centro' }),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Lots);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function flushInitialLoads(): void {
    const lotsReq = http.expectOne((r) => r.url === '/api/v1/lots');
    flushJson(lotsReq, {
      content: [SAMPLE_LOT],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['expiryDate,asc'],
    });
    const alertsReq = http.expectOne((r) => r.url === '/api/v1/expiry-alerts');
    flushJson(alertsReq, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: ['expiryDate,asc'],
    });
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('carga listado de lotes y sincroniza filtros status/withStock evitando contradicciones', async () => {
    await setup();
    flushInitialLoads();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L2026-01');
    });

    const ctrl = component as unknown as {
      filterForm: {
        patchValue: (v: Record<string, unknown>) => void;
        controls: {
          status: { setValue: (v: 'ACTIVE' | 'DEPLETED' | null) => void; value: unknown };
          withStock: { setValue: (v: boolean | null) => void; value: unknown };
        };
      };
      applyFilters: () => void;
    };

    ctrl.filterForm.controls.withStock.setValue(false);
    ctrl.filterForm.controls.status.setValue('ACTIVE');
    expect(ctrl.filterForm.controls.withStock.value).toBeNull();

    ctrl.filterForm.patchValue({ q: 'L2026', expiringWithinDays: 20 });
    ctrl.applyFilters();

    const filteredReq = http.expectOne((r) => r.url === '/api/v1/lots');
    expect(filteredReq.request.params.get('status')).toBe('ACTIVE');
    expect(filteredReq.request.params.get('q')).toBe('L2026');
    expect(filteredReq.request.params.get('expiringWithinDays')).toBe('20');
    flushJson(filteredReq, {
      content: [SAMPLE_LOT],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['expiryDate,asc'],
    });
  });

  it('oculta acciones de edición sin inventory:lot:update y recarga al editar con permiso', async () => {
    await setup(['inventory:stock:read', 'inventory:expiry:read']);
    flushInitialLoads();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L2026-01');
    });
    expect(
      fixture.nativeElement.querySelector('button[aria-label="Acciones para lote L2026-01"]'),
    ).toBeNull();

    TestBed.resetTestingModule();
    await setup();
    flushInitialLoads();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L2026-01');
    });

    const dialog = TestBed.inject(MatDialog);
    const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({
      afterClosed: () => of({ updated: { ...SAMPLE_LOT, lotNumber: 'L2026-01B', version: 3 } }),
    } as never);

    const ctrl = component as unknown as { openEditLot: (lot: LotResponse) => void };
    ctrl.openEditLot(SAMPLE_LOT);
    expect(openSpy).toHaveBeenCalled();

    const lotsReload = http.expectOne((r) => r.url === '/api/v1/lots');
    flushJson(lotsReload, {
      content: [{ ...SAMPLE_LOT, lotNumber: 'L2026-01B', version: 3 }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['expiryDate,asc'],
    });
    const alertsReload = http.expectOne((r) => r.url === '/api/v1/expiry-alerts');
    flushJson(alertsReload, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: ['expiryDate,asc'],
    });
  });
});

describe('LotEditDialog', () => {
  let fixture: ComponentFixture<LotEditDialog>;
  let component: LotEditDialog;
  let http: HttpTestingController;
  let closeSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [LotEditDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { lot: SAMPLE_LOT } },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LotEditDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('maneja 409 CONCURRENT_MODIFICATION y permite cerrar para recargar', async () => {
    const ctrl = component as unknown as {
      form: { patchValue: (v: Record<string, unknown>) => void };
      concurrentConflict: () => boolean;
      serverError: () => string | null;
      submit: () => void;
      closeForReload: () => void;
    };

    ctrl.form.patchValue({
      lotNumber: 'L2026-CORR',
      expiryDate: '2026-12-10',
      reason: 'Corrección de fecha impresa',
    });
    ctrl.submit();

    const req = http.expectOne('/api/v1/lots/30');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      lotNumber: 'L2026-CORR',
      expiryDate: '2026-12-10',
      reason: 'Corrección de fecha impresa',
      version: 2,
    });
    flushBlobError(req, 409, {
      code: 'CONCURRENT_MODIFICATION',
      detail: 'El lote fue modificado por otro usuario.',
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(ctrl.concurrentConflict()).toBe(true);
      expect(ctrl.serverError()).toContain('modificado por otro usuario');
    });

    ctrl.closeForReload();
    expect(closeSpy).toHaveBeenCalledWith({ reloadRequired: true });
  });
});

describe('FefoSimulatorTab', () => {
  let fixture: ComponentFixture<FefoSimulatorTab>;
  let component: FefoSimulatorTab;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FefoSimulatorTab],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FefoSimulatorTab);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('consulta GET /api/v1/lots/fefo y muestra asignaciones y faltante', async () => {
    const ctrl = component as unknown as {
      onProductSelected: (e: unknown) => void;
      form: { patchValue: (v: Record<string, unknown>) => void };
      submit: () => void;
    };

    ctrl.onProductSelected({ option: { value: { id: 5, sku: 'LEC-01', name: 'Leche' } } });
    const prodReq = http.expectOne('/api/v1/products/5');
    flushJson(prodReq, {
      id: 5,
      sku: 'LEC-01',
      name: 'Leche',
      unitOfMeasure: 'UNIT',
      lotControlled: true,
    });

    ctrl.form.patchValue({ quantity: 10 });
    ctrl.submit();

    const fefoReq = http.expectOne((r) => r.url === '/api/v1/lots/fefo');
    expect(fefoReq.request.params.get('productId')).toBe('5');
    expect(fefoReq.request.params.get('quantity')).toBe('10');
    flushJson(fefoReq, {
      productId: 5,
      requestedQty: 10,
      shortage: 3,
      allocations: [
        { lotId: 1, lotNumber: 'L-01', expiryDate: '2026-12-01', quantity: 5 },
        { lotId: 2, lotNumber: 'L-02', expiryDate: '2026-12-15', quantity: 2 },
      ],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const text = fixture.nativeElement.textContent as string;
      expect(text).toContain('L-01');
      expect(text).toContain('L-02');
      expect(text).toContain('Faltante: 3 u');
    });
  });
});
