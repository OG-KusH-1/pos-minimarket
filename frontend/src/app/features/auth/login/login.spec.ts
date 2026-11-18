import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { buildTokenResponse } from '../../../core/auth/auth.testing';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { sanitizeReturnUrl } from '../auth-errors';
import { Login } from './login';

@Component({ template: '' })
class DummyPage {}

describe('Login', () => {
  let http: HttpTestingController;
  let router: Router;
  let storeContext: StoreContextService;

  function setup(returnUrl: string | null = null) {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: '', component: DummyPage },
          { path: 'cambiar-clave', component: DummyPage },
          { path: 'pos/venta', component: DummyPage },
        ]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: convertToParamMap(returnUrl ? { returnUrl } : {}),
            },
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    storeContext = TestBed.inject(StoreContextService);
  }

  afterEach(() => {
    http.verify();
  });

  function flushJson(req: TestRequest, body: object[]): void {
    if (req.request.responseType === 'blob') {
      req.flush(new Blob([JSON.stringify(body)], { type: 'application/json' }));
    } else {
      req.flush(body);
    }
  }

  function fillCredentials(root: HTMLElement, username: string, password: string): void {
    const userInput = root.querySelector<HTMLInputElement>('input[formcontrolname="username"]')!;
    const passInput = root.querySelector<HTMLInputElement>('input[formcontrolname="password"]')!;
    userInput.value = username;
    userInput.dispatchEvent(new Event('input'));
    passInput.value = password;
    passInput.dispatchEvent(new Event('input'));
  }

  it('completa el flujo login → selección de local → selección de terminal y navega a returnUrl', async () => {
    setup('/pos/venta?tab=1');
    const navigateSpy = vi.spyOn(router, 'navigateByUrl');
    const fixture = TestBed.createComponent(Login);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    fillCredentials(root, 'cajero1', 'Secret123');
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    http.expectOne('/api/v1/auth/login').flush(buildTokenResponse());
    await vi.waitFor(() => {
      const req = http.expectOne('/api/v1/auth/me/stores');
      flushJson(req, [
        { id: 10, code: 'L01', name: 'Local Centro', active: true },
        { id: 20, code: 'L02', name: 'Local Norte', active: true },
      ]);
    });
    await vi.waitFor(() => {
      TestBed.tick();
      expect(root.textContent).toContain('Seleccionar local');
    });
    expect(root.textContent).toContain('Local Centro');
    expect(root.textContent).toContain('Local Norte');

    // Selecciona Local Norte (id 20) y confirma
    const radios = root.querySelectorAll<HTMLElement>('mat-radio-button');
    const secondRadioInput = radios.item(1)?.querySelector<HTMLInputElement>('input');
    expect(secondRadioInput).toBeTruthy();
    secondRadioInput!.click();
    await fixture.whenStable();
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    await vi.waitFor(() => {
      const req = http.expectOne('/api/v1/stores/20/terminals');
      flushJson(req, [
        { id: 101, storeId: 20, code: 'CAJA-1', name: 'Caja 1', active: true },
        { id: 102, storeId: 20, code: 'CAJA-2', name: 'Caja 2', active: true },
      ]);
    });
    await vi.waitFor(() => {
      TestBed.tick();
      expect(root.textContent).toContain('Seleccionar terminal');
    });

    expect(storeContext.storeId()).toBe(20);
    expect(root.textContent).toContain('Caja 1');

    // Confirma la terminal preseleccionada (101)
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    await vi.waitFor(() => {
      const req = http.expectOne('/api/v1/auth/session/terminal');
      expect(req.request.body).toEqual({ terminalId: 101 });
      req.flush(null);
    });
    await vi.waitFor(() => {
      expect(storeContext.terminalId()).toBe(101);
      expect(navigateSpy).toHaveBeenCalledWith('/pos/venta?tab=1');
    });
  });

  it('auto-selecciona el local cuando el usuario tiene solo uno asignado', async () => {
    setup();
    const fixture = TestBed.createComponent(Login);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    fillCredentials(root, 'cajero1', 'Secret123');
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    http.expectOne('/api/v1/auth/login').flush(buildTokenResponse());
    await vi.waitFor(() => {
      const req = http.expectOne('/api/v1/auth/me/stores');
      flushJson(req, [{ id: 10, code: 'L01', name: 'Local Centro', active: true }]);
    });
    await vi.waitFor(() => {
      const req = http.expectOne('/api/v1/stores/10/terminals');
      flushJson(req, [{ id: 5, storeId: 10, code: 'POS-1', name: 'Caja Principal', active: true }]);
    });
    await vi.waitFor(() => {
      TestBed.tick();
      expect(root.textContent).toContain('Seleccionar terminal');
    });

    expect(storeContext.storeId()).toBe(10);
    expect(root.textContent).toContain('Caja Principal');
  });

  it('redirige a /cambiar-clave cuando mustChangePassword es true', async () => {
    setup('/pos/venta');
    const navigateSpy = vi.spyOn(router, 'navigateByUrl');
    const fixture = TestBed.createComponent(Login);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    fillCredentials(root, 'cajero1', 'Temp1234');
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    http.expectOne('/api/v1/auth/login').flush(buildTokenResponse({ mustChangePassword: true }));
    await fixture.whenStable();

    expect(navigateSpy).toHaveBeenCalledWith('/cambiar-clave');
  });

  it('muestra error en pantalla y limpia la contraseña cuando fallan las credenciales', async () => {
    setup();
    const fixture = TestBed.createComponent(Login);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    fillCredentials(root, 'cajero1', 'MalaClave');
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    http
      .expectOne('/api/v1/auth/login')
      .flush(
        { code: 'INVALID_CREDENTIALS', detail: 'Credenciales inválidas' },
        { status: 401, statusText: 'Unauthorized' },
      );

    await vi.waitFor(() => {
      TestBed.tick();
      const alert = root.querySelector('[role="alert"]');
      expect(alert?.textContent).toContain('Usuario o contraseña incorrectos.');
    });
    const passInput = root.querySelector<HTMLInputElement>('input[formcontrolname="password"]')!;
    expect(passInput.value).toBe('');
  });

  describe('sanitizeReturnUrl (L-06)', () => {
    it('acepta rutas internas válidas y rechaza URLs externas o protocol-relative', () => {
      expect(sanitizeReturnUrl('/pos/venta')).toBe('/pos/venta');
      expect(sanitizeReturnUrl('/caja?turno=1')).toBe('/caja?turno=1');
      expect(sanitizeReturnUrl('//evil.example.com')).toBe('/');
      expect(sanitizeReturnUrl('https://evil.example.com')).toBe('/');
      expect(sanitizeReturnUrl('/\\evil.example.com')).toBe('/');
      expect(sanitizeReturnUrl('/login')).toBe('/');
      expect(sanitizeReturnUrl(null)).toBe('/');
    });
  });
});
