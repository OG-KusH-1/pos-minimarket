import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { SKIP_ERROR_NOTIFICATION } from '../../../core/http/http-context-tokens';
import { OverrideDialogData, OverrideResponse, describeOverrideContext } from './override.models';
import { OverrideDialog } from './override-dialog';

const DATA: OverrideDialogData = {
  action: 'DISCOUNT_ITEM_OVER_LIMIT',
  context: { lineIndex: 0, productId: 7, percent: 12, amount: 1500, cartHash: 'abc' },
};
const URL = '/api/v1/auth/overrides';

function query<T extends Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) {
    throw new Error(`No se encontró ${selector}`);
  }
  return element;
}

function type(selector: string, value: string): void {
  const input = query<HTMLInputElement | HTMLTextAreaElement>(selector);
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

function submitButton(): HTMLButtonElement {
  return query<HTMLButtonElement>('button[type="submit"]');
}

describe('OverrideDialog', () => {
  let http: HttpTestingController;
  let result: Promise<OverrideResponse | undefined>;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    const ref = TestBed.inject(MatDialog).open<
      OverrideDialog,
      OverrideDialogData,
      OverrideResponse | undefined
    >(OverrideDialog, { data: DATA });
    result = firstValueFrom(ref.afterClosed());
    await vi.waitFor(() => expect(document.querySelector('pos-override-dialog')).not.toBeNull());
    TestBed.tick();
  });

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
    http.verify();
  });

  it('muestra la acción y el contexto en lenguaje claro, sin el hash técnico', () => {
    const text = document.body.textContent ?? '';
    expect(text).toContain('Descuento en un producto sobre el límite permitido');
    expect(text).toContain('12 %');
    expect(text).toContain('$1.500');
    expect(text).not.toContain('abc');
  });

  it('no envía nada si el formulario es inválido', async () => {
    submitButton().click();

    await vi.waitFor(() => expect(document.body.textContent).toContain('Ingresa el usuario'));
    http.expectNone(URL);
  });

  it('exige un PIN de 4 a 6 dígitos', async () => {
    type('input[formcontrolname="supervisorUsername"]', 'jefe');
    type('input[formcontrolname="secret"]', '12');
    submitButton().click();

    await vi.waitFor(() => expect(document.body.textContent).toContain('4 a 6 dígitos'));
    http.expectNone(URL);
  });

  it('llama a POST /auth/overrides con el cuerpo correcto y devuelve el token', async () => {
    type('input[formcontrolname="supervisorUsername"]', ' jefe ');
    type('input[formcontrolname="secret"]', '1234');
    type('textarea[formcontrolname="reason"]', 'Cliente frecuente');
    submitButton().click();

    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      action: 'DISCOUNT_ITEM_OVER_LIMIT',
      context: DATA.context,
      supervisorUsername: 'jefe',
      method: 'PIN',
      secret: '1234',
      reason: 'Cliente frecuente',
    });
    expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(true);
    const response: OverrideResponse = {
      overrideToken: 'tok-1',
      authorizationId: 9,
      expiresAt: '2026-01-01T00:00:00Z',
    };
    req.flush(response);

    expect(await result).toEqual(response);
  });

  it('omite el motivo si está vacío', () => {
    type('input[formcontrolname="supervisorUsername"]', 'jefe');
    type('input[formcontrolname="secret"]', '1234');
    submitButton().click();

    const req = http.expectOne(URL);
    expect(req.request.body).not.toHaveProperty('reason');
    req.flush({ overrideToken: 't', authorizationId: 1, expiresAt: 'x' });
  });

  it('muestra el error en el propio diálogo, lo mantiene abierto y limpia la credencial', async () => {
    type('input[formcontrolname="supervisorUsername"]', 'jefe');
    type('input[formcontrolname="secret"]', '9999');
    submitButton().click();

    http
      .expectOne(URL)
      .flush(
        { status: 422, code: 'OVERRIDE_LIMIT_EXCEEDED', detail: 'x' },
        { status: 422, statusText: 'Unprocessable Entity' },
      );

    await vi.waitFor(() => {
      expect(query('[role="alert"]').textContent).toContain('límite del supervisor');
    });
    expect(document.querySelector('pos-override-dialog')).not.toBeNull();
    expect(query<HTMLInputElement>('input[formcontrolname="secret"]').value).toBe('');
  });

  it('Escape cancela igual que Cancelar cuando no se está enviando', async () => {
    query('input[formcontrolname="supervisorUsername"]').dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }),
    );

    expect(await result).toBeUndefined();
    http.expectNone(URL);
  });

  it('no se puede cerrar mientras se envía y vuelve a poder cerrarse tras un error', async () => {
    const dialogs = TestBed.inject(MatDialog);
    type('input[formcontrolname="supervisorUsername"]', 'jefe');
    type('input[formcontrolname="secret"]', '1234');
    submitButton().click();

    const req = http.expectOne(URL);
    expect(dialogs.openDialogs[0]?.disableClose).toBe(true);
    req.flush({ code: 'OVERRIDE_INVALID' }, { status: 422, statusText: 'Unprocessable Entity' });

    await vi.waitFor(() => expect(dialogs.openDialogs[0]?.disableClose).toBe(false));
  });

  it('devuelve undefined al cancelar y no llama al servidor', async () => {
    const cancel = Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes('Cancelar'),
    );
    cancel?.click();

    expect(await result).toBeUndefined();
    http.expectNone(URL);
  });
});

describe('describeOverrideContext', () => {
  it('omite nulos, vacíos y cartHash, y deja las claves desconocidas con su nombre', () => {
    expect(
      describeOverrideContext({ saleId: 5, customerId: null, note: '', cartHash: 'h', extra: 'v' }),
    ).toEqual([
      { label: 'Venta (id)', value: '5' },
      { label: 'extra', value: 'v' },
    ]);
  });
});
