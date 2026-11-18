import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { Stores } from './stores';
import { StoresService } from './stores.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('StoresService', () => {
  let service: StoresService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(StoresService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('gestiona locales con listStores, createStore, updateStore, deactivateStore y activateStore', async () => {
    const listPromise = firstValueFrom(service.listStores({ page: 0, size: 20 }));
    const listReq = http.expectOne((r) => r.url === '/api/v1/stores');
    expect(listReq.request.method).toBe('GET');
    flushJson(listReq, {
      content: [{ id: 1, code: 'SUC01', name: 'Casa Matriz', active: true }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    const page = await listPromise;
    expect(page.content?.length ?? 0).toBe(1);

    const createPromise = firstValueFrom(
      service.createStore({
        code: 'SUC02',
        name: 'Sucursal Norte',
        address: 'Av. Norte 123',
        commune: 'Independencia',
        city: 'Santiago',
      }),
    );
    const postReq = http.expectOne('/api/v1/stores');
    expect(postReq.request.method).toBe('POST');
    flushJson(postReq, { id: 2, code: 'SUC02', name: 'Sucursal Norte', active: true });
    const created = await createPromise;
    expect(created.id).toBe(2);

    const updatePromise = firstValueFrom(
      service.updateStore(2, {
        code: 'SUC02',
        name: 'Sucursal Norte Actualizada',
        address: 'Av. Norte 123',
        commune: 'Independencia',
        city: 'Santiago',
      }),
    );
    const putReq = http.expectOne('/api/v1/stores/2');
    expect(putReq.request.method).toBe('PUT');
    flushJson(putReq, { id: 2, code: 'SUC02', name: 'Sucursal Norte Actualizada', active: true });
    await updatePromise;

    const deactPromise = firstValueFrom(service.deactivateStore(2));
    const deactReq = http.expectOne('/api/v1/stores/2/deactivate');
    expect(deactReq.request.method).toBe('POST');
    deactReq.flush(null, { status: 204, statusText: 'No Content' });
    await deactPromise;

    const actPromise = firstValueFrom(service.activateStore(2));
    const actReq = http.expectOne('/api/v1/stores/2/activate');
    expect(actReq.request.method).toBe('POST');
    actReq.flush(null, { status: 204, statusText: 'No Content' });
    await actPromise;
  });

  it('envía X-Store-Id del local dueño al crear, editar y activar/desactivar terminales', async () => {
    const listPromise = firstValueFrom(service.listTerminals(5));
    const listReq = http.expectOne('/api/v1/stores/5/terminals');
    expect(listReq.request.method).toBe('GET');
    flushJson(listReq, [{ id: 10, storeId: 5, code: 'CAJA-01', name: 'Caja 1', active: true }]);
    const terminals = await listPromise;
    expect(terminals).toHaveLength(1);

    const createPromise = firstValueFrom(
      service.createTerminal(5, { code: 'CAJA-02', name: 'Caja 2' }),
    );
    const postReq = http.expectOne('/api/v1/stores/5/terminals');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.headers.get('X-Store-Id')).toBe('5');
    flushJson(postReq, { id: 11, storeId: 5, code: 'CAJA-02', name: 'Caja 2', active: true });
    await createPromise;

    const updatePromise = firstValueFrom(
      service.updateTerminal(5, 11, { code: 'CAJA-02', name: 'Caja Rápida' }),
    );
    const putReq = http.expectOne('/api/v1/terminals/11');
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.headers.get('X-Store-Id')).toBe('5');
    flushJson(putReq, { id: 11, storeId: 5, code: 'CAJA-02', name: 'Caja Rápida', active: true });
    await updatePromise;

    const deactPromise = firstValueFrom(service.deactivateTerminal(5, 11));
    const deactReq = http.expectOne('/api/v1/terminals/11/deactivate');
    expect(deactReq.request.method).toBe('POST');
    expect(deactReq.request.headers.get('X-Store-Id')).toBe('5');
    deactReq.flush(null, { status: 204, statusText: 'No Content' });
    await deactPromise;
  });
});

describe('Stores', () => {
  let fixture: ComponentFixture<Stores>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Stores],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: ConfirmDialogService, useValue: { confirm: vi.fn() } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) =>
              code === 'config:store:manage' || code === 'config:terminal:manage',
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Stores);
    fixture.detectChanges();

    flushJson(
      http.expectOne((r) => r.url === '/api/v1/stores'),
      {
        content: [
          {
            id: 1,
            code: 'SUC01',
            name: 'Casa Matriz',
            address: 'Av. Principal 100',
            commune: 'Santiago',
            city: 'Santiago',
            active: true,
          },
        ],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      },
    );
    await vi.waitFor(() => {
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('Casa Matriz');
    });
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('renderiza "Locales y terminales" y carga las terminales al seleccionar un local', async () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toBe('Locales y terminales');

    const terminalsBtn = el.querySelector<HTMLButtonElement>(
      'button[aria-label="Ver terminales de Casa Matriz"]',
    );
    expect(terminalsBtn).not.toBeNull();
    terminalsBtn?.click();
    fixture.detectChanges();

    const termReq = http.expectOne('/api/v1/stores/1/terminals');
    flushJson(termReq, [
      {
        id: 10,
        storeId: 1,
        code: 'CAJA-01',
        name: 'Caja Principal',
        active: true,
        hasOpenSession: true,
      },
    ]);

    await vi.waitFor(() => {
      const text = el.textContent ?? '';
      expect(text).toContain('CAJA-01');
      expect(text).toContain('Turno abierto');
    });
  });
});
