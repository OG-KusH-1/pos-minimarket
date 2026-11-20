import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../api/api-configuration';
import { ApiError } from '../../core/http/api-error';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { CustomersService } from './customers.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

/** Cliente de ejemplo con estado crediticio completo. */
function sampleCustomer(): Record<string, unknown> {
  return {
    id: 1,
    rut: '123456785',
    name: 'María Pérez',
    phone: '912345678',
    creditEnabled: true,
    creditLimit: 50000,
    creditBalance: 20000,
    availableCredit: 30000,
    active: true,
    version: 2,
  };
}

describe('CustomersService', () => {
  let service: CustomersService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CustomersService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listCustomers y getCustomer consultan /api/v1/customers con sus filtros y desenvuelven Blob JSON', async () => {
    const listPromise = firstValueFrom(
      service.listCustomers({ q: 'María', active: true, withDebt: true, page: 0, size: 20 }),
    );
    const listReq = http.expectOne((r) => r.url === '/api/v1/customers');
    expect(listReq.request.method).toBe('GET');
    expect(listReq.request.params.get('q')).toBe('María');
    expect(listReq.request.params.get('active')).toBe('true');
    expect(listReq.request.params.get('withDebt')).toBe('true');
    expect(listReq.request.params.get('page')).toBe('0');
    expect(listReq.request.params.get('size')).toBe('20');
    flushJson(listReq, {
      content: [sampleCustomer()],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    const page = await listPromise;
    expect(page.content?.[0]?.name).toBe('María Pérez');
    expect(page.content?.[0]?.creditBalance).toBe(20000);

    const getPromise = firstValueFrom(service.getCustomer(1));
    const getReq = http.expectOne('/api/v1/customers/1');
    expect(getReq.request.method).toBe('GET');
    flushJson(getReq, sampleCustomer());
    const detail = await getPromise;
    expect(detail.version).toBe(2);
  });

  it('createCustomer hace POST /api/v1/customers y updateCustomer PUT /api/v1/customers/{id}', async () => {
    const createPromise = firstValueFrom(
      service.createCustomer({ rut: '123456785', name: 'María Pérez' }),
    );
    const createReq = http.expectOne('/api/v1/customers');
    expect(createReq.request.method).toBe('POST');
    // La creación de clientes no es idempotente ([I]): no lleva Idempotency-Key.
    expect(createReq.request.headers.has('Idempotency-Key')).toBe(false);
    expect(createReq.request.body).toMatchObject({ rut: '123456785', name: 'María Pérez' });
    flushJson(createReq, sampleCustomer());
    expect((await createPromise).id).toBe(1);

    const updatePromise = firstValueFrom(
      service.updateCustomer(1, {
        rut: '123456785',
        name: 'María Pérez Soto',
        version: 2,
      }),
    );
    const updateReq = http.expectOne('/api/v1/customers/1');
    expect(updateReq.request.method).toBe('PUT');
    flushJson(updateReq, { ...sampleCustomer(), name: 'María Pérez Soto', version: 3 });
    const updated = await updatePromise;
    expect(updated.name).toBe('María Pérez Soto');
    expect(updated.version).toBe(3);
  });

  it('activate y deactivate hacen POST contra /api/v1/customers/{id}/activate y /deactivate', async () => {
    const deactivatePromise = firstValueFrom(service.deactivateCustomer(1));
    const deactivateReq = http.expectOne('/api/v1/customers/1/deactivate');
    expect(deactivateReq.request.method).toBe('POST');
    deactivateReq.flush('');
    await deactivatePromise;

    const activatePromise = firstValueFrom(service.activateCustomer(1));
    const activateReq = http.expectOne('/api/v1/customers/1/activate');
    expect(activateReq.request.method).toBe('POST');
    activateReq.flush('');
    await activatePromise;
  });

  it('updateCreditSettings hace PUT /api/v1/customers/{id}/credit con cupo, habilitación y motivo', async () => {
    const promise = firstValueFrom(
      service.updateCreditSettings(1, {
        creditEnabled: true,
        creditLimit: 80000,
        reason: 'Aumento de cupo autorizado por gerencia',
      }),
    );
    const req = http.expectOne('/api/v1/customers/1/credit');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toMatchObject({
      creditEnabled: true,
      creditLimit: 80000,
      reason: 'Aumento de cupo autorizado por gerencia',
    });
    flushJson(req, { ...sampleCustomer(), creditLimit: 80000 });
    expect((await promise).creditLimit).toBe(80000);
  });

  it('getAccount consulta /api/v1/customers/{id}/account con paginación de la cartola', async () => {
    const promise = firstValueFrom(service.getAccount({ id: 1, page: 1, size: 10 }));
    const req = http.expectOne((r) => r.url === '/api/v1/customers/1/account');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('10');
    flushJson(req, {
      customer: sampleCustomer(),
      movements: {
        content: [
          {
            id: 9,
            movementType: 'PAYMENT',
            amount: -5000,
            balanceAfter: 15000,
            paymentMethod: 'CASH',
            occurredAt: '2026-11-19T10:00:00Z',
          },
        ],
        page: 1,
        size: 10,
        totalElements: 3,
        totalPages: 1,
      },
    });
    const statement = await promise;
    expect(statement.customer?.creditBalance).toBe(20000);
    expect(statement.movements?.content?.[0]?.movementType).toBe('PAYMENT');
    expect(statement.movements?.content?.[0]?.balanceAfter).toBe(15000);
  });

  it('registerPayment hace POST /api/v1/customers/{id}/payments con Idempotency-Key (UUID o la entregada)', async () => {
    const generatedPromise = firstValueFrom(
      service.registerPayment(1, { amount: 5000, paymentMethod: 'CASH' }),
    );
    const generatedReq = http.expectOne('/api/v1/customers/1/payments');
    expect(generatedReq.request.method).toBe('POST');
    expect(generatedReq.request.headers.get('Idempotency-Key')).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(generatedReq.request.body).toMatchObject({ amount: 5000, paymentMethod: 'CASH' });
    flushJson(generatedReq, {
      id: 9,
      movementType: 'PAYMENT',
      amount: -5000,
      balanceAfter: 15000,
    });
    expect((await generatedPromise).balanceAfter).toBe(15000);

    const explicitPromise = firstValueFrom(
      service.registerPayment(
        1,
        { amount: 3000, paymentMethod: 'TRANSFER' },
        {
          idempotencyKey: 'key-123',
        },
      ),
    );
    const explicitReq = http.expectOne('/api/v1/customers/1/payments');
    expect(explicitReq.request.headers.get('Idempotency-Key')).toBe('key-123');
    flushJson(explicitReq, { id: 10, movementType: 'PAYMENT', amount: -3000, balanceAfter: 12000 });
    expect((await explicitPromise).id).toBe(10);
  });

  it('registerAdjustment hace POST /api/v1/customers/{id}/adjustments con Idempotency-Key', async () => {
    const promise = firstValueFrom(
      service.registerAdjustment(1, { amount: -1000, reason: 'Corrección de cargo duplicado' }),
    );
    const req = http.expectOne('/api/v1/customers/1/adjustments');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(req.request.body).toMatchObject({
      amount: -1000,
      reason: 'Corrección de cargo duplicado',
    });
    flushJson(req, { id: 11, movementType: 'ADJUSTMENT', amount: -1000, balanceAfter: 19000 });
    expect((await promise).movementType).toBe('ADJUSTMENT');
  });

  it('convierte PAYMENT_EXCEEDS_DEBT recibido como Blob en ApiError tipado', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    const interceptedService = TestBed.inject(CustomersService);
    const interceptedHttp = TestBed.inject(HttpTestingController);

    const errPromise = firstValueFrom(
      interceptedService.registerPayment(
        1,
        { amount: 25000, paymentMethod: 'CASH' },
        undefined,
        withoutErrorNotification(),
      ),
    ).catch((err: unknown) => err);

    const req = interceptedHttp.expectOne('/api/v1/customers/1/payments');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            status: 422,
            code: 'PAYMENT_EXCEEDS_DEBT',
            detail: 'El abono excede la deuda actual',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    const err = await errPromise;
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('PAYMENT_EXCEEDS_DEBT');
    interceptedHttp.verify();
  });
});
