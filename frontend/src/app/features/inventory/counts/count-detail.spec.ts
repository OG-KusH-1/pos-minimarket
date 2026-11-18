import { HttpRequest, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { CountLineResponse } from '../../../api/models/count-line-response';
import { InventoryCountResponse } from '../../../api/models/inventory-count-response';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { CountDetail } from './count-detail';
import { CountReasonDialog } from './count-reason-dialog';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

function flushBlobError(req: TestRequest, status: number, body: unknown): void {
  const payload =
    req.request.responseType === 'blob'
      ? new Blob([JSON.stringify(body)], { type: 'application/json' })
      : JSON.stringify(body);
  req.flush(payload, {
    status,
    statusText: 'Error',
  });
}

async function expectOneAsync(
  http: HttpTestingController,
  matcher: string | ((req: HttpRequest<unknown>) => boolean),
): Promise<TestRequest> {
  let matched: TestRequest | undefined;
  await vi.waitFor(() => {
    const found = http.match(matcher as never);
    expect(found.length).toBe(1);
    matched = found[0];
  });
  if (!matched) {
    throw new Error('Expected request not found');
  }
  return matched;
}

const SAMPLE_LINES: readonly CountLineResponse[] = [
  {
    id: 101,
    productId: 1,
    sku: 'SKU-01',
    name: 'Arroz Grado 1',
    unitOfMeasure: 'UNIT',
    countedQty: undefined,
    theoreticalQty: 10,
    difference: undefined,
    unitCost: 1200,
  },
  {
    id: 102,
    productId: 2,
    sku: 'SKU-02',
    name: 'Queso Gouda',
    unitOfMeasure: 'KG',
    countedQty: 2.5,
    theoreticalQty: 3,
    difference: -0.5,
    unitCost: 8000,
    countedAt: '2026-11-16T10:00:00Z',
  },
  {
    id: 103,
    productId: 3,
    sku: 'SKU-03',
    name: 'Leche Entera 1L',
    unitOfMeasure: 'UNIT',
    countedQty: 12,
    theoreticalQty: 12,
    difference: 0,
    unitCost: 950,
    countedAt: '2026-11-16T10:05:00Z',
  },
];

function makeCount(overrides: Partial<InventoryCountResponse> = {}): InventoryCountResponse {
  return {
    id: 25,
    number: 'TI-001-000025',
    storeId: 1,
    countType: 'CYCLIC',
    category: { id: 3, name: 'Abarrotes' },
    status: 'OPEN',
    blind: false,
    startedBy: { id: 10, fullName: 'Pedro Bodega' },
    startedAt: '2026-11-16T09:00:00Z',
    progress: { totalLines: 3, countedLines: 2 },
    differenceSummary: { lines: 1, valueIn: 0, valueOut: 4000 },
    version: 1,
    ...overrides,
  };
}

describe('CountDetail', () => {
  let fixture: ComponentFixture<CountDetail>;
  let component: CountDetail;
  let http: HttpTestingController;
  let permissions: Set<string>;
  let currentUserId: ReturnType<typeof signal<number | null>>;
  let activeStoreId: ReturnType<typeof signal<number | null>>;
  let confirmSpy: ReturnType<typeof vi.fn>;

  async function setup(options?: {
    permissions?: readonly string[];
    userId?: number;
  }): Promise<void> {
    permissions = new Set(
      options?.permissions ?? [
        'inventory:count:execute',
        'inventory:count:manage',
        'inventory:count:approve',
        'catalog:cost:read',
      ],
    );
    currentUserId = signal<number | null>(options?.userId ?? 99);
    activeStoreId = signal<number | null>(1);
    confirmSpy = vi.fn().mockReturnValue(of(true));

    await TestBed.configureTestingModule({
      imports: [CountDetail],
      providers: [
        provideRouter([]),
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: ConfirmDialogService, useValue: { confirm: confirmSpy } },
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of(convertToParamMap({ id: '25' })),
          },
        },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => permissions.has(code),
            user: () =>
              currentUserId() !== null ? { id: currentUserId(), fullName: 'Usuario' } : null,
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
    fixture = TestBed.createComponent(CountDetail);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function flushInitial(
    count: InventoryCountResponse = makeCount(),
    lines: readonly CountLineResponse[] = SAMPLE_LINES,
  ): void {
    const headerReq = http.expectOne('/api/v1/inventory-counts/25');
    flushJson(headerReq, count);

    const linesReq = http.expectOne((r) => r.url === '/api/v1/inventory-counts/25/lines');
    flushJson(linesReq, {
      content: lines,
      page: 0,
      size: 100,
      totalElements: lines.length,
      totalPages: 1,
    });
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('navega fila a fila con teclado (Enter, ArrowDown y ArrowUp) entre inputs de cantidad', async () => {
    await setup();
    flushInitial();

    await vi.waitFor(() => {
      fixture.detectChanges();
      const inputs = fixture.nativeElement.querySelectorAll('input.qty-input');
      expect(inputs.length).toBe(3);
    });

    const inputs = Array.from(
      fixture.nativeElement.querySelectorAll('input.qty-input'),
    ) as HTMLInputElement[];

    inputs[0]?.focus();
    expect(document.activeElement).toBe(inputs[0]);

    inputs[0]?.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(document.activeElement).toBe(inputs[1]);

    inputs[1]?.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(document.activeElement).toBe(inputs[2]);

    inputs[2]?.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(document.activeElement).toBe(inputs[1]);
  });

  it('valida enteros para UNIT, muestra indicador de cambios pendientes y guarda lote con Ctrl+S', async () => {
    await setup();
    flushInitial();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelectorAll('input.qty-input').length).toBe(3);
    });

    const inputs = Array.from(
      fixture.nativeElement.querySelectorAll('input.qty-input'),
    ) as HTMLInputElement[];
    const input0 = inputs[0];
    const input1 = inputs[1];
    if (!input0 || !input1) {
      throw new Error('Faltan inputs de cantidad');
    }

    // Valor decimal en producto UNIT -> error inline y sin llamada HTTP.
    input0.value = '4.5';
    input0.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      'Este producto se cuenta en unidades enteras.',
    );

    // Valor entero válido en fila 0 y decimal válido en fila 1 (KG).
    input0.value = '9';
    input0.dispatchEvent(new Event('input', { bubbles: true }));
    input1.value = '2.75';
    input1.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('2 cambio(s) pendiente(s)');

    // Ctrl+S sobre la grilla dispara guardado en lote.
    const grid = fixture.nativeElement.querySelector('.table-container') as HTMLElement;
    grid.dispatchEvent(
      new KeyboardEvent('keydown', { key: 's', ctrlKey: true, bubbles: true, cancelable: true }),
    );

    const saveReq = http.expectOne('/api/v1/inventory-counts/25/lines');
    expect(saveReq.request.method).toBe('PUT');
    expect(saveReq.request.body).toEqual({
      lines: [
        { lineId: 101, countedQty: 9 },
        { lineId: 102, countedQty: 2.75 },
      ],
    });
    flushJson(saveReq, [
      { ...SAMPLE_LINES[0], countedQty: 9, difference: -1, countedAt: '2026-11-16T11:00:00Z' },
      {
        ...SAMPLE_LINES[1],
        countedQty: 2.75,
        difference: -0.25,
        countedAt: '2026-11-16T11:00:00Z',
      },
    ]);

    const headerReload = await expectOneAsync(http, '/api/v1/inventory-counts/25');
    flushJson(
      headerReload,
      makeCount({ progress: { totalLines: 3, countedLines: 3 }, version: 2 }),
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Guardar cambios (0)');
      expect(fixture.nativeElement.textContent).toContain('3 / 3 líneas (100%)');
    });
  });

  it('respeta modo ciego estricto en OPEN y revela teórico/costos solo tras enviar con permiso de aprobar y catalog:cost:read', async () => {
    // Caso 1: Toma ciega en estado OPEN, incluso con permiso approve + cost:read -> oculta teórico y diferencia.
    await setup({
      permissions: [
        'inventory:count:execute',
        'inventory:count:manage',
        'inventory:count:approve',
        'catalog:cost:read',
      ],
    });
    flushInitial(
      makeCount({ blind: true, status: 'OPEN', differenceSummary: undefined }),
      SAMPLE_LINES.map((l) => ({ ...l, theoreticalQty: undefined, difference: undefined })),
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Modo ciego activo');
    });

    expect(fixture.nativeElement.textContent).not.toContain('Teórico');
    expect(fixture.nativeElement.textContent).not.toContain('Diferencia');
    expect(fixture.nativeElement.textContent).not.toContain('Sobrantes valorizados');

    // Caso 2: Toma ciega en estado SUBMITTED, pero usuario SIN inventory:count:approve -> sigue oculto.
    TestBed.resetTestingModule();
    await setup({ permissions: ['inventory:count:execute', 'catalog:cost:read'] });
    flushInitial(
      makeCount({ blind: true, status: 'SUBMITTED', differenceSummary: undefined }),
      SAMPLE_LINES.map((l) => ({ ...l, theoreticalQty: undefined, difference: undefined })),
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Modo ciego activo');
    });
    expect(fixture.nativeElement.textContent).not.toContain('Teórico');

    // Caso 3: Toma ciega en estado SUBMITTED CON inventory:count:approve y catalog:cost:read -> se revela.
    TestBed.resetTestingModule();
    await setup({
      permissions: ['inventory:count:execute', 'inventory:count:approve', 'catalog:cost:read'],
      userId: 50,
    });
    flushInitial(makeCount({ blind: true, status: 'SUBMITTED' }), SAMPLE_LINES);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Teórico');
      expect(fixture.nativeElement.textContent).toContain('Diferencia');
      expect(fixture.nativeElement.textContent).toContain('Faltantes valorizados');
      expect(fixture.nativeElement.textContent).toContain('$4.000');
    });
  });

  it('ejecuta el ciclo OPEN -> SUBMITTED -> APPROVED y bloquea SoD cuando el usuario inició o contó', async () => {
    // Usuario 50 (distinto a startedBy=10) puede enviar y aprobar.
    await setup({ userId: 50 });
    flushInitial(makeCount({ progress: { totalLines: 3, countedLines: 3 } }), SAMPLE_LINES);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Enviar a revisión');
    });

    const ctrl = component as unknown as {
      submitForReview: () => void;
      approveCount: () => void;
      rejectCount: () => void;
      cancelCount: () => void;
      isSodBlocked: () => boolean;
      concurrencyConflict: () => string | null;
    };

    ctrl.submitForReview();
    const submitReq = http.expectOne('/api/v1/inventory-counts/25/submit');
    expect(submitReq.request.method).toBe('POST');
    flushJson(
      submitReq,
      makeCount({ status: 'SUBMITTED', progress: { totalLines: 3, countedLines: 3 } }),
    );

    const linesAfterSubmit = await expectOneAsync(
      http,
      (r) => r.url === '/api/v1/inventory-counts/25/lines',
    );
    flushJson(linesAfterSubmit, {
      content: SAMPLE_LINES,
      page: 0,
      size: 100,
      totalElements: 3,
      totalPages: 1,
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Aprobar toma');
    });
    expect(ctrl.isSodBlocked()).toBe(false);

    ctrl.approveCount();
    const approveReq = http.expectOne('/api/v1/inventory-counts/25/approve');
    expect(approveReq.request.method).toBe('POST');
    flushJson(
      approveReq,
      makeCount({
        status: 'APPROVED',
        approvedBy: { id: 50, fullName: 'Supervisor' },
        progress: { totalLines: 3, countedLines: 3 },
      }),
    );

    const linesAfterApprove = await expectOneAsync(
      http,
      (r) => r.url === '/api/v1/inventory-counts/25/lines',
    );
    flushJson(linesAfterApprove, {
      content: SAMPLE_LINES,
      page: 0,
      size: 100,
      totalElements: 3,
      totalPages: 1,
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Aprobada');
    });

    // Ahora verificamos SoD cuando el usuario actual es el mismo que inició la toma (id = 10).
    TestBed.resetTestingModule();
    await setup({ userId: 10 });
    flushInitial(
      makeCount({ status: 'SUBMITTED', startedBy: { id: 10, fullName: 'Pedro Bodega' } }),
      SAMPLE_LINES,
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Segregación de funciones');
    });
    const approveBtn = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((b) => b.textContent?.includes('Aprobar toma'));
    expect(approveBtn?.disabled).toBe(true);
  });

  it('permite rechazar a reconteo, cancelar con motivo y muestra alerta de conflicto 409', async () => {
    await setup({ userId: 50 });
    flushInitial(makeCount({ status: 'SUBMITTED' }), SAMPLE_LINES);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Rechazar a reconteo');
    });

    const dialog = TestBed.inject(MatDialog);
    vi.spyOn(dialog, 'open').mockReturnValue({
      afterClosed: () => of('Diferencia alta en lácteos'),
    } as never);

    const ctrl = component as unknown as {
      rejectCount: () => void;
      cancelCount: () => void;
      concurrencyConflict: () => string | null;
    };

    ctrl.rejectCount();
    const rejectReq = http.expectOne('/api/v1/inventory-counts/25/reject');
    expect(rejectReq.request.body).toEqual({ reason: 'Diferencia alta en lácteos' });
    flushJson(
      rejectReq,
      makeCount({ status: 'OPEN', rejectionReason: 'Diferencia alta en lácteos', version: 3 }),
    );
    const linesAfterReject = await expectOneAsync(
      http,
      (r) => r.url === '/api/v1/inventory-counts/25/lines',
    );
    flushJson(linesAfterReject, {
      content: SAMPLE_LINES,
      page: 0,
      size: 100,
      totalElements: 3,
      totalPages: 1,
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Motivo registrado:');
      expect(fixture.nativeElement.textContent).toContain('Diferencia alta en lácteos');
    });

    // Simula conflicto de concurrencia 409 al cancelar.
    ctrl.cancelCount();
    const cancelReq = http.expectOne('/api/v1/inventory-counts/25/cancel');
    flushBlobError(cancelReq, 409, {
      code: 'CONCURRENT_MODIFICATION',
      detail: 'La toma fue modificada por otro usuario.',
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(ctrl.concurrencyConflict()).toContain('La toma fue modificada');
      expect(fixture.nativeElement.textContent).toContain('Recargar toma');
    });
  });

  it('bloquea aprobación por SoD si el usuario contó en la sesión o si backend responde 422 SEGREGATION_OF_DUTIES', async () => {
    await setup({ userId: 50 });
    flushInitial(
      makeCount({ status: 'OPEN', startedBy: { id: 10, fullName: 'Otro usuario' } }),
      SAMPLE_LINES,
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelectorAll('input.qty-input').length).toBe(3);
    });

    const input0 = fixture.nativeElement.querySelector('input.qty-input') as HTMLInputElement;
    input0.value = '10';
    input0.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    const ctrl = component as unknown as {
      savePendingLines: () => void;
      submitForReview: () => void;
      approveCount: () => void;
      isSodBlocked: () => boolean;
      errorMessage: () => string | null;
    };

    ctrl.savePendingLines();
    const saveReq = http.expectOne('/api/v1/inventory-counts/25/lines');
    flushJson(saveReq, [
      { ...SAMPLE_LINES[0], countedQty: 10, difference: 0, countedAt: '2026-11-16T12:00:00Z' },
    ]);
    const headerReload = await expectOneAsync(http, '/api/v1/inventory-counts/25');
    flushJson(
      headerReload,
      makeCount({ status: 'OPEN', progress: { totalLines: 3, countedLines: 3 }, version: 2 }),
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('3 / 3 líneas');
    });

    ctrl.submitForReview();
    const submitReq = http.expectOne('/api/v1/inventory-counts/25/submit');
    flushJson(
      submitReq,
      makeCount({ status: 'SUBMITTED', progress: { totalLines: 3, countedLines: 3 }, version: 3 }),
    );
    const linesAfterSubmit = await expectOneAsync(
      http,
      (r) => r.url === '/api/v1/inventory-counts/25/lines',
    );
    flushJson(linesAfterSubmit, {
      content: SAMPLE_LINES,
      page: 0,
      size: 100,
      totalElements: 3,
      totalPages: 1,
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(ctrl.isSodBlocked()).toBe(true);
      expect(fixture.nativeElement.textContent).toContain('Segregación de funciones');
    });
    http.expectNone('/api/v1/inventory-counts/25/approve');

    // Caso backend: otro usuario que contó en sesión previa recibe 422 SEGREGATION_OF_DUTIES.
    TestBed.resetTestingModule();
    await setup({ userId: 77 });
    flushInitial(
      makeCount({ status: 'SUBMITTED', startedBy: { id: 10, fullName: 'Otro' } }),
      SAMPLE_LINES,
    );
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Aprobar toma');
    });

    const ctrl2 = component as unknown as {
      approveCount: () => void;
      errorMessage: () => string | null;
    };
    ctrl2.approveCount();
    const approveReq = http.expectOne('/api/v1/inventory-counts/25/approve');
    flushBlobError(approveReq, 422, {
      code: 'SEGREGATION_OF_DUTIES',
      detail: 'Segregación de funciones: quien aprueba no puede haber registrado conteos.',
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(ctrl2.errorMessage()).toContain('Segregación de funciones');
    });
  });
});

describe('CountReasonDialog', () => {
  let fixture: ComponentFixture<CountReasonDialog>;
  let component: CountReasonDialog;
  let closeSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [CountReasonDialog],
      providers: [
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            title: 'Rechazar toma',
            message: 'Indica el motivo del rechazo.',
            confirmLabel: 'Confirmar rechazo',
          },
        },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CountReasonDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('exige motivo entre 3 y 250 caracteres y cierra con el texto recortado', () => {
    const ctrl = component as unknown as {
      form: {
        controls: { reason: { setValue: (v: string) => void; hasError: (e: string) => boolean } };
      };
      submit: () => void;
    };

    ctrl.form.controls.reason.setValue('ab');
    ctrl.submit();
    expect(closeSpy).not.toHaveBeenCalled();

    ctrl.form.controls.reason.setValue('  Diferencia sin respaldo en pasillo 1  ');
    ctrl.submit();
    expect(closeSpy).toHaveBeenCalledWith('Diferencia sin respaldo en pasillo 1');
  });
});
