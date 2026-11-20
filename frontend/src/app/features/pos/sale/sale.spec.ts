import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { CartQuoteResponse } from '../../../api/models/cart-quote-response';
import { CustomerResponse } from '../../../api/models/customer-response';
import { Line } from '../../../api/models/line';
import { ScanResultResponse } from '../../../api/models/scan-result-response';
import { AuthService } from '../../../core/auth/auth.service';
import { buildTokenResponse } from '../../../core/auth/auth.testing';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { storeContextInterceptor } from '../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { POS_CART_STORAGE_KEY } from './cart/pos-cart.models';
import { Sale } from './sale';
import { BarcodeScannerDetector } from './search/barcode-scanner-detector';

function jsonBlob(payload: unknown): Blob {
  return new Blob([JSON.stringify(payload)], { type: 'application/json' });
}

function makeScan(
  productId: number,
  name: string,
  priceGross: number,
  restricted = false,
): ScanResultResponse {
  return {
    product: {
      productId,
      sku: `SKU-${productId}`,
      name,
      saleType: 'UNIT',
      unitOfMeasure: 'UNIT',
      priceGross,
      restricted,
      stockOnHand: 30,
    },
    quantity: 1,
  };
}

function makeQuote(lines: Line[], overrides: Partial<CartQuoteResponse> = {}): CartQuoteResponse {
  const subtotal = lines.reduce((sum, l) => sum + (l.lineSubtotalGross ?? 0), 0);
  const total = lines.reduce((sum, l) => sum + (l.lineTotalGross ?? 0), 0);
  const discount = Math.max(0, subtotal - total);
  const net = Math.round(total / 1.19);
  const tax = total - net;
  return {
    subtotalGross: subtotal,
    discountTotal: discount,
    totalGross: total,
    netTotal: net,
    taxTotal: tax,
    exemptTotal: 0,
    taxBreakdown: [
      {
        taxCode: 'IVA',
        rate: 19,
        baseAmount: net,
        taxAmount: tax,
      },
    ],
    roundingAdjustment: 0,
    totalPayable: total,
    lines,
    requiredOverrides: [],
    requiresAgeConfirmation: lines.some((l) => l.restricted),
    ...overrides,
  };
}

describe('Sale (POS Parte 1)', () => {
  let fixture: ComponentFixture<Sale>;
  let http: HttpTestingController | undefined;
  let clock = 5000;

  beforeEach(async () => {
    localStorage.clear();
    clock = 5000;

    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideRouter([]),
        provideHttpClient(withInterceptors([errorInterceptor, storeContextInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });

    http = TestBed.inject(HttpTestingController);
    TestBed.inject(BarcodeScannerDetector).configure({ now: () => clock });

    TestBed.inject(AuthService).login({ username: 'cajero1', password: 'secret' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(
      buildTokenResponse({
        permissions: ['sales:pos:operate', 'sales:discount:apply'],
        user: { limits: { DISCOUNT_ITEM_MAX_PCT: 10 } },
      }),
    );

    const storeContext = TestBed.inject(StoreContextService);
    storeContext.setStore(10);
    storeContext.setTerminal(2);

    fixture = TestBed.createComponent(Sale);
    fixture.detectChanges();

    const sessionReq = http.expectOne('/api/v1/cash-sessions/current');
    expect(sessionReq.request.headers.get('X-Store-Id')).toBe('10');
    expect(sessionReq.request.headers.get('X-Terminal-Id')).toBe('2');
    sessionReq.flush(jsonBlob({ id: 77, number: 'CJ-02-001', status: 'OPEN' }));

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="cash-session-open"]'),
      ).not.toBeNull();
    });
  });

  afterEach(() => {
    document
      .querySelectorAll('iframe[data-testid="ticket-print-iframe"]')
      .forEach((el) => el.remove());
    localStorage.clear();
    try {
      TestBed.inject(MatDialog).closeAll();
    } catch {
      // Ignora si no hay diálogos
    }
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  function dispatchBurst(code: string): void {
    for (const ch of code) {
      clock += 6;
      document.dispatchEvent(new KeyboardEvent('keydown', { key: ch, cancelable: true }));
    }
    clock += 6;
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
  }

  it('renderiza el título exacto "Venta" y muestra la sesión de caja abierta', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toBe('Venta');
    expect(el.querySelector('[data-testid="cash-session-open"]')?.textContent).toContain(
      'CJ-02-001',
    );
  });

  it('procesa una ráfaga de lector (< 30 ms), agrega el producto, cotiza y mantiene foco en el escáner', async () => {
    dispatchBurst('780123456');

    const resolveReq = http!.expectOne((r) => r.url === '/api/v1/pos/products/resolve');
    expect(resolveReq.request.method).toBe('GET');
    expect(resolveReq.request.params.get('code')).toBe('780123456');
    resolveReq.flush(jsonBlob(makeScan(10, 'Pan Hallulla', 1500)));

    await vi.waitFor(() => {
      const quoteReq = http!.match('/api/v1/pos/cart/quote');
      expect(quoteReq).toHaveLength(1);
      quoteReq[0]!.flush(
        jsonBlob(
          makeQuote([
            {
              lineNumber: 1,
              productId: 10,
              name: 'Pan Hallulla',
              quantity: 1,
              unitPriceGross: 1500,
              lineSubtotalGross: 1500,
              promotionDiscount: 0,
              manualDiscount: 0,
              proratedDiscount: 0,
              lineTotalGross: 1500,
            },
          ]),
        ),
      );
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelectorAll('.cart-row')).toHaveLength(1);
      expect(el.querySelector('[data-testid="total-payable"]')?.textContent).toContain('$1.500');
      expect(document.activeElement).toBe(el.querySelector('[data-pos-scan-input]'));
    });
  });

  it('edita cantidad con F4, confirma edad +18 con F7 y quita la línea con Supr', async () => {
    dispatchBurst('780999');
    const resolveReq = http!.expectOne((r) => r.url === '/api/v1/pos/products/resolve');
    expect(resolveReq.request.method).toBe('GET');
    expect(resolveReq.request.params.get('code')).toBe('780999');
    resolveReq.flush(jsonBlob(makeScan(55, 'Vino Tinto', 5000, true)));

    await vi.waitFor(() => {
      TestBed.tick();
      const req = http!.expectOne('/api/v1/pos/cart/quote');
      req.flush(
        jsonBlob(
          makeQuote([
            {
              lineNumber: 1,
              productId: 55,
              name: 'Vino Tinto',
              quantity: 1,
              unitPriceGross: 5000,
              lineSubtotalGross: 5000,
              promotionDiscount: 0,
              manualDiscount: 0,
              proratedDiscount: 0,
              lineTotalGross: 5000,
              restricted: true,
            },
          ]),
        ),
      );
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('[data-testid="age-confirmation-card"]')).not.toBeNull();
      expect((el.querySelector('[data-testid="pay-btn"]') as HTMLButtonElement).disabled).toBe(
        true,
      );
    });

    const el = fixture.nativeElement as HTMLElement;

    // F7 confirma mayoría de edad
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F7', cancelable: true }));
    fixture.detectChanges();
    expect((el.querySelector('[data-testid="pay-btn"]') as HTMLButtonElement).disabled).toBe(false);

    // F4 abre diálogo de cantidad
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F4', cancelable: true }));
    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-quantity-dialog')).not.toBeNull();
    });
    const qtyInput = document.querySelector<HTMLInputElement>(
      'pos-quantity-dialog input[type="number"]',
    )!;
    qtyInput.value = '3';
    qtyInput.dispatchEvent(new Event('input'));
    document.querySelector<HTMLButtonElement>('pos-quantity-dialog button[type="submit"]')!.click();

    await vi.waitFor(() => {
      TestBed.tick();
      const req = http!.expectOne('/api/v1/pos/cart/quote');
      expect(req.request.body.lines[0].quantity).toBe(3);
      req.flush(
        jsonBlob(
          makeQuote([
            {
              lineNumber: 1,
              productId: 55,
              name: 'Vino Tinto',
              quantity: 3,
              unitPriceGross: 5000,
              lineSubtotalGross: 15000,
              promotionDiscount: 0,
              manualDiscount: 0,
              proratedDiscount: 0,
              lineTotalGross: 15000,
              restricted: true,
            },
          ]),
        ),
      );
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(el.querySelector('[data-testid="total-payable"]')?.textContent).toContain('$15.000');
    });

    // Supr quita la línea seleccionada (cuando el input de escaneo está vacío)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', cancelable: true }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="empty-cart-state"]')).not.toBeNull();
  });

  it('aplica descuento con F6 y dispara autorización de supervisor cuando quote devuelve requiredOverrides', async () => {
    dispatchBurst('780555');
    const resolveReq = http!.expectOne((r) => r.url === '/api/v1/pos/products/resolve');
    expect(resolveReq.request.method).toBe('GET');
    expect(resolveReq.request.params.get('code')).toBe('780555');
    resolveReq.flush(jsonBlob(makeScan(20, 'Aceite 1L', 2000)));

    await vi.waitFor(() => {
      TestBed.tick();
      const req = http!.expectOne('/api/v1/pos/cart/quote');
      req.flush(
        jsonBlob(
          makeQuote([
            {
              lineNumber: 1,
              productId: 20,
              name: 'Aceite 1L',
              quantity: 1,
              unitPriceGross: 2000,
              lineSubtotalGross: 2000,
              promotionDiscount: 0,
              manualDiscount: 0,
              proratedDiscount: 0,
              lineTotalGross: 2000,
            },
          ]),
        ),
      );
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).querySelectorAll('.cart-row')).toHaveLength(1);
    });

    // F6 abre el diálogo de descuento
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F6', cancelable: true }));
    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-discount-dialog')).not.toBeNull();
    });

    const discInput = document.querySelector<HTMLInputElement>(
      'pos-discount-dialog input[formcontrolname="value"]',
    )!;
    discInput.value = '25';
    discInput.dispatchEvent(new Event('input'));
    document.querySelector<HTMLButtonElement>('pos-discount-dialog button[type="submit"]')!.click();

    await vi.waitFor(() => {
      TestBed.tick();
      const req = http!.expectOne('/api/v1/pos/cart/quote');
      req.flush(
        jsonBlob(
          makeQuote(
            [
              {
                lineNumber: 1,
                productId: 20,
                name: 'Aceite 1L',
                quantity: 1,
                unitPriceGross: 2000,
                lineSubtotalGross: 2000,
                promotionDiscount: 0,
                manualDiscount: 500,
                proratedDiscount: 0,
                lineTotalGross: 1500,
              },
            ],
            {
              requiredOverrides: [
                {
                  action: 'DISCOUNT_ITEM_OVER_LIMIT',
                  context: { lineIndex: 0, percent: 25, cartHash: 'cart-hash-1' },
                },
              ],
            },
          ),
        ),
      );
    });

    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-override-dialog')).not.toBeNull();
    });
    const supUser = document.querySelector<HTMLInputElement>(
      'pos-override-dialog input[formcontrolname="supervisorUsername"]',
    )!;
    const supPin = document.querySelector<HTMLInputElement>(
      'pos-override-dialog input[formcontrolname="secret"]',
    )!;
    supUser.value = 'jefe';
    supUser.dispatchEvent(new Event('input'));
    supPin.value = '1234';
    supPin.dispatchEvent(new Event('input'));
    document.querySelector<HTMLButtonElement>('pos-override-dialog button[type="submit"]')!.click();

    http!.expectOne('/api/v1/auth/overrides').flush({ overrideToken: 'tok-sup-25' });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="pending-overrides-card"]',
        ),
      ).toBeNull();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="total-payable"]')
          ?.textContent,
      ).toContain('$1.500');
    });
  });

  // --- Parte 2 (T47): cobro mixto, suspensión/retomado, ticket 80 mm ---

  async function scanSingleItem(
    productId: number,
    code: string,
    name: string,
    priceGross: number,
  ): Promise<void> {
    dispatchBurst(code);

    const resolveReq = http!.expectOne((r) => r.url === '/api/v1/pos/products/resolve');
    resolveReq.flush(jsonBlob(makeScan(productId, name, priceGross)));

    await vi.waitFor(() => {
      const quoteReq = http!.expectOne('/api/v1/pos/cart/quote');
      quoteReq.flush(
        jsonBlob(
          makeQuote([
            {
              productId,
              name,
              quantity: 1,
              unitPriceGross: priceGross,
              lineSubtotalGross: priceGross,
              promotionDiscount: 0,
              manualDiscount: 0,
              proratedDiscount: 0,
              lineTotalGross: priceGross,
            },
          ]),
        ),
      );
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).querySelectorAll('.cart-row').length).toBe(1);
    });
  }

  it('completa un cobro mixto (F10 + tarjeta + efectivo redondeado), registra la venta y abre el ticket 80 mm', async () => {
    await scanSingleItem(31, '78000031', 'Café Grano 500g', 3005);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F10', cancelable: true }));

    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-payment-dialog')).not.toBeNull();
    });

    const amountInput = document.querySelector<HTMLInputElement>(
      'pos-payment-dialog [data-testid="electronic-amount-input"]',
    )!;
    amountInput.value = '1010';
    amountInput.dispatchEvent(new Event('input'));
    TestBed.tick();

    document
      .querySelector<HTMLButtonElement>(
        'pos-payment-dialog [data-testid="add-electronic-payment-btn"]',
      )!
      .click();

    const cardAuthReq = http!.expectOne('/api/v1/pos/card-payments');
    expect(cardAuthReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(cardAuthReq.request.body).toEqual({ method: 'DEBIT', amount: 1010 });
    cardAuthReq.flush(
      jsonBlob({
        id: 410,
        method: 'DEBIT',
        amount: 1010,
        status: 'AUTHORIZED',
        authorizationCode: 'AUT410',
        cardBrand: 'MASTERCARD',
        last4: '9876',
      }),
    );

    await vi.waitFor(() => {
      TestBed.tick();
      expect(
        document.querySelector('pos-payment-dialog [data-testid="payment-rounding-row"]')
          ?.textContent,
      ).toContain('-$5');
      expect(
        document.querySelector('pos-payment-dialog [data-testid="cash-payable-pill"]')?.textContent,
      ).toContain('$1.990');
    });

    const cashInput = document.querySelector<HTMLInputElement>(
      'pos-payment-dialog [data-testid="cash-tendered-input"]',
    )!;
    cashInput.value = '5000';
    cashInput.dispatchEvent(new Event('input'));
    TestBed.tick();

    await vi.waitFor(() => {
      TestBed.tick();
      expect(
        document.querySelector('pos-payment-dialog [data-testid="payment-change"]')?.textContent,
      ).toContain('$3.010');
    });

    document
      .querySelector<HTMLButtonElement>('pos-payment-dialog [data-testid="confirm-payment-btn"]')!
      .click();

    const createSaleReq = http!.expectOne('/api/v1/sales');
    expect(createSaleReq.request.method).toBe('POST');
    expect(createSaleReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(createSaleReq.request.body.payments).toEqual([
      { method: 'DEBIT', amount: 1010, cardAuthorizationId: 410 },
      { method: 'CASH', amount: 1990, tenderedAmount: 5000 },
    ]);
    createSaleReq.flush(jsonBlob({ id: 800, number: 'V-00800', totalPayable: 3000 }));

    await vi.waitFor(() => {
      TestBed.tick();
      fixture.detectChanges();
      expect(document.querySelector('pos-payment-dialog')).toBeNull();
      expect(document.querySelector('pos-ticket-dialog')).not.toBeNull();
    });

    const ticketReq = http!.expectOne('/api/v1/sales/800/ticket');
    ticketReq.flush(
      jsonBlob({
        number: 'V-00800',
        paperWidthMm: 80,
        rounding: -5,
        change: 3010,
        lines: [
          { sku: 'SKU-31', name: 'Café Grano 500g', quantity: 1, unitPrice: 3005, total: 3005 },
        ],
        totals: { subtotalGross: 3005, totalGross: 3005, totalPayable: 3000 },
        payments: [
          { method: 'DEBIT', amount: 1010, last4: '9876', authorizationCode: 'AUT410' },
          { method: 'CASH', amount: 1990, tenderedAmount: 5000, changeAmount: 3010 },
        ],
      }),
    );

    // El ticket consulta el documento tributario de la venta: 204 = pendiente de emisión.
    http!.expectOne('/api/v1/sales/800/tax-document').flush(null, {
      status: 204,
      statusText: 'No Content',
    });

    await vi.waitFor(() => {
      TestBed.tick();
      fixture.detectChanges();
      expect(
        document.querySelector('pos-ticket-dialog [data-testid="ticket-number"]')?.textContent,
      ).toContain('V-00800');
      expect(
        document.querySelector('pos-ticket-dialog [data-testid="tax-document-pending"]'),
      ).not.toBeNull();
      expect((fixture.nativeElement as HTMLElement).querySelectorAll('.cart-row').length).toBe(0);
      expect(
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="last-sale-ticket-btn"]',
        ),
      ).not.toBeNull();
    });
  }, 20000);

  it('anula autorizaciones de tarjeta al cancelar cobro y mantiene el carrito intacto', async () => {
    await scanSingleItem(32, '78000032', 'Té Negro 20u', 1500);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F10', cancelable: true }));
    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-payment-dialog')).not.toBeNull();
    });

    const amountInput = document.querySelector<HTMLInputElement>(
      'pos-payment-dialog [data-testid="electronic-amount-input"]',
    )!;
    amountInput.value = '1500';
    amountInput.dispatchEvent(new Event('input'));
    TestBed.tick();

    document
      .querySelector<HTMLButtonElement>(
        'pos-payment-dialog [data-testid="add-electronic-payment-btn"]',
      )!
      .click();

    http!.expectOne('/api/v1/pos/card-payments').flush(
      jsonBlob({
        id: 411,
        method: 'DEBIT',
        amount: 1500,
        status: 'AUTHORIZED',
        authorizationCode: 'AUT411',
      }),
    );

    await vi.waitFor(() => {
      TestBed.tick();
      expect(
        document.querySelector('pos-payment-dialog [data-testid="added-card-payment"]'),
      ).not.toBeNull();
    });

    document
      .querySelector<HTMLButtonElement>('pos-payment-dialog [data-testid="cancel-payment-btn"]')!
      .click();

    const voidReq = http!.expectOne('/api/v1/pos/card-payments/411/void');
    expect(voidReq.request.method).toBe('POST');
    voidReq.flush(jsonBlob({ id: 411, status: 'VOIDED' }));

    await vi.waitFor(() => {
      TestBed.tick();
      fixture.detectChanges();
      expect(document.querySelector('pos-payment-dialog')).toBeNull();
      expect((fixture.nativeElement as HTMLElement).querySelectorAll('.cart-row').length).toBe(1);
    });
  }, 20000);

  it('suspende la venta actual (F8) y permite retomarla (F9) restaurando PosCartStore', async () => {
    await scanSingleItem(33, '78000033', 'Leche Entera 1L', 1250);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F8', cancelable: true }));
    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-suspend-dialog')).not.toBeNull();
    });

    const labelInput = document.querySelector<HTMLInputElement>(
      'pos-suspend-dialog [data-testid="suspend-label-input"]',
    )!;
    labelInput.value = 'Cliente en espera';
    labelInput.dispatchEvent(new Event('input'));
    TestBed.tick();

    document
      .querySelector<HTMLButtonElement>('pos-suspend-dialog [data-testid="confirm-suspend-btn"]')!
      .click();

    const suspendReq = http!.expectOne('/api/v1/pos/suspended-sales');
    expect(suspendReq.request.method).toBe('POST');
    expect(suspendReq.request.body.label).toBe('Cliente en espera');
    suspendReq.flush(
      jsonBlob({
        id: 71,
        label: 'Cliente en espera',
        cart: { lines: [{ productId: 33, quantity: 1 }] },
      }),
    );

    await vi.waitFor(() => {
      TestBed.tick();
      fixture.detectChanges();
      expect(document.querySelector('pos-suspend-dialog')).toBeNull();
      expect((fixture.nativeElement as HTMLElement).querySelectorAll('.cart-row').length).toBe(0);
    });

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F9', cancelable: true }));
    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-suspended-list-dialog')).not.toBeNull();
    });

    http!.expectOne('/api/v1/pos/suspended-sales').flush(
      jsonBlob([
        {
          id: 71,
          label: 'Cliente en espera',
          createdAt: '2026-11-14T21:15:00Z',
          cart: { lines: [{ productId: 33, quantity: 1 }] },
        },
      ]),
    );

    await vi.waitFor(() => {
      TestBed.tick();
      expect(
        document.querySelector('pos-suspended-list-dialog [data-testid="resume-suspended-btn"]'),
      ).not.toBeNull();
    });

    document
      .querySelector<HTMLButtonElement>(
        'pos-suspended-list-dialog [data-testid="resume-suspended-btn"]',
      )!
      .click();

    http!.expectOne('/api/v1/pos/suspended-sales/71/resume').flush(
      jsonBlob({
        id: 71,
        label: 'Cliente en espera',
        cart: { lines: [{ productId: 33, quantity: 1 }] },
      }),
    );

    await vi.waitFor(() => {
      http!.expectOne('/api/v1/products/33').flush(
        jsonBlob({
          id: 33,
          sku: 'SKU-33',
          name: 'Leche Entera 1L',
          saleType: 'UNIT',
          unitOfMeasure: 'UNIT',
          priceGross: 1250,
          restricted: false,
        }),
      );
    });

    await vi.waitFor(() => {
      http!.expectOne('/api/v1/pos/cart/quote').flush(
        jsonBlob(
          makeQuote([
            {
              productId: 33,
              name: 'Leche Entera 1L',
              quantity: 1,
              unitPriceGross: 1250,
              lineSubtotalGross: 1250,
              promotionDiscount: 0,
              manualDiscount: 0,
              proratedDiscount: 0,
              lineTotalGross: 1250,
            },
          ]),
        ),
      );
    });

    await vi.waitFor(() => {
      TestBed.tick();
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).querySelectorAll('.cart-row').length).toBe(1);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Leche Entera 1L');
    });
  }, 20000);

  it('permite abrir el cobro (F10) y enviar ageConfirmed=true tras confirmar mayoría de edad (+18 con F7)', async () => {
    dispatchBurst('780777');

    const resolveReq = http!.expectOne((r) => r.url === '/api/v1/pos/products/resolve');
    resolveReq.flush(jsonBlob(makeScan(77, 'Vino Reserva 750ml', 4500, true)));

    await vi.waitFor(() => {
      const quoteReq = http!.expectOne('/api/v1/pos/cart/quote');
      quoteReq.flush(
        jsonBlob(
          makeQuote([
            {
              productId: 77,
              name: 'Vino Reserva 750ml',
              quantity: 1,
              unitPriceGross: 4500,
              lineSubtotalGross: 4500,
              promotionDiscount: 0,
              manualDiscount: 0,
              proratedDiscount: 0,
              lineTotalGross: 4500,
              restricted: true,
            },
          ]),
        ),
      );
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).querySelectorAll('.cart-row').length).toBe(1);
    });

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F10', cancelable: true }));
    TestBed.tick();
    fixture.detectChanges();
    expect(document.querySelector('pos-payment-dialog')).toBeNull();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F7', cancelable: true }));
    fixture.detectChanges();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F10', cancelable: true }));
    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-payment-dialog')).not.toBeNull();
    });

    const cashInput = document.querySelector<HTMLInputElement>(
      'pos-payment-dialog [data-testid="cash-tendered-input"]',
    )!;
    cashInput.value = '5000';
    cashInput.dispatchEvent(new Event('input'));
    TestBed.tick();

    document
      .querySelector<HTMLButtonElement>('pos-payment-dialog [data-testid="confirm-payment-btn"]')!
      .click();

    const createSaleReq = http!.expectOne('/api/v1/sales');
    expect(createSaleReq.request.body.ageConfirmed).toBe(true);
    createSaleReq.flush(jsonBlob({ id: 810, number: 'V-00810', totalPayable: 4500 }));

    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-ticket-dialog')).not.toBeNull();
    });

    http!.expectOne('/api/v1/sales/810/ticket').flush(jsonBlob({ number: 'V-00810' }));
    http!.expectOne('/api/v1/sales/810/tax-document').flush(null, {
      status: 204,
      statusText: 'No Content',
    });
  }, 20000);

  it('pasa totalGross (no totalPayable) al diálogo de cobro cuando quote incluye roundingAdjustment !== 0', async () => {
    dispatchBurst('780888');

    const resolveReq = http!.expectOne((r) => r.url === '/api/v1/pos/products/resolve');
    resolveReq.flush(jsonBlob(makeScan(88, 'Aceite Especial 1L', 1995)));

    await vi.waitFor(() => {
      const quoteReq = http!.expectOne('/api/v1/pos/cart/quote');
      quoteReq.flush(
        jsonBlob(
          makeQuote(
            [
              {
                productId: 88,
                name: 'Aceite Especial 1L',
                quantity: 1,
                unitPriceGross: 1995,
                lineSubtotalGross: 1995,
                promotionDiscount: 0,
                manualDiscount: 0,
                proratedDiscount: 0,
                lineTotalGross: 1995,
              },
            ],
            {
              roundingAdjustment: -5,
              totalPayable: 1990,
            },
          ),
        ),
      );
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).querySelectorAll('.cart-row').length).toBe(1);
    });

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F10', cancelable: true }));
    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-payment-dialog')).not.toBeNull();
    });

    const dialogEl = document.querySelector<HTMLElement>('pos-payment-dialog')!;
    expect(dialogEl.querySelector('.payment-summary')?.textContent).toContain('$1.995');
    expect(dialogEl.querySelector('[data-testid="payment-rounding-row"]')?.textContent).toContain(
      '-$5',
    );
    expect(dialogEl.querySelector('[data-testid="payment-total-payable"]')?.textContent).toContain(
      '$1.990',
    );
    expect(dialogEl.querySelector('[data-testid="cash-payable-pill"]')?.textContent).toContain(
      '$1.990',
    );

    dialogEl.querySelector<HTMLButtonElement>('[data-testid="cancel-payment-btn"]')!.click();
    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-payment-dialog')).toBeNull();
    });
  }, 20000);
});

describe('Sale: cliente de la venta (cableado T77)', () => {
  let fixture: ComponentFixture<Sale>;
  let http: HttpTestingController | undefined;

  /** Cliente de ejemplo con fiado habilitado (como el devuelto por `GET /customers/{id}`). */
  function creditCustomer(id: number, name = 'María González'): CustomerResponse {
    return {
      id,
      rut: '12345678-5',
      name,
      email: 'maria@correo.cl',
      creditEnabled: true,
      creditLimit: 50000,
      creditBalance: 45000,
      availableCredit: 5000,
      active: true,
      version: 1,
    };
  }

  /** Línea cotizada que corresponde al carrito persistido de ejemplo (Leche Entera 1L). */
  function lecheLine(): Line {
    return {
      lineNumber: 1,
      productId: 33,
      name: 'Leche Entera 1L',
      quantity: 1,
      unitPriceGross: 1250,
      lineSubtotalGross: 1250,
      promotionDiscount: 0,
      manualDiscount: 0,
      proratedDiscount: 0,
      lineTotalGross: 1250,
    };
  }

  /** Persiste en `localStorage` un carrito con la línea de ejemplo y el cliente indicado. */
  function seedPersistedCart(customerId: number | null): void {
    localStorage.setItem(
      POS_CART_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        idempotencyKey: 'idem-customer-cart',
        lines: [
          {
            lineId: 'line-customer-1',
            productId: 33,
            sku: 'SKU-33',
            name: 'Leche Entera 1L',
            saleType: 'UNIT',
            unitOfMeasure: 'UNIT',
            unitPriceGross: 1250,
            quantity: 1,
            restricted: false,
          },
        ],
        totalDiscount: null,
        ageConfirmed: false,
        customerId,
      }),
    );
  }

  /** Crea la pantalla de venta con el carrito persistido sembrado (recarga de página). */
  function createSaleWithPersistedCart(customerId: number | null): void {
    localStorage.clear();
    seedPersistedCart(customerId);
    fixture = TestBed.createComponent(Sale);
    fixture.detectChanges();
  }

  beforeEach(() => {
    localStorage.clear();

    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideRouter([]),
        provideHttpClient(withInterceptors([errorInterceptor, storeContextInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);

    TestBed.inject(AuthService).login({ username: 'cajero1', password: 'secret' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(
      buildTokenResponse({
        permissions: ['sales:pos:operate', 'sales:discount:apply'],
        user: { limits: { DISCOUNT_ITEM_MAX_PCT: 10 } },
      }),
    );

    const storeContext = TestBed.inject(StoreContextService);
    storeContext.setStore(10);
    storeContext.setTerminal(2);
  });

  afterEach(() => {
    document
      .querySelectorAll('iframe[data-testid="ticket-print-iframe"]')
      .forEach((el) => el.remove());
    localStorage.clear();
    try {
      TestBed.inject(MatDialog).closeAll();
    } catch {
      // Ignora si no hay diálogos
    }
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('restaura la ficha del cliente de un carrito persistido y cotiza con customerId', async () => {
    createSaleWithPersistedCart(7);

    const sessionReq = http!.expectOne('/api/v1/cash-sessions/current');
    sessionReq.flush(jsonBlob({ id: 77, number: 'CJ-02-001', status: 'OPEN' }));

    // El carrito traía customerId 7: la pantalla resuelve la ficha vía GET /customers/7
    // (la petición nace del efecto de sincronización durante la primera detección).
    await vi.waitFor(() => {
      const customerReq = http!.expectOne('/api/v1/customers/7');
      expect(customerReq.request.method).toBe('GET');
      customerReq.flush(jsonBlob(creditCustomer(7)));
    });

    // La recotización del carrito restaurado incluye al cliente asociado.
    await vi.waitFor(() => {
      const quoteReq = http!.expectOne('/api/v1/pos/cart/quote');
      expect(quoteReq.request.body.customerId).toBe(7);
      quoteReq.flush(jsonBlob(makeQuote([lecheLine()])));
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      const summary = el.querySelector<HTMLElement>('[data-testid="customer-summary"]');
      expect(summary).not.toBeNull();
      expect(summary?.textContent).toContain('María González');
      expect(summary?.textContent).toContain('Fiado habilitado');
      // El carrito restaurado sigue visible junto a la ficha del cliente.
      expect(el.querySelectorAll('.cart-row')).toHaveLength(1);
    });
  }, 20000);

  it('propaga el cliente elegido en el selector al store y lo quita con «Quitar cliente»', async () => {
    createSaleWithPersistedCart(null);

    const sessionReq = http!.expectOne('/api/v1/cash-sessions/current');
    sessionReq.flush(jsonBlob({ id: 77, number: 'CJ-02-001', status: 'OPEN' }));

    await vi.waitFor(() => {
      const quoteReq = http!.expectOne('/api/v1/pos/cart/quote');
      expect(quoteReq.request.body.customerId).toBeUndefined();
      quoteReq.flush(jsonBlob(makeQuote([lecheLine()])));
    });

    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('[data-pos-customer-input]')!;
    expect(input).not.toBeNull();

    // Búsqueda incremental por nombre (mismo servicio del selector).
    input.value = 'fernández';
    input.dispatchEvent(new Event('input'));

    await vi.waitFor(() => {
      const searchReq = http!.expectOne((r) => r.url === '/api/v1/customers');
      expect(searchReq.request.method).toBe('GET');
      expect(searchReq.request.params.get('q')).toBe('fernández');
      searchReq.flush(
        jsonBlob({
          content: [creditCustomer(8, 'María Fernández')],
          page: 0,
          size: 20,
          sort: ['name,asc'],
          totalElements: 1,
          totalPages: 1,
        }),
      );
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(el.querySelectorAll('.result-row')).toHaveLength(1);
    });

    // Enter selecciona el resultado resaltado y asocia al cliente en el store.
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));

    await vi.waitFor(() => {
      const quoteReq = http!.expectOne('/api/v1/pos/cart/quote');
      expect(quoteReq.request.body.customerId).toBe(8);
      quoteReq.flush(jsonBlob(makeQuote([lecheLine()])));
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const summary = el.querySelector<HTMLElement>('[data-testid="customer-summary"]');
      expect(summary?.textContent).toContain('María Fernández');
    });

    // La ficha ya estaba resuelta por el selector: el efecto de sincronización no vuelve
    // a consultar al backend por el cliente recién asociado.
    expect(http!.match((r) => r.url === '/api/v1/customers/8')).toHaveLength(0);

    // «Quitar cliente» vuelve a anónima la venta y recotifica sin customerId.
    el.querySelector<HTMLButtonElement>('[data-testid="customer-clear-btn"]')!.click();

    await vi.waitFor(() => {
      const quoteReq = http!.expectOne('/api/v1/pos/cart/quote');
      expect(quoteReq.request.body.customerId).toBeUndefined();
      quoteReq.flush(jsonBlob(makeQuote([lecheLine()])));
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(el.querySelector('[data-testid="customer-summary"]')).toBeNull();
      expect(el.querySelector<HTMLInputElement>('[data-pos-customer-input]')).not.toBeNull();
    });
  }, 20000);
});
