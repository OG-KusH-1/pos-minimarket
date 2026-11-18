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
import { ProductsService } from './products.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('ProductsService', () => {
  let service: ProductsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProductsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listProducts y getProduct consultan /api/v1/products y desenvuelven Blob JSON', async () => {
    const listPromise = firstValueFrom(service.listProducts({ q: 'agua', page: 0, size: 20 }));
    const listReq = http.expectOne((r) => r.url === '/api/v1/products');
    expect(listReq.request.method).toBe('GET');
    expect(listReq.request.params.get('q')).toBe('agua');
    flushJson(listReq, {
      content: [{ id: 10, sku: 'AGU-500', name: 'Agua 500ml', priceGross: 990 }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    const page = await listPromise;
    expect(page.content?.[0]?.sku).toBe('AGU-500');

    const getPromise = firstValueFrom(service.getProduct(10));
    const getReq = http.expectOne('/api/v1/products/10');
    expect(getReq.request.method).toBe('GET');
    flushJson(getReq, { id: 10, sku: 'AGU-500', name: 'Agua 500ml', version: 2 });
    const prod = await getPromise;
    expect(prod.version).toBe(2);
  });

  it('ejecuta precio, costo, códigos de barras, pack e impuestos contra sus endpoints', async () => {
    const pricePromise = firstValueFrom(
      service.changePrice(10, { newPriceGross: 1200, reason: 'Alza proveedor', version: 2 }),
    );
    const priceReq = http.expectOne('/api/v1/products/10/price');
    expect(priceReq.request.method).toBe('PUT');
    flushJson(priceReq, { id: 10, priceGross: 1200, version: 3 });
    expect((await pricePromise).priceGross).toBe(1200);

    const costPromise = firstValueFrom(
      service.changeCost(10, { newAvgCost: 650, reason: 'Ajuste inventario', version: 3 }),
    );
    const costReq = http.expectOne('/api/v1/products/10/cost');
    expect(costReq.request.method).toBe('PUT');
    flushJson(costReq, { id: 10, avgCost: 650, version: 4 });
    expect((await costPromise).avgCost).toBe(650);

    const barcodePromise = firstValueFrom(
      service.addBarcode(10, { code: '7801234567894', barcodeType: 'EAN13', isPrimary: true }),
    );
    const barcodeReq = http.expectOne('/api/v1/products/10/barcodes');
    expect(barcodeReq.request.method).toBe('POST');
    flushJson(barcodeReq, { id: 77, code: '7801234567894', barcodeType: 'EAN13', isPrimary: true });
    expect((await barcodePromise).id).toBe(77);

    const primaryPromise = firstValueFrom(service.setPrimaryBarcode(10, 77));
    const primaryReq = http.expectOne('/api/v1/products/10/barcodes/77/primary');
    expect(primaryReq.request.method).toBe('PUT');
    primaryReq.flush(null, { status: 204, statusText: 'No Content' });
    await primaryPromise;

    const packPromise = firstValueFrom(service.setPack(10, { baseProductId: 2, factor: 6 }));
    const packReq = http.expectOne('/api/v1/products/10/pack');
    expect(packReq.request.method).toBe('PUT');
    flushJson(packReq, { baseProductId: 2, factor: 6 });
    expect((await packPromise).factor).toBe(6);

    const taxPromise = firstValueFrom(service.updateTaxes(10, { vatExempt: false, taxIds: [1] }));
    const taxReq = http.expectOne('/api/v1/products/10/taxes');
    expect(taxReq.request.method).toBe('PUT');
    flushJson(taxReq, [{ id: 1, code: 'IVA', name: 'IVA 19%', ratePercent: 19 }]);
    expect((await taxPromise)[0]?.code).toBe('IVA');
  });

  it('uploadProductImage y downloadProductImage envían X-Store-Id cuando se indica storeId', async () => {
    const file = new File(['img'], 'foto.png', { type: 'image/png' });
    const uploadPromise = firstValueFrom(service.uploadProductImage(file, 5));
    const uploadReq = http.expectOne(
      (r) => r.url === '/api/v1/files' && r.params.get('category') === 'PRODUCT_IMAGE',
    );
    expect(uploadReq.request.method).toBe('POST');
    expect(uploadReq.request.headers.get('X-Store-Id')).toBe('5');
    flushJson(uploadReq, { id: 99, url: '/api/v1/files/99/content' });
    expect((await uploadPromise).id).toBe(99);

    const dlPromise = firstValueFrom(service.downloadProductImage(99, 5));
    const dlReq = http.expectOne('/api/v1/files/99/content');
    expect(dlReq.request.method).toBe('GET');
    expect(dlReq.request.headers.get('X-Store-Id')).toBe('5');
    const expectedBlob = new Blob(['pngdata'], { type: 'image/png' });
    dlReq.flush(expectedBlob);
    expect(await dlPromise).toBe(expectedBlob);
  });

  describe('con errorInterceptor y ProblemDetail Blob o string', () => {
    beforeEach(() => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          provideApiConfiguration(''),
          provideHttpClient(withInterceptors([errorInterceptor])),
          provideHttpClientTesting(),
          { provide: MatSnackBar, useValue: { open: vi.fn() } },
        ],
      });
      service = TestBed.inject(ProductsService);
      http = TestBed.inject(HttpTestingController);
    });

    it('desempaqueta ProblemDetail Blob en BARCODE_INVALID y errores por campo', async () => {
      const promise = firstValueFrom(
        service.addBarcode(
          10,
          { code: '7801234567890', barcodeType: 'EAN13', isPrimary: false },
          withoutErrorNotification(),
        ),
      );
      const req = http.expectOne('/api/v1/products/10/barcodes');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'BARCODE_INVALID',
              detail: 'El dígito verificador EAN-13 no es válido.',
              errors: [
                {
                  field: 'code',
                  code: 'BARCODE_INVALID',
                  message: 'Dígito verificador incorrecto.',
                },
              ],
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 422, statusText: 'Unprocessable Entity' },
      );

      const err = (await promise.catch((e: unknown) => e)) as ApiError;
      expect(err).toBeInstanceOf(ApiError);
      expect(err.code).toBe('BARCODE_INVALID');
      expect(err.errors[0]).toEqual({
        field: 'code',
        code: 'BARCODE_INVALID',
        message: 'Dígito verificador incorrecto.',
      });
    });

    it('desempaqueta ProblemDetail Blob en CONCURRENT_MODIFICATION al actualizar producto', async () => {
      const promise = firstValueFrom(
        service.updateProduct(
          10,
          {
            sku: 'AGU-500',
            name: 'Agua 500ml',
            categoryId: 1,
            saleType: 'UNIT',
            unitOfMeasure: 'UNIT',
            version: 1,
          },
          withoutErrorNotification(),
        ),
      );
      const req = http.expectOne('/api/v1/products/10');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'CONCURRENT_MODIFICATION',
              detail: 'El producto fue modificado por otro usuario. Recarga la ficha.',
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 409, statusText: 'Conflict' },
      );

      const err = (await promise.catch((e: unknown) => e)) as ApiError;
      expect(err).toBeInstanceOf(ApiError);
      expect(err.code).toBe('CONCURRENT_MODIFICATION');
      expect(err.message).toContain('modificado por otro usuario');
    });

    it('desempaqueta ProblemDetail JSON string en ENTITY_IN_USE al desactivar producto', async () => {
      const promise = firstValueFrom(service.deactivateProduct(10, withoutErrorNotification()));
      const req = http.expectOne('/api/v1/products/10/deactivate');
      req.flush(
        JSON.stringify({
          code: 'ENTITY_IN_USE',
          detail: 'No se puede desactivar el producto porque forma parte de un pack activo.',
        }),
        { status: 409, statusText: 'Conflict' },
      );

      const err = (await promise.catch((e: unknown) => e)) as ApiError;
      expect(err).toBeInstanceOf(ApiError);
      expect(err.code).toBe('ENTITY_IN_USE');
      expect(err.message).toContain('pack activo');
    });
  });
});
