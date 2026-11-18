import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { Orders } from './orders';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('Orders', () => {
  let fixture: ComponentFixture<Orders>;
  let http: HttpTestingController;
  let grantedPermissions: Set<string>;

  beforeEach(async () => {
    grantedPermissions = new Set([
      'purchasing:order:read',
      'purchasing:order:create',
      'purchasing:order:send',
      'purchasing:order:cancel',
      'purchasing:receipt:create',
      'catalog:cost:read',
    ]);

    await TestBed.configureTestingModule({
      imports: [Orders],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            hasPermission: (perm: string) => grantedPermissions.has(perm),
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: signal(1),
            activeStore: signal({ id: 1, code: 'CTR', name: 'Local Centro' }),
          },
        },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function flushInitialLoad(): void {
    const ordersReq = http.expectOne((r) => r.url === '/api/v1/purchase-orders');
    flushJson(ordersReq, {
      content: [
        {
          id: 10,
          number: 'OC-010',
          supplierName: 'Distribuidora Sur',
          origin: 'MANUAL',
          status: 'SENT',
          total: 125000,
          createdAt: '2026-11-18T10:00:00Z',
          expectedDate: '2026-11-22',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['createdAt,desc'],
    });

    const supReq = http.expectOne((r) => r.url === '/api/v1/suppliers');
    flushJson(supReq, {
      content: [{ id: 1, legalName: 'Distribuidora Sur', rut: '76.111.222-3' }],
    });
  }

  it('muestra el listado de órdenes y la columna Total cuando tiene catalog:cost:read', async () => {
    fixture = TestBed.createComponent(Orders);
    fixture.detectChanges();
    flushInitialLoad();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('OC-010');
    expect(text).toContain('Distribuidora Sur');
    expect(text).toContain('Total');
    expect(text).toContain('Nueva orden de compra');
  });

  it('oculta la columna Total sin catalog:cost:read y el botón crear sin purchasing:order:create', async () => {
    grantedPermissions.delete('catalog:cost:read');
    grantedPermissions.delete('purchasing:order:create');

    fixture = TestBed.createComponent(Orders);
    fixture.detectChanges();
    flushInitialLoad();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('OC-010');
    expect(text).not.toContain('Nueva orden de compra');
    const headers = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('th')).map(
      (th) => th.textContent?.trim(),
    );
    expect(headers).not.toContain('Total');
  });
});
