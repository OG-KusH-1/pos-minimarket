import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApiConfiguration } from '../../../../api/api-configuration';
import { CartQuoteResponse } from '../../../../api/models/cart-quote-response';
import { ScanResultResponse } from '../../../../api/models/scan-result-response';
import { errorInterceptor } from '../../../../core/http/error.interceptor';
import { storeContextInterceptor } from '../../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../../core/store-context/store-context.service';
import { POS_CART_STORAGE_KEY } from './pos-cart.models';
import { PosCartStore } from './pos-cart.store';

function jsonBlob(payload: unknown): Blob {
  return new Blob([JSON.stringify(payload)], { type: 'application/json' });
}

function problemBlob(payload: unknown): Blob {
  return new Blob([JSON.stringify(payload)], { type: 'application/problem+json' });
}

function makeScan(
  productId: number,
  name: string,
  price: number,
  restricted = false,
): ScanResultResponse {
  return {
    product: {
      productId,
      sku: `SKU-${productId}`,
      name,
      saleType: 'UNIT',
      unitOfMeasure: 'UNIT',
      priceGross: price,
      restricted,
      stockOnHand: 20,
    },
    quantity: 1,
  };
}

function makeQuoteResponse(overrides: Partial<CartQuoteResponse> = {}): CartQuoteResponse {
  return {
    subtotalGross: 2000,
    discountTotal: 0,
    totalGross: 2000,
    netTotal: 1681,
    taxTotal: 319,
    exemptTotal: 0,
    taxBreakdown: [{ taxCode: 'IVA', rate: 19, baseAmount: 1681, taxAmount: 319 }],
    roundingAdjustment: 0,
    totalPayable: 2000,
    lines: [
      {
        lineNumber: 1,
        productId: 10,
        name: 'Leche Entera',
        quantity: 2,
        unitPriceGross: 1000,
        lineSubtotalGross: 2000,
        promotionDiscount: 0,
        manualDiscount: 0,
        proratedDiscount: 0,
        lineTotalGross: 2000,
      },
    ],
    requiredOverrides: [],
    ...overrides,
  };
}

describe('PosCartStore', () => {
  let store: PosCartStore;
  let storeContext: StoreContextService;
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
    store = TestBed.inject(PosCartStore);
    storeContext = TestBed.inject(StoreContextService);
    storeContext.setStore(10);
    storeContext.setTerminal(3);
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

  it('agrupa productos idénticos al escanear, envía X-Store-Id/X-Terminal-Id y persiste en localStorage', async () => {
    const scan = makeScan(10, 'Leche Entera', 1000);

    const p1 = store.addFromScan(scan, '780111');
    const req1 = http!.expectOne('/api/v1/pos/cart/quote');
    expect(req1.request.headers.get('X-Store-Id')).toBe('10');
    expect(req1.request.headers.get('X-Terminal-Id')).toBe('3');
    req1.flush(
      jsonBlob(makeQuoteResponse({ subtotalGross: 1000, totalGross: 1000, totalPayable: 1000 })),
    );
    await p1;

    const p2 = store.addFromScan(scan, '780111');
    const req2 = http!.expectOne('/api/v1/pos/cart/quote');
    expect(req2.request.body.lines).toEqual([
      { productId: 10, quantity: 2, scannedCode: '780111' },
    ]);
    req2.flush(jsonBlob(makeQuoteResponse()));
    await p2;

    expect(store.lineCount()).toBe(1);
    expect(store.lines()[0]?.quantity).toBe(2);
    expect(store.totalPayable()).toBe(2000);

    const raw = localStorage.getItem(POS_CART_STORAGE_KEY);
    expect(raw).not.toBeNull();
    const persisted = JSON.parse(raw!);
    expect(persisted.lines).toHaveLength(1);
    expect(persisted.lines[0].quantity).toBe(2);
    expect(persisted.idempotencyKey).toBe(store.idempotencyKey());
  });

  it('mantiene líneas pesables con embeddedPrice separadas en el carrito', async () => {
    const weightScan1: ScanResultResponse = {
      product: {
        productId: 25,
        sku: '25001',
        name: 'Pan Marraqueta',
        saleType: 'WEIGHT',
        unitOfMeasure: 'KG',
        priceGross: 2000,
      },
      quantity: 0.5,
      embeddedPrice: 1000,
    };
    const weightScan2: ScanResultResponse = {
      ...weightScan1,
      quantity: 0.75,
      embeddedPrice: 1500,
    };

    const p1 = store.addFromScan(weightScan1, '2002500010001');
    http!
      .expectOne('/api/v1/pos/cart/quote')
      .flush(jsonBlob(makeQuoteResponse({ totalPayable: 1000 })));
    await p1;

    const p2 = store.addFromScan(weightScan2, '2002500015002');
    const req2 = http!.expectOne('/api/v1/pos/cart/quote');
    expect(req2.request.body.lines).toHaveLength(2);
    req2.flush(jsonBlob(makeQuoteResponse({ totalPayable: 2500 })));
    await p2;

    expect(store.lineCount()).toBe(2);
  });

  it('restaura el carrito desde localStorage tras recarga preservando la idempotencyKey', () => {
    localStorage.setItem(
      POS_CART_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        idempotencyKey: '11111111-1111-4111-8111-111111111111',
        lines: [
          {
            lineId: 'line-restored',
            productId: 5,
            sku: '5001',
            name: 'Cerveza Artesanal',
            saleType: 'UNIT',
            unitOfMeasure: 'UNIT',
            unitPriceGross: 2500,
            quantity: 1,
            restricted: true,
          },
        ],
        totalDiscount: null,
        ageConfirmed: false,
        customerId: null,
      }),
    );

    store.restoreFromStorage();

    expect(store.idempotencyKey()).toBe('11111111-1111-4111-8111-111111111111');
    expect(store.lineCount()).toBe(1);
    expect(store.requiresAgeConfirmation()).toBe(true);
    expect(store.canProceedToPayment()).toBe(false);

    store.confirmAge(true);
    expect(store.canProceedToPayment()).toBe(true);
  });

  it('revierte el cambio y desenvuelve ProblemDetail en Blob si /pos/cart/quote falla', async () => {
    const addPromise = store.addFromScan(makeScan(10, 'Leche', 1000));
    http!
      .expectOne('/api/v1/pos/cart/quote')
      .flush(jsonBlob(makeQuoteResponse({ totalPayable: 1000 })));
    await addPromise;

    const discountPromise = store.applyLineDiscount(0, { kind: 'PERCENT', value: 15 });
    const failedReq = http!.expectOne('/api/v1/pos/cart/quote');
    failedReq.flush(
      problemBlob({
        code: 'DISCOUNT_LIMIT_EXCEEDED',
        detail: 'El producto no admite descuento manual.',
      }),
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    const result = await discountPromise;
    expect(result).toBeNull();
    expect(store.lines()[0]?.manualDiscount).toBeUndefined();
    expect(store.quoteError()).toBe('El producto no admite descuento manual.');
  });

  it('registra overrides otorgados y expone el encabezado X-Override-Token separado por comas', async () => {
    const p1 = store.addFromScan(makeScan(10, 'Leche', 1000));
    http!.expectOne('/api/v1/pos/cart/quote').flush(
      jsonBlob(
        makeQuoteResponse({
          requiredOverrides: [
            { action: 'DISCOUNT_ITEM_OVER_LIMIT', context: { lineIndex: 0, percent: 25 } },
            { action: 'DISCOUNT_TOTAL_OVER_LIMIT', context: { percent: 15 } },
          ],
        }),
      ),
    );
    await p1;

    expect(store.pendingOverrides()).toHaveLength(2);
    expect(store.canProceedToPayment()).toBe(false);

    const [first, second] = store.requiredOverrides();
    store.recordGrantedOverride(first!, 'tok-item-1');
    store.recordGrantedOverride(second!, 'tok-total-2');

    expect(store.pendingOverrides()).toHaveLength(0);
    expect(store.overrideTokenHeader()).toBe('tok-item-1,tok-total-2');
    expect(store.canProceedToPayment()).toBe(true);
  });
});
