import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { InventoryCountResponse } from '../../../api/models/inventory-count-response';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { Counts } from './counts';
import { CreateCountDialog } from './create-count-dialog';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

function flushBlobError(req: TestRequest, status: number, body: unknown): void {
  req.flush(new Blob([JSON.stringify(body)], { type: 'application/json' }), {
    status,
    statusText: 'Error',
  });
}

const SAMPLE_COUNT: InventoryCountResponse = {
  id: 12,
  number: 'TI-001-000012',
  storeId: 1,
  countType: 'CYCLIC',
  category: { id: 3, name: 'Lácteos' },
  status: 'SUBMITTED',
  blind: true,
  startedBy: { id: 2, fullName: 'Camila Bodega' },
  startedAt: '2026-11-15T14:30:00Z',
  progress: { totalLines: 20, countedLines: 20 },
  differenceSummary: { lines: 2, valueIn: 4500, valueOut: 1500 },
  version: 2,
};

describe('Counts', () => {
  let fixture: ComponentFixture<Counts>;
  let component: Counts;
  let http: HttpTestingController;
  let router: Router;
  let permissions: Set<string>;
  let activeStoreId: ReturnType<typeof signal<number | null>>;

  async function setup(
    grantedPermissions: readonly string[] = [
      'inventory:count:execute',
      'inventory:count:manage',
      'inventory:count:approve',
      'catalog:cost:read',
    ],
  ): Promise<void> {
    permissions = new Set(grantedPermissions);
    activeStoreId = signal<number | null>(1);

    await TestBed.configureTestingModule({
      imports: [Counts],
      providers: [
        provideRouter([]),
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => permissions.has(code),
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: activeStoreId.asReadonly(),
            activeStore: () => ({ id: activeStoreId(), name: 'Local Centro' }),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Counts);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function flushCountsList(items: InventoryCountResponse[] = [SAMPLE_COUNT]): void {
    const req = http.expectOne((r) => r.url === '/api/v1/inventory-counts');
    flushJson(req, {
      content: items,
      page: 0,
      size: 20,
      totalElements: items.length,
      totalPages: items.length ? 1 : 0,
      sort: ['createdAt,desc'],
    });
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('renderiza la bandeja de tomas con avance y diferencias valorizadas si tiene permisos', async () => {
    await setup();
    flushCountsList();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('TI-001-000012');
    });

    const h1 = fixture.nativeElement.querySelector('h1') as HTMLElement | null;
    expect(h1?.textContent?.trim()).toBe('Tomas de inventario');
    expect(fixture.nativeElement.textContent).toContain('Nueva toma');
    expect(fixture.nativeElement.textContent).toContain('20 / 20');
    expect(fixture.nativeElement.textContent).toContain('$3.000');
  });

  it('oculta botón Nueva toma sin inventory:count:manage y oculta diferencias en modo ciego o sin catalog:cost:read', async () => {
    await setup(['inventory:count:execute']);
    flushCountsList([{ ...SAMPLE_COUNT, status: 'OPEN', differenceSummary: undefined }]);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('TI-001-000012');
    });

    expect(fixture.nativeElement.textContent).not.toContain('Nueva toma');
    expect(fixture.nativeElement.textContent).not.toContain('Diferencias valorizadas');
  });

  it('filtra por estado y alcance, y navega al detalle al abrir una toma o crear una nueva', async () => {
    await setup();
    flushCountsList();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('TI-001-000012');
    });

    const ctrl = component as unknown as {
      filterForm: { patchValue: (v: Record<string, unknown>) => void };
      applyFilters: () => void;
      openCreateDialog: () => void;
      openDetail: (row: InventoryCountResponse) => void;
    };

    ctrl.filterForm.patchValue({ status: 'OPEN', countType: 'FULL' });
    ctrl.applyFilters();

    const filteredReq = http.expectOne((r) => r.url === '/api/v1/inventory-counts');
    expect(filteredReq.request.params.get('status')).toBe('OPEN');
    expect(filteredReq.request.params.get('countType')).toBe('FULL');
    flushJson(filteredReq, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: ['createdAt,desc'],
    });

    const navSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    ctrl.openDetail(SAMPLE_COUNT);
    expect(navSpy).toHaveBeenCalledWith(['/inventario/tomas', 12]);

    const dialog = TestBed.inject(MatDialog);
    vi.spyOn(dialog, 'open').mockReturnValue({
      afterClosed: () => of({ id: 99, number: 'TI-001-000099' }),
    } as never);
    ctrl.openCreateDialog();
    expect(navSpy).toHaveBeenCalledWith(['/inventario/tomas', 99]);
  });
});

describe('CreateCountDialog', () => {
  let fixture: ComponentFixture<CreateCountDialog>;
  let component: CreateCountDialog;
  let http: HttpTestingController;
  let closeSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [CreateCountDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CreateCountDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();

    const catReq = http.expectOne((r) => r.url === '/api/v1/categories');
    flushJson(catReq, [
      { id: 3, name: 'Lácteos', active: true, children: [{ id: 4, name: 'Yogurt', active: true }] },
    ]);
    await vi.waitFor(() => {
      fixture.detectChanges();
    });
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('exige categoría cuando countType es CYCLIC y previsualiza productos activos de la categoría', async () => {
    const ctrl = component as unknown as {
      form: {
        patchValue: (v: Record<string, unknown>) => void;
        controls: { categoryId: { hasError: (e: string) => boolean } };
      };
      submit: () => void;
    };

    ctrl.form.patchValue({ countType: 'CYCLIC' });
    ctrl.submit();
    expect(ctrl.form.controls.categoryId.hasError('required')).toBe(true);
    http.expectNone('/api/v1/inventory-counts');

    ctrl.form.patchValue({ categoryId: 3 });
    const previewReq = http.expectOne((r) => r.url === '/api/v1/products');
    expect(previewReq.request.params.get('categoryId')).toBe('3');
    flushJson(previewReq, {
      content: [{ id: 10, sku: 'LEC-01', name: 'Leche 1L' }],
      totalElements: 8,
      page: 0,
      size: 5,
      totalPages: 2,
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('8 producto(s) activo(s)');
    });
  });

  it('envía POST /api/v1/inventory-counts con Idempotency-Key y muestra conflicto DUPLICATED_VALUE', async () => {
    const ctrl = component as unknown as {
      form: { patchValue: (v: Record<string, unknown>) => void };
      serverError: () => string | null;
      submit: () => void;
    };

    ctrl.form.patchValue({ countType: 'FULL', blind: true, notes: 'Inventario general' });
    ctrl.submit();

    const failReq = http.expectOne('/api/v1/inventory-counts');
    expect(failReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    flushBlobError(failReq, 409, {
      code: 'DUPLICATED_VALUE',
      detail: 'Ya existe una toma de inventario abierta para el mismo local y alcance',
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(ctrl.serverError()).toContain('Ya existe una toma de inventario abierta');
    });
    expect(closeSpy).not.toHaveBeenCalled();

    ctrl.submit();
    const okReq = http.expectOne('/api/v1/inventory-counts');
    expect(okReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    const created: InventoryCountResponse = {
      id: 44,
      number: 'TI-001-000044',
      countType: 'FULL',
      status: 'OPEN',
      blind: true,
    };
    flushJson(okReq, created);

    await vi.waitFor(() => {
      expect(closeSpy).toHaveBeenCalledWith(created);
    });
  });
});
