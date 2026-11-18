import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import {
  ProductSupplierFormDialog,
  ProductSupplierFormDialogData,
} from './product-supplier-form-dialog';
import { computeUnitCostNet } from './supplier-validators';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('ProductSupplierFormDialog', () => {
  let fixture: ComponentFixture<ProductSupplierFormDialog>;
  let http: HttpTestingController;
  let dialogRefMock: { close: ReturnType<typeof vi.fn> };
  let grantedPermissions: Set<string>;

  async function mount(
    permissions: readonly string[],
    data: ProductSupplierFormDialogData = { supplierId: 5, supplierName: 'Distribuidora Sur' },
  ): Promise<void> {
    grantedPermissions = new Set(permissions);
    dialogRefMock = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [ProductSupplierFormDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRefMock },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => grantedPermissions.has(code),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProductSupplierFormDialog);
    fixture.detectChanges();
  }

  afterEach(() => http?.verify());

  it('calcula unitCostNet = 500 para caja de 12 a $6.000 (HU-PRV-02)', async () => {
    expect(computeUnitCostNet(6000, 12)).toBe(500);

    await mount(['purchasing:product-supplier:manage', 'catalog:cost:read']);

    const comp = fixture.componentInstance as unknown as {
      form: {
        controls: {
          purchaseUnitName: { setValue: (v: string) => void };
          unitsPerPurchaseUnit: { setValue: (v: number) => void };
          costPerPurchaseUnit: { setValue: (v: number) => void };
          minOrderQty: { setValue: (v: number) => void };
          isPreferred: { setValue: (v: boolean) => void };
        };
      };
      previewUnitCostNet: () => number | null;
      submit: () => void;
    };

    fixture.componentInstance.selectProduct({
      id: 10,
      sku: 'BEB-001',
      name: 'Bebida 350ml',
      saleType: 'UNIT',
    });

    comp.form.controls.purchaseUnitName.setValue('Caja');
    comp.form.controls.unitsPerPurchaseUnit.setValue(12);
    comp.form.controls.costPerPurchaseUnit.setValue(6000);
    comp.form.controls.minOrderQty.setValue(1);
    comp.form.controls.isPreferred.setValue(true);
    fixture.detectChanges();

    expect(comp.previewUnitCostNet()).toBe(500);

    comp.submit();
    const postReq = http.expectOne('/api/v1/product-suppliers');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.body).toMatchObject({
      productId: 10,
      supplierId: 5,
      purchaseUnitName: 'Caja',
      unitsPerPurchaseUnit: 12,
      costPerPurchaseUnit: 6000,
      minOrderQty: 1,
      isPreferred: true,
    });
    flushJson(postReq, { id: 88, unitCostNet: 500 });
    await fixture.whenStable();
    expect(dialogRefMock.close).toHaveBeenCalledWith(expect.objectContaining({ id: 88 }));
  });

  it('rechaza productos de tipo PACK al seleccionarlos (HU-PRV-02)', async () => {
    await mount(['purchasing:product-supplier:manage', 'catalog:cost:read']);

    fixture.componentInstance.selectProduct({
      id: 22,
      sku: 'PCK-001',
      name: 'Six Pack Bebida',
      saleType: 'PACK',
    });
    fixture.detectChanges();

    const comp = fixture.componentInstance as unknown as {
      form: { controls: { productId: { hasError: (e: string) => boolean; value: number | null } } };
      errorMessage: () => string | null;
    };

    expect(comp.form.controls.productId.value).toBeNull();
    expect(comp.form.controls.productId.hasError('packNotAllowed')).toBe(true);
    expect(comp.errorMessage()).toContain('PACK');
  });

  it('oculta los campos de costo cuando falta catalog:cost:read', async () => {
    await mount(['purchasing:product-supplier:manage']);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Costo neto por unidad de compra');
    expect(text).not.toContain('unitCostNet');
  });
});
