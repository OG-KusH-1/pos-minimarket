import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { Products } from './products';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('Products', () => {
  let fixture: ComponentFixture<Products>;
  let http: HttpTestingController;
  let grantedPermissions: Set<string>;

  async function mountWithPermissions(permissions: readonly string[]): Promise<void> {
    grantedPermissions = new Set(permissions);
    await TestBed.configureTestingModule({
      imports: [Products],
      providers: [
        provideApiConfiguration(''),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => grantedPermissions.has(code),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Products);
    fixture.detectChanges();
  }

  async function flushInitialRequests(withCosts: boolean): Promise<void> {
    flushJson(
      http.expectOne((r) => r.url === '/api/v1/categories'),
      [{ id: 1, name: 'Bebidas', active: true, children: [] }],
    );
    flushJson(
      http.expectOne((r) => r.url === '/api/v1/brands'),
      { content: [{ id: 2, name: 'CCU', active: true }] },
    );
    flushJson(
      http.expectOne((r) => r.url === '/api/v1/products'),
      {
        content: [
          {
            id: 10,
            sku: 'BEB-001',
            name: 'Agua Mineral 500ml',
            categoryName: 'Bebidas',
            brandName: 'CCU',
            saleType: 'UNIT',
            primaryBarcode: '7801234567894',
            priceGross: 990,
            avgCost: withCosts ? 500 : null,
            marginPct: withCosts ? 39.88 : null,
            active: true,
          },
        ],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        sort: [],
      },
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('Agua Mineral 500ml');
    });
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('renderiza el título <h1>Productos</h1> y oculta columnas de costo/margen sin catalog:cost:read', async () => {
    await mountWithPermissions(['catalog:product:read']);
    await flushInitialRequests(false);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent?.trim()).toBe('Productos');
    expect(el.textContent).toContain('Agua Mineral 500ml');
    expect(el.textContent).not.toContain('Nuevo producto');
    expect(el.textContent).not.toContain('Costo prom.');
    expect(el.textContent).not.toContain('Margen');
  });

  it('muestra columnas de costo/margen y botón Nuevo producto cuando tiene permisos', async () => {
    await mountWithPermissions([
      'catalog:product:read',
      'catalog:product:create',
      'catalog:cost:read',
    ]);
    await flushInitialRequests(true);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Nuevo producto');
    expect(el.textContent).toContain('Costo prom.');
    expect(el.textContent).toContain('Margen');
    expect(el.textContent).toContain('$500');
    expect(el.textContent).toContain('39,88 %');
  });

  it('envía parámetros de filtro al consultar GET /api/v1/products', async () => {
    await mountWithPermissions(['catalog:product:read']);
    await flushInitialRequests(false);

    const component = fixture.componentInstance as unknown as {
      filterForm: Products['filterForm'];
      applyFilters(): void;
    };
    component.filterForm.patchValue({
      q: 'agua',
      saleType: 'UNIT',
      restricted: true,
      lotControlled: false,
    });
    component.applyFilters();

    const filteredReq = http.expectOne((r) => r.url === '/api/v1/products');
    expect(filteredReq.request.params.get('q')).toBe('agua');
    expect(filteredReq.request.params.get('saleType')).toBe('UNIT');
    expect(filteredReq.request.params.get('restricted')).toBe('true');
    expect(filteredReq.request.params.get('lotControlled')).toBe('false');
    flushJson(filteredReq, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: [],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('No hay productos que coincidan con los filtros.');
    });
  });
});
