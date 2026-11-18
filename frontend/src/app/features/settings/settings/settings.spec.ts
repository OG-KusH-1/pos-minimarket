import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { Settings } from './settings';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('Settings', () => {
  let fixture: ComponentFixture<Settings>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Settings],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) =>
              code === 'config:settings:read' || code === 'config:settings:update',
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: () => 1,
            availableStores: () => [{ id: 1, code: 'SUC01', name: 'Casa Matriz', active: true }],
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Settings);
    fixture.detectChanges();

    flushJson(
      http.expectOne((r) => r.url === '/api/v1/stores'),
      {
        content: [{ id: 1, code: 'SUC01', name: 'Casa Matriz', active: true }],
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
      },
    );
    flushJson(http.expectOne('/api/v1/company'), {
      id: 1,
      rut: '76123456-0',
      tradeName: 'Minimarket Central',
    });

    await vi.waitFor(() => {
      const reqs = http.match(
        (r) => r.url === '/api/v1/settings' || r.url === '/api/v1/settings/effective',
      );
      expect(reqs.length).toBeGreaterThanOrEqual(2);
      for (const req of reqs) {
        if (req.request.url === '/api/v1/settings') {
          flushJson(req, [
            { key: 'ticket.paper_width_mm', scope: 'GLOBAL', value: 80, valueType: 'NUMBER' },
            { key: 'pos.return.max_days', scope: 'GLOBAL', value: 30, valueType: 'NUMBER' },
          ]);
        } else {
          flushJson(req, {
            values: { 'ticket.paper_width_mm': 80, 'pos.return.max_days': 30 },
          });
        }
      }
    });
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('renderiza el título exacto "Parámetros" y las tres pestañas', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toBe('Parámetros');
    const text = el.textContent ?? '';
    expect(text).toContain('Ticket por local');
    expect(text).toContain('Operación y caja');
    expect(text).toContain('Seguridad (Global)');
  });
});
