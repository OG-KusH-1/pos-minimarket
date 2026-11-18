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
import { TaxesService } from './taxes.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('TaxesService', () => {
  let service: TaxesService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TaxesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listTaxes consulta GET /api/v1/taxes', async () => {
    const promise = firstValueFrom(service.listTaxes());
    const req = http.expectOne('/api/v1/taxes');
    expect(req.request.method).toBe('GET');

    flushJson(req, [
      {
        id: 1,
        code: 'IVA',
        name: 'IVA 19%',
        taxType: 'VAT',
        rate: 19,
        siiCode: '14',
        validFrom: '2026-01-01',
        active: true,
      },
    ]);
    const taxes = await promise;
    expect(taxes).toHaveLength(1);
    expect(taxes[0]?.code).toBe('IVA');
  });

  it('createTax envía POST /api/v1/taxes', async () => {
    const promise = firstValueFrom(
      service.createTax({
        code: 'IABA_18',
        name: 'IABA Bebidas Azucaradas 18%',
        taxType: 'ADDITIONAL',
        rate: 18,
        siiCode: '271',
        validFrom: '2026-01-01',
      }),
    );

    const req = http.expectOne('/api/v1/taxes');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      code: 'IABA_18',
      name: 'IABA Bebidas Azucaradas 18%',
      taxType: 'ADDITIONAL',
      rate: 18,
      siiCode: '271',
      validFrom: '2026-01-01',
    });

    flushJson(req, {
      id: 2,
      code: 'IABA_18',
      name: 'IABA Bebidas Azucaradas 18%',
      taxType: 'ADDITIONAL',
      rate: 18,
      siiCode: '271',
      validFrom: '2026-01-01',
      active: true,
    });
    const created = await promise;
    expect(created.id).toBe(2);
    expect(created.taxType).toBe('ADDITIONAL');
  });

  it('updateTax envía PUT /api/v1/taxes/{id}', async () => {
    const promise = firstValueFrom(
      service.updateTax(2, {
        code: 'IABA_18',
        name: 'IABA Bebidas Azucaradas',
        taxType: 'ADDITIONAL',
        rate: 18,
        validFrom: '2026-01-01',
        validTo: '2026-12-31',
      }),
    );

    const req = http.expectOne('/api/v1/taxes/2');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.validTo).toBe('2026-12-31');

    flushJson(req, {
      id: 2,
      code: 'IABA_18',
      name: 'IABA Bebidas Azucaradas',
      taxType: 'ADDITIONAL',
      rate: 18,
      validFrom: '2026-01-01',
      validTo: '2026-12-31',
      active: true,
    });
    const updated = await promise;
    expect(updated.validTo).toBe('2026-12-31');
  });

  describe('con errorInterceptor y respuestas Blob', () => {
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
      service = TestBed.inject(TaxesService);
      http = TestBed.inject(HttpTestingController);
    });

    it('desempaqueta ProblemDetail Blob en VALIDATION_ERROR con errores por campo', async () => {
      const promise = firstValueFrom(
        service.createTax(
          {
            code: 'IABA_18',
            name: 'IABA',
            taxType: 'ADDITIONAL',
            rate: 18,
            validFrom: '2026-06-01',
            validTo: '2026-01-01',
          },
          withoutErrorNotification(),
        ),
      );
      const req = http.expectOne('/api/v1/taxes');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'VALIDATION_ERROR',
              detail: 'La fecha de término debe ser posterior a la fecha de inicio.',
              errors: [
                {
                  field: 'validTo',
                  code: 'INVALID_RANGE',
                  message: 'validTo debe ser posterior a validFrom.',
                },
              ],
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 400, statusText: 'Bad Request' },
      );

      const error = await promise.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).code).toBe('VALIDATION_ERROR');
      expect((error as ApiError).hasFieldErrors).toBe(true);
      expect((error as ApiError).errors).toEqual([
        {
          field: 'validTo',
          code: 'INVALID_RANGE',
          message: 'validTo debe ser posterior a validFrom.',
        },
      ]);
    });
  });
});
