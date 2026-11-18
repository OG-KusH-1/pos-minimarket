import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../../api/api-configuration';
import { TicketResponse } from '../../../../api/models/ticket-response';
import { errorInterceptor } from '../../../../core/http/error.interceptor';
import { PosTicketDialog, PosTicketDialogData } from './pos-ticket-dialog';
import { TicketComponent } from './ticket.component';
import { IframeTicketPrinter, TICKET_80MM_PAGE_CSS } from './ticket.service';

function jsonBlob(payload: unknown, type = 'application/json'): Blob {
  return new Blob([JSON.stringify(payload)], { type });
}

function buildTicket(overrides: Partial<TicketResponse> = {}): TicketResponse {
  return {
    number: 'V-00501',
    soldAt: '2026-11-14T21:10:00Z',
    paperWidthMm: 80,
    reprintCount: 0,
    showTaxBreakdown: true,
    rounding: -5,
    change: 10,
    company: {
      tradeName: 'Minimarket Don Pedro',
      legalName: 'Comercial Don Pedro SpA',
      rut: '76123456-K',
      address: 'Av. Providencia 123',
      commune: 'Providencia',
      city: 'Santiago',
      phone: '+56 2 2222 1111',
    },
    store: {
      id: 10,
      code: 'SUC-01',
      name: 'Sucursal Centro',
    },
    terminal: { id: 2, code: 'CAJA-01', name: 'Caja 1' },
    cashier: { id: 4, username: 'cajero1', fullName: 'Camila Soto' },
    lines: [
      {
        sku: 'SKU-1',
        name: 'Aceite Maravilla 1L',
        quantity: 1,
        unitPrice: 1995,
        discount: 0,
        total: 1995,
      },
    ],
    totals: {
      subtotalGross: 1995,
      discountTotal: 0,
      totalGross: 1995,
      netTotal: 1676,
      taxTotal: 319,
      exemptTotal: 0,
      totalPayable: 1990,
      paidTotal: 2000,
    },
    payments: [
      {
        id: 90,
        method: 'CASH',
        amount: 1990,
        tenderedAmount: 2000,
        changeAmount: 10,
      },
    ],
    headerLines: ['¡Bienvenido!'],
    footerLines: ['Gracias por su compra'],
    ...overrides,
  };
}

describe('TicketComponent, IframeTicketPrinter y PosTicketDialog', () => {
  let http: HttpTestingController | undefined;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    document
      .querySelectorAll('iframe[data-testid="ticket-print-iframe"]')
      .forEach((el) => el.remove());
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

  it('declara @page { size: 80mm auto } en estilos del componente y en el iframe oculto de impresión', () => {
    const fixture = TestBed.createComponent(TicketComponent);
    fixture.componentRef.setInput('ticket', buildTicket({ copyLabel: 'COPIA' }));
    fixture.detectChanges();

    const hostEl = fixture.nativeElement as HTMLElement;
    expect(hostEl.textContent).toContain('Minimarket Don Pedro');
    expect(hostEl.textContent).toContain('V-00501');
    expect(hostEl.textContent).toContain('COPIA');
    expect(hostEl.textContent).toContain('Aceite Maravilla 1L');
    expect(hostEl.textContent).toContain('$1.990');
    expect(hostEl.textContent).toContain('VUELTO');

    const headStyles = Array.from(document.querySelectorAll('style'))
      .map((style) => style.textContent ?? '')
      .join('\n');
    expect(headStyles).toContain('80mm auto');
    expect(TICKET_80MM_PAGE_CSS).toContain('@page');
    expect(TICKET_80MM_PAGE_CSS).toContain('size: 80mm auto');

    const printer = TestBed.inject(IframeTicketPrinter);
    const printed = printer.printElement(hostEl, 'Ticket V-00501');
    expect(printed).toBe(true);

    const iframe = document.querySelector<HTMLIFrameElement>(
      'iframe[data-testid="ticket-print-iframe"]',
    );
    expect(iframe).not.toBeNull();
    const iframeStyle = iframe?.contentDocument?.querySelector('style')?.textContent ?? '';
    expect(iframeStyle).toContain('@page');
    expect(iframeStyle).toContain('size: 80mm auto');
    expect(iframe?.contentDocument?.body.textContent).toContain('V-00501');
  }, 20000);

  it('carga GET /sales/{id}/ticket, autoimprime y reimprime COPIA con override de supervisor', async () => {
    const printer = TestBed.inject(IframeTicketPrinter);
    const printSpy = vi.spyOn(printer, 'printElement').mockReturnValue(true);

    const dialogData: PosTicketDialogData = {
      saleId: 501,
      saleNumber: 'V-00501',
      autoPrint: true,
    };

    TestBed.inject(MatDialog).open(PosTicketDialog, { data: dialogData });

    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelector('pos-ticket-dialog')).not.toBeNull();
    });

    const ticketReq = http!.expectOne('/api/v1/sales/501/ticket');
    expect(ticketReq.request.method).toBe('GET');
    ticketReq.flush(jsonBlob(buildTicket()));

    await vi.waitFor(() => {
      TestBed.tick();
      expect(printSpy).toHaveBeenCalledTimes(1);
    });

    const reprintBtn = document.querySelector<HTMLButtonElement>(
      'pos-ticket-dialog [data-testid="ticket-reprint-btn"]',
    )!;
    reprintBtn.click();

    const firstReprint = http!.expectOne('/api/v1/sales/501/reprint');
    expect(firstReprint.request.method).toBe('POST');
    firstReprint.flush(
      jsonBlob(
        {
          code: 'OVERRIDE_REQUIRED',
          detail: 'Requiere supervisor para reimprimir ticket.',
          action: 'RECEIPT_REPRINT',
          context: { saleId: 501 },
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
    document.querySelector<HTMLButtonElement>('pos-override-dialog button[type="submit"]')!.click();

    http!.expectOne('/api/v1/auth/overrides').flush({ overrideToken: 'tok-reprint-1' });

    const retryReprint = await vi.waitFor(() => http!.expectOne('/api/v1/sales/501/reprint'));
    expect(retryReprint.request.headers.get('X-Override-Token')).toBe('tok-reprint-1');
    retryReprint.flush(jsonBlob(buildTicket({ copyLabel: 'COPIA', reprintCount: 1 })));

    await vi.waitFor(() => {
      TestBed.tick();
      expect(
        document.querySelector('pos-ticket-dialog [data-testid="ticket-copy-label"]')?.textContent,
      ).toContain('COPIA');
      expect(printSpy).toHaveBeenCalledTimes(2);
    });
  }, 20000);
});
