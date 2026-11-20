import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../../api/api-configuration';
import { TaxDocumentResponse } from '../../../../api/models/tax-document-response';
import { TicketResponse } from '../../../../api/models/ticket-response';
import { errorInterceptor } from '../../../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../../../core/http/idempotency.interceptor';
import { storeContextInterceptor } from '../../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../../core/store-context/store-context.service';
import { PosTicketDialog, PosTicketDialogData } from './pos-ticket-dialog';

/** Envuelve un payload como `Blob` JSON (el cliente generado usa `responseType: 'blob'`). */
function jsonBlob(payload: unknown, type = 'application/json'): Blob {
  return new Blob([JSON.stringify(payload)], { type });
}

/** Ticket mínimo suficiente para que el diálogo habilite sus acciones. */
function buildTicket(overrides: Partial<TicketResponse> = {}): TicketResponse {
  return {
    number: 'V-00152',
    lines: [
      { sku: 'SKU-1', name: 'Aceite Maravilla 1L', quantity: 1, unitPrice: 1995, total: 1995 },
    ],
    totals: { subtotalGross: 1995, totalGross: 1995, totalPayable: 1990 },
    ...overrides,
  };
}

/** Documento tributario emitido de ejemplo (boleta 39 con folio y timbre). */
function issuedDocument(overrides: Partial<TaxDocumentResponse> = {}): TaxDocumentResponse {
  return {
    id: 39,
    documentType: 'BOLETA',
    typeLabel: 'Boleta electrónica',
    stampText: 'TIMBRE-39',
    siiTypeCode: 39,
    folio: 39,
    status: 'ISSUED',
    saleId: 152,
    attempts: 1,
    issuedAt: '2026-11-18T12:30:00-03:00',
    ...overrides,
  };
}

describe('PosTicketDialog: correo y documento tributario (cableado T77)', () => {
  let http: HttpTestingController | undefined;
  let snackBar: { open: ReturnType<typeof vi.fn> };
  let dialog: MatDialog;

  beforeEach(() => {
    const snackBarStub = { open: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(
          withInterceptors([errorInterceptor, idempotencyInterceptor, storeContextInterceptor]),
        ),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: snackBarStub },
      ],
    });

    const storeContext = TestBed.inject(StoreContextService);
    storeContext.setStore(10);
    storeContext.setTerminal(2);

    snackBar = snackBarStub;
    dialog = TestBed.inject(MatDialog);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    document
      .querySelectorAll('iframe[data-testid="ticket-print-iframe"]')
      .forEach((el) => el.remove());
    try {
      dialog.closeAll();
    } catch {
      // Sin diálogos abiertos
    }
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  function openTicketDialog(data: PosTicketDialogData): void {
    dialog.open(PosTicketDialog, { data });
  }

  /** Espera el diálogo y desarrolla las dos cargas iniciales: ticket y documento tributario. */
  async function openAndFlush(
    data: PosTicketDialogData,
    taxDocument: TaxDocumentResponse | null,
  ): Promise<void> {
    openTicketDialog(data);
    await vi.waitFor(() => {
      expect(document.querySelector('pos-ticket-dialog')).not.toBeNull();
    });
    TestBed.tick();

    const ticketReq = http!.expectOne(`/api/v1/sales/${data.saleId}/ticket`);
    ticketReq.flush(jsonBlob(buildTicket({ number: data.saleNumber ?? 'V-00152' })));

    if (taxDocument) {
      http!.expectOne(`/api/v1/sales/${data.saleId}/tax-document`).flush(jsonBlob(taxDocument));
    } else {
      // 204 No Content: la boleta sigue pendiente de emisión asíncrona.
      http!
        .expectOne(`/api/v1/sales/${data.saleId}/tax-document`)
        .flush(null, { status: 204, statusText: 'No Content' });
    }

    // Espera una condición fuerte: el botón de imprimir solo se habilita cuando el
    // comprobante terminó de cargar y quedó renderizado.
    await vi.waitFor(() => {
      TestBed.tick();
      const printBtn = document.querySelector<HTMLButtonElement>(
        'pos-ticket-dialog [data-testid="ticket-print-btn"]',
      );
      expect(printBtn?.disabled).toBe(false);
    });
  }

  it('muestra folio, tipo y timbre cuando el documento tributario ya fue emitido (200)', async () => {
    await openAndFlush(
      { saleId: 152, saleNumber: 'V-00152' },
      issuedDocument({ typeLabel: 'Boleta electrónica', folio: 39, stampText: 'TIMBRE-39' }),
    );

    const dialogEl = document.querySelector<HTMLElement>('pos-ticket-dialog')!;
    await vi.waitFor(() => {
      TestBed.tick();
      expect(dialogEl.querySelector('[data-testid="tax-document-issued"]')).not.toBeNull();
    });
    const issued = dialogEl.querySelector<HTMLElement>('[data-testid="tax-document-issued"]');
    expect(issued?.textContent).toContain('Boleta electrónica');
    expect(issued?.textContent).toContain('Folio 39');
    expect(issued?.textContent).toContain('TIMBRE-39');
    expect(dialogEl.querySelector('[data-testid="tax-document-pending"]')).toBeNull();
  });

  it('muestra «pendiente de emisión» cuando el documento responde 204', async () => {
    await openAndFlush({ saleId: 153, saleNumber: 'V-00153' }, null);

    const dialogEl = document.querySelector<HTMLElement>('pos-ticket-dialog')!;
    await vi.waitFor(() => {
      TestBed.tick();
      expect(dialogEl.querySelector('[data-testid="tax-document-pending"]')).not.toBeNull();
    });
    const pending = dialogEl.querySelector<HTMLElement>('[data-testid="tax-document-pending"]');
    expect(pending?.textContent).toContain('pendiente de emisión');
    expect(dialogEl.querySelector('[data-testid="tax-document-issued"]')).toBeNull();
  });

  it('oculta la fila del documento cuando la consulta falla, sin bloquear el ticket', async () => {
    openTicketDialog({ saleId: 154, saleNumber: 'V-00154' });
    await vi.waitFor(() => {
      expect(document.querySelector('pos-ticket-dialog')).not.toBeNull();
    });
    TestBed.tick();

    http!.expectOne('/api/v1/sales/154/ticket').flush(jsonBlob(buildTicket({ number: 'V-00154' })));
    // La consulta del documento es informativa: un error no debe romper el comprobante.
    http!
      .expectOne('/api/v1/sales/154/tax-document')
      .flush(jsonBlob({ code: 'INTERNAL_ERROR', detail: 'falla transitoria' }), {
        status: 500,
        statusText: 'Internal Server Error',
      });

    await vi.waitFor(() => {
      TestBed.tick();
      expect(
        document.querySelector('pos-ticket-dialog [data-testid="tax-document-pending"]'),
      ).toBeNull();
      expect(
        document.querySelector('pos-ticket-dialog [data-testid="tax-document-issued"]'),
      ).toBeNull();
      // El comprobante sigue operable: el botón de impresión quedó habilitado.
      expect(
        (
          document.querySelector(
            'pos-ticket-dialog [data-testid="ticket-print-btn"]',
          ) as HTMLButtonElement | null
        )?.disabled,
      ).toBe(false);
    });
  });

  it('abre el diálogo de correo desde el ticket con los datos de la venta y el correo sugerido', async () => {
    await openAndFlush(
      { saleId: 152, saleNumber: 'V-00152', defaultEmail: 'maria@correo.cl' },
      null,
    );

    const dialogEl = document.querySelector<HTMLElement>('pos-ticket-dialog')!;
    const emailBtn = dialogEl.querySelector<HTMLButtonElement>('[data-testid="ticket-email-btn"]');
    expect(emailBtn).not.toBeNull();
    expect(emailBtn?.disabled).toBe(false);
    emailBtn!.click();
    TestBed.tick();

    await vi.waitFor(() => {
      expect(document.querySelector('pos-email-ticket-dialog')).not.toBeNull();
    });

    // El diálogo de correo recibe el número legible de la venta y el correo sugerido.
    const emailDialogEl = document.querySelector<HTMLElement>('pos-email-ticket-dialog')!;
    expect(emailDialogEl.textContent).toContain('venta V-00152');
    const emailInput = emailDialogEl.querySelector<HTMLInputElement>(
      '[data-testid="pos-email-input"]',
    )!;
    expect(emailInput.value).toBe('maria@correo.cl');

    // El envío usa el identificador de la venta abierta en el ticket.
    emailDialogEl.querySelector<HTMLButtonElement>('[data-testid="pos-email-send-btn"]')!.click();
    TestBed.tick();

    const emailReq = http!.expectOne((r) => r.url === '/api/v1/sales/152/email');
    expect(emailReq.request.method).toBe('POST');
    expect(emailReq.request.body).toEqual({ email: 'maria@correo.cl' });
    emailReq.flush(null, { status: 202, statusText: 'Accepted' });

    await vi.waitFor(() => {
      expect(snackBar.open).toHaveBeenCalledTimes(1);
      expect(String(snackBar.open.mock.calls[0]?.[0])).toContain('en cola de envío');
      expect(document.querySelector('pos-email-ticket-dialog')).toBeNull();
    });
    // El ticket permanece abierto tras enviar el correo.
    expect(document.querySelector('pos-ticket-dialog')).not.toBeNull();
  }, 20000);
});
