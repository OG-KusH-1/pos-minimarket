import { HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../../api/api-configuration';
import { errorInterceptor } from '../../../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../../../core/http/idempotency.interceptor';
import { storeContextInterceptor } from '../../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../../core/store-context/store-context.service';
import { PosEmailTicketDialog } from './pos-email-ticket-dialog';

describe('PosEmailTicketDialog', () => {
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
    try {
      dialog.closeAll();
    } finally {
      try {
        http?.verify();
      } finally {
        TestBed.resetTestingModule();
      }
    }
  });

  function openDialog(): void {
    dialog.open(PosEmailTicketDialog, {
      data: { saleId: 152, saleNumber: 'V-152', defaultEmail: 'maria@correo.cl' },
    });
  }

  async function waitForDialog(): Promise<void> {
    await vi.waitFor(() => {
      expect(document.querySelector('pos-email-ticket-dialog')).not.toBeNull();
    });
    TestBed.tick();
  }

  it('prellena el correo sugerido y envía el comprobante (202) con confirmación al usuario', async () => {
    openDialog();
    await waitForDialog();

    const input = document.querySelector<HTMLInputElement>('[data-testid="pos-email-input"]')!;
    expect(input).not.toBeNull();
    expect(input.value).toBe('maria@correo.cl');

    const sendBtn = document.querySelector<HTMLButtonElement>(
      '[data-testid="pos-email-send-btn"]',
    )!;
    expect(sendBtn.disabled).toBe(false);
    sendBtn.click();
    TestBed.tick();

    const req = http!.expectOne((r) => r.url === '/api/v1/sales/152/email');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.has('Idempotency-Key')).toBe(true);
    expect(req.request.body).toEqual({ email: 'maria@correo.cl' });
    req.flush(null, { status: 202, statusText: 'Accepted' });

    await vi.waitFor(() => {
      expect(snackBar.open).toHaveBeenCalledTimes(1);
      expect(String(snackBar.open.mock.calls[0]?.[0])).toContain('en cola de envío');
    });
    await vi.waitFor(() => {
      expect(document.querySelector('pos-email-ticket-dialog')).toBeNull();
    });
  });

  it('valida el correo antes de enviar y no llama a la API si es inválido', async () => {
    openDialog();
    await waitForDialog();

    const input = document.querySelector<HTMLInputElement>('[data-testid="pos-email-input"]')!;
    input.value = 'correo-invalido';
    input.dispatchEvent(new Event('input'));
    TestBed.tick();

    const sendBtn = document.querySelector<HTMLButtonElement>(
      '[data-testid="pos-email-send-btn"]',
    )!;
    sendBtn.click();
    TestBed.tick();

    expect(snackBar.open).not.toHaveBeenCalled();
    // Sin petición en vuelo: el verificador fallaría si se hubiera llamado a la API.
    expect(document.querySelector('pos-email-ticket-dialog')).not.toBeNull();
  });

  it('muestra el mensaje del ApiError dentro del diálogo cuando el backend rechaza el envío', async () => {
    openDialog();
    await waitForDialog();

    const sendBtn = document.querySelector<HTMLButtonElement>(
      '[data-testid="pos-email-send-btn"]',
    )!;
    sendBtn.click();
    TestBed.tick();

    const req = http!.expectOne((r) => r.url === '/api/v1/sales/152/email');
    // `emailTicket` usa `responseType: 'text'`: el backend de pruebas exige cuerpo de texto.
    req.flush(
      JSON.stringify({
        type: 'about:blank',
        title: 'Venta no encontrada',
        status: 404,
        detail: 'No existe la venta 152 en este local.',
        code: 'NOT_FOUND',
      }),
      {
        status: 404,
        statusText: 'Not Found',
        headers: new HttpHeaders({ 'Content-Type': 'application/problem+json' }),
      },
    );

    await vi.waitFor(() => {
      const errorBanner = document.querySelector<HTMLElement>('[data-testid="pos-email-error"]');
      expect(errorBanner).not.toBeNull();
      expect(errorBanner?.textContent).toContain('No existe la venta 152 en este local.');
    });
    // El diálogo permanece abierto para corregir y reintentar.
    expect(document.querySelector('pos-email-ticket-dialog')).not.toBeNull();
  });
});
