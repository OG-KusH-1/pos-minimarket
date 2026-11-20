import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../api/api-configuration';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { CustomerAccountDialog, CustomerAccountDialogData } from './customer-account-dialog';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

function flushBlobProblem(req: TestRequest, body: Record<string, unknown>, status: number): void {
  req.flush(new Blob([JSON.stringify(body)], { type: 'application/problem+json' }), {
    status,
    statusText: 'Unprocessable Entity',
  });
}

/** Estado de cuenta con deuda $20.000 y un movimiento de ejemplo. */
function statement(creditBalance: number): Record<string, unknown> {
  return {
    customer: {
      id: 5,
      rut: '123456785',
      name: 'María Pérez',
      creditEnabled: true,
      creditLimit: 50000,
      creditBalance,
      availableCredit: 50000 - creditBalance,
      active: true,
    },
    movements: {
      content: [
        {
          id: 7,
          movementType: 'CHARGE',
          amount: 20000,
          balanceAfter: creditBalance,
          occurredAt: '2026-11-18T15:30:00Z',
          saleNumber: 'V-000123',
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    },
  };
}

describe('CustomerAccountDialog (formulario de abono, HU-POS-17)', () => {
  let fixture: ComponentFixture<CustomerAccountDialog>;
  let http: HttpTestingController;
  let dialogRefMock: { close: ReturnType<typeof vi.fn> };
  let snackBarOpen: ReturnType<typeof vi.fn>;

  /** Formulario de abono y señales del componente (miembros protected, tipados para la prueba). */
  function comp(): {
    paymentForm: {
      controls: {
        amount: { setValue: (v: number | null) => void; hasError: (code: string) => boolean };
        paymentMethod: { setValue: (v: string) => void };
      };
      invalid: boolean;
      reset: (value: Record<string, unknown>) => void;
    };
    paymentError: () => string | null;
    debt: () => number;
    submitPayment: () => void;
    submitAdjustment: () => void;
    adjustmentForm: {
      controls: {
        amount: { setValue: (v: number | null) => void; hasError: (code: string) => boolean };
        reason: { setValue: (v: string) => void };
      };
    };
    adjustmentError: () => string | null;
  } {
    return fixture.componentInstance as unknown as ReturnType<typeof comp>;
  }

  async function mount(
    data: CustomerAccountDialogData = { customerId: 5, customerName: 'María Pérez' },
  ): Promise<void> {
    dialogRefMock = { close: vi.fn() };
    snackBarOpen = vi.fn();
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [CustomerAccountDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRefMock },
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
        // SupervisorOverrideService abre un diálogo de override; en estas pruebas no debe abrirse.
        { provide: MatDialog, useValue: { open: vi.fn() } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CustomerAccountDialog);
    fixture.detectChanges();
  }

  afterEach(() => http?.verify());

  it('carga el estado de cuenta y la cartola al abrir', async () => {
    await mount();

    const req = http.expectOne((r) => r.url === '/api/v1/customers/5/account');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('10');
    flushJson(req, statement(20000));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp().debt()).toBe(20000);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('$20.000');
    expect(text).toContain('V-000123');
  });

  it('registra un abono válido (monto ≤ deuda) con Idempotency-Key y recarga la cartola', async () => {
    await mount();

    const loadReq = http.expectOne((r) => r.url === '/api/v1/customers/5/account');
    flushJson(loadReq, statement(20000));
    await fixture.whenStable();
    fixture.detectChanges();

    comp().paymentForm.controls.amount.setValue(15000);
    comp().paymentForm.controls.paymentMethod.setValue('CASH');
    fixture.detectChanges();

    expect(comp().paymentForm.invalid).toBe(false);
    comp().submitPayment();

    const postReq = http.expectOne('/api/v1/customers/5/payments');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.headers.get('Idempotency-Key')).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(postReq.request.body).toMatchObject({ amount: 15000, paymentMethod: 'CASH' });
    flushJson(postReq, {
      id: 9,
      movementType: 'PAYMENT',
      amount: -15000,
      balanceAfter: 5000,
    });
    await fixture.whenStable();
    fixture.detectChanges();

    // Tras el abono se recarga el estado de cuenta (página 0): la deuda baja a $5.000.
    const reloadReq = http.expectOne((r) => r.url === '/api/v1/customers/5/account');
    flushJson(reloadReq, statement(5000));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp().debt()).toBe(5000);
    expect(snackBarOpen).toHaveBeenCalled();
    expect(dialogRefMock.close).not.toHaveBeenCalled();
  });

  it('bloquea en el formulario un monto mayor que la deuda sin llamar a la API', async () => {
    await mount();

    const loadReq = http.expectOne((r) => r.url === '/api/v1/customers/5/account');
    flushJson(loadReq, statement(20000));
    await fixture.whenStable();
    fixture.detectChanges();

    comp().paymentForm.controls.amount.setValue(25000);
    fixture.detectChanges();

    expect(comp().paymentForm.invalid).toBe(true);
    expect(comp().paymentForm.controls.amount.hasError('exceedsDebt')).toBe(true);

    comp().submitPayment();
    http.expectNone((r) => r.url === '/api/v1/customers/5/payments');
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('El monto excede la deuda actual');
  });

  it('maneja PAYMENT_EXCEEDS_DEBT de la API marcando el campo monto y mostrando el mensaje', async () => {
    await mount();

    const loadReq = http.expectOne((r) => r.url === '/api/v1/customers/5/account');
    flushJson(loadReq, statement(20000));
    await fixture.whenStable();
    fixture.detectChanges();

    // Monto válido en el cliente ($20.000 = deuda), pero la deuda cambió en el servidor.
    comp().paymentForm.controls.amount.setValue(20000);
    fixture.detectChanges();

    expect(comp().paymentForm.invalid).toBe(false);
    comp().submitPayment();

    const postReq = http.expectOne('/api/v1/customers/5/payments');
    flushBlobProblem(
      postReq,
      {
        status: 422,
        code: 'PAYMENT_EXCEEDS_DEBT',
        detail: 'El abono excede la deuda actual del cliente',
      },
      422,
    );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp().paymentForm.controls.amount.hasError('exceedsDebt')).toBe(true);
    expect(comp().paymentError()).toContain('excede la deuda actual');
    expect(dialogRefMock.close).not.toHaveBeenCalled();
  });

  it('maneja CREDIT_LIMIT_EXCEEDED del ajuste sin abrir el diálogo de override de supervisor', async () => {
    await mount();

    const loadReq = http.expectOne((r) => r.url === '/api/v1/customers/5/account');
    flushJson(loadReq, statement(20000));
    await fixture.whenStable();
    fixture.detectChanges();

    comp().adjustmentForm.controls.amount.setValue(99000);
    comp().adjustmentForm.controls.reason.setValue('Corrección por cargo erróneo');
    fixture.detectChanges();

    comp().submitAdjustment();

    const postReq = http.expectOne('/api/v1/customers/5/adjustments');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(postReq.request.body).toMatchObject({
      amount: 99000,
      reason: 'Corrección por cargo erróneo',
    });
    flushBlobProblem(
      postReq,
      {
        status: 422,
        code: 'CREDIT_LIMIT_EXCEEDED',
        detail: 'El ajuste excede el monto máximo permitido para su rol.',
      },
      422,
    );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp().adjustmentForm.controls.amount.hasError('limitExceeded')).toBe(true);
    expect(comp().adjustmentError()).toContain('máximo permitido');
    // El límite es un error duro del backend: nunca se abre el diálogo de override.
    expect(TestBed.inject(MatDialog).open).not.toHaveBeenCalled();
  });
});
