import { provideHttpClient, withInterceptors } from '@angular/common/http';
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
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { ReceiptFormDialog, ReceiptFormDialogData } from './receipt-form-dialog';

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

describe('ReceiptFormDialog', () => {
  let fixture: ComponentFixture<ReceiptFormDialog>;
  let component: ReceiptFormDialog;
  let http: HttpTestingController;
  let dialogRefMock: { close: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    dialogRefMock = { close: vi.fn() };
    const data: ReceiptFormDialogData = { purchaseOrderId: 10 };

    await TestBed.configureTestingModule({
      imports: [ReceiptFormDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([idempotencyInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRefMock },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (perm: string) =>
              perm === 'catalog:cost:read' || perm === 'purchasing:receipt:confirm',
          },
        },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReceiptFormDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();

    const draftReq = http.expectOne('/api/v1/purchase-orders/10/receipt-draft');
    flushJson(draftReq, {
      purchaseOrderId: 10,
      supplierId: 2,
      supplierDocumentType: 'INVOICE',
      supplierDocumentNumber: 'F-123',
      lines: [
        {
          productId: 100,
          purchaseOrderLineId: 1001,
          qtyReceived: 10,
          qtyRejected: 0,
          unitCostNet: 500,
        },
        {
          productId: 200,
          purchaseOrderLineId: 1002,
          qtyReceived: 5,
          qtyRejected: 0,
          unitCostNet: 1200,
        },
      ],
    });

    const orderReq = http.expectOne('/api/v1/purchase-orders/10');
    flushJson(orderReq, {
      id: 10,
      number: 'OC-010',
      supplier: { id: 2, legalName: 'Proveedor Lácteos' },
      lines: [
        { id: 1001, productId: 100, sku: 'LEC-01', name: 'Leche entera 1L' },
        { id: 1002, productId: 200, sku: 'ARR-01', name: 'Arroz 1kg' },
      ],
    });

    // Esperar a que Blob.text() resuelva en draftReq y orderReq y se disparen ambos GET de productos.
    const prod1Req = await vi.waitFor(() => http.expectOne('/api/v1/products/100'));
    flushJson(prod1Req, {
      id: 100,
      sku: 'LEC-01',
      name: 'Leche entera 1L',
      lotControlled: true,
      shelfLifeDays: 30,
    });

    const prod2Req = await vi.waitFor(() => http.expectOne('/api/v1/products/200'));
    flushJson(prod2Req, {
      id: 200,
      sku: 'ARR-01',
      name: 'Arroz 1kg',
      lotControlled: false,
    });

    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('exige lotNumber y expiryDate obligatorios cuando lotControlled es true y los deja opcionales cuando es false', () => {
    const internal = component as unknown as {
      form: ReceiptFormDialog['form'];
      linesArray: ReceiptFormDialog['linesArray'];
    };

    const lotLine = internal.linesArray.at(0);
    const nonLotLine = internal.linesArray.at(1);

    expect(lotLine.controls.lotNumber.hasError('required')).toBe(true);
    expect(lotLine.controls.expiryDate.hasError('required')).toBe(true);
    expect(nonLotLine.controls.lotNumber.valid).toBe(true);
    expect(nonLotLine.controls.expiryDate.valid).toBe(true);
    expect(internal.form.invalid).toBe(true);

    lotLine.controls.lotNumber.setValue('L-2026-11');
    lotLine.controls.expiryDate.setValue('2026-12-18');
    lotLine.updateValueAndValidity();

    expect(lotLine.valid).toBe(true);
    expect(internal.form.valid).toBe(true);
  });

  it('exige differenceReason cuando qtyReceived difiere de lo esperado en la OC', () => {
    const internal = component as unknown as {
      linesArray: ReceiptFormDialog['linesArray'];
    };

    const lotLine = internal.linesArray.at(0);
    lotLine.controls.lotNumber.setValue('L-2026-11');
    lotLine.controls.expiryDate.setValue('2026-12-18');
    lotLine.controls.qtyReceived.setValue(8);
    lotLine.updateValueAndValidity();

    expect(lotLine.controls.differenceReason.hasError('differenceReasonRequired')).toBe(true);

    lotLine.controls.differenceReason.setValue('2 cajas dañadas');
    lotLine.updateValueAndValidity();

    expect(lotLine.controls.differenceReason.hasError('differenceReasonRequired')).toBe(false);
  });

  it('muestra mensaje específico ante SEGREGATION_OF_DUTIES al confirmar recepción', async () => {
    const internal = component as unknown as {
      linesArray: ReceiptFormDialog['linesArray'];
      serverError: () => string | null;
      saveAndConfirm: () => void;
    };

    const lotLine = internal.linesArray.at(0);
    lotLine.controls.lotNumber.setValue('L-2026-11');
    lotLine.controls.expiryDate.setValue('2026-12-18');
    lotLine.updateValueAndValidity();

    internal.saveAndConfirm();

    const createReq = http.expectOne('/api/v1/goods-receipts');
    expect(createReq.request.method).toBe('POST');
    flushJson(createReq, { id: 55, number: 'RC-055', status: 'DRAFT', version: 0, lines: [] });

    const confirmReq = await vi.waitFor(() => http.expectOne('/api/v1/goods-receipts/55/confirm'));
    expect(confirmReq.request.method).toBe('POST');
    expect(confirmReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    flushBlobError(confirmReq, 422, {
      status: 422,
      code: 'SEGREGATION_OF_DUTIES',
      detail: 'Requiere confirmación de otro usuario.',
    });

    await fixture.whenStable();
    fixture.detectChanges();

    expect(internal.serverError()).toContain('segregación de funciones');
    expect(dialogRefMock.close).not.toHaveBeenCalled();
  });
});
