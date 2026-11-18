import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import type { MeResponse } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { buildTokenResponse } from '../../../core/auth/auth.testing';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PinSwitchDialog } from './pin-switch-dialog';

describe('PinSwitchDialog', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  let storeContext: StoreContextService;
  let result: Promise<MeResponse | undefined>;

  async function openDialog(withTerminal = true): Promise<void> {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    storeContext = TestBed.inject(StoreContextService);

    auth.login({ username: 'cajero1', password: 'Secret123' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(buildTokenResponse());
    storeContext.setStore(10);
    storeContext.setTerminal(withTerminal ? 5 : null);

    const ref = TestBed.inject(MatDialog).open<PinSwitchDialog, void, MeResponse | undefined>(
      PinSwitchDialog,
    );
    result = firstValueFrom(ref.afterClosed());
    await vi.waitFor(() => expect(document.querySelector('pos-pin-switch-dialog')).not.toBeNull());
    TestBed.tick();
  }

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
    http.verify();
  });

  function typeField(selector: string, value: string): void {
    const input = document.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('cambia de cajero con PIN y actualiza la sesión a scope POS', async () => {
    await openDialog(true);

    typeField('input[formcontrolname="username"]', 'cajero2');
    typeField('input[formcontrolname="pin"]', '4829');
    document.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();

    const req = http.expectOne('/api/v1/auth/pin-login');
    expect(req.request.body).toEqual({ username: 'cajero2', pin: '4829' });
    req.flush(
      buildTokenResponse({
        scope: 'POS',
        permissions: ['sales:pos:operate'],
        user: { id: 2, username: 'cajero2', fullName: 'Cajero Dos' },
      }),
    );

    const closedUser = await result;
    expect(closedUser?.username).toBe('cajero2');
    expect(auth.user()?.fullName).toBe('Cajero Dos');
    expect(auth.scope()).toBe('POS');
  });

  it('muestra error y limpia el PIN cuando el PIN está bloqueado o es incorrecto', async () => {
    await openDialog(true);

    typeField('input[formcontrolname="username"]', 'cajero2');
    typeField('input[formcontrolname="pin"]', '0000');
    document.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();

    http
      .expectOne('/api/v1/auth/pin-login')
      .flush(
        { code: 'PIN_LOCKED', detail: 'PIN bloqueado' },
        { status: 422, statusText: 'Unprocessable Entity' },
      );

    await vi.waitFor(() => {
      const text = document.body.textContent ?? '';
      expect(text).toContain('El PIN está bloqueado temporalmente');
    });
    const pinInput = document.querySelector<HTMLInputElement>('input[formcontrolname="pin"]')!;
    expect(pinInput.value).toBe('');
  });

  it('deshabilita el envío si no hay una terminal activa en la sesión', async () => {
    await openDialog(false);

    const submit = document.querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(submit.disabled).toBe(true);
    expect(document.body.textContent).toContain('No hay una terminal activa asociada');
  });
});
