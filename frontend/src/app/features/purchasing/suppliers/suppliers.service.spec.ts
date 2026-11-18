import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { ApiError } from '../../../core/http/api-error';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { SuppliersService } from './suppliers.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('SuppliersService', () => {
  let service: SuppliersService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SuppliersService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listSuppliers y getSupplier consultan /api/v1/suppliers y desenvuelven Blob JSON', async () => {
    const listPromise = firstValueFrom(service.listSuppliers({ q: 'distribuidora', active: true }));
    const listReq = http.expectOne((r) => r.url === '/api/v1/suppliers');
    expect(listReq.request.method).toBe('GET');
    expect(listReq.request.params.get('q')).toBe('distribuidora');
    expect(listReq.request.params.get('active')).toBe('true');
    flushJson(listReq, {
      content: [{ id: 1, rut: '76123456K', legalName: 'Distribuidora Sur Ltda.', active: true }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    const page = await listPromise;
    expect(page.content?.[0]?.legalName).toBe('Distribuidora Sur Ltda.');

    const getPromise = firstValueFrom(service.getSupplier(1));
    const getReq = http.expectOne('/api/v1/suppliers/1');
    expect(getReq.request.method).toBe('GET');
    flushJson(getReq, {
      id: 1,
      rut: '76123456K',
      legalName: 'Distribuidora Sur Ltda.',
      deliveryWeekdays: [1, 4],
      version: 3,
    });
    const detail = await getPromise;
    expect(detail.deliveryWeekdays).toEqual([1, 4]);
    expect(detail.version).toBe(3);
  });

  it('crea, actualiza, activa y desactiva proveedores contra sus endpoints', async () => {
    const createPromise = firstValueFrom(
      service.createSupplier({
        rut: '76123456K',
        legalName: 'Proveedor Uno',
        paymentTermsType: 'CREDIT',
        paymentTermsDays: 30,
        leadTimeDays: 2,
        deliveryWeekdays: [1, 4],
      }),
    );
    const createReq = http.expectOne('/api/v1/suppliers');
    expect(createReq.request.method).toBe('POST');
    flushJson(createReq, { id: 5, rut: '76123456K', legalName: 'Proveedor Uno', version: 1 });
    expect((await createPromise).id).toBe(5);

    const updatePromise = firstValueFrom(
      service.updateSupplier(5, {
        rut: '76123456K',
        legalName: 'Proveedor Uno S.A.',
        paymentTermsType: 'CASH_ON_DELIVERY',
        leadTimeDays: 3,
        version: 1,
      }),
    );
    const updateReq = http.expectOne('/api/v1/suppliers/5');
    expect(updateReq.request.method).toBe('PUT');
    flushJson(updateReq, { id: 5, legalName: 'Proveedor Uno S.A.', version: 2 });
    expect((await updatePromise).version).toBe(2);

    const deactivatePromise = firstValueFrom(service.deactivateSupplier(5));
    const deactivateReq = http.expectOne('/api/v1/suppliers/5/deactivate');
    expect(deactivateReq.request.method).toBe('POST');
    deactivateReq.flush('');
    await deactivatePromise;

    const activatePromise = firstValueFrom(service.activateSupplier(5));
    const activateReq = http.expectOne('/api/v1/suppliers/5/activate');
    expect(activateReq.request.method).toBe('POST');
    activateReq.flush('');
    await activatePromise;
  });

  it('gestiona asociaciones producto-proveedor y marcar preferido', async () => {
    const listPromise = firstValueFrom(service.listSupplierProducts({ id: 5, page: 0, size: 20 }));
    const listReq = http.expectOne((r) => r.url === '/api/v1/suppliers/5/products');
    expect(listReq.request.method).toBe('GET');
    flushJson(listReq, {
      content: [
        {
          id: 99,
          productId: 10,
          supplierId: 5,
          purchaseUnitName: 'Caja',
          unitsPerPurchaseUnit: 12,
          costPerPurchaseUnit: 6000,
          unitCostNet: 500,
          isPreferred: false,
          active: true,
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    const page = await listPromise;
    expect(page.content?.[0]?.unitCostNet).toBe(500);

    const createPromise = firstValueFrom(
      service.createProductSupplier({
        productId: 10,
        supplierId: 5,
        purchaseUnitName: 'Caja',
        unitsPerPurchaseUnit: 12,
        costPerPurchaseUnit: 6000,
        minOrderQty: 1,
        isPreferred: true,
      }),
    );
    const createReq = http.expectOne('/api/v1/product-suppliers');
    expect(createReq.request.method).toBe('POST');
    flushJson(createReq, { id: 100, unitCostNet: 500 });
    expect((await createPromise).id).toBe(100);

    const updatePromise = firstValueFrom(
      service.updateProductSupplier(100, {
        productId: 10,
        supplierId: 5,
        purchaseUnitName: 'Display',
        unitsPerPurchaseUnit: 6,
        costPerPurchaseUnit: 3000,
        minOrderQty: 2,
        version: 1,
      }),
    );
    const updateReq = http.expectOne('/api/v1/product-suppliers/100');
    expect(updateReq.request.method).toBe('PUT');
    flushJson(updateReq, { id: 100, purchaseUnitName: 'Display', unitCostNet: 500 });
    expect((await updatePromise).purchaseUnitName).toBe('Display');

    const prefPromise = firstValueFrom(service.markPreferredProductSupplier(100));
    const prefReq = http.expectOne('/api/v1/product-suppliers/100/preferred');
    expect(prefReq.request.method).toBe('PUT');
    prefReq.flush('');
    await prefPromise;

    const deactPromise = firstValueFrom(service.deactivateProductSupplier(100));
    const deactReq = http.expectOne('/api/v1/product-suppliers/100/deactivate');
    expect(deactReq.request.method).toBe('POST');
    deactReq.flush('');
    await deactPromise;

    const actPromise = firstValueFrom(service.activateProductSupplier(100));
    const actReq = http.expectOne('/api/v1/product-suppliers/100/activate');
    expect(actReq.request.method).toBe('POST');
    actReq.flush('');
    await actPromise;
  });

  it('convierte ProblemDetail recibido como Blob en ApiError tipado', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    const interceptedService = TestBed.inject(SuppliersService);
    const interceptedHttp = TestBed.inject(HttpTestingController);

    const errPromise = firstValueFrom(
      interceptedService.createSupplier(
        {
          rut: '111111111',
          legalName: 'Test',
          paymentTermsType: 'CASH_ON_DELIVERY',
          leadTimeDays: 1,
        },
        withoutErrorNotification(),
      ),
    ).catch((err: unknown) => err);

    const req = interceptedHttp.expectOne('/api/v1/suppliers');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            status: 422,
            code: 'RUT_INVALID',
            detail: 'RUT inválido',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    const err = await errPromise;
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('RUT_INVALID');
    interceptedHttp.verify();
  });
});
