import { HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../../../api/api-configuration';
import { ApiError } from '../../../../core/http/api-error';
import { errorInterceptor } from '../../../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../../../core/http/idempotency.interceptor';
import { storeContextInterceptor } from '../../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../../core/store-context/store-context.service';
import { SaleEmailService } from './sale-email.service';

describe('SaleEmailService', () => {
  let service: SaleEmailService;
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

    service = TestBed.inject(SaleEmailService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('encola el envío del comprobante con Idempotency-Key (202 aceptado)', async () => {
    const pending = firstValueFrom(service.sendTicketByEmail(152, 'cliente@correo.cl'));

    const req = http!.expectOne((r) => r.url === '/api/v1/sales/152/email');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.has('Idempotency-Key')).toBe(true);
    expect(req.request.headers.get('X-Store-Id')).toBe('10');
    expect(req.request.body).toEqual({ email: 'cliente@correo.cl' });

    // 202 Accepted: el backend encola el correo en la outbox (HU-POS-11).
    req.flush(null, { status: 202, statusText: 'Accepted' });

    await expect(pending).resolves.toBeUndefined();
  });

  it('propaga los errores de negocio como ApiError (venta inexistente, 404)', async () => {
    const pending = firstValueFrom(service.sendTicketByEmail(999, 'cliente@correo.cl'));

    const req = http!.expectOne((r) => r.url === '/api/v1/sales/999/email');
    // `emailTicket` usa `responseType: 'text'`: el backend de pruebas exige cuerpo de texto.
    req.flush(
      JSON.stringify({
        type: 'about:blank',
        title: 'Venta no encontrada',
        status: 404,
        detail: 'No existe la venta 999.',
        code: 'NOT_FOUND',
      }),
      {
        status: 404,
        statusText: 'Not Found',
        headers: new HttpHeaders({ 'Content-Type': 'application/problem+json' }),
      },
    );

    let captured: unknown;
    try {
      await pending;
    } catch (error: unknown) {
      captured = error;
    }

    expect(captured).toBeInstanceOf(ApiError);
    expect((captured as ApiError).code).toBe('NOT_FOUND');
  });
});
