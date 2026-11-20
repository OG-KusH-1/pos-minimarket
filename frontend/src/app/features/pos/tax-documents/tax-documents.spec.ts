import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { TaxDocumentResponse } from '../../../api/models/tax-document-response';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { storeContextInterceptor } from '../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { TaxDocuments } from './tax-documents';

/** Envuelve un payload como `Blob` JSON (el cliente generado usa `responseType: 'blob'`). */
function jsonBlob(payload: unknown, type = 'application/json'): Blob {
  return new Blob([JSON.stringify(payload)], { type });
}

/** Página vacía reutilizable para las recargas de la tabla. */
function emptyPage(): unknown {
  return { content: [], page: 0, size: 20, sort: [], totalElements: 0, totalPages: 0 };
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

describe('TaxDocuments (pantalla)', () => {
  let http: HttpTestingController | undefined;
  let snackBar: { open: ReturnType<typeof vi.fn> };
  let confirm: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    const snackBarStub = { open: vi.fn() };
    const confirmStub = vi.fn(() => of(true));
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, storeContextInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: snackBarStub },
        { provide: ConfirmDialogService, useValue: { confirm: confirmStub } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) =>
              code === 'sales:tax-document:retry' || code === 'sales:pos:operate',
          },
        },
      ],
    });

    const storeContext = TestBed.inject(StoreContextService);
    storeContext.setStore(10);
    storeContext.setTerminal(2);

    snackBar = snackBarStub;
    confirm = confirmStub;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  /** Crea la pantalla y responde la carga inicial de la tabla. */
  function createScreen(initialContent: TaxDocumentResponse[] = []): {
    el: HTMLElement;
    detect: () => void;
  } {
    const fixture = TestBed.createComponent(TaxDocuments);
    fixture.detectChanges();

    const req = http!.expectOne((r) => r.url === '/api/v1/tax-documents');
    expect(req.request.method).toBe('GET');
    req.flush(
      jsonBlob({
        content: initialContent,
        page: 0,
        size: 20,
        sort: ['id,desc'],
        totalElements: initialContent.length,
        totalPages: initialContent.length > 0 ? 1 : 0,
      }),
    );

    const el = fixture.nativeElement as HTMLElement;
    return { el, detect: () => fixture.detectChanges() };
  }

  it('carga la lista de documentos con estado en español y reintento solo para ERROR', async () => {
    const { el, detect } = createScreen([
      sampleDocument({ id: 39, folio: 39, status: 'ISSUED' }),
      sampleDocument({ id: 41, folio: 41, status: 'ERROR', attempts: 2, lastError: 'SII caído' }),
    ]);

    // Las filas renderizan sus botones de acción: esperamos a que la tabla pinte ambos documentos.
    await vi.waitFor(() => {
      detect();
      expect(el.querySelector('[data-testid="doc-detail-btn-39"]')).not.toBeNull();
      expect(el.querySelector('[data-testid="doc-detail-btn-41"]')).not.toBeNull();
    });

    // Estado traducido al español en las celdas de la tabla.
    expect(el.textContent).toContain('Emitido');
    expect(el.textContent).toContain('Error');

    // El reintento aparece solo para el documento en ERROR.
    expect(el.querySelector('[data-testid="doc-retry-btn-39"]')).toBeNull();
    expect(el.querySelector('[data-testid="doc-retry-btn-41"]')).not.toBeNull();
  });

  it('reintenta la emisión con confirmación previa y recarga la lista', async () => {
    const { el, detect } = createScreen([
      sampleDocument({ id: 41, folio: 41, status: 'ERROR', attempts: 2 }),
    ]);

    await vi.waitFor(() => {
      detect();
      expect(el.querySelector('[data-testid="doc-retry-btn-41"]')).not.toBeNull();
    });

    el.querySelector<HTMLButtonElement>('[data-testid="doc-retry-btn-41"]')!.click();

    // Confirmación previa obligatoria (ConfirmDialogService sobreescrito con `of(true)`).
    await vi.waitFor(() => {
      detect();
      expect(confirm).toHaveBeenCalledTimes(1);
      expect(String(confirm.mock.calls[0]?.[0]?.title)).toBe('Reintentar emisión');
    });

    const retryReq = http!.expectOne((r) => r.url === '/api/v1/tax-documents/41/retry');
    expect(retryReq.request.method).toBe('POST');
    retryReq.flush(jsonBlob(sampleDocument({ id: 41, folio: 41, status: 'ISSUED', attempts: 3 })));

    await vi.waitFor(() => {
      detect();
      expect(snackBar.open).toHaveBeenCalledTimes(1);
      expect(String(snackBar.open.mock.calls[0]?.[0])).toContain('Emitido');
    });

    // Tras el reintento la pantalla recarga la lista (nuevo GET /tax-documents).
    const reloadReq = http!.expectOne((r) => r.url === '/api/v1/tax-documents');
    reloadReq.flush(jsonBlob(emptyPage()));
  });

  it('muestra el 204 como estado pendiente (sin error) y permite enviar el comprobante', async () => {
    const { el, detect } = createScreen();

    const saleIdInput = el.querySelector<HTMLInputElement>('[data-testid="sale-id-input"]')!;
    expect(saleIdInput).not.toBeNull();
    saleIdInput.value = '152';
    saleIdInput.dispatchEvent(new Event('input'));
    detect();

    el.querySelector<HTMLButtonElement>('[data-testid="sale-lookup-btn"]')!.click();

    const req = http!.expectOne((r) => r.url === '/api/v1/sales/152/tax-document');
    expect(req.request.method).toBe('GET');

    // 204 No Content: la boleta sigue pendiente de emisión asíncrona.
    req.flush(null, { status: 204, statusText: 'No Content' });

    await vi.waitFor(() => {
      detect();
      expect(el.querySelector('[data-testid="sale-tax-doc-pending"]')).not.toBeNull();
    });

    // Es un estado informativo: nunca un banner de error.
    expect(el.querySelector('[data-testid="sale-tax-doc-error"]')).toBeNull();
    expect(el.textContent).toContain('aún no se emite');

    // Con permiso de POS, se puede enviar el comprobante por correo de todos modos.
    expect(el.querySelector('[data-testid="sale-email-btn"]')).not.toBeNull();
  });

  it('muestra el documento de la venta cuando ya fue emitido', async () => {
    const { el, detect } = createScreen();

    const saleIdInput = el.querySelector<HTMLInputElement>('[data-testid="sale-id-input"]')!;
    saleIdInput.value = '152';
    saleIdInput.dispatchEvent(new Event('input'));
    detect();

    el.querySelector<HTMLButtonElement>('[data-testid="sale-lookup-btn"]')!.click();

    const req = http!.expectOne((r) => r.url === '/api/v1/sales/152/tax-document');
    req.flush(jsonBlob(sampleDocument()));

    await vi.waitFor(() => {
      detect();
      expect(el.querySelector('[data-testid="sale-tax-doc-result"]')).not.toBeNull();
    });

    const result = el.querySelector<HTMLElement>('[data-testid="sale-tax-doc-result"]')!;
    // El bloque @if introduce espacios extra: se afirman las partes por separado.
    expect(result.textContent).toContain('Boleta');
    expect(result.textContent).toContain('folio 39');
    expect(result.textContent).toContain('Emitido');
    expect(result.textContent).toContain('Venta #152');
    expect(el.querySelector('[data-testid="sale-tax-doc-pending"]')).toBeNull();
  });

  it('muestra el error de la consulta por venta (404) sin estado pendiente', async () => {
    const { el, detect } = createScreen();

    const saleIdInput = el.querySelector<HTMLInputElement>('[data-testid="sale-id-input"]')!;
    saleIdInput.value = '999';
    saleIdInput.dispatchEvent(new Event('input'));
    detect();

    el.querySelector<HTMLButtonElement>('[data-testid="sale-lookup-btn"]')!.click();

    const req = http!.expectOne((r) => r.url === '/api/v1/sales/999/tax-document');
    req.flush(
      jsonBlob({
        type: 'about:blank',
        title: 'Venta no encontrada',
        status: 404,
        detail: 'No existe la venta 999.',
        code: 'NOT_FOUND',
      }),
      { status: 404, statusText: 'Not Found' },
    );

    await vi.waitFor(() => {
      detect();
      const banner = el.querySelector<HTMLElement>('[data-testid="sale-tax-doc-error"]');
      expect(banner).not.toBeNull();
      expect(banner?.textContent).toContain('No existe la venta 999.');
    });

    expect(el.querySelector('[data-testid="sale-tax-doc-pending"]')).toBeNull();
    expect(el.querySelector('[data-testid="sale-tax-doc-result"]')).toBeNull();
  });
});
