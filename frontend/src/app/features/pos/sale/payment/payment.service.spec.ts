import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../../../api/api-configuration';
import { errorInterceptor } from '../../../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../../../core/http/idempotency.interceptor';
import { storeContextInterceptor } from '../../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../../core/store-context/store-context.service';
import { PosPaymentDialogData } from './payment.models';
import { PaymentService } from './payment.service';
import { PosPaymentDialog } from './pos-payment-dialog';

function jsonBlob(payload: unknown, type = 'application/json'): Blob {
  return new Blob([JSON.stringify(payload)], { type });
}

describe('PaymentService y PosPaymentDialog', () => {
  let service: PaymentService;
  let http: HttpTestingController | undefined;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(
          withInterceptors([errorInterceptor, idempotencyInterceptor, storeContextInterceptor]),
        ),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });

    const storeContext = TestBed.inject(StoreContextService);
    storeContext.setStore(10);
    storeContext.setTerminal(2);

    service = TestBed.inject(PaymentService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
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

  describe('Redondeo legal chileno (Ley 20.956) y vuelto', () => {
    it('redondea $1.995 hacia abajo a $1.990 (ajuste -5) y $1.996 hacia arriba a $2.000 (ajuste +4)', () => {
      expect(service.calculateCashRoundingAdjustment(1995)).toBe(-5);
      expect(service.calculateCashRoundingAdjustment(1996)).toBe(4);
      expect(service.calculateCashRoundingAdjustment(1990)).toBe(0);
      expect(service.calculateCashRoundingAdjustment(1991)).toBe(-1);
      expect(service.calculateCashRoundingAdjustment(1999)).toBe(1);

      const down = service.calculateCashBreakdown(1995, 0, 2000);
      expect(down.roundingAdjustment).toBe(-5);
      expect(down.cashPayable).toBe(1990);
      expect(down.totalPayable).toBe(1990);
      expect(down.changeAmount).toBe(10);
      expect(down.shortfall).toBe(0);

      const up = service.calculateCashBreakdown(1996, 0, 2000);
      expect(up.roundingAdjustment).toBe(4);
      expect(up.cashPayable).toBe(2000);
      expect(up.totalPayable).toBe(2000);
      expect(up.changeAmount).toBe(0);
      expect(up.shortfall).toBe(0);
    });

    it('no aplica redondeo cuando la regla está deshabilitada y calcula falta de efectivo', () => {
      const disabled = service.calculateCashBreakdown(1995, 0, 1500, false);
      expect(disabled.roundingAdjustment).toBe(0);
      expect(disabled.cashPayable).toBe(1995);
      expect(disabled.changeAmount).toBe(0);
      expect(disabled.shortfall).toBe(495);
    });

    it('aplica redondeo únicamente sobre la porción en efectivo en un pago mixto', () => {
      const mixed = service.calculateCashBreakdown(3005, 1010, 5000);
      expect(mixed.nonCashTotal).toBe(1010);
      expect(mixed.cashDue).toBe(1995);
      expect(mixed.roundingAdjustment).toBe(-5);
      expect(mixed.cashPayable).toBe(1990);
      expect(mixed.totalPayable).toBe(3000);
      expect(mixed.changeAmount).toBe(3010);
      expect(mixed.overpaidNonCash).toBe(0);
    });

    it('detecta sobrepago con medios electrónicos cuando supera el total bruto', () => {
      const over = service.calculateCashBreakdown(2000, 2500, null);
      expect(over.cashDue).toBe(0);
      expect(over.cashPayable).toBe(0);
      expect(over.overpaidNonCash).toBe(500);
    });
  });

  describe('Autorización/anulación de tarjeta y venta idempotente con overrides L-05', () => {
    it('autoriza y anula tarjeta enviando Idempotency-Key', async () => {
      const authPromise = firstValueFrom(service.authorizeCardPayment('DEBIT', 1500));
      const authReq = http!.expectOne('/api/v1/pos/card-payments');
      expect(authReq.request.method).toBe('POST');
      expect(authReq.request.headers.get('Idempotency-Key')).toBeTruthy();
      expect(authReq.request.body).toEqual({ method: 'DEBIT', amount: 1500 });
      authReq.flush(
        jsonBlob({
          id: 88,
          method: 'DEBIT',
          amount: 1500,
          status: 'AUTHORIZED',
          authorizationCode: 'AUTH88',
          cardBrand: 'VISA',
          last4: '4321',
        }),
      );

      const authRes = await authPromise;
      expect(authRes.id).toBe(88);
      expect(authRes.authorizationCode).toBe('AUTH88');

      const voidPromise = firstValueFrom(service.voidCardAuthorization(88));
      const voidReq = http!.expectOne('/api/v1/pos/card-payments/88/void');
      expect(voidReq.request.method).toBe('POST');
      voidReq.flush(jsonBlob({ id: 88, status: 'VOIDED' }));
      const voidRes = await voidPromise;
      expect(voidRes.status).toBe('VOIDED');
    });

    it('encadena overrides de supervisor (L-05) en POST /sales manteniendo Idempotency-Key', async () => {
      const salePromise = firstValueFrom(
        service.createSale(
          {
            lines: [{ productId: 5, quantity: 1 }],
            payments: [{ method: 'CASH', amount: 1990, tenderedAmount: 2000 }],
          },
          'sale-idem-1',
          ['tok-discount-1'],
        ),
      );

      const firstReq = http!.expectOne('/api/v1/sales');
      expect(firstReq.request.headers.get('Idempotency-Key')).toBe('sale-idem-1');
      expect(firstReq.request.headers.get('X-Override-Token')).toBe('tok-discount-1');
      firstReq.flush(
        jsonBlob(
          {
            code: 'OVERRIDE_REQUIRED',
            detail: 'Se requiere autorización para venta con precio especial.',
            action: 'PRICE_OVERRIDE',
            context: { productId: 5 },
          },
          'application/problem+json',
        ),
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
      document
        .querySelector<HTMLButtonElement>('pos-override-dialog button[type="submit"]')!
        .click();

      http!.expectOne('/api/v1/auth/overrides').flush({ overrideToken: 'tok-price-2' });

      await vi.waitFor(() => {
        const retryReq = http!.expectOne('/api/v1/sales');
        expect(retryReq.request.headers.get('Idempotency-Key')).toBe('sale-idem-1');
        expect(retryReq.request.headers.get('X-Override-Token')).toBe('tok-discount-1,tok-price-2');
        retryReq.flush(jsonBlob({ id: 901, number: 'V-00901', totalPayable: 1990 }));
      });

      const sale = await salePromise;
      expect(sale.id).toBe(901);
      expect(sale.number).toBe('V-00901');
    }, 20000);

    it('anula tarjetas autorizadas automáticamente cuando se cancela PosPaymentDialog', async () => {
      const dialogData: PosPaymentDialogData = {
        cartLines: [{ productId: 1, quantity: 1 }],
        totalDiscount: null,
        customerId: null,
        ageConfirmed: false,
        totalGross: 3000,
        idempotencyKey: 'idem-cancel-1',
        initialOverrideTokens: [],
      };

      const dialogRef = TestBed.inject(MatDialog).open(PosPaymentDialog, { data: dialogData });
      const closedPromise = firstValueFrom(dialogRef.afterClosed());

      await vi.waitFor(() => {
        TestBed.tick();
        expect(document.querySelector('pos-payment-dialog')).not.toBeNull();
      });

      const dialogEl = document.querySelector<HTMLElement>('pos-payment-dialog')!;
      const amountInput = dialogEl.querySelector<HTMLInputElement>(
        '[data-testid="electronic-amount-input"]',
      )!;
      amountInput.value = '1500';
      amountInput.dispatchEvent(new Event('input'));
      TestBed.tick();

      dialogEl
        .querySelector<HTMLButtonElement>('[data-testid="add-electronic-payment-btn"]')!
        .click();
      const authReq = http!.expectOne('/api/v1/pos/card-payments');
      authReq.flush(
        jsonBlob({
          id: 301,
          method: 'DEBIT',
          amount: 1500,
          status: 'AUTHORIZED',
          authorizationCode: 'AUT301',
          last4: '1111',
        }),
      );

      await vi.waitFor(() => {
        TestBed.tick();
        expect(dialogEl.querySelectorAll('[data-testid="added-card-payment"]').length).toBe(1);
      });

      dialogEl.querySelector<HTMLButtonElement>('[data-testid="cancel-payment-btn"]')!.click();
      const voidReq = http!.expectOne('/api/v1/pos/card-payments/301/void');
      voidReq.flush(jsonBlob({ id: 301, status: 'VOIDED' }));

      await expect(closedPromise).resolves.toBeUndefined();
    }, 20000);
  });

  describe('Fiado CUSTOMER_CREDIT (HU-POS-16)', () => {
    /** Abre el modal de cobro con una venta de $5.000 para el cliente 7 (con fiado). */
    function openDialog(overrides: Partial<PosPaymentDialogData>): void {
      const data: PosPaymentDialogData = {
        cartLines: [{ productId: 1, quantity: 1 }],
        totalDiscount: null,
        customerId: 7,
        ageConfirmed: false,
        totalGross: 5000,
        idempotencyKey: 'idem-fiado-1',
        initialOverrideTokens: [],
        ...overrides,
      };
      TestBed.inject(MatDialog).open(PosPaymentDialog, { data });
    }

    it('no ofrece el fiado cuando el cliente no lo tiene habilitado', async () => {
      openDialog({ customerCreditEnabled: false });

      await vi.waitFor(() => {
        TestBed.tick();
        expect(document.querySelector('pos-payment-dialog')).not.toBeNull();
      });
      const dialogEl = document.querySelector<HTMLElement>('pos-payment-dialog')!;
      expect(dialogEl.querySelector('[data-testid="add-credit-payment-btn"]')).toBeNull();

      dialogEl.querySelector<HTMLButtonElement>('[data-testid="cancel-payment-btn"]')!.click();
      await vi.waitFor(() => {
        TestBed.tick();
        expect(document.querySelector('pos-payment-dialog')).toBeNull();
      });
    });

    it('agrega fiado por el total, registra el cargo y envía CUSTOMER_CREDIT con customerId', async () => {
      openDialog({ customerCreditEnabled: true });

      await vi.waitFor(() => {
        TestBed.tick();
        expect(document.querySelector('pos-payment-dialog')).not.toBeNull();
      });
      const dialogEl = document.querySelector<HTMLElement>('pos-payment-dialog')!;

      // El monto a fiado viene prefijado con el total de la venta.
      const amountInput = dialogEl.querySelector<HTMLInputElement>(
        '[data-testid="credit-amount-input"]',
      )!;
      expect(amountInput.value).toBe('5000');

      dialogEl.querySelector<HTMLButtonElement>('[data-testid="add-credit-payment-btn"]')!.click();
      TestBed.tick();

      await vi.waitFor(() => {
        TestBed.tick();
        // El cargo queda registrado y el total cubierto sin efectivo.
        expect(
          dialogEl.querySelector('[data-testid="added-credit-payment"]')?.textContent,
        ).toContain('$5.000');
        expect(dialogEl.querySelector('[data-testid="payment-fully-covered"]')).not.toBeNull();
        // Con el cargo ya agregado, el formulario de captura desaparece.
        expect(dialogEl.querySelector('[data-testid="credit-amount-input"]')).toBeNull();
      });

      const confirmBtn = dialogEl.querySelector<HTMLButtonElement>(
        '[data-testid="confirm-payment-btn"]',
      )!;
      expect(confirmBtn.disabled).toBe(false);
      confirmBtn.click();

      const createSaleReq = http!.expectOne('/api/v1/sales');
      expect(createSaleReq.request.method).toBe('POST');
      // F.2/F.3: con CUSTOMER_CREDIT la venta exige `customerId` y el monto aplicado.
      expect(createSaleReq.request.body.customerId).toBe(7);
      expect(createSaleReq.request.body.payments).toEqual([
        { method: 'CUSTOMER_CREDIT', amount: 5000 },
      ]);
      createSaleReq.flush(jsonBlob({ id: 950, number: 'V-00950', totalPayable: 5000 }));

      await vi.waitFor(() => {
        TestBed.tick();
        expect(document.querySelector('pos-payment-dialog')).toBeNull();
      });
    }, 20000);

    it('valida el monto contra el saldo pendiente y permite fiado parcial combinado con efectivo', async () => {
      openDialog({ customerCreditEnabled: true });

      await vi.waitFor(() => {
        TestBed.tick();
        expect(document.querySelector('pos-payment-dialog')).not.toBeNull();
      });
      const dialogEl = document.querySelector<HTMLElement>('pos-payment-dialog')!;
      const amountInput = dialogEl.querySelector<HTMLInputElement>(
        '[data-testid="credit-amount-input"]',
      )!;

      // Monto mayor al saldo pendiente: error en línea, sin registrar el cargo.
      amountInput.value = '9999';
      amountInput.dispatchEvent(new Event('input'));
      TestBed.tick();
      dialogEl.querySelector<HTMLButtonElement>('[data-testid="add-credit-payment-btn"]')!.click();
      TestBed.tick();
      expect(dialogEl.querySelector('[data-testid="payment-error-banner"]')?.textContent).toContain(
        'supera el saldo pendiente',
      );
      expect(dialogEl.querySelector('[data-testid="added-credit-payment"]')).toBeNull();

      // Fiado parcial de $2.000: queda efectivo por cobrar de $3.000.
      amountInput.value = '2000';
      amountInput.dispatchEvent(new Event('input'));
      TestBed.tick();
      dialogEl.querySelector<HTMLButtonElement>('[data-testid="add-credit-payment-btn"]')!.click();
      TestBed.tick();

      await vi.waitFor(() => {
        TestBed.tick();
        expect(
          dialogEl.querySelector('[data-testid="added-credit-payment"]')?.textContent,
        ).toContain('$2.000');
        expect(dialogEl.querySelector('[data-testid="cash-payable-pill"]')?.textContent).toContain(
          '$3.000',
        );
      });

      dialogEl.querySelector<HTMLButtonElement>('[data-testid="confirm-payment-btn"]')!.click();

      const createSaleReq = http!.expectOne('/api/v1/sales');
      expect(createSaleReq.request.body.payments).toEqual([
        { method: 'CUSTOMER_CREDIT', amount: 2000 },
        { method: 'CASH', amount: 3000, tenderedAmount: 3000 },
      ]);
      createSaleReq.flush(jsonBlob({ id: 951, number: 'V-00951', totalPayable: 5000 }));

      await vi.waitFor(() => {
        TestBed.tick();
        expect(document.querySelector('pos-payment-dialog')).toBeNull();
      });
    }, 20000);
  });
});
