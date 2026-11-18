import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../../../api/api-configuration';
import { PosProductResult } from '../../../../api/models/pos-product-result';
import { ScanResultResponse } from '../../../../api/models/scan-result-response';
import { errorInterceptor } from '../../../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../../../core/http/http-context-tokens';
import { storeContextInterceptor } from '../../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../../core/store-context/store-context.service';
import { extractPosErrorMessage } from '../cart/pos-http.utils';
import { PosProductService } from './pos-product.service';

function jsonBlob(payload: unknown): Blob {
  return new Blob([JSON.stringify(payload)], { type: 'application/json' });
}

function problemBlob(payload: unknown): Blob {
  return new Blob([JSON.stringify(payload)], { type: 'application/problem+json' });
}

describe('PosProductService', () => {
  let service: PosProductService;
  let http: HttpTestingController | undefined;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, storeContextInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    const storeContext = TestBed.inject(StoreContextService);
    storeContext.setStore(4);
    storeContext.setTerminal(2);
    service = TestBed.inject(PosProductService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    localStorage.clear();
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('busca productos en /pos/products/search y desenvuelve la respuesta Blob', async () => {
    const expected: PosProductResult[] = [
      {
        productId: 101,
        sku: '101',
        name: 'Bebida Cola 1.5L',
        saleType: 'UNIT',
        unitOfMeasure: 'UNIT',
        priceGross: 1890,
        stockOnHand: 12,
      },
    ];

    const promise = firstValueFrom(service.searchProducts('cola', 15));
    const req = http!.expectOne((r) => r.url === '/api/v1/pos/products/search');
    expect(req.request.params.get('q')).toBe('cola');
    expect(req.request.params.get('limit')).toBe('15');
    expect(req.request.headers.get('X-Store-Id')).toBe('4');
    req.flush(jsonBlob(expected));

    const results = await promise;
    expect(results).toEqual(expected);
  });

  it('resuelve un código en /pos/products/resolve incluyendo código pesado', async () => {
    const expected: ScanResultResponse = {
      product: {
        productId: 50,
        sku: '20050',
        name: 'Queso Mantecoso',
        saleType: 'WEIGHT',
        unitOfMeasure: 'KG',
        priceGross: 9900,
      },
      quantity: 0.45,
      embeddedPrice: 4455,
    };

    const promise = firstValueFrom(service.resolveCode('2005000044551'));
    const req = http!.expectOne((r) => r.url === '/api/v1/pos/products/resolve');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('code')).toBe('2005000044551');
    req.flush(jsonBlob(expected));

    const resolved = await promise;
    expect(resolved).toEqual(expected);
  });

  it('resuelve código y desenvuelve ProblemDetail CODE_NOT_FOUND desde Blob con errorInterceptor', async () => {
    const promise = firstValueFrom(service.resolveCode('000000', withoutErrorNotification())).catch(
      async (error: unknown) => extractPosErrorMessage(error, 'Fallback'),
    );

    const req = http!.expectOne((r) => r.url === '/api/v1/pos/products/resolve');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('code')).toBe('000000');
    req.flush(
      problemBlob({
        code: 'CODE_NOT_FOUND',
        detail: 'No existe ningún producto asociado al código 000000.',
      }),
      { status: 404, statusText: 'Not Found' },
    );

    const message = await promise;
    expect(message).toBe('No existe ningún producto asociado al código 000000.');
  });
});
