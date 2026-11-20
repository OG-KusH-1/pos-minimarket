import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { TaxDocumentResponse } from '../../../api/models/tax-document-response';
import { ApiError } from '../../../core/http/api-error';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { storeContextInterceptor } from '../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { TaxDocumentsService } from './tax-documents.service';

/** Envuelve un payload como `Blob` JSON (el cliente generado usa `responseType: 'blob'`). */
function jsonBlob(payload: unknown, type = 'application/json'): Blob {
  return new Blob([JSON.stringify(payload)], { type });
}

/** Documento de ejemplo reutilizable en las respuestas simuladas. */
function sampleDocument(overrides: Partial<TaxDocumentResponse> = {}): TaxDocumentResponse {
  return {
    id: 39,
    documentType: 'BOLETA',
    typeLabel: 'Boleta',
    stampText: 'TIMBRE-39',
    siiTypeCode: 39,
    folio: 39,
    status: 'ISSUED',
    saleId: 152,
    saleReturnId: undefined,
    provider: 'SimulatedInvoiceProvider',
    providerTrackId: 'TRK-39',
    netAmount: 8820,
    exemptAmount: 0,
    taxAmount: 1680,
    totalAmount: 10500,
    attempts: 1,
    lastError: undefined,
    issuedAt: '2026-11-18T12:30:00-03:00',
    ...overrides,
  };
}

describe('TaxDocumentsService', () => {
  let service: TaxDocumentsService;
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

    service = TestBed.inject(TaxDocumentsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('lista documentos con filtros, paginación y orden', async () => {
    const pending = firstValueFrom(
      service.listDocuments({
        status: 'ERROR',
        documentType: 'BOLETA',
        folio: 39,
        page: 0,
        size: 20,
        sort: ['id,desc'],
      }),
    );

    const req = http!.expectOne((r) => r.url === '/api/v1/tax-documents');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('ERROR');
    expect(req.request.params.get('documentType')).toBe('BOLETA');
    expect(req.request.params.get('folio')).toBe('39');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.getAll('sort')).toEqual(['id,desc']);
    req.flush(
      jsonBlob({
        content: [sampleDocument()],
        page: 0,
        size: 20,
        sort: ['id,desc'],
        totalElements: 1,
        totalPages: 1,
      }),
    );

    const page = await pending;
    expect(page.content).toHaveLength(1);
    expect(page.content[0]?.folio).toBe(39);
  });

  it('obtiene el detalle de un documento por id', async () => {
    const pending = firstValueFrom(service.getDocument(39));

    const req = http!.expectOne((r) => r.url === '/api/v1/tax-documents/39');
    expect(req.request.method).toBe('GET');
    req.flush(jsonBlob(sampleDocument()));

    const document = await pending;
    expect(document.id).toBe(39);
    expect(document.status).toBe('ISSUED');
  });

  it('reintenta la emisión de un documento en ERROR y devuelve el estado resultante', async () => {
    const pending = firstValueFrom(service.retryDocument(41));

    const req = http!.expectOne((r) => r.url === '/api/v1/tax-documents/41/retry');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(jsonBlob(sampleDocument({ id: 41, folio: 41, status: 'ISSUED', attempts: 2 })));

    const updated = await pending;
    expect(updated.id).toBe(41);
    expect(updated.status).toBe('ISSUED');
    expect(updated.attempts).toBe(2);
  });

  it('traduce el 204 de documento aún no emitido a estado pendiente (null), no a error', async () => {
    const pending = firstValueFrom(service.getSaleTaxDocument(152));

    const req = http!.expectOne((r) => r.url === '/api/v1/sales/152/tax-document');
    expect(req.request.method).toBe('GET');

    // 204 No Content: la boleta de la venta sigue pendiente de emisión asíncrona.
    req.flush(null, { status: 204, statusText: 'No Content' });

    const document = await pending;
    expect(document).toBeNull();
  });

  it('devuelve el documento tributario cuando la venta ya lo tiene emitido', async () => {
    const pending = firstValueFrom(service.getSaleTaxDocument(152));

    const req = http!.expectOne((r) => r.url === '/api/v1/sales/152/tax-document');
    req.flush(jsonBlob(sampleDocument()));

    const document = await pending;
    expect(document?.folio).toBe(39);
    expect(document?.status).toBe('ISSUED');
  });

  it('propaga los errores de negocio como ApiError (reintento en estado inválido, 409)', async () => {
    const pending = firstValueFrom(service.retryDocument(5));

    const req = http!.expectOne((r) => r.url === '/api/v1/tax-documents/5/retry');
    req.flush(
      jsonBlob({
        type: 'about:blank',
        title: 'Transición de estado inválida',
        status: 409,
        detail: 'El documento 5 no está en estado ERROR ni REJECTED.',
        code: 'INVALID_STATE_TRANSITION',
      }),
      { status: 409, statusText: 'Conflict' },
    );

    let captured: unknown;
    try {
      await pending;
    } catch (error: unknown) {
      captured = error;
    }

    expect(captured).toBeInstanceOf(ApiError);
    expect((captured as ApiError).code).toBe('INVALID_STATE_TRANSITION');
    expect((captured as ApiError).status).toBe(409);
    expect((captured as ApiError).message).toContain('estado ERROR ni REJECTED');
  });
});
