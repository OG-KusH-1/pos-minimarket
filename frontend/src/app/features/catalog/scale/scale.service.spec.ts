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
import { ScaleService } from './scale.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('ScaleService', () => {
  let service: ScaleService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ScaleService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listConfigs consulta GET /api/v1/scale-barcode-configs', async () => {
    const promise = firstValueFrom(service.listConfigs());
    const req = http.expectOne('/api/v1/scale-barcode-configs');
    expect(req.request.method).toBe('GET');

    flushJson(req, [
      {
        id: 1,
        name: 'Balanza peso kg',
        prefix: '20',
        embeddedType: 'WEIGHT',
        pluStart: 2,
        pluLength: 5,
        valueStart: 7,
        valueLength: 5,
        valueDecimals: 3,
        active: true,
      },
    ]);

    const configs = await promise;
    expect(configs).toHaveLength(1);
    expect(configs[0]?.prefix).toBe('20');
  });

  it('createConfig y updateConfig envían POST y PUT respectivamente', async () => {
    const payload = {
      name: 'Balanza precio CLP',
      prefix: '21',
      embeddedType: 'PRICE' as const,
      pluStart: 2,
      pluLength: 5,
      valueStart: 7,
      valueLength: 5,
      valueDecimals: 0,
    };

    const createPromise = firstValueFrom(service.createConfig(payload));
    const postReq = http.expectOne('/api/v1/scale-barcode-configs');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.body).toEqual(payload);
    flushJson(postReq, { id: 2, ...payload, active: true });
    const created = await createPromise;
    expect(created.id).toBe(2);

    const updatePromise = firstValueFrom(
      service.updateConfig(2, { ...payload, name: 'Balanza 21' }),
    );
    const putReq = http.expectOne('/api/v1/scale-barcode-configs/2');
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body.name).toBe('Balanza 21');
    flushJson(putReq, { id: 2, ...payload, name: 'Balanza 21', active: true });
    const updated = await updatePromise;
    expect(updated.name).toBe('Balanza 21');
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
      service = TestBed.inject(ScaleService);
      http = TestBed.inject(HttpTestingController);
    });

    it('desempaqueta ProblemDetail Blob en DUPLICATED_VALUE al crear configuración', async () => {
      const promise = firstValueFrom(
        service.createConfig(
          {
            name: 'Balanza 20',
            prefix: '20',
            embeddedType: 'WEIGHT',
            pluStart: 2,
            pluLength: 5,
            valueStart: 7,
            valueLength: 5,
            valueDecimals: 3,
          },
          withoutErrorNotification(),
        ),
      );
      const req = http.expectOne('/api/v1/scale-barcode-configs');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'DUPLICATED_VALUE',
              detail: "Ya existe una configuración de balanza con el prefijo '20'.",
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 409, statusText: 'Conflict' },
      );

      const error = await promise.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).code).toBe('DUPLICATED_VALUE');
      expect((error as ApiError).message).toBe(
        "Ya existe una configuración de balanza con el prefijo '20'.",
      );
    });
  });
});
