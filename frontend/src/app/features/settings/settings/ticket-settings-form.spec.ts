import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { SettingItem } from './settings.metadata';
import { TicketSettingsForm } from './ticket-settings-form';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('TicketSettingsForm', () => {
  let fixture: ComponentFixture<TicketSettingsForm>;
  let component: TicketSettingsForm;
  let http: HttpTestingController;
  let snackBarOpen: ReturnType<typeof vi.fn>;

  const sampleSettings: SettingItem[] = [
    {
      key: 'ticket.paper_width_mm',
      scope: 'GLOBAL',
      value: 80,
      valueType: 'NUMBER',
    },
    {
      key: 'ticket.show_tax_breakdown',
      scope: 'GLOBAL',
      value: true,
      valueType: 'BOOLEAN',
    },
    {
      key: 'ticket.header_lines',
      scope: 'GLOBAL',
      value: ['Bienvenidos'],
      valueType: 'JSON',
    },
    {
      key: 'ticket.footer_lines',
      scope: 'STORE',
      storeId: 2,
      value: ['Gracias por su compra en Local 2'],
      valueType: 'JSON',
    },
  ];

  beforeEach(async () => {
    snackBarOpen = vi.fn();

    await TestBed.configureTestingModule({
      imports: [TicketSettingsForm],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TicketSettingsForm);
    fixture.componentRef.setInput('storeId', 2);
    fixture.componentRef.setInput('storeName', 'Sucursal Centro');
    fixture.componentRef.setInput('settings', sampleSettings);
    fixture.componentRef.setInput('effectiveValues', {
      'ticket.paper_width_mm': 80,
      'ticket.show_tax_breakdown': true,
      'ticket.header_lines': ['Bienvenidos'],
      'ticket.footer_lines': ['Gracias por su compra en Local 2'],
    });
    fixture.componentRef.setInput('canUpdateStore', true);
    fixture.componentRef.setInput('canUpdateGlobal', false);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('carga valores por local y prohíbe al gerente seleccionar alcance GLOBAL', () => {
    const internal = component as unknown as {
      form: {
        controls: {
          scope: { value: string };
          paperWidthMm: { value: number };
        };
      };
      footerLines: { getRawValue: () => string[] };
      canEditCurrentScope: () => boolean;
    };

    expect(internal.form.controls.scope.value).toBe('STORE');
    expect(internal.form.controls.paperWidthMm.value).toBe(80);
    expect(internal.footerLines.getRawValue()).toEqual(['Gracias por su compra en Local 2']);
    expect(internal.canEditCurrentScope()).toBe(true);
  });

  it('valida que las líneas del ticket no superen 48 caracteres ni más de 6 líneas', () => {
    const internal = component as unknown as {
      form: { invalid: boolean };
      footerLines: {
        at: (i: number) => { setValue: (v: string) => void; markAsTouched: () => void };
        length: number;
      };
      addFooterLine: () => void;
      submit: () => void;
    };

    // Línea > 48 caracteres invalida el formulario y bloquea el guardado
    internal.footerLines.at(0).setValue('A'.repeat(49));
    internal.footerLines.at(0).markAsTouched();
    fixture.detectChanges();

    expect(internal.form.invalid).toBe(true);
    internal.submit();
    http.expectNone((r) => r.url.startsWith('/api/v1/settings/'));

    // Restaura línea válida y verifica tope de 6 líneas
    internal.footerLines.at(0).setValue('Gracias por su visita');
    for (let i = 0; i < 10; i++) {
      internal.addFooterLine();
    }
    expect(internal.footerLines.length).toBe(6);
  });

  it('valida el ancho de papel entre 58 y 80 mm', () => {
    const internal = component as unknown as {
      form: {
        controls: {
          paperWidthMm: { setValue: (v: number) => void; hasError: (err: string) => boolean };
        };
        invalid: boolean;
      };
      submit: () => void;
    };

    internal.form.controls.paperWidthMm.setValue(45);
    fixture.detectChanges();
    expect(internal.form.controls.paperWidthMm.hasError('min')).toBe(true);
    expect(internal.form.invalid).toBe(true);

    internal.submit();
    http.expectNone((r) => r.url.startsWith('/api/v1/settings/'));

    internal.form.controls.paperWidthMm.setValue(58);
    expect(internal.form.invalid).toBe(false);
  });

  it('guarda ticket.* para el local seleccionado con X-Store-Id y emite saved', async () => {
    const savedSpy = vi.fn();
    component.saved.subscribe(savedSpy);

    const internal = component as unknown as {
      form: {
        controls: {
          paperWidthMm: { setValue: (v: number) => void };
          showTaxBreakdown: { setValue: (v: boolean) => void };
        };
      };
      footerLines: { at: (i: number) => { setValue: (v: string) => void } };
      submit: () => void;
    };

    internal.form.controls.paperWidthMm.setValue(58);
    internal.form.controls.showTaxBreakdown.setValue(false);
    internal.footerLines.at(0).setValue('Cambios dentro de 10 días con boleta');
    internal.submit();

    const widthReq = http.expectOne('/api/v1/settings/ticket.paper_width_mm');
    expect(widthReq.request.method).toBe('PUT');
    expect(widthReq.request.headers.get('X-Store-Id')).toBe('2');
    expect(widthReq.request.body).toEqual({ scope: 'STORE', value: 58 });

    const taxReq = http.expectOne('/api/v1/settings/ticket.show_tax_breakdown');
    expect(taxReq.request.body).toEqual({ scope: 'STORE', value: false });

    const headerReq = http.expectOne('/api/v1/settings/ticket.header_lines');
    expect(headerReq.request.body).toEqual({ scope: 'STORE', value: ['Bienvenidos'] });

    const footerReq = http.expectOne('/api/v1/settings/ticket.footer_lines');
    expect(footerReq.request.body).toEqual({
      scope: 'STORE',
      value: ['Cambios dentro de 10 días con boleta'],
    });

    flushJson(widthReq, { key: 'ticket.paper_width_mm', scope: 'STORE', storeId: 2, value: 58 });
    flushJson(taxReq, {
      key: 'ticket.show_tax_breakdown',
      scope: 'STORE',
      storeId: 2,
      value: false,
    });
    flushJson(headerReq, {
      key: 'ticket.header_lines',
      scope: 'STORE',
      storeId: 2,
      value: ['Bienvenidos'],
    });
    flushJson(footerReq, {
      key: 'ticket.footer_lines',
      scope: 'STORE',
      storeId: 2,
      value: ['Cambios dentro de 10 días con boleta'],
    });

    await vi.waitFor(() => {
      expect(savedSpy).toHaveBeenCalledTimes(1);
    });
    expect(snackBarOpen).toHaveBeenCalledWith(
      'Formato de ticket actualizado correctamente.',
      'Cerrar',
      { duration: 4000 },
    );
  });
});
