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
import { provideApiConfiguration } from '../../api/api-configuration';
import { ShrinkageResponse } from '../../api/models/shrinkage-response';
import { AuthService } from '../../core/auth/auth.service';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../core/http/idempotency.interceptor';
import { StoreContextService } from '../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog';
import { OverrideDialog } from '../../shared/ui/override-dialog';
import { Shrinkage } from './shrinkage';
import { ShrinkageCreateDialog } from './shrinkage-create-dialog';
import { ShrinkageDetailDialog } from './shrinkage-detail-dialog';
import { ShrinkageRejectDialog } from './shrinkage-reject-dialog';

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

const SAMPLE_SHRINKAGE: ShrinkageResponse = {
  id: 20,
  number: 'L01-MR-000020',
  product: { id: 5, sku: 'LEC-01', name: 'Leche entera 1L' },
  lot: { id: 30, lotNumber: 'L2026-01', expiryDate: '2026-12-30' },
  shrinkageType: 'EXPIRED',
  quantity: 4,
  unitCost: 1200,
  totalCost: 4800,
  reason: 'Vencida en góndola',
  status: 'PENDING_APPROVAL',
  autoApproved: false,
  affectsStock: true,
  reportedBy: { id: 99, username: 'bodeguero', fullName: 'Bodeguero Uno' },
  responsible: { id: 99, username: 'bodeguero', fullName: 'Bodeguero Uno' },
  occurredAt: '2026-11-18T11:30:00Z',
};

const SYSTEMIC_SHRINKAGE: ShrinkageResponse = {
  ...SAMPLE_SHRINKAGE,
  id: 21,
  number: 'L01-MR-000021',
  shrinkageType: 'TRANSFER_DIFFERENCE',
  affectsStock: false,
  transferLineId: 77,
};

describe('Shrinkage', () => {
  let fixture: ComponentFixture<Shrinkage>;
  let component: Shrinkage;
  let http: HttpTestingController;
  let permissions: Set<string>;
  let currentUserId: number;
  let activeStoreId: ReturnType<typeof signal<number | null>>;
  let confirmMock: ReturnType<typeof vi.fn>;
  let dialogOpenSpy: ReturnType<typeof vi.fn>;

  async function setup(
    grantedPermissions: readonly string[] = [
      'shrinkage:record:read',
      'shrinkage:record:create',
      'shrinkage:record:approve',
    ],
    userId = 1,
  ): Promise<void> {
    permissions = new Set(grantedPermissions);
    currentUserId = userId;
    activeStoreId = signal<number | null>(1);
    confirmMock = vi.fn().mockReturnValue(of(true));

    await TestBed.configureTestingModule({
      imports: [Shrinkage],
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
            user: () => ({ id: currentUserId, username: 'supervisor', fullName: 'Supervisor' }),
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: activeStoreId.asReadonly(),
            activeStore: () => ({ id: 1, code: 'L01', name: 'Local Centro' }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(Shrinkage);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    dialogOpenSpy = vi.spyOn(TestBed.inject(MatDialog), 'open');
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  function flushInitialList(items: ShrinkageResponse[] = [SAMPLE_SHRINKAGE]): void {
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === '/api/v1/shrinkages');
    flushJson(req, {
      content: items,
      page: 0,
      size: 20,
      totalElements: items.length,
      totalPages: items.length ? 1 : 0,
      sort: ['occurredAt,desc'],
    });
  }

  it('carga la bandeja paginada de mermas y distingue mermas sistémicas', async () => {
    await setup();
    flushInitialList([SAMPLE_SHRINKAGE, SYSTEMIC_SHRINKAGE]);

    await vi.waitFor(() => {
      fixture.detectChanges();
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('L01-MR-000020');
      expect(text).toContain('L01-MR-000021');
      expect(text).toContain('Sistémica');
      expect(text).toContain('Costo total');
    });
  });

  it('oculta columna de costos y botón de registro cuando faltan permisos', async () => {
    await setup(['shrinkage:record:read']);
    flushInitialList([{ ...SAMPLE_SHRINKAGE, unitCost: undefined, totalCost: undefined }]);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('L01-MR-000020');
    });

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).not.toContain('Costo total');
    expect(el.textContent).not.toContain('Registrar merma');
  });

  it('muestra columna de costos con catalog:cost:read aunque no tenga shrinkage:record:approve', async () => {
    await setup(['shrinkage:record:read', 'catalog:cost:read']);
    flushInitialList();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Costo total');
    });
  });

  it('respeta segregación de funciones deshabilitando aprobar/rechazar en mermas propias', async () => {
    await setup(
      ['shrinkage:record:read', 'shrinkage:record:create', 'shrinkage:record:approve'],
      99,
    );
    flushInitialList();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('L01-MR-000020');
    });

    expect(
      (component as unknown as { isOwnReport(r: ShrinkageResponse): boolean }).isOwnReport(
        SAMPLE_SHRINKAGE,
      ),
    ).toBe(true);
    expect(
      (
        component as unknown as { canResolveShrinkage(r: ShrinkageResponse): boolean }
      ).canResolveShrinkage(SAMPLE_SHRINKAGE),
    ).toBe(false);
  });

  it('aplica filtros por estado, tipo de merma y fechas al recargar la bandeja', async () => {
    await setup();
    flushInitialList();

    const comp = component as unknown as {
      filterForm: { patchValue: (v: Record<string, unknown>) => void };
      applyFilters: () => void;
    };
    comp.filterForm.patchValue({
      status: 'APPROVED',
      shrinkageType: 'THEFT_LOSS',
      from: '2026-11-01',
      to: '2026-11-18',
    });
    comp.applyFilters();

    const req = http.expectOne((r) => r.url === '/api/v1/shrinkages');
    expect(req.request.params.get('status')).toBe('APPROVED');
    expect(req.request.params.get('shrinkageType')).toBe('THEFT_LOSS');
    expect(req.request.params.get('from')).toBe('2026-11-01');
    expect(req.request.params.get('to')).toBe('2026-11-18');
    flushJson(req, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });

  it('aprueba con SupervisorOverrideService cuando el backend responde 422 OVERRIDE_REQUIRED', async () => {
    await setup();
    flushInitialList();

    dialogOpenSpy.mockImplementation((dialogComponent) => {
      if (dialogComponent === OverrideDialog) {
        return {
          afterClosed: () => of({ overrideToken: 'sup-token-123' }),
        } as unknown as MatDialogRef<unknown>;
      }
      return { afterClosed: () => of(undefined) } as unknown as MatDialogRef<unknown>;
    });

    (component as unknown as { approveShrinkage(r: ShrinkageResponse): void }).approveShrinkage(
      SAMPLE_SHRINKAGE,
    );

    const firstAttempt = http.expectOne('/api/v1/shrinkages/20/approve');
    expect(firstAttempt.request.headers.has('X-Override-Token')).toBe(false);
    flushBlobError(firstAttempt, 422, {
      status: 422,
      code: 'OVERRIDE_REQUIRED',
      detail: 'Supera límite de aprobación de merma.',
      action: 'SHRINKAGE_APPROVAL',
      context: { entityId: 20, amount: 4800 },
    });

    const retry = await vi.waitFor(() => http.expectOne('/api/v1/shrinkages/20/approve'));
    expect(retry.request.headers.get('X-Override-Token')).toBe('sup-token-123');
    flushJson(retry, { ...SAMPLE_SHRINKAGE, status: 'APPROVED' });

    const reloadReq = await vi.waitFor(() => http.expectOne((r) => r.url === '/api/v1/shrinkages'));
    flushJson(reloadReq, {
      content: [{ ...SAMPLE_SHRINKAGE, status: 'APPROVED' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
  });
});

describe('ShrinkageCreateDialog', () => {
  let fixture: ComponentFixture<ShrinkageCreateDialog>;
  let dialog: ShrinkageCreateDialog;
  let http: HttpTestingController;
  let dialogCloseSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    dialogCloseSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [ShrinkageCreateDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close: dialogCloseSpy } },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => code === 'shrinkage:record:create',
            user: () => ({ id: 5, username: 'bodeguero', fullName: 'Bodeguero' }),
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: signal<number | null>(1).asReadonly(),
            activeStore: () => ({ id: 1, code: 'L01', name: 'Local Centro' }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ShrinkageCreateDialog);
    dialog = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  async function selectProduct(
    id: number,
    lotControlled: boolean,
    unitOfMeasure: 'UNIT' | 'KG' = 'UNIT',
  ): Promise<void> {
    (
      dialog as unknown as {
        onProductSelected(e: {
          option: { value: { id: number; sku: string; name: string } };
        }): void;
      }
    ).onProductSelected({
      option: { value: { id, sku: `SKU-${id}`, name: `Producto ${id}` } },
    });

    const prodReq = http.expectOne(`/api/v1/products/${id}`);
    flushJson(prodReq, {
      id,
      sku: `SKU-${id}`,
      name: `Producto ${id}`,
      lotControlled,
      unitOfMeasure,
    });

    if (lotControlled) {
      const lotsReq = await vi.waitFor(() => http.expectOne((r) => r.url === '/api/v1/lots'));
      flushJson(lotsReq, {
        content: [{ id: 301, lotNumber: 'LOT-301', expiryDate: '2026-12-31', quantity: 12 }],
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
      });
      await vi.waitFor(() => {
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Lote');
      });
    } else {
      await vi.waitFor(() => {
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain(`SKU-${id}`);
      });
    }
  }

  it('exige lote obligatorio si lotControlled=true y lo oculta/omite si lotControlled=false', async () => {
    const form = (dialog as unknown as { form: ShrinkageCreateDialog['form'] }).form;

    await selectProduct(10, true, 'UNIT');
    expect(form.controls.lotId.hasError('required')).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Lote');

    form.controls.lotId.setValue(301);
    expect(form.controls.lotId.valid).toBe(true);

    await selectProduct(11, false, 'UNIT');
    expect(form.controls.lotId.value).toBeNull();
    expect(form.controls.lotId.valid).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('LOT-301');
  });

  it('exige motivo obligatorio solo para THEFT_LOSS e INTERNAL_CONSUMPTION', () => {
    const form = (dialog as unknown as { form: ShrinkageCreateDialog['form'] }).form;

    form.controls.shrinkageType.setValue('BROKEN');
    expect(form.controls.reason.valid).toBe(true);

    form.controls.shrinkageType.setValue('THEFT_LOSS');
    expect(form.controls.reason.hasError('required')).toBe(true);

    form.controls.reason.setValue('   ');
    expect(form.controls.reason.hasError('required')).toBe(true);

    form.controls.reason.setValue('Botella sustraída en pasillo 3');
    expect(form.controls.reason.valid).toBe(true);

    form.controls.shrinkageType.setValue('INTERNAL_CONSUMPTION');
    form.controls.reason.setValue('');
    expect(form.controls.reason.hasError('required')).toBe(true);
  });

  it('valida cantidad entera para productos UNIT y permite decimales en KG', async () => {
    const form = (dialog as unknown as { form: ShrinkageCreateDialog['form'] }).form;

    await selectProduct(10, false, 'UNIT');
    form.controls.quantity.setValue(1.5);
    expect(form.controls.quantity.hasError('integerRequired')).toBe(true);

    form.controls.quantity.setValue(2);
    expect(form.controls.quantity.valid).toBe(true);

    await selectProduct(12, false, 'KG');
    form.controls.quantity.setValue(0.75);
    expect(form.controls.quantity.valid).toBe(true);
  });

  it('adjunta evidencia opcional y envía POST /api/v1/shrinkages con Idempotency-Key', async () => {
    const form = (dialog as unknown as { form: ShrinkageCreateDialog['form'] }).form;
    await selectProduct(10, true, 'UNIT');

    const file = new File(['bytes'], 'foto.jpg', { type: 'image/jpeg' });
    (
      dialog as unknown as {
        onEvidenceSelected(e: { target: { files: File[]; value: string } }): void;
      }
    ).onEvidenceSelected({
      target: { files: [file], value: 'foto.jpg' },
    });

    const uploadReq = http.expectOne((r) => r.url === '/api/v1/files');
    expect(uploadReq.request.params.get('category')).toBe('SHRINKAGE_EVIDENCE');
    flushJson(uploadReq, { id: 77, originalName: 'foto.jpg', category: 'SHRINKAGE_EVIDENCE' });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('foto.jpg');
    });

    form.patchValue({
      lotId: 301,
      shrinkageType: 'THEFT_LOSS',
      quantity: 2,
      reason: 'Extravío detectado en inventario',
    });

    (dialog as unknown as { submit(): void }).submit();

    const createReq = http.expectOne('/api/v1/shrinkages');
    expect(createReq.request.method).toBe('POST');
    expect(createReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(createReq.request.body).toMatchObject({
      productId: 10,
      lotId: 301,
      shrinkageType: 'THEFT_LOSS',
      quantity: 2,
      reason: 'Extravío detectado en inventario',
      evidenceFileId: 77,
      responsibleUserId: 5,
    });

    flushJson(createReq, { id: 50, number: 'L01-MR-000050', status: 'PENDING_APPROVAL' });
    await vi.waitFor(() => {
      expect(dialogCloseSpy).toHaveBeenCalledWith(
        expect.objectContaining({ id: 50, number: 'L01-MR-000050' }),
      );
    });
  });
});

describe('ShrinkageDetailDialog y ShrinkageRejectDialog', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('ShrinkageDetailDialog oculta costos cuando canReadCosts es false', async () => {
    await TestBed.configureTestingModule({
      imports: [ShrinkageDetailDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            shrinkage: SYSTEMIC_SHRINKAGE,
            canReadCosts: false,
            canApprove: false,
            currentUserId: 1,
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(ShrinkageDetailDialog);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Sistémica');
    expect(text).not.toContain('Costo total');
    expect(text).not.toContain('Costo unitario');
  });

  it('ShrinkageRejectDialog exige motivo de 3 a 250 caracteres y envía POST /reject', async () => {
    const closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [ShrinkageRejectDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close: closeSpy } },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { shrinkage: SAMPLE_SHRINKAGE } },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(ShrinkageRejectDialog);
    const component = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    const form = (component as unknown as { form: ShrinkageRejectDialog['form'] }).form;
    form.controls.reason.setValue('ab');
    expect(form.controls.reason.hasError('trimmedMinLength')).toBe(true);

    form.controls.reason.setValue('Cantidad mal contada');
    (component as unknown as { submit(): void }).submit();

    const req = http.expectOne('/api/v1/shrinkages/20/reject');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Cantidad mal contada' });
    flushJson(req, { ...SAMPLE_SHRINKAGE, status: 'REJECTED' });
    await vi.waitFor(() => {
      expect(closeSpy).toHaveBeenCalledWith(expect.objectContaining({ status: 'REJECTED' }));
    });
    http.verify();
  });
});
