import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../api/api-configuration';
import { AuthService } from '../../core/auth/auth.service';
import { Promotions } from './promotions';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('Promotions (bandeja)', () => {
  let fixture: ComponentFixture<Promotions>;
  let http: HttpTestingController;
  let grantedPermissions: Set<string>;

  async function mountWithPermissions(permissions: readonly string[]): Promise<void> {
    grantedPermissions = new Set(permissions);
    await TestBed.configureTestingModule({
      imports: [Promotions],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => grantedPermissions.has(code),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Promotions);
    fixture.detectChanges();
  }

  afterEach(() => http?.verify());

  it('lista promociones desde GET /api/v1/promotions ordenando por prioridad ascendente', async () => {
    await mountWithPermissions(['promotions:promotion:read']);

    const req = http.expectOne(
      (r) => r.url === '/api/v1/promotions' && r.params.get('sort') === 'priority,asc',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('0');
    flushJson(req, {
      content: [
        {
          id: 1,
          name: '3x2 bebidas',
          promotionType: 'BUY_X_PAY_Y',
          priority: 10,
          stackable: false,
          validFrom: '2026-11-19T13:00:00.000Z',
          active: true,
          items: [{ targetType: 'CATEGORY', targetId: 3, targetName: 'Bebidas', requiredQty: 1 }],
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('3x2 bebidas');
    expect(text).toContain('Lleva N, paga M');
    expect(text).toContain('Bebidas');
    expect(text).toContain('Activa');
  });

  it('oculta la gestión (editar/activar/nueva) sin promotions:promotion:manage', async () => {
    await mountWithPermissions(['promotions:promotion:read']);

    const req = http.expectOne((r) => r.url === '/api/v1/promotions');
    flushJson(req, {
      content: [
        {
          id: 1,
          name: 'Descuento 10% pan',
          promotionType: 'PERCENT',
          priority: 100,
          active: true,
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Nueva promoción');
    expect(text).not.toContain('Editar');

    const toggleButtons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).filter((button) => (button.getAttribute('aria-label') ?? '').includes('promoción'));
    expect(toggleButtons).toHaveLength(0);
  });

  it('envía el filtro de tipo elegido al recargar', async () => {
    await mountWithPermissions(['promotions:promotion:manage', 'promotions:promotion:read']);

    const initial = http.expectOne((r) => r.url === '/api/v1/promotions');
    flushJson(initial, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance as unknown as {
      filterForm: { controls: { type: AbstractControlLike } };
      applyFilters: () => void;
    };
    comp.filterForm.controls.type.setValue('COMBO');
    comp.applyFilters();

    const filtered = http.expectOne(
      (r) => r.url === '/api/v1/promotions' && r.params.get('type') === 'COMBO',
    );
    flushJson(filtered, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
    await fixture.whenStable();
  });
});

interface AbstractControlLike {
  setValue(value: unknown): void;
}
