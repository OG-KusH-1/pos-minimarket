import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { StockResponse } from '../../../api/models/stock-response';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { StockAlerts } from './stock-alerts';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('StockAlerts', () => {
  let fixture: ComponentFixture<StockAlerts>;
  let component: StockAlerts;
  let http: HttpTestingController;
  let permissions: Set<string>;

  async function setup(
    grantedPermissions: readonly string[] = [
      'inventory:stock:read',
      'inventory:stock-limits:update',
      'inventory:kardex:read',
      'catalog:cost:read',
    ],
    expandedByDefault = true,
  ): Promise<void> {
    permissions = new Set(grantedPermissions);
    await TestBed.configureTestingModule({
      imports: [StockAlerts],
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
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(StockAlerts);
    fixture.componentRef.setInput('expandedByDefault', expandedByDefault);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('carga alertas y muestra conteos, productos bajo mínimo y lotes por vencer', async () => {
    await setup();
    const req = http.expectOne('/api/v1/stock/alerts');
    flushJson(req, {
      belowMin: [
        {
          productId: 5,
          sku: 'LEC-01',
          name: 'Leche entera 1L',
          categoryName: 'Lácteos',
          unitOfMeasure: 'UNIT',
          quantity: 8,
          minQty: 10,
          maxQty: 30,
          status: 'BELOW_MIN',
          valuation: 9600,
        },
      ],
      zeroCount: 3,
      expiringCount: 1,
      expiring: [
        {
          lotId: 9,
          productId: 5,
          sku: 'LEC-01',
          name: 'Leche entera 1L',
          lotNumber: 'L2026-01',
          expiryDate: '2026-11-20',
          daysToExpiry: 6,
          quantity: 4,
          valuation: 4800,
        },
      ],
    });

    await vi.waitFor(() => {
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('[data-testid="below-min-count"]')?.textContent?.trim()).toBe('1');
      expect(el.querySelector('[data-testid="zero-count"]')?.textContent?.trim()).toBe('3');
      expect(el.querySelector('[data-testid="expiring-count"]')?.textContent?.trim()).toBe('1');
      expect(el.textContent).toContain('Leche entera 1L');
      expect(el.textContent).toContain('L2026-01');
      expect(el.textContent).toContain('6 días');
      expect(el.textContent).toContain('Valorización');
    });
  });

  it('oculta columnas de valorización y botón editar límites sin permisos', async () => {
    await setup(['inventory:stock:read']);
    const req = http.expectOne('/api/v1/stock/alerts');
    flushJson(req, {
      belowMin: [
        {
          productId: 5,
          sku: 'LEC-01',
          name: 'Leche entera 1L',
          categoryName: 'Lácteos',
          unitOfMeasure: 'UNIT',
          quantity: 8,
          minQty: 10,
          status: 'BELOW_MIN',
        },
      ],
      zeroCount: 0,
      expiringCount: 0,
      expiring: [],
    });

    await vi.waitFor(() => {
      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('Leche entera 1L');
      expect(el.textContent).not.toContain('Valorización');
      expect(el.querySelector('button[aria-label="Editar límites de Leche entera 1L"]')).toBeNull();
    });
  });

  it('emite filterStatus, editLimits y viewKardex al interactuar con tarjetas y acciones', async () => {
    await setup();
    const filterSpy = vi.fn();
    const editSpy = vi.fn();
    const kardexSpy = vi.fn();
    component.filterStatus.subscribe(filterSpy);
    component.editLimits.subscribe(editSpy);
    component.viewKardex.subscribe(kardexSpy);

    const item: StockResponse = {
      productId: 7,
      sku: 'PAN-01',
      name: 'Pan molde',
      categoryName: 'Panadería',
      unitOfMeasure: 'UNIT',
      quantity: 2,
      minQty: 5,
      status: 'BELOW_MIN',
    };

    const req = http.expectOne('/api/v1/stock/alerts');
    flushJson(req, {
      belowMin: [item],
      zeroCount: 4,
      expiringCount: 0,
      expiring: [],
    });

    await vi.waitFor(() => {
      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('Pan molde');
    });

    const el = fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>(
      'button[aria-label^="Filtrar productos bajo mínimo"]',
    )?.click();
    el.querySelector<HTMLButtonElement>(
      'button[aria-label^="Filtrar productos sin stock"]',
    )?.click();
    el.querySelector<HTMLButtonElement>(
      'button[aria-label="Editar límites de Pan molde"]',
    )?.click();
    el.querySelector<HTMLButtonElement>('button[aria-label="Ver kardex de Pan molde"]')?.click();

    expect(filterSpy).toHaveBeenNthCalledWith(1, 'BELOW_MIN');
    expect(filterSpy).toHaveBeenNthCalledWith(2, 'ZERO');
    expect(editSpy).toHaveBeenCalledWith(item);
    expect(kardexSpy).toHaveBeenCalledWith(7);
  });

  it('muestra alerta de error desempaquetado y permite reintentar', async () => {
    await setup();
    const firstReq = http.expectOne('/api/v1/stock/alerts');
    firstReq.flush(
      new Blob(
        [
          JSON.stringify({
            code: 'STORE_ACCESS_DENIED',
            detail: 'No tienes acceso al local seleccionado.',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 403, statusText: 'Forbidden' },
    );

    await vi.waitFor(() => {
      const alertBox = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
      expect(alertBox?.textContent).toContain('No tienes acceso al local seleccionado.');
    });

    const retryBtn = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '[role="alert"] button',
    );
    retryBtn?.click();

    const retryReq = http.expectOne('/api/v1/stock/alerts');
    flushJson(retryReq, { belowMin: [], zeroCount: 0, expiringCount: 0, expiring: [] });

    await vi.waitFor(() => {
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('[role="alert"]')).toBeNull();
      expect(el.querySelector('[data-testid="zero-count"]')?.textContent?.trim()).toBe('0');
    });
  });
});
