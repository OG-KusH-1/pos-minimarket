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
import { StockTransferResponse } from '../../../api/models/stock-transfer-response';
import { AuthService } from '../../../core/auth/auth.service';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { TransferCancelDialog } from './transfer-cancel-dialog';
import { TransferDetailDialog } from './transfer-detail-dialog';
import { TransferFormDialog, TransferFormDialogData } from './transfer-form-dialog';
import { TransferReceiveDialog } from './transfer-receive-dialog';
import { Transfers } from './transfers';

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

const DRAFT_OUT_TRANSFER: StockTransferResponse = {
  id: 10,
  number: 'TR-0010',
  origin: { id: 1, code: 'CTR', name: 'Local Centro' },
  destination: { id: 2, code: 'NTE', name: 'Local Norte' },
  status: 'DRAFT',
  notes: 'Reposición matinal',
  version: 0,
  lines: [
    {
      id: 101,
      productId: 50,
      sku: 'LEC-01',
      name: 'Leche entera 1L',
      qtySent: 10,
    },
  ],
};

const SENT_IN_TRANSFER: StockTransferResponse = {
  id: 11,
  number: 'TR-0011',
  origin: { id: 2, code: 'NTE', name: 'Local Norte' },
  destination: { id: 1, code: 'CTR', name: 'Local Centro' },
  status: 'SENT',
  sentAt: '2026-11-18T10:00:00Z',
  sentBy: { id: 4, username: 'bodega2', fullName: 'Juan Bodega' },
  version: 1,
  lines: [
    {
      id: 201,
      productId: 50,
      sku: 'LEC-01',
      name: 'Leche entera 1L',
      lotNumber: 'L-2026-A',
      expiryDate: '2026-12-01',
      qtySent: 6,
    },
    {
      id: 202,
      productId: 50,
      sku: 'LEC-01',
      name: 'Leche entera 1L',
      lotNumber: 'L-2026-B',
      expiryDate: '2026-12-15',
      qtySent: 4,
    },
  ],
};

describe('Transfers', () => {
  let fixture: ComponentFixture<Transfers>;
  let component: Transfers;
  let http: HttpTestingController;
  let permissions: Set<string>;
  let activeStoreId: ReturnType<typeof signal<number | null>>;

  async function setup(
    grantedPermissions: readonly string[] = [
      'inventory:transfer:read',
      'inventory:transfer:send',
      'inventory:transfer:receive',
      'inventory:transfer:cancel',
    ],
  ): Promise<void> {
    permissions = new Set(grantedPermissions);
    activeStoreId = signal<number | null>(1);

    await TestBed.configureTestingModule({
      imports: [Transfers],
      providers: [
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
            activeStore: () => ({ id: activeStoreId(), code: 'CTR', name: 'Local Centro' }),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Transfers);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function flushList(
    items: StockTransferResponse[] = [DRAFT_OUT_TRANSFER, SENT_IN_TRANSFER],
  ): void {
    const req = http.expectOne((r) => r.url === '/api/v1/stock-transfers');
    flushJson(req, {
      content: items,
      page: 0,
      size: 20,
      totalElements: items.length,
      totalPages: items.length ? 1 : 0,
      sort: ['id,desc'],
    });
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('renderiza título, listado paginado con direcciones/estados y oculta botón crear sin permiso send', async () => {
    await setup(['inventory:transfer:read']);
    flushList();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('TR-0010');
    });

    const h1 = fixture.nativeElement.querySelector('h1') as HTMLElement | null;
    expect(h1?.textContent?.trim()).toBe('Transferencias');
    expect(fixture.nativeElement.textContent).toContain('Saliente');
    expect(fixture.nativeElement.textContent).toContain('Entrante');
    expect(fixture.nativeElement.textContent).toContain('Borrador');
    expect(fixture.nativeElement.textContent).toContain('En tránsito');
    expect(fixture.nativeElement.textContent).not.toContain('Nueva transferencia');
  });

  it('aplica filtros de dirección, estado y fechas, y valida rango from > to', async () => {
    await setup();
    flushList();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('TR-0010');
    });

    const ctrl = component as unknown as {
      filterForm: { patchValue: (v: Record<string, unknown>) => void };
      applyFilters: () => void;
      clearFilters: () => void;
      filterError: () => string | null;
    };

    ctrl.filterForm.patchValue({ from: '2026-11-20', to: '2026-11-10' });
    ctrl.applyFilters();
    expect(ctrl.filterError()).toBe('La fecha "Desde" no puede ser posterior a "Hasta".');
    http.expectNone((r) => r.url === '/api/v1/stock-transfers');

    ctrl.filterForm.patchValue({
      direction: 'in',
      status: 'SENT',
      from: '2026-11-01',
      to: '2026-11-18',
    });
    ctrl.applyFilters();

    const filteredReq = http.expectOne((r) => r.url === '/api/v1/stock-transfers');
    expect(filteredReq.request.params.get('direction')).toBe('in');
    expect(filteredReq.request.params.get('status')).toBe('SENT');
    expect(filteredReq.request.params.get('from')).toBe('2026-11-01');
    expect(filteredReq.request.params.get('to')).toBe('2026-11-18');
    flushJson(filteredReq, {
      content: [SENT_IN_TRANSFER],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['id,desc'],
    });

    ctrl.clearFilters();
    const resetReq = http.expectOne((r) => r.url === '/api/v1/stock-transfers');
    expect(resetReq.request.params.get('direction')).toBeNull();
    expect(resetReq.request.params.get('status')).toBeNull();
    flushJson(resetReq, {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sort: ['id,desc'],
    });
  });

  it('evalúa permisos contextuales por estado y local activo (origen vs destino)', async () => {
    await setup();
    flushList();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('TR-0010');
    });

    const ctrl = component as unknown as {
      canEditRow: (r: StockTransferResponse) => boolean;
      canSendRow: (r: StockTransferResponse) => boolean;
      canReceiveRow: (r: StockTransferResponse) => boolean;
      canCancelRow: (r: StockTransferResponse) => boolean;
    };

    // DRAFT saliente en local 1: puede editar, despachar y cancelar, pero no recepcionar.
    expect(ctrl.canEditRow(DRAFT_OUT_TRANSFER)).toBe(true);
    expect(ctrl.canSendRow(DRAFT_OUT_TRANSFER)).toBe(true);
    expect(ctrl.canCancelRow(DRAFT_OUT_TRANSFER)).toBe(true);
    expect(ctrl.canReceiveRow(DRAFT_OUT_TRANSFER)).toBe(false);

    // SENT entrante hacia local 1: solo puede recepcionar.
    expect(ctrl.canReceiveRow(SENT_IN_TRANSFER)).toBe(true);
    expect(ctrl.canEditRow(SENT_IN_TRANSFER)).toBe(false);
    expect(ctrl.canSendRow(SENT_IN_TRANSFER)).toBe(false);
    expect(ctrl.canCancelRow(SENT_IN_TRANSFER)).toBe(false);

    // Si el local activo cambia al origen (2), ya no puede recepcionar SENT_IN_TRANSFER.
    activeStoreId.set(2);
    fixture.detectChanges();
    const reloadReq = http.expectOne((r) => r.url === '/api/v1/stock-transfers');
    flushJson(reloadReq, {
      content: [SENT_IN_TRANSFER],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['id,desc'],
    });
    expect(ctrl.canReceiveRow(SENT_IN_TRANSFER)).toBe(false);
  });

  it('despacha un borrador con idempotencia y abre el detalle con las líneas reales desdobladas por FEFO', async () => {
    await setup();
    flushList();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('TR-0010');
    });

    const confirmService = TestBed.inject(ConfirmDialogService);
    vi.spyOn(confirmService, 'confirm').mockReturnValue(of(true));

    const dialog = TestBed.inject(MatDialog);
    const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({
      afterClosed: () => of(undefined),
    } as never);

    const ctrl = component as unknown as {
      confirmAndSend: (r: StockTransferResponse) => void;
    };

    ctrl.confirmAndSend(DRAFT_OUT_TRANSFER);

    const sendReq = http.expectOne('/api/v1/stock-transfers/10/send');
    expect(sendReq.request.method).toBe('POST');
    expect(sendReq.request.headers.get('Idempotency-Key')).toBeTruthy();

    const dispatchedWithFefoSplit: StockTransferResponse = {
      ...DRAFT_OUT_TRANSFER,
      status: 'SENT',
      sentAt: '2026-11-18T11:00:00Z',
      lines: [
        {
          id: 101,
          productId: 50,
          sku: 'LEC-01',
          name: 'Leche entera 1L',
          lotNumber: 'L-01',
          expiryDate: '2026-12-01',
          qtySent: 6,
        },
        {
          id: 102,
          productId: 50,
          sku: 'LEC-01',
          name: 'Leche entera 1L',
          lotNumber: 'L-02',
          expiryDate: '2026-12-15',
          qtySent: 4,
        },
      ],
    };
    flushJson(sendReq, dispatchedWithFefoSplit);

    let reloadReq: TestRequest | undefined;
    await vi.waitFor(() => {
      reloadReq = http.expectOne((r) => r.url === '/api/v1/stock-transfers');
    });
    flushJson(reloadReq!, {
      content: [dispatchedWithFefoSplit],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['id,desc'],
    });

    await vi.waitFor(() => {
      expect(openSpy).toHaveBeenCalledWith(
        TransferDetailDialog,
        expect.objectContaining({
          data: expect.objectContaining({
            transfer: dispatchedWithFefoSplit,
            justDispatched: true,
          }),
        }),
      );
    });
  });
});

describe('TransferFormDialog', () => {
  let fixture: ComponentFixture<TransferFormDialog>;
  let component: TransferFormDialog;
  let http: HttpTestingController;
  let closeSpy: ReturnType<typeof vi.fn>;

  async function setupDialog(data: TransferFormDialogData): Promise<void> {
    closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [TransferFormDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
        {
          provide: StoreContextService,
          useValue: {
            storeId: signal<number | null>(1).asReadonly(),
            activeStore: () => ({ id: 1, code: 'CTR', name: 'Local Centro' }),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TransferFormDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('crea borrador con destino distinto al origen, producto con lote, FEFO automático y vista previa FEFO', async () => {
    await setupDialog({ mode: 'create' });

    const storesReq = http.expectOne((r) => r.url === '/api/v1/stores');
    flushJson(storesReq, {
      content: [
        { id: 1, code: 'CTR', name: 'Local Centro', active: true },
        { id: 2, code: 'NTE', name: 'Local Norte', active: true },
      ],
    });

    const ctrl = component as unknown as {
      stores: () => { id?: number }[];
      form: {
        patchValue: (v: Record<string, unknown>) => void;
      };
      onProductSelected: (e: { option: { value: unknown } }) => void;
      lineGroupAt: (i: number) => {
        controls: {
          qtySent: { setValue: (v: number) => void };
          originLotId: { setValue: (v: number | null) => void };
        };
      };
      previewFefo: (i: number) => void;
      lineMetas: () => { fefoSuggestion: { shortage?: number } | null }[];
      submit: () => void;
    };

    // Excluye el local activo (1) de los destinos disponibles.
    await vi.waitFor(() => {
      expect(ctrl.stores().map((s) => s.id)).toEqual([2]);
    });

    ctrl.onProductSelected({
      option: { value: { id: 50, sku: 'LEC-01', name: 'Leche', saleType: 'UNIT' } },
    });

    const prodReq = http.expectOne('/api/v1/products/50');
    flushJson(prodReq, {
      id: 50,
      sku: 'LEC-01',
      name: 'Leche',
      lotControlled: true,
      unitOfMeasure: 'UNIT',
    });

    let lotsReq: TestRequest | undefined;
    await vi.waitFor(() => {
      lotsReq = http.expectOne((r) => r.url === '/api/v1/lots');
    });
    flushJson(lotsReq!, {
      content: [
        { id: 70, lotNumber: 'L-01', expiryDate: '2026-12-01', quantity: 6 },
        { id: 71, lotNumber: 'L-02', expiryDate: '2026-12-15', quantity: 10 },
      ],
    });

    await vi.waitFor(() => {
      expect(ctrl.lineMetas()[0]).toBeDefined();
    });

    ctrl.form.patchValue({ destinationStoreId: 2, notes: 'Reposición lote' });
    ctrl.lineGroupAt(0).controls.qtySent.setValue(8);
    ctrl.previewFefo(0);

    const fefoReq = http.expectOne((r) => r.url === '/api/v1/lots/fefo');
    expect(fefoReq.request.params.get('productId')).toBe('50');
    expect(fefoReq.request.params.get('quantity')).toBe('8');
    flushJson(fefoReq, {
      productId: 50,
      requestedQty: 8,
      allocations: [
        { lotId: 70, lotNumber: 'L-01', quantity: 6 },
        { lotId: 71, lotNumber: 'L-02', quantity: 2 },
      ],
      shortage: 0,
    });
    await vi.waitFor(() => {
      expect(ctrl.lineMetas()[0]?.fefoSuggestion?.shortage).toBe(0);
    });

    // Dejamos FEFO automático (originLotId = null) y enviamos.
    ctrl.submit();

    const createReq = http.expectOne('/api/v1/stock-transfers');
    expect(createReq.request.method).toBe('POST');
    expect(createReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(createReq.request.body).toEqual({
      destinationStoreId: 2,
      notes: 'Reposición lote',
      lines: [{ productId: 50, qtySent: 8 }],
    });

    const saved = { ...DRAFT_OUT_TRANSFER, id: 99, number: 'TR-0099' };
    flushJson(createReq, saved);

    await vi.waitFor(() => {
      expect(closeSpy).toHaveBeenCalledWith(saved);
    });
  });

  it('edita borrador existente preseleccionando lote por lotNumber y valida líneas duplicadas', async () => {
    const draftWithLot: StockTransferResponse = {
      ...DRAFT_OUT_TRANSFER,
      version: 2,
      lines: [
        {
          id: 101,
          productId: 50,
          sku: 'LEC-01',
          name: 'Leche entera 1L',
          lotNumber: 'L-01',
          expiryDate: '2026-12-01',
          qtySent: 5,
        },
      ],
    };

    await setupDialog({ mode: 'edit', transfer: draftWithLot });

    const storesReq = http.expectOne((r) => r.url === '/api/v1/stores');
    flushJson(storesReq, {
      content: [
        { id: 1, code: 'CTR', name: 'Local Centro', active: true },
        { id: 2, code: 'NTE', name: 'Local Norte', active: true },
      ],
    });

    const prodReq = http.expectOne('/api/v1/products/50');
    flushJson(prodReq, {
      id: 50,
      sku: 'LEC-01',
      name: 'Leche entera 1L',
      lotControlled: true,
      unitOfMeasure: 'UNIT',
    });

    let lotsReq: TestRequest | undefined;
    await vi.waitFor(() => {
      lotsReq = http.expectOne((r) => r.url === '/api/v1/lots');
    });
    flushJson(lotsReq!, {
      content: [{ id: 70, lotNumber: 'L-01', expiryDate: '2026-12-01', quantity: 12 }],
    });

    const ctrl = component as unknown as {
      lineGroupAt: (i: number) => {
        controls: {
          originLotId: { value: number | null; setValue: (v: number | null) => void };
          qtySent: { setValue: (v: number) => void };
        };
      };
      onProductSelected: (e: { option: { value: unknown } }) => void;
      duplicateIndexes: () => ReadonlySet<number>;
      removeLine: (i: number) => void;
      submit: () => void;
    };

    await vi.waitFor(() => {
      expect(ctrl.lineGroupAt(0).controls.originLotId.value).toBe(70);
    });

    // Agregar el mismo producto y asignarle el mismo lote marca duplicado e impide enviar.
    ctrl.onProductSelected({
      option: { value: { id: 50, sku: 'LEC-01', name: 'Leche entera 1L', saleType: 'UNIT' } },
    });
    const prodReq2 = http.expectOne('/api/v1/products/50');
    flushJson(prodReq2, {
      id: 50,
      sku: 'LEC-01',
      name: 'Leche entera 1L',
      lotControlled: true,
      unitOfMeasure: 'UNIT',
    });
    let lotsReq2: TestRequest | undefined;
    await vi.waitFor(() => {
      lotsReq2 = http.expectOne((r) => r.url === '/api/v1/lots');
    });
    flushJson(lotsReq2!, {
      content: [{ id: 70, lotNumber: 'L-01', expiryDate: '2026-12-01', quantity: 12 }],
    });

    ctrl.lineGroupAt(1).controls.originLotId.setValue(70);
    ctrl.submit();
    expect(ctrl.duplicateIndexes().size).toBe(2);
    http.expectNone('/api/v1/stock-transfers/10');

    // Quitamos la línea duplicada y actualizamos la cantidad a 4.
    ctrl.removeLine(1);
    ctrl.lineGroupAt(0).controls.qtySent.setValue(4);
    ctrl.submit();

    const updateReq = http.expectOne('/api/v1/stock-transfers/10');
    expect(updateReq.request.method).toBe('PUT');
    expect(updateReq.request.body).toEqual({
      destinationStoreId: 2,
      notes: 'Reposición matinal',
      version: 2,
      lines: [{ productId: 50, qtySent: 4, originLotId: 70 }],
    });
    flushJson(updateReq, { ...draftWithLot, version: 3 });

    await vi.waitFor(() => {
      expect(closeSpy).toHaveBeenCalledWith({ ...draftWithLot, version: 3 });
    });
  });
});

describe('TransferReceiveDialog', () => {
  let fixture: ComponentFixture<TransferReceiveDialog>;
  let component: TransferReceiveDialog;
  let http: HttpTestingController;
  let closeSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [TransferReceiveDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { transfer: SENT_IN_TRANSFER } },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TransferReceiveDialog);
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

  it('exige motivo de diferencia obligatorio cuando qtyReceived < qtySent y envía recepción con idempotencia', async () => {
    const ctrl = component as unknown as {
      lineGroupAt: (i: number) => {
        controls: {
          qtyReceived: { setValue: (v: number | null) => void; value: number | null };
          differenceReason: {
            setValue: (v: string) => void;
            hasError: (e: string) => boolean;
            getError: (e: string) => unknown;
          };
        };
      };
      summary: () => { differenceLines: number; totalMissing: number };
      submit: () => void;
    };

    // Inicialmente recibe todo completo: sin diferencias ni motivo requerido.
    expect(ctrl.summary()).toEqual({ differenceLines: 0, totalMissing: 0 });
    expect(ctrl.lineGroupAt(0).controls.differenceReason.hasError('differenceRequired')).toBe(
      false,
    );

    // Baja qtyReceived en línea 0 (de 6 a 4): exige motivo obligatorio y no envía sin motivo.
    ctrl.lineGroupAt(0).controls.qtyReceived.setValue(4);
    fixture.detectChanges();

    expect(ctrl.summary()).toEqual({ differenceLines: 1, totalMissing: 2 });
    expect(ctrl.lineGroupAt(0).controls.differenceReason.hasError('differenceRequired')).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('TRANSFER_DIFFERENCE');

    ctrl.submit();
    http.expectNone('/api/v1/stock-transfers/11/receive');

    // Completa el motivo y envía; verifica mapeo de error de servidor y éxito posterior.
    ctrl.lineGroupAt(0).controls.differenceReason.setValue('2 cajas dañadas');
    ctrl.submit();

    const failReq = http.expectOne('/api/v1/stock-transfers/11/receive');
    flushBlobError(failReq, 400, {
      code: 'VALIDATION_ERROR',
      detail: 'Datos inválidos',
      errors: [
        {
          field: 'lines[0].differenceReason',
          code: 'MAX_LENGTH_EXCEEDED',
          message: 'Motivo inválido desde servidor',
        },
      ],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(ctrl.lineGroupAt(0).controls.differenceReason.getError('server')).toBe(
        'Motivo inválido desde servidor',
      );
    });

    ctrl.lineGroupAt(0).controls.differenceReason.setValue('2 unidades quebradas en traslado');
    ctrl.submit();

    const okReq = http.expectOne('/api/v1/stock-transfers/11/receive');
    expect(okReq.request.method).toBe('POST');
    expect(okReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(okReq.request.body).toEqual({
      lines: [
        { lineId: 201, qtyReceived: 4, differenceReason: '2 unidades quebradas en traslado' },
        { lineId: 202, qtyReceived: 4 },
      ],
    });

    const received = { ...SENT_IN_TRANSFER, status: 'RECEIVED' as const };
    flushJson(okReq, received);

    await vi.waitFor(() => {
      expect(closeSpy).toHaveBeenCalledWith(received);
    });
  });
});

describe('TransferCancelDialog', () => {
  let fixture: ComponentFixture<TransferCancelDialog>;
  let component: TransferCancelDialog;
  let http: HttpTestingController;
  let closeSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [TransferCancelDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { transfer: DRAFT_OUT_TRANSFER } },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TransferCancelDialog);
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

  it('exige motivo de al menos 3 caracteres y envía POST /cancel', async () => {
    const ctrl = component as unknown as {
      form: {
        controls: { reason: { setValue: (v: string) => void; hasError: (e: string) => boolean } };
      };
      submit: () => void;
    };

    ctrl.form.controls.reason.setValue('ab');
    ctrl.submit();
    expect(ctrl.form.controls.reason.hasError('minlength')).toBe(true);
    http.expectNone('/api/v1/stock-transfers/10/cancel');

    ctrl.form.controls.reason.setValue('Pedido duplicado por error');
    ctrl.submit();

    const cancelReq = http.expectOne('/api/v1/stock-transfers/10/cancel');
    expect(cancelReq.request.method).toBe('POST');
    expect(cancelReq.request.body).toEqual({ reason: 'Pedido duplicado por error' });
    cancelReq.flush('');

    await vi.waitFor(() => {
      expect(closeSpy).toHaveBeenCalledWith(true);
    });
  });
});

describe('TransferDetailDialog', () => {
  it('muestra las líneas desdobladas por FEFO y el banner de despacho reciente', async () => {
    const closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [TransferDetailDialog],
      providers: [
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            transfer: SENT_IN_TRANSFER,
            justDispatched: true,
            canReceive: true,
          },
        },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
        {
          provide: StoreContextService,
          useValue: {
            storeId: signal<number | null>(1).asReadonly(),
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(TransferDetailDialog);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('TR-0011');
    expect(text).toContain('L-2026-A');
    expect(text).toContain('L-2026-B');
    expect(text).toContain('asignación FEFO por lote');
    expect(text).toContain('Recepcionar');

    TestBed.resetTestingModule();
  });
});
