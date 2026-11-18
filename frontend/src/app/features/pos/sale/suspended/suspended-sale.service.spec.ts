import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../../../api/api-configuration';
import { errorInterceptor } from '../../../../core/http/error.interceptor';
import { storeContextInterceptor } from '../../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../../core/store-context/store-context.service';
import { PosCartStore } from '../cart/pos-cart.store';
import { extractPosErrorMessage } from '../cart/pos-http.utils';
import { SuspendedSaleService } from './suspended-sale.service';

function jsonBlob(payload: unknown, type = 'application/json'): Blob {
  return new Blob([JSON.stringify(payload)], { type });
}

describe('SuspendedSaleService', () => {
  let service: SuspendedSaleService;
  let http: HttpTestingController | undefined;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, storeContextInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });

    const storeContext = TestBed.inject(StoreContextService);
    storeContext.setStore(10);
    storeContext.setTerminal(2);

    service = TestBed.inject(SuspendedSaleService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    localStorage.clear();
    try {
      TestBed.inject(MatDialog).closeAll();
    } catch {
      // Sin diálogos abiertos
    }
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('lista y suspende ventas exponiendo mensajes ProblemDetail en Blob', async () => {
    const listPromise = firstValueFrom(service.list());
    const listReq = http!.expectOne('/api/v1/pos/suspended-sales');
    expect(listReq.request.method).toBe('GET');
    listReq.flush(
      jsonBlob([
        {
          id: 11,
          label: 'Cliente efectivo',
          createdAt: '2026-11-14T20:00:00Z',
          cart: { lines: [{ productId: 7, quantity: 2 }] },
        },
      ]),
    );
    const items = await listPromise;
    expect(items).toHaveLength(1);
    expect(items[0]?.label).toBe('Cliente efectivo');

    const failPromise = firstValueFrom(
      service.suspend({
        label: 'Nueva espera',
        cart: { lines: [{ productId: 7, quantity: 1 }] },
      }),
    ).catch((err: unknown) => err);

    const suspendReq = http!.expectOne('/api/v1/pos/suspended-sales');
    expect(suspendReq.request.method).toBe('POST');
    suspendReq.flush(
      jsonBlob(
        {
          code: 'SUSPENDED_LIMIT_REACHED',
          detail: 'Se alcanzó el máximo de 5 ventas suspendidas en la terminal.',
        },
        'application/problem+json',
      ),
      { status: 409, statusText: 'Conflict' },
    );

    const captured = await failPromise;
    expect(extractPosErrorMessage(captured, 'Error')).toContain('máximo de 5 ventas suspendidas');
  });

  it('retoma una venta suspendida, hidrata productos y recotiza en PosCartStore', async () => {
    const cartStore = TestBed.inject(PosCartStore);
    const resumePromise = firstValueFrom(service.resume(15));
    const resumeReq = http!.expectOne('/api/v1/pos/suspended-sales/15/resume');
    expect(resumeReq.request.method).toBe('POST');
    resumeReq.flush(
      jsonBlob({
        id: 15,
        label: 'Pan y bebida',
        customerId: 9,
        cart: {
          lines: [{ productId: 42, quantity: 2 }],
          totalDiscount: { kind: 'AMOUNT', value: 100 },
        },
      }),
    );

    const resumed = await resumePromise;
    const restorePromise = service.restoreIntoCartStore(resumed, cartStore);

    const prodReq = await vi.waitFor(() => http!.expectOne('/api/v1/products/42'));
    prodReq.flush(
      jsonBlob({
        id: 42,
        sku: 'BEB-42',
        name: 'Bebida 1.5L',
        saleType: 'UNIT',
        unitOfMeasure: 'UNIT',
        priceGross: 1500,
        restricted: false,
      }),
    );

    const quoteReq = await vi.waitFor(() => http!.expectOne('/api/v1/pos/cart/quote'));
    expect(quoteReq.request.body).toEqual({
      lines: [{ productId: 42, quantity: 2 }],
      totalDiscount: { kind: 'AMOUNT', value: 100 },
      customerId: 9,
    });
    quoteReq.flush(
      jsonBlob({
        subtotalGross: 3000,
        discountTotal: 100,
        totalGross: 2900,
        netTotal: 2437,
        taxTotal: 463,
        exemptTotal: 0,
        taxBreakdown: [],
        roundingAdjustment: 0,
        totalPayable: 2900,
        lines: [
          {
            productId: 42,
            name: 'Bebida 1.5L',
            quantity: 2,
            unitPriceGross: 1500,
            lineSubtotalGross: 3000,
            promotionDiscount: 0,
            manualDiscount: 0,
            proratedDiscount: 100,
            lineTotalGross: 2900,
          },
        ],
        requiredOverrides: [],
        requiresAgeConfirmation: false,
      }),
    );

    await restorePromise;
    expect(cartStore.lines()).toHaveLength(1);
    expect(cartStore.lines()[0]?.name).toBe('Bebida 1.5L');
    expect(cartStore.lines()[0]?.quantity).toBe(2);
    expect(cartStore.totalPayable()).toBe(2900);
    expect(cartStore.customerId()).toBe(9);
  });

  it('solicita override SUSPENDED_SALE_DELETE y reintenta DELETE con X-Override-Token', async () => {
    const deletePromise = firstValueFrom(service.delete(19));

    const firstDelete = http!.expectOne('/api/v1/pos/suspended-sales/19');
    expect(firstDelete.request.method).toBe('DELETE');
    firstDelete.flush(
      JSON.stringify({
        code: 'OVERRIDE_REQUIRED',
        detail: 'Solo un supervisor puede eliminar ventas suspendidas de otro cajero.',
        action: 'SUSPENDED_SALE_DELETE',
        context: { suspendedSaleId: 19 },
      }),
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-override-dialog')).not.toBeNull();
    });

    const userInput = document.querySelector<HTMLInputElement>(
      'pos-override-dialog input[formcontrolname="supervisorUsername"]',
    )!;
    const pinInput = document.querySelector<HTMLInputElement>(
      'pos-override-dialog input[formcontrolname="secret"]',
    )!;
    userInput.value = 'sup1';
    userInput.dispatchEvent(new Event('input'));
    pinInput.value = '1234';
    pinInput.dispatchEvent(new Event('input'));
    document.querySelector<HTMLButtonElement>('pos-override-dialog button[type="submit"]')!.click();

    http!.expectOne('/api/v1/auth/overrides').flush({ overrideToken: 'tok-susp-del' });

    await vi.waitFor(() => {
      const retryDelete = http!.expectOne('/api/v1/pos/suspended-sales/19');
      expect(retryDelete.request.method).toBe('DELETE');
      expect(retryDelete.request.headers.get('X-Override-Token')).toBe('tok-susp-del');
      retryDelete.flush('');
    });

    await expect(deletePromise).resolves.toBeUndefined();
  }, 20000);
});
