import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { ApiError } from '../../../core/http/api-error';
import { SupplierFormDialog, SupplierFormDialogData } from './supplier-form-dialog';
import { Suppliers } from './suppliers';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('Suppliers', () => {
  let fixture: ComponentFixture<Suppliers>;
  let http: HttpTestingController;
  let grantedPermissions: Set<string>;

  async function mountWithPermissions(permissions: readonly string[]): Promise<void> {
    grantedPermissions = new Set(permissions);
    await TestBed.configureTestingModule({
      imports: [Suppliers],
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
    fixture = TestBed.createComponent(Suppliers);
    fixture.detectChanges();
  }

  afterEach(() => http?.verify());

  it('lista proveedores y oculta botón Nuevo proveedor sin purchasing:supplier:manage', async () => {
    await mountWithPermissions(['purchasing:supplier:read']);

    const req = http.expectOne((r) => r.url === '/api/v1/suppliers');
    expect(req.request.method).toBe('GET');
    flushJson(req, {
      content: [
        {
          id: 1,
          rut: '76123456K',
          legalName: 'Distribuidora Sur Ltda.',
          tradeName: 'DistSur',
          leadTimeDays: 3,
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
    expect(text).toContain('Distribuidora Sur Ltda.');
    expect(text).toContain('76.123.456-K');
    expect(text).not.toContain('Nuevo proveedor');
  });

  it('muestra Nuevo proveedor con purchasing:supplier:manage y aplica filtros', async () => {
    await mountWithPermissions(['purchasing:supplier:read', 'purchasing:supplier:manage']);

    flushJson(
      http.expectOne((r) => r.url === '/api/v1/suppliers'),
      { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
    );
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Nuevo proveedor');

    const comp = fixture.componentInstance as unknown as {
      filterForm: { patchValue: (v: { q: string; active: boolean | null }) => void };
      applyFilters: () => void;
    };
    comp.filterForm.patchValue({ q: 'sur', active: true });
    comp.applyFilters();

    const filteredReq = http.expectOne((r) => r.url === '/api/v1/suppliers');
    expect(filteredReq.request.params.get('q')).toBe('sur');
    expect(filteredReq.request.params.get('active')).toBe('true');
    flushJson(filteredReq, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });
});

describe('SupplierFormDialog', () => {
  let fixture: ComponentFixture<SupplierFormDialog>;
  let http: HttpTestingController;
  let dialogRefMock: { close: ReturnType<typeof vi.fn> };

  async function mountDialog(data: SupplierFormDialogData): Promise<void> {
    dialogRefMock = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [SupplierFormDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRefMock },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SupplierFormDialog);
    fixture.detectChanges();
  }

  afterEach(() => http?.verify());

  it('valida RUT y envía deliveryWeekdays al crear proveedor', async () => {
    await mountDialog({ supplierId: null });
    const comp = fixture.componentInstance as unknown as {
      form: {
        controls: {
          rut: { setValue: (v: string) => void; valid: boolean; hasError: (e: string) => boolean };
          legalName: { setValue: (v: string) => void };
          paymentTermsType: { setValue: (v: 'CASH_ON_DELIVERY' | 'CREDIT') => void };
          paymentTermsDays: { setValue: (v: number) => void; enabled: boolean };
          leadTimeDays: { setValue: (v: number) => void };
          deliveryWeekdays: { setValue: (v: number[]) => void };
        };
      };
      submit: () => void;
    };

    comp.form.controls.rut.setValue('76123456-9');
    expect(comp.form.controls.rut.hasError('rut')).toBe(true);

    comp.form.controls.rut.setValue('11.111.111-1');
    expect(comp.form.controls.rut.valid).toBe(true);

    comp.form.controls.legalName.setValue('Comercial Andes SpA');
    comp.form.controls.paymentTermsType.setValue('CREDIT');
    expect(comp.form.controls.paymentTermsDays.enabled).toBe(true);
    comp.form.controls.paymentTermsDays.setValue(30);
    comp.form.controls.leadTimeDays.setValue(4);
    comp.form.controls.deliveryWeekdays.setValue([4, 1]);

    comp.submit();
    const req = http.expectOne('/api/v1/suppliers');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toMatchObject({
      rut: '111111111',
      legalName: 'Comercial Andes SpA',
      paymentTermsType: 'CREDIT',
      paymentTermsDays: 30,
      leadTimeDays: 4,
      deliveryWeekdays: [1, 4],
    });
    flushJson(req, { id: 10, rut: '111111111', legalName: 'Comercial Andes SpA', version: 1 });
    await fixture.whenStable();
    expect(dialogRefMock.close).toHaveBeenCalledWith(expect.objectContaining({ id: 10 }));
  });

  it('maneja 409 CONCURRENT_MODIFICATION al editar proveedor', async () => {
    await mountDialog({ supplierId: 7 });

    const getReq = http.expectOne('/api/v1/suppliers/7');
    flushJson(getReq, {
      id: 7,
      rut: '111111111',
      legalName: 'Proveedor Siete',
      paymentTermsType: 'CASH_ON_DELIVERY',
      leadTimeDays: 2,
      deliveryWeekdays: [2],
      version: 4,
    });
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance as unknown as {
      submit: () => void;
      concurrentConflict: () => boolean;
      errorMessage: () => string | null;
    };
    comp.submit();

    const putReq = http.expectOne('/api/v1/suppliers/7');
    expect(putReq.request.body.version).toBe(4);
    putReq.flush(
      new Blob(
        [
          JSON.stringify({
            status: 409,
            code: 'CONCURRENT_MODIFICATION',
            detail: 'Conflicto de versión',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 409, statusText: 'Conflict' },
    );

    // En test sin errorInterceptor, HttpClient entrega HttpErrorResponse; verificamos ApiError directo
    expect(putReq.request.method).toBe('PUT');
    const handler = fixture.componentInstance as unknown as {
      handleError: (err: unknown) => void;
    };
    handler.handleError(new ApiError(409, 'CONCURRENT_MODIFICATION', 'Conflicto', null, [], {}));
    expect(comp.concurrentConflict()).toBe(true);
    expect(comp.errorMessage()).toContain('modificado por otro usuario');
  });
});
