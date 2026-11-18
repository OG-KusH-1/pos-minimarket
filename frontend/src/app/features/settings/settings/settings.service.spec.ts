import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { SettingsService } from './settings.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('SettingsService', () => {
  let service: SettingsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SettingsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listSettings consulta /api/v1/settings con filtros y X-Store-Id del local elegido', async () => {
    const listPromise = firstValueFrom(
      service.listSettings({ scope: 'STORE', prefix: 'ticket.' }, 4),
    );
    const req = http.expectOne((r) => r.url === '/api/v1/settings');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('scope')).toBe('STORE');
    expect(req.request.params.get('prefix')).toBe('ticket.');
    expect(req.request.headers.get('X-Store-Id')).toBe('4');

    flushJson(req, [
      {
        key: 'ticket.footer_lines',
        scope: 'STORE',
        storeId: 4,
        value: ['Gracias por su compra'],
        valueType: 'JSON',
      },
    ]);

    const items = await listPromise;
    expect(items).toHaveLength(1);
    expect(items[0]?.value).toEqual(['Gracias por su compra']);
  });

  it('getEffectiveSettings consulta /api/v1/settings/effective y devuelve valores nativos', async () => {
    const effPromise = firstValueFrom(service.getEffectiveSettings(2));
    const req = http.expectOne('/api/v1/settings/effective');
    expect(req.request.method).toBe('GET');
    expect(req.request.headers.get('X-Store-Id')).toBe('2');

    flushJson(req, {
      values: {
        'ticket.paper_width_mm': 58,
        'ticket.show_tax_breakdown': false,
        'ticket.footer_lines': ['Local 2'],
      },
    });

    const values = await effPromise;
    expect(values['ticket.paper_width_mm']).toBe(58);
    expect(values['ticket.show_tax_breakdown']).toBe(false);
    expect(values['ticket.footer_lines']).toEqual(['Local 2']);
  });

  it('updateSetting envía PUT /api/v1/settings/{key} con scope, valor JSON y X-Store-Id', async () => {
    const updatePromise = firstValueFrom(
      service.updateSetting(
        'ticket.footer_lines',
        'STORE',
        ['Gracias por preferirnos', 'Vuelva pronto'],
        3,
      ),
    );
    const req = http.expectOne('/api/v1/settings/ticket.footer_lines');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('X-Store-Id')).toBe('3');
    expect(req.request.body).toEqual({
      scope: 'STORE',
      value: ['Gracias por preferirnos', 'Vuelva pronto'],
    });

    flushJson(req, {
      key: 'ticket.footer_lines',
      scope: 'STORE',
      storeId: 3,
      value: ['Gracias por preferirnos', 'Vuelva pronto'],
      valueType: 'JSON',
    });

    const updated = await updatePromise;
    expect(updated.scope).toBe('STORE');
    expect(updated.storeId).toBe(3);
  });

  it('updateSetting soporta alcance GLOBAL para valores numéricos y booleanos', async () => {
    const updatePromise = firstValueFrom(
      service.updateSetting('pos.return.max_days', 'GLOBAL', 45, 1),
    );
    const req = http.expectOne('/api/v1/settings/pos.return.max_days');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ scope: 'GLOBAL', value: 45 });

    flushJson(req, {
      key: 'pos.return.max_days',
      scope: 'GLOBAL',
      value: 45,
      valueType: 'NUMBER',
    });

    const updated = await updatePromise;
    expect(updated.value).toBe(45);
  });
});
