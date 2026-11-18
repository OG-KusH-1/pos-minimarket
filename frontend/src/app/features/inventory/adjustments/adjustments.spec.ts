import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { StockAdjustmentResponse } from '../../../api/models/stock-adjustment-response';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { OverrideDialog } from '../../../shared/ui/override-dialog';
import { AdjustmentCreateDialog } from './adjustment-create-dialog';
import { AdjustmentDetailDialog } from './adjustment-detail-dialog';
import { AdjustmentRejectDialog } from './adjustment-reject-dialog';
import { Adjustments } from './adjustments';

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

const SAMPLE_ADJUSTMENT: StockAdjustmentResponse = {
  id: 10,
  number: 'L01-AJ-000010',
  reasonCode: 'COUNT_CORRECTION',
  notes: 'Conteo de bodega',
  status: 'PENDING_APPROVAL',
  totalCostImpact: 75000,
  createdAt: '2026-11-18T10:00:00Z',
  requestedBy: { id: 99, username: 'bodeguero', fullName: 'Bodeguero Uno' },
  lines: [
    {
      id: 1,
      productId: 5,
      sku: 'LEC-01',
      name: 'Leche entera 1L',
      lotId: 20,
      lotNumber: 'L2026-01',
      quantityDelta: 5,
      unitCost: 15000,
    },
  ],
};

describe('Adjustments', () => {
  let fixture: ComponentFixture<Adjustments>;
  let component: Adjustments;
  let http: HttpTestingController;
  let permissions: Set<string>;
  let currentUserId: number;
  let activeStoreId: ReturnType<typeof signal<number | null>>;
  let confirmMock: ReturnType<typeof vi.fn>;
  let dialogOpenSpy: ReturnType<typeof vi.fn>;

  async function setup(
    grantedPermissions: readonly string[] = [
      'inventory:adjustment:create',
      'inventory:adjustment:approve',
      'catalog:cost:read',
    ],
    userId = 1,
  ): Promise<void> {
    permissions = new Set(grantedPermissions);
    currentUserId = userId;
    activeStoreId = signal<number | null>(1);
    confirmMock = vi.fn().mockReturnValue(of(true));

    await TestBed.configureTestingModule({
      imports: [Adjustments],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: ConfirmDialogService, useValue: { confirm: confirmMock } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => permissions.has(code),
            user: () => ({ id: currentUserId, username: 'supervisor' }),
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
    const dialog = TestBed.inject(MatDialog);
    dialogOpenSpy = vi.spyOn(dialog, 'open');
    fixture = TestBed.createComponent(Adjustments);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function flushList(items: StockAdjustmentResponse[] = [SAMPLE_ADJUSTMENT]): void {
    const req = http.expectOne((r) => r.url === '/api/v1/stock-adjustments');
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

  it('renderiza bandeja de ajustes, muestra impacto costo con permiso y oculta costos sin permiso', async () => {
    await setup(['inventory:adjustment:create', 'catalog:cost:read']);
    flushList();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L01-AJ-000010');
    });
    expect(fixture.nativeElement.textContent).toContain('Impacto costo');
    expect(fixture.nativeElement.textContent).toContain('$75.000');

    TestBed.resetTestingModule();
    await setup(['inventory:adjustment:create']);
    flushList([{ ...SAMPLE_ADJUSTMENT, totalCostImpact: undefined }]);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L01-AJ-000010');
    });
    expect(fixture.nativeElement.textContent).not.toContain('Impacto costo');
  });

  it('aplica filtros por estado, fechas y producto', async () => {
    await setup();
    flushList();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L01-AJ-000010');
    });

    const ctrl = component as unknown as {
      filterForm: { patchValue: (v: Record<string, unknown>) => void };
      applyFilters: () => void;
    };
    ctrl.filterForm.patchValue({
      status: 'PENDING_APPROVAL',
      from: '2026-11-01',
      to: '2026-11-18',
      productQuery: 'no-existe',
    });
    ctrl.applyFilters();

    const filteredReq = http.expectOne((r) => r.url === '/api/v1/stock-adjustments');
    expect(filteredReq.request.params.get('status')).toBe('PENDING_APPROVAL');
    expect(filteredReq.request.params.get('from')).toBe('2026-11-01');
    expect(filteredReq.request.params.get('to')).toBe('2026-11-18');
    expect(filteredReq.request.params.has('productQuery')).toBe(false);
    flushJson(filteredReq, {
      content: [SAMPLE_ADJUSTMENT],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['createdAt,desc'],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain(
        'No hay ajustes registrados con los filtros seleccionados.',
      );
    });
  });

  it('respeta segregación de funciones impidiendo aprobar ajuste propio', async () => {
    await setup(['inventory:adjustment:create', 'inventory:adjustment:approve'], 99);
    flushList();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L01-AJ-000010');
    });

    const ctrl = component as unknown as {
      isOwnRequest: (row: StockAdjustmentResponse) => boolean;
      canResolveAdjustment: (row: StockAdjustmentResponse) => boolean;
      approveAdjustment: (row: StockAdjustmentResponse) => void;
    };

    expect(ctrl.isOwnRequest(SAMPLE_ADJUSTMENT)).toBe(true);
    expect(ctrl.canResolveAdjustment(SAMPLE_ADJUSTMENT)).toBe(false);
    ctrl.approveAdjustment(SAMPLE_ADJUSTMENT);
    expect(confirmMock).not.toHaveBeenCalled();
  });

  it('aprueba ajuste solicitando override de supervisor ante 422 OVERRIDE_REQUIRED y reintentando con X-Override-Token', async () => {
    await setup();
    flushList();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L01-AJ-000010');
    });

    dialogOpenSpy.mockReturnValue({
      afterClosed: () =>
        of({
          overrideToken: 'ovr-adj-123',
          authorizationId: 44,
          expiresAt: '2026-11-18T12:00:00Z',
        }),
    } as never);

    const ctrl = component as unknown as {
      approveAdjustment: (row: StockAdjustmentResponse) => void;
    };
    ctrl.approveAdjustment(SAMPLE_ADJUSTMENT);

    const firstApprove = http.expectOne('/api/v1/stock-adjustments/10/approve');
    expect(firstApprove.request.method).toBe('POST');
    expect(firstApprove.request.headers.has('X-Override-Token')).toBe(false);
    flushBlobError(firstApprove, 422, {
      code: 'OVERRIDE_REQUIRED',
      detail: 'El impacto supera el límite del supervisor.',
      action: 'STOCK_ADJUSTMENT_APPROVAL',
      context: { entityId: 10, amount: 75000 },
    });

    await vi.waitFor(() => {
      expect(dialogOpenSpy).toHaveBeenCalledWith(
        OverrideDialog,
        expect.objectContaining({
          data: {
            action: 'STOCK_ADJUSTMENT_APPROVAL',
            context: { entityId: 10, amount: 75000 },
          },
        }),
      );
    });

    const retryApprove = await vi.waitFor(() =>
      http.expectOne('/api/v1/stock-adjustments/10/approve'),
    );
    expect(retryApprove.request.headers.get('X-Override-Token')).toBe('ovr-adj-123');
    flushJson(retryApprove, { ...SAMPLE_ADJUSTMENT, status: 'APPROVED' });

    const reloadReq = await vi.waitFor(() =>
      http.expectOne((r) => r.url === '/api/v1/stock-adjustments'),
    );
    flushJson(reloadReq, {
      content: [{ ...SAMPLE_ADJUSTMENT, status: 'APPROVED' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['createdAt,desc'],
    });
  });

  it('abre diálogo de rechazo y recarga el listado al confirmar rechazo', async () => {
    await setup();
    flushList();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('L01-AJ-000010');
    });

    dialogOpenSpy.mockReturnValue({
      afterClosed: () => of({ ...SAMPLE_ADJUSTMENT, status: 'REJECTED' }),
    } as never);

    const ctrl = component as unknown as {
      openRejectDialog: (row: StockAdjustmentResponse) => void;
    };
    ctrl.openRejectDialog(SAMPLE_ADJUSTMENT);

    expect(dialogOpenSpy).toHaveBeenCalledWith(
      AdjustmentRejectDialog,
      expect.objectContaining({
        data: { adjustment: SAMPLE_ADJUSTMENT },
      }),
    );

    const reloadReq = await vi.waitFor(() =>
      http.expectOne((r) => r.url === '/api/v1/stock-adjustments'),
    );
    flushJson(reloadReq, {
      content: [{ ...SAMPLE_ADJUSTMENT, status: 'REJECTED' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['createdAt,desc'],
    });
  });
});

describe('AdjustmentCreateDialog', () => {
  let fixture: ComponentFixture<AdjustmentCreateDialog>;
  let component: AdjustmentCreateDialog;
  let http: HttpTestingController;
  let closeSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [AdjustmentCreateDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdjustmentCreateDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('crea solicitud multi-línea con producto sin lote y producto con lote + vencimiento', async () => {
    const ctrl = component as unknown as {
      form: {
        patchValue: (v: Record<string, unknown>) => void;
        controls: {
          lines: {
            at: (i: number) => {
              patchValue: (v: Record<string, unknown>) => void;
              valid: boolean;
            };
          };
        };
      };
      addLine: () => void;
      onLineProductSelected: (index: number, e: unknown) => void;
      generateLotNumber: (index: number) => void;
      submit: () => void;
    };

    ctrl.form.patchValue({ reasonCode: 'FOUND_GOODS', notes: 'Reposición de inventario' });
    ctrl.onLineProductSelected(0, { option: { value: { id: 10, sku: 'ARR-1', name: 'Arroz' } } });

    const prod1Req = http.expectOne('/api/v1/products/10');
    flushJson(prod1Req, {
      id: 10,
      sku: 'ARR-1',
      name: 'Arroz',
      unitOfMeasure: 'UNIT',
      lotControlled: false,
    });
    await vi.waitFor(() => {
      fixture.detectChanges();
    });
    ctrl.form.controls.lines.at(0).patchValue({ quantityDelta: 4 });

    ctrl.addLine();
    ctrl.onLineProductSelected(1, { option: { value: { id: 20, sku: 'LEC-1', name: 'Leche' } } });

    const prod2Req = http.expectOne('/api/v1/products/20');
    flushJson(prod2Req, {
      id: 20,
      sku: 'LEC-1',
      name: 'Leche',
      unitOfMeasure: 'UNIT',
      lotControlled: true,
      shelfLifeDays: 30,
    });
    const lots2Req = await vi.waitFor(() => http.expectOne((r) => r.url === '/api/v1/lots'));
    flushJson(lots2Req, {
      content: [],
      page: 0,
      size: 200,
      totalElements: 0,
      totalPages: 0,
      sort: [],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Nuevo lote');
    });

    ctrl.form.controls.lines.at(1).patchValue({ quantityDelta: 6, lotMode: 'new' });
    ctrl.generateLotNumber(1);
    // Falta expiryDate obligatorio por shelfLifeDays -> no debe enviar aún
    ctrl.submit();
    http.expectNone('/api/v1/stock-adjustments');

    ctrl.form.controls.lines.at(1).patchValue({
      lotNumber: 'LOT-2026-A',
      expiryDate: '2026-12-15',
    });
    ctrl.submit();

    const createReq = http.expectOne('/api/v1/stock-adjustments');
    expect(createReq.request.method).toBe('POST');
    expect(createReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(createReq.request.body).toEqual({
      reasonCode: 'FOUND_GOODS',
      notes: 'Reposición de inventario',
      lines: [
        { productId: 10, quantityDelta: 4 },
        { productId: 20, quantityDelta: 6, lotNumber: 'LOT-2026-A', expiryDate: '2026-12-15' },
      ],
    });
    const created = { ...SAMPLE_ADJUSTMENT, id: 77, number: 'L01-AJ-000077' };
    flushJson(createReq, created);

    await vi.waitFor(() => {
      expect(closeSpy).toHaveBeenCalledWith(created);
    });
  });
});

describe('AdjustmentRejectDialog y AdjustmentDetailDialog', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('AdjustmentRejectDialog valida motivo obligatorio y envía POST /reject', async () => {
    const closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [AdjustmentRejectDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { adjustment: SAMPLE_ADJUSTMENT } },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    }).compileComponents();

    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(AdjustmentRejectDialog);
    const component = fixture.componentInstance as unknown as {
      form: { patchValue: (v: Record<string, unknown>) => void };
      submit: () => void;
    };
    fixture.detectChanges();

    component.form.patchValue({ reason: 'ab' });
    component.submit();
    http.expectNone('/api/v1/stock-adjustments/10/reject');

    component.form.patchValue({ reason: 'Cantidad incorrecta en conteo' });
    component.submit();

    const req = http.expectOne('/api/v1/stock-adjustments/10/reject');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Cantidad incorrecta en conteo' });
    const rejected = { ...SAMPLE_ADJUSTMENT, status: 'REJECTED' as const };
    flushJson(req, rejected);

    await vi.waitFor(() => {
      expect(closeSpy).toHaveBeenCalledWith(rejected);
    });
    http.verify();
  });

  it('AdjustmentDetailDialog respeta segregación de funciones y visibilidad de costos', async () => {
    const closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [AdjustmentDetailDialog],
      providers: [
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            adjustment: SAMPLE_ADJUSTMENT,
            canReadCosts: false,
            canApprove: true,
            currentUserId: 99,
          },
        },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(AdjustmentDetailDialog);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Leche entera 1L');
    expect(text).toContain('Segregación de funciones');
    expect(text).not.toContain('$75.000');
    expect(text).not.toContain('Aprobar');
  });
});
