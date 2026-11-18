import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Observable, catchError, firstValueFrom, of, throwError } from 'rxjs';
import { ApiError, ErrorCodes, isApiError } from '../../../../core/http/api-error';
import {
  OVERRIDE_TOKEN_HEADER,
  SupervisorOverrideService,
} from '../../../../shared/ui/override-dialog';
import { PosOverrideService } from './pos-override.service';

describe('PosOverrideService', () => {
  let service: PosOverrideService;
  let http: HttpTestingController | undefined;

  afterEach(() => {
    try {
      TestBed.inject(MatDialog).closeAll();
    } catch {
      // Sin diálogo inicializado
    }
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('abre el flujo de supervisor para un RequiredOverride de quote y devuelve el token', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(PosOverrideService);

    const tokenPromise = firstValueFrom(
      service.authorizeRequiredOverride({
        action: 'DISCOUNT_ITEM_OVER_LIMIT',
        context: { lineIndex: 0, percent: 25, cartHash: 'h1' },
      }),
    );

    await vi.waitFor(() => expect(document.querySelector('pos-override-dialog')).not.toBeNull());
    TestBed.tick();

    const usernameInput = document.querySelector<HTMLInputElement>(
      'input[formcontrolname="supervisorUsername"]',
    )!;
    const secretInput = document.querySelector<HTMLInputElement>(
      'input[formcontrolname="secret"]',
    )!;
    usernameInput.value = 'sup1';
    usernameInput.dispatchEvent(new Event('input'));
    secretInput.value = '1234';
    secretInput.dispatchEvent(new Event('input'));
    document.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();

    const authReq = http.expectOne('/api/v1/auth/overrides');
    authReq.flush({ overrideToken: 'ovr-tok-123' });

    const token = await tokenPromise;
    expect(token).toBe('ovr-tok-123');
  });

  it('devuelve null si el supervisor cancela el diálogo en authorizeRequiredOverride', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(PosOverrideService);

    const tokenPromise = firstValueFrom(
      service.authorizeRequiredOverride({
        action: 'DISCOUNT_TOTAL_OVER_LIMIT',
        context: { percent: 20, cartHash: 'h2' },
      }),
    );

    await vi.waitFor(() => expect(document.querySelector('pos-override-dialog')).not.toBeNull());
    TestBed.inject(MatDialog).closeAll();

    const token = await tokenPromise;
    expect(token).toBeNull();
  });

  it('encadena múltiples rondas L-05 acumulando X-Override-Token separados por coma', async () => {
    let tokenSeq = 0;
    const mockSupervisor = {
      withSupervisorOverride: vi.fn(
        (fn: (headers: Record<string, string>) => Observable<unknown>) =>
          fn({}).pipe(
            catchError((err: unknown) => {
              if (isApiError(err) && err.code === ErrorCodes.overrideRequired) {
                tokenSeq += 1;
                return fn({ [OVERRIDE_TOKEN_HEADER]: `tok-${tokenSeq}` });
              }
              return throwError(() => err);
            }),
          ),
      ),
    };

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SupervisorOverrideService, useValue: mockSupervisor },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(PosOverrideService);

    const sentHeaders: (string | undefined)[] = [];
    const result = await firstValueFrom(
      service.withMultipleSupervisorOverrides(
        (headerValue) => {
          sentHeaders.push(headerValue);
          if (!headerValue?.includes('tok-1')) {
            return throwError(
              () =>
                new ApiError(422, ErrorCodes.overrideRequired, 'Falta primer override', null, [], {
                  action: 'DISCOUNT_ITEM_OVER_LIMIT',
                  context: { lineIndex: 0 },
                }),
            );
          }
          if (!headerValue.includes('tok-2')) {
            return throwError(
              () =>
                new ApiError(422, ErrorCodes.overrideRequired, 'Falta segundo override', null, [], {
                  action: 'CREDIT_OVER_LIMIT',
                  context: { customerId: 7, amount: 15000 },
                }),
            );
          }
          return of('SALE-OK');
        },
        ['pre-tok'],
      ),
    );

    expect(result).toBe('SALE-OK');
    expect(mockSupervisor.withSupervisorOverride).toHaveBeenCalledTimes(2);
    expect(sentHeaders).toEqual([
      'pre-tok',
      'pre-tok,tok-1',
      'pre-tok,tok-1',
      'pre-tok,tok-1,tok-2',
    ]);
  });
});
