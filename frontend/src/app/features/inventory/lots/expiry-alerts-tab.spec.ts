import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { ExpiryAlertResponse } from '../../../api/models/expiry-alert-response';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ExpiryAlertsTab } from './expiry-alerts-tab';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

const SAMPLE_ALERTS: ExpiryAlertResponse[] = [
  {
    lotId: 9,
    productId: 5,
    sku: 'LEC-01',
    name: 'Leche entera 1L',
    lotNumber: 'L2026-01',
    expiryDate: '2026-11-22',
    daysToExpiry: 4,
    quantity: 6,
    valuation: 7200,
  },
  {
    lotId: 11,
    productId: 8,
    sku: 'YOG-01',
    name: 'Yogurt natural',
    lotNumber: 'L2026-00',
    expiryDate: '2026-11-15',
    daysToExpiry: -3,
    quantity: 2,
    valuation: 1800,
  },
];

describe('ExpiryAlertsTab', () => {
  let fixture: ComponentFixture<ExpiryAlertsTab>;
  let component: ExpiryAlertsTab;
  let http: HttpTestingController;
  let permissions: Set<string>;
  let activeStoreId: ReturnType<typeof signal<number | null>>;

  async function setup(
    grantedPermissions: readonly string[] = [
      'inventory:expiry:read',
      'catalog:cost:read',
      'inventory:kardex:read',
    ],
  ): Promise<void> {
    permissions = new Set(grantedPermissions);
    activeStoreId = signal<number | null>(1);

    await TestBed.configureTestingModule({
      imports: [ExpiryAlertsTab],
      providers: [
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
    fixture = TestBed.createComponent(ExpiryAlertsTab);
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

  it('carga alertas de vencimiento, muestra badges y valorización con catalog:cost:read', async () => {
    await setup();
    const req = http.expectOne((r) => r.url === '/api/v1/expiry-alerts');
    flushJson(req, {
      content: SAMPLE_ALERTS,
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
      sort: ['expiryDate,asc'],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Leche entera 1L');
    });

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('4 días');
    expect(text).toContain('Vencido (3 d)');
    expect(text).toContain('Valorización');
    expect(text).toContain('$7.200');
  });

  it('oculta Valorización sin catalog:cost:read y filtra por ventana days', async () => {
    await setup(['inventory:expiry:read']);
    const initialReq = http.expectOne((r) => r.url === '/api/v1/expiry-alerts');
    flushJson(initialReq, {
      content: [{ ...SAMPLE_ALERTS[0], valuation: undefined }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['expiryDate,asc'],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Leche entera 1L');
    });
    expect(fixture.nativeElement.textContent).not.toContain('Valorización');

    const ctrl = component as unknown as {
      filterForm: { patchValue: (v: Record<string, unknown>) => void };
      applyFilters: () => void;
    };
    ctrl.filterForm.patchValue({ days: 7 });
    ctrl.applyFilters();

    const filteredReq = http.expectOne((r) => r.url === '/api/v1/expiry-alerts');
    expect(filteredReq.request.params.get('days')).toBe('7');
    flushJson(filteredReq, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: ['expiryDate,asc'],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain(
        'No hay lotes vencidos ni próximos a vencer en la ventana indicada.',
      );
    });
  });

  it('emite viewKardex al hacer clic en la acción de kardex', async () => {
    await setup();
    const spy = vi.fn();
    component.viewKardex.subscribe(spy);

    const req = http.expectOne((r) => r.url === '/api/v1/expiry-alerts');
    flushJson(req, {
      content: [SAMPLE_ALERTS[0]],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['expiryDate,asc'],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Leche entera 1L');
    });

    const btn = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      'button[aria-label="Ver kardex de lote L2026-01"]',
    );
    btn?.click();
    expect(spy).toHaveBeenCalledWith({ productId: 5, lotId: 9 });
  });
});
