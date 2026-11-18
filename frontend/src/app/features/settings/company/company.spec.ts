import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { Company } from './company';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('Company', () => {
  let fixture: ComponentFixture<Company>;
  let component: Company;
  let http: HttpTestingController;
  let snackBarOpen: ReturnType<typeof vi.fn>;

  const sampleCompany = {
    id: 1,
    rut: '76123456-0',
    legalName: 'Minimarket Central SpA',
    tradeName: 'Minimarket Central',
    businessActivity: 'Venta al por menor en comercios no especializados',
    address: 'Av. Libertador 500',
    commune: 'Santiago',
    city: 'Santiago',
    phone: '+56223334444',
    email: 'contacto@central.cl',
    timezone: 'America/Santiago',
    version: 1,
  };

  beforeEach(async () => {
    snackBarOpen = vi.fn();

    await TestBed.configureTestingModule({
      imports: [Company],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => code === 'config:company:update',
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: () => 1,
            availableStores: () => [{ id: 1, code: 'SUC1', name: 'Casa Matriz', active: true }],
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Company);
    component = fixture.componentInstance;
    fixture.detectChanges();

    flushJson(http.expectOne('/api/v1/company'), sampleCompany);
    await vi.waitFor(() => {
      expect(
        (component as unknown as { form: { controls: { legalName: { value: string } } } }).form
          .controls.legalName.value,
      ).toBe('Minimarket Central SpA');
    });
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('renderiza el título exacto "Empresa" y carga los datos iniciales', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toBe('Empresa');

    const form = (
      component as unknown as {
        form: {
          controls: {
            rut: { value: string };
            tradeName: { value: string };
          };
        };
      }
    ).form;
    expect(form.controls.rut.value).toBe('76.123.456-0');
    expect(form.controls.tradeName.value).toBe('Minimarket Central');
  });

  it('rechaza un RUT inválido en el formulario y bloquea el envío PUT', () => {
    const internal = component as unknown as {
      form: {
        controls: {
          rut: {
            setValue: (v: string) => void;
            markAsTouched: () => void;
            hasError: (code: string) => boolean;
          };
        };
        invalid: boolean;
      };
      submit: () => void;
    };

    internal.form.controls.rut.setValue('11.111.111-9');
    internal.form.controls.rut.markAsTouched();
    fixture.detectChanges();

    expect(internal.form.controls.rut.hasError('rut')).toBe(true);
    expect(internal.form.invalid).toBe(true);

    internal.submit();
    fixture.detectChanges();

    http.expectNone('/api/v1/company');
    const errorText = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(errorText).toContain('El RUT ingresado no es válido.');
  });

  it('envía PUT /api/v1/company cuando el formulario es válido', async () => {
    const internal = component as unknown as {
      form: {
        controls: {
          tradeName: { setValue: (v: string) => void };
        };
      };
      submit: () => void;
    };

    internal.form.controls.tradeName.setValue('Minimarket Barrio');
    internal.submit();

    const putReq = http.expectOne('/api/v1/company');
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body).toEqual({
      rut: '76.123.456-0',
      legalName: 'Minimarket Central SpA',
      tradeName: 'Minimarket Barrio',
      businessActivity: 'Venta al por menor en comercios no especializados',
      address: 'Av. Libertador 500',
      commune: 'Santiago',
      city: 'Santiago',
      phone: '+56223334444',
      email: 'contacto@central.cl',
      timezone: 'America/Santiago',
      logoFileId: undefined,
    });

    flushJson(putReq, { ...sampleCompany, tradeName: 'Minimarket Barrio' });

    await vi.waitFor(() => {
      expect(snackBarOpen).toHaveBeenCalledWith(
        'Datos de la empresa actualizados correctamente.',
        'Cerrar',
        { duration: 4000 },
      );
    });
  });

  it('marca el control RUT si el backend responde 422 RUT_INVALID', async () => {
    const internal = component as unknown as {
      form: {
        controls: {
          rut: { hasError: (code: string) => boolean; getError: (code: string) => unknown };
        };
      };
      submit: () => void;
    };

    internal.submit();

    const putReq = http.expectOne('/api/v1/company');
    putReq.flush(
      new Blob(
        [
          JSON.stringify({
            code: 'RUT_INVALID',
            detail: 'El RUT no supera la validación tributaria.',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 422, statusText: 'Unprocessable Content' },
    );

    await vi.waitFor(() => {
      expect(internal.form.controls.rut.hasError('server')).toBe(true);
    });
    expect(internal.form.controls.rut.getError('server')).toBe(
      'El RUT no supera la validación tributaria.',
    );
  });
});
