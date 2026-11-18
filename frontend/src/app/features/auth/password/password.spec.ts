import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { authGuard } from '../../../core/auth/auth.guard';
import { AuthService } from '../../../core/auth/auth.service';
import { buildTokenResponse } from '../../../core/auth/auth.testing';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { Password } from './password';

@Component({ template: '' })
class DummyPage {}

describe('Password y guard de mustChangePassword', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  let router: Router;
  const snackBarOpen = vi.fn();

  beforeEach(() => {
    localStorage.clear();
    snackBarOpen.mockReset();
    TestBed.configureTestingModule({
      imports: [Password],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: '', component: DummyPage },
          { path: 'login', component: DummyPage },
          { path: 'cambiar-clave', component: Password },
          { path: 'pos/venta', component: DummyPage },
        ]),
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    http.verify();
  });

  function signIn(mustChangePassword: boolean): void {
    auth.login({ username: 'cajero1', password: 'Temp1234' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(buildTokenResponse({ mustChangePassword }));
  }

  function fillPasswordForm(
    root: HTMLElement,
    currentPassword: string,
    newPassword: string,
    confirmPassword: string,
  ): void {
    const currentInput = root.querySelector<HTMLInputElement>(
      'input[formcontrolname="currentPassword"]',
    )!;
    const newInput = root.querySelector<HTMLInputElement>('input[formcontrolname="newPassword"]')!;
    const confirmInput = root.querySelector<HTMLInputElement>(
      'input[formcontrolname="confirmPassword"]',
    )!;
    currentInput.value = currentPassword;
    currentInput.dispatchEvent(new Event('input'));
    newInput.value = newPassword;
    newInput.dispatchEvent(new Event('input'));
    confirmInput.value = confirmPassword;
    confirmInput.dispatchEvent(new Event('input'));
  }

  it('el guard bloquea la navegación mientras mustChangePassword sea true y la habilita tras cambiarla', async () => {
    signIn(true);

    const blockedResult = TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/pos/venta' } as RouterStateSnapshot),
    );
    expect(blockedResult).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(blockedResult as UrlTree)).toBe('/cambiar-clave');

    const navigateSpy = vi.spyOn(router, 'navigateByUrl');
    const fixture = TestBed.createComponent(Password);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('Debes cambiar tu contraseña temporal');

    fillPasswordForm(root, 'Temp1234', 'NuevaClave99', 'NuevaClave99');
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const req = http.expectOne('/api/v1/auth/me/password');
    expect(req.request.body).toEqual({
      currentPassword: 'Temp1234',
      newPassword: 'NuevaClave99',
    });
    req.flush(null);
    await vi.waitFor(() => {
      expect(auth.mustChangePassword()).toBe(false);
    });
    expect(snackBarOpen).toHaveBeenCalledOnce();
    expect(navigateSpy).toHaveBeenCalledWith('/');

    const allowedResult = TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/pos/venta' } as RouterStateSnapshot),
    );
    expect(allowedResult).toBe(true);
  });

  it('muestra error en pantalla si el servidor rechaza la política o clave actual', async () => {
    signIn(true);
    const fixture = TestBed.createComponent(Password);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    fillPasswordForm(root, 'Incorrecta1', 'NuevaClave99', 'NuevaClave99');
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    http
      .expectOne('/api/v1/auth/me/password')
      .flush(
        { code: 'INVALID_CREDENTIALS', detail: 'Clave actual incorrecta' },
        { status: 401, statusText: 'Unauthorized' },
      );

    await vi.waitFor(() => {
      TestBed.tick();
      expect(root.querySelector('[role="alert"]')?.textContent).toContain(
        'La contraseña actual es incorrecta.',
      );
    });
    expect(auth.mustChangePassword()).toBe(true);
  });
});

describe('Password con errorInterceptor activo', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  const snackBarOpen = vi.fn();

  beforeEach(() => {
    localStorage.clear();
    snackBarOpen.mockReset();
    TestBed.configureTestingModule({
      imports: [Password],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideRouter([{ path: 'cambiar-clave', component: Password }]),
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => {
    http.verify();
  });

  it('recupera PASSWORD_POLICY desde ApiError.cause cuando changePassword usa responseType text', async () => {
    auth.login({ username: 'cajero1', password: 'Temp1234' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(buildTokenResponse({ mustChangePassword: true }));

    const fixture = TestBed.createComponent(Password);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    const currentInput = root.querySelector<HTMLInputElement>(
      'input[formcontrolname="currentPassword"]',
    )!;
    const newInput = root.querySelector<HTMLInputElement>('input[formcontrolname="newPassword"]')!;
    const confirmInput = root.querySelector<HTMLInputElement>(
      'input[formcontrolname="confirmPassword"]',
    )!;
    currentInput.value = 'Temp1234';
    currentInput.dispatchEvent(new Event('input'));
    newInput.value = 'NuevaClave99';
    newInput.dispatchEvent(new Event('input'));
    confirmInput.value = 'NuevaClave99';
    confirmInput.dispatchEvent(new Event('input'));

    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    http
      .expectOne('/api/v1/auth/me/password')
      .flush(JSON.stringify({ code: 'PASSWORD_POLICY', detail: 'Clave débil' }), {
        status: 422,
        statusText: 'Unprocessable Entity',
      });

    await vi.waitFor(() => {
      TestBed.tick();
      expect(root.querySelector('[role="alert"]')?.textContent).toContain(
        'La nueva contraseña no cumple la política de seguridad',
      );
    });
  });
});
