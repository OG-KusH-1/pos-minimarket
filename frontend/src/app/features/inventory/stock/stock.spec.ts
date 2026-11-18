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
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { StockResponse } from '../../../api/models/stock-response';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { Stock } from './stock';
import { StockByStoreDialog } from './stock-by-store-dialog';
import { StockLimitsDialog } from './stock-limits-dialog';

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

const SAMPLE_STOCK: StockResponse = {
  productId: 10,
  sku: 'ARR-01',
  name: 'Arroz grado 1',
  categoryName: 'Abarrotes',
  unitOfMeasure: 'UNIT',
  quantity: 4,
  minQty: 10,
  maxQty: 40,
  status: 'BELOW_MIN',
  valuation: 4800,
  lastMovementAt: '2026-11-10T12:00:00Z',
};

describe('Stock', () => {
  let fixture: ComponentFixture<Stock>;
  let component: Stock;
  let http: HttpTestingController;
  let router: Router;
  let permissions: Set<string>;
  let activeStoreId: ReturnType<typeof signal<number | null>>;

  async function setup(
    grantedPermissions: readonly string[] = [
      'inventory:stock:read',
      'inventory:stock-limits:update',
      'inventory:kardex:read',
      'catalog:cost:read',
    ],
  ): Promise<void> {
    permissions = new Set(grantedPermissions);
    activeStoreId = signal<number | null>(1);

    await TestBed.configureTestingModule({
      imports: [Stock],
      providers: [
        provideRouter([]),
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
            storeId: activeStoreId.asReadonly(),
            activeStore: () => ({ id: activeStoreId(), name: 'Local Centro' }),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Stock);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function flushInitialLoads(items: StockResponse[] = [SAMPLE_STOCK]): void {
    const catReq = http.expectOne((r) => r.url === '/api/v1/categories');
    flushJson(catReq, [{ id: 1, name: 'Abarrotes', children: [] }]);

    const alertsReq = http.expectOne('/api/v1/stock/alerts');
    flushJson(alertsReq, {
      belowMin: items.filter((i) => i.status === 'BELOW_MIN'),
      zeroCount: 2,
      expiringCount: 0,
      expiring: [],
    });

    const stockReq = http.expectOne((r) => r.url === '/api/v1/stock');
    flushJson(stockReq, {
      content: items,
      page: 0,
      size: 20,
      totalElements: items.length,
      totalPages: items.length ? 1 : 0,
      sort: ['name,asc'],
    });
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('renderiza título Stock y carga categorías, alertas y tabla de existencias', async () => {
    await setup();
    flushInitialLoads();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Arroz grado 1');
    });

    const h1 = fixture.nativeElement.querySelector('h1') as HTMLElement | null;
    expect(h1?.textContent?.trim()).toBe('Stock');
    expect(fixture.nativeElement.textContent).toContain('Valorización');
    expect(fixture.nativeElement.textContent).toContain('$4.800');
  });

  it('oculta la columna Valorización cuando falta catalog:cost:read', async () => {
    await setup(['inventory:stock:read']);
    flushInitialLoads();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Arroz grado 1');
    });

    expect(fixture.nativeElement.textContent).not.toContain('Valorización');
    expect(fixture.nativeElement.textContent).not.toContain('$4.800');
  });

  it('filtra por estado BELOW_MIN y ZERO desde el formulario y desde las alertas', async () => {
    await setup();
    flushInitialLoads();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Arroz grado 1');
    });

    const ctrl = component as unknown as {
      filterForm: {
        patchValue: (v: Record<string, unknown>) => void;
      };
      applyFilters: () => void;
      filterByAlertStatus: (status: 'BELOW_MIN' | 'ZERO') => void;
    };

    ctrl.filterForm.patchValue({ status: 'BELOW_MIN', q: 'arroz' });
    ctrl.applyFilters();

    const belowReq = http.expectOne((r) => r.url === '/api/v1/stock');
    expect(belowReq.request.params.get('status')).toBe('BELOW_MIN');
    expect(belowReq.request.params.get('q')).toBe('arroz');
    flushJson(belowReq, {
      content: [SAMPLE_STOCK],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['name,asc'],
    });

    ctrl.filterByAlertStatus('ZERO');
    const zeroReq = http.expectOne((r) => r.url === '/api/v1/stock');
    expect(zeroReq.request.params.get('status')).toBe('ZERO');
    flushJson(zeroReq, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: ['name,asc'],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain(
        'No hay productos que coincidan con los filtros.',
      );
    });
  });

  it('abre diálogo de límites y recarga stock y alertas al guardar, o navega al kardex', async () => {
    await setup();
    flushInitialLoads();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Arroz grado 1');
    });

    const dialog = TestBed.inject(MatDialog);
    const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({
      afterClosed: () => of({ ...SAMPLE_STOCK, minQty: 15, maxQty: 50 }),
    } as never);
    const navSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const ctrl = component as unknown as {
      openEditLimits: (row: StockResponse) => void;
      goToKardex: (target: number | StockResponse | undefined) => void;
    };

    ctrl.openEditLimits(SAMPLE_STOCK);
    expect(openSpy).toHaveBeenCalled();

    const alertsReload = http.expectOne('/api/v1/stock/alerts');
    flushJson(alertsReload, { belowMin: [], zeroCount: 0, expiringCount: 0, expiring: [] });
    const stockReload = http.expectOne((r) => r.url === '/api/v1/stock');
    flushJson(stockReload, {
      content: [{ ...SAMPLE_STOCK, minQty: 15, maxQty: 50 }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['name,asc'],
    });

    ctrl.goToKardex(SAMPLE_STOCK);
    expect(navSpy).toHaveBeenCalledWith(['/inventario/kardex'], {
      queryParams: { productId: 10 },
    });

    navSpy.mockClear();
    ctrl.goToKardex(SAMPLE_STOCK.productId);
    expect(navSpy).toHaveBeenCalledWith(['/inventario/kardex'], {
      queryParams: { productId: 10 },
    });
  });
});

describe('StockLimitsDialog', () => {
  let fixture: ComponentFixture<StockLimitsDialog>;
  let component: StockLimitsDialog;
  let http: HttpTestingController;
  let closeSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [StockLimitsDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { item: SAMPLE_STOCK } },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(StockLimitsDialog);
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

  it('valida RN-34 (maxQty >= minQty) sin enviar solicitud si maxQty < minQty', () => {
    const ctrl = component as unknown as {
      form: {
        patchValue: (v: Record<string, unknown>) => void;
        controls: { maxQty: { hasError: (e: string) => boolean } };
      };
      submit: () => void;
    };

    ctrl.form.patchValue({ minQty: 20, maxQty: 10 });
    ctrl.submit();

    expect(ctrl.form.controls.maxQty.hasError('minMaxOrder')).toBe(true);
    http.expectNone('/api/v1/stock/10/limits');
  });

  it('envía PUT /api/v1/stock/{productId}/limits y muestra errores de servidor de ApiError', async () => {
    const ctrl = component as unknown as {
      form: {
        patchValue: (v: Record<string, unknown>) => void;
        controls: { maxQty: { getError: (e: string) => unknown } };
      };
      serverError: () => string | null;
      submit: () => void;
    };

    ctrl.form.patchValue({ minQty: 12, maxQty: 30 });
    ctrl.submit();

    const failFieldReq = http.expectOne('/api/v1/stock/10/limits');
    expect(failFieldReq.request.method).toBe('PUT');
    flushBlobError(failFieldReq, 400, {
      code: 'VALIDATION_ERROR',
      detail: 'Datos inválidos',
      errors: [
        {
          field: 'maxQty',
          code: 'MIN_MAX_ORDER',
          message: 'El máximo excede la capacidad permitida',
        },
      ],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(ctrl.form.controls.maxQty.getError('server')).toBe(
        'El máximo excede la capacidad permitida',
      );
    });

    ctrl.form.patchValue({ minQty: 12, maxQty: 28 });
    ctrl.submit();

    const failGeneralReq = http.expectOne('/api/v1/stock/10/limits');
    flushBlobError(failGeneralReq, 409, {
      code: 'BUSINESS_RULE',
      detail: 'Regla de negocio rechazada',
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(ctrl.serverError()).toBe('Regla de negocio rechazada');
    });

    ctrl.form.patchValue({ minQty: 12, maxQty: 25 });
    ctrl.submit();

    const okReq = http.expectOne('/api/v1/stock/10/limits');
    expect(okReq.request.body).toEqual({ minQty: 12, maxQty: 25 });
    const updated = { ...SAMPLE_STOCK, minQty: 12, maxQty: 25 };
    flushJson(okReq, updated);

    await vi.waitFor(() => {
      expect(closeSpy).toHaveBeenCalledWith(updated);
    });
  });
});

describe('StockByStoreDialog', () => {
  let fixture: ComponentFixture<StockByStoreDialog>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StockByStoreDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { item: SAMPLE_STOCK } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(StockByStoreDialog);
    fixture.detectChanges();
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('carga desglose por local, total empresa y unidades en tránsito', async () => {
    const req = http.expectOne('/api/v1/stock/10');
    flushJson(req, {
      productId: 10,
      sku: 'ARR-01',
      name: 'Arroz grado 1',
      companyQuantity: 25,
      inTransit: 6,
      stores: [
        { storeId: 1, storeCode: 'CTR', storeName: 'Local Centro', quantity: 10 },
        { storeId: 2, storeCode: 'NTE', storeName: 'Local Norte', quantity: 15 },
      ],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Local Centro');
    });

    expect(fixture.nativeElement.textContent).toContain('Local Norte');
    expect(fixture.nativeElement.textContent).toContain('25 u');
    expect(fixture.nativeElement.textContent).toContain('6 u');
  });
});
