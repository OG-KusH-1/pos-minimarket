import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { ProductSummary } from '../../../api/models/product-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { Kardex } from './kardex';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('Kardex', () => {
  let fixture: ComponentFixture<Kardex>;
  let component: Kardex;
  let http: HttpTestingController;
  let permissions: Set<string>;
  let activeStoreId: ReturnType<typeof signal<number | null>>;

  async function setup(options?: {
    queryParams?: Record<string, string>;
    permissions?: readonly string[];
  }): Promise<void> {
    permissions = new Set(options?.permissions ?? ['inventory:kardex:read', 'catalog:cost:read']);
    activeStoreId = signal<number | null>(1);

    await TestBed.configureTestingModule({
      imports: [Kardex],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: {
            queryParamMap: of(convertToParamMap(options?.queryParams ?? {})),
          },
        },
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
    fixture = TestBed.createComponent(Kardex);
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

  it('muestra estado inicial solicitando producto y no llama a /api/v1/kardex sin producto', async () => {
    await setup();
    http.expectNone((r) => r.url === '/api/v1/kardex');

    const h1 = fixture.nativeElement.querySelector('h1') as HTMLElement | null;
    expect(h1?.textContent?.trim()).toBe('Kardex');
    expect(fixture.nativeElement.textContent).toContain(
      'Busca y selecciona un producto para consultar sus movimientos de kardex.',
    );
  });

  it('carga movimientos al recibir ?productId=10 y ?lotId=5, mostrando tipo Venta, delta negativo y costo unitario', async () => {
    await setup({ queryParams: { productId: '10', lotId: '5' } });

    const prodReq = http.expectOne('/api/v1/products/10');
    flushJson(prodReq, {
      id: 10,
      sku: 'LEC-01',
      name: 'Leche entera 1L',
      unitOfMeasure: 'UNIT',
    });

    const kardexReq = http.expectOne((r) => r.url === '/api/v1/kardex');
    expect(kardexReq.request.params.get('productId')).toBe('10');
    expect(kardexReq.request.params.get('lotId')).toBe('5');
    expect(kardexReq.request.params.getAll('sort')).toEqual(['occurredAt,desc']);
    flushJson(kardexReq, {
      content: [
        {
          id: 201,
          productId: 10,
          movementType: 'SALE',
          quantityDelta: -3,
          balanceAfter: 17,
          unitCost: 1100,
          lotNumber: 'L2026-05',
          sourceType: 'SALE',
          sourceId: 88,
          username: 'cajero1',
          occurredAt: '2026-11-12T15:30:00Z',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['occurredAt,desc'],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Leche entera 1L');
    });

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Venta');
    expect(text).toContain('-3 u');
    expect(text).toContain('17 u');
    expect(text).toContain('Costo unitario');
    expect(text).toContain('$1.100');
    expect(text).toContain('L2026-05');
    expect(text).toContain('cajero1');
    expect(text).not.toContain('Editar');
    expect(text).not.toContain('Eliminar');
  });

  it('oculta Costo unitario sin catalog:cost:read y aplica filtros de fecha y tipo', async () => {
    await setup({ permissions: ['inventory:kardex:read'] });

    const ctrl = component as unknown as {
      onProductSelected: (ev: { option: { value: ProductSummary } }) => void;
      filterForm: { patchValue: (v: Record<string, unknown>) => void };
      applyFilters: () => void;
    };

    ctrl.onProductSelected({
      option: {
        value: {
          id: 22,
          sku: 'PAN-01',
          name: 'Pan marraqueta',
          saleType: 'WEIGHT',
        },
      },
    });

    const initReq = http.expectOne((r) => r.url === '/api/v1/kardex');
    expect(initReq.request.params.get('productId')).toBe('22');
    flushJson(initReq, {
      content: [
        {
          id: 300,
          productId: 22,
          movementType: 'PURCHASE_RECEIPT',
          quantityDelta: 5.5,
          balanceAfter: 10.5,
          unitCost: 1450,
          sourceType: 'GOODS_RECEIPT',
          sourceId: 12,
          occurredAt: '2026-11-11T10:00:00Z',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['occurredAt,desc'],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Pan marraqueta');
    });

    expect(fixture.nativeElement.textContent).not.toContain('Costo unitario');
    expect(fixture.nativeElement.textContent).not.toContain('$1.450');

    ctrl.filterForm.patchValue({
      from: '2026-11-01',
      to: '2026-11-12',
      movementType: 'PURCHASE_RECEIPT',
    });
    ctrl.applyFilters();

    const filteredReq = http.expectOne((r) => r.url === '/api/v1/kardex');
    expect(filteredReq.request.params.get('movementType')).toBe('PURCHASE_RECEIPT');
    expect(filteredReq.request.params.get('from')).toBeTruthy();
    expect(filteredReq.request.params.get('to')).toBeTruthy();
    flushJson(filteredReq, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: ['occurredAt,desc'],
    });
  });

  it('recarga movimientos al cambiar el local activo', async () => {
    await setup({ queryParams: { productId: '10' } });

    flushJson(http.expectOne('/api/v1/products/10'), {
      id: 10,
      sku: 'LEC-01',
      name: 'Leche entera 1L',
      unitOfMeasure: 'UNIT',
    });
    flushJson(
      http.expectOne((r) => r.url === '/api/v1/kardex'),
      {
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
        sort: ['occurredAt,desc'],
      },
    );

    activeStoreId.set(2);
    fixture.detectChanges();

    const reloadReq = http.expectOne((r) => r.url === '/api/v1/kardex');
    expect(reloadReq.request.params.get('productId')).toBe('10');
    flushJson(reloadReq, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: ['occurredAt,desc'],
    });
  });
});
