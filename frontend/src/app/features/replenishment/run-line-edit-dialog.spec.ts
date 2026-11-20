import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ProductSupplierResponse } from '../../api/models/product-supplier-response';
import { ReplenishmentLineResponse } from '../../api/models/replenishment-line-response';
import { LineEditDialogData, LineEditResult, RunLineEditDialog } from './run-line-edit-dialog';

const SAMPLE_LINE: ReplenishmentLineResponse = {
  id: 101,
  status: 'SUGGESTED',
  suggestedPurchaseUnits: 6,
  product: { id: 42, sku: 'ACE-01', name: 'Aceite 1L' },
  supplier: { id: 3, name: 'Distribuidora Norte' },
  purchaseUnitName: 'Caja',
  unitsPerPurchaseUnit: 12,
};

const SAMPLE_SUPPLIERS: ProductSupplierResponse[] = [
  {
    id: 33,
    productId: 42,
    supplierId: 3,
    supplierName: 'Distribuidora Norte',
    active: true,
    minOrderQty: 12,
    unitsPerPurchaseUnit: 12,
    purchaseUnitName: 'Caja',
  },
  {
    id: 34,
    productId: 42,
    supplierId: 4,
    supplierName: 'Bodegas Sur',
    active: false,
    minOrderQty: 2,
  },
  {
    id: 35,
    productId: 42,
    supplierId: 5,
    supplierName: 'Comercial Centro',
    active: true,
    minOrderQty: 1,
    unitsPerPurchaseUnit: 6,
    purchaseUnitName: 'Display',
  },
];

describe('RunLineEditDialog (edición de unidades finales)', () => {
  let fixture: ComponentFixture<RunLineEditDialog>;
  let component: RunLineEditDialog;
  let closeSpy: ReturnType<typeof vi.fn>;

  async function setup(
    data: LineEditDialogData = { line: SAMPLE_LINE, suppliers: SAMPLE_SUPPLIERS },
  ): Promise<void> {
    closeSpy = vi.fn();
    await TestBed.configureTestingModule({
      imports: [RunLineEditDialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RunLineEditDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function ctrl(): {
    form: {
      patchValue: (v: Record<string, unknown>) => void;
      invalid: boolean;
      errors: Record<string, unknown> | null;
      markAllAsTouched: () => void;
    };
    submit: () => void;
    cancel: () => void;
  } {
    return component as unknown as ReturnType<typeof ctrl>;
  }

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('resuelve la asociación producto-proveedor activa del proveedor de la línea como valor inicial', async () => {
    await setup();

    const internal = component as unknown as {
      form: {
        getRawValue: () => {
          productSupplierId: number | null;
          finalPurchaseUnits: number;
          status: string;
        };
      };
      suppliers: () => readonly ProductSupplierResponse[];
    };

    expect(internal.form.getRawValue().productSupplierId).toBe(33);
    expect(internal.form.getRawValue().finalPurchaseUnits).toBe(6);
    expect(internal.form.getRawValue().status).toBe('ACCEPTED');
    // Los candidatos inactivos no se ofrecen para elegir.
    expect(internal.suppliers().map((ps) => ps.id)).toEqual([33, 35]);
  });

  it('valida el MOQ del proveedor elegido: unidades menores al mínimo bloquean el envío', async () => {
    await setup();

    const c = ctrl();
    c.form.patchValue({ finalPurchaseUnits: 5, productSupplierId: 33 });
    c.form.markAllAsTouched();
    fixture.detectChanges();

    expect(c.form.invalid).toBe(true);
    expect(c.form.errors?.['moq']).toEqual({ min: 12 });
    expect(fixture.nativeElement.textContent).toContain('MOQ del proveedor (12)');
    c.submit();
    expect(closeSpy).not.toHaveBeenCalled();
  });

  it('exige proveedor cuando las unidades finales son mayores que 0', async () => {
    await setup();

    const c = ctrl();
    c.form.patchValue({ finalPurchaseUnits: 3, productSupplierId: null });
    c.form.markAllAsTouched();
    fixture.detectChanges();

    expect(c.form.invalid).toBe(true);
    expect(c.form.errors?.['supplierRequired']).toBe(true);
    c.submit();
    expect(closeSpy).not.toHaveBeenCalled();
  });

  it('acepta unidades que cumplen el MOQ y envía el payload exacto del PATCH', async () => {
    await setup();

    const c = ctrl();
    c.form.patchValue({ finalPurchaseUnits: 12, productSupplierId: 33, status: 'ACCEPTED' });
    fixture.detectChanges();

    expect(c.form.invalid).toBe(false);
    c.submit();

    expect(closeSpy).toHaveBeenCalledTimes(1);
    expect(closeSpy).toHaveBeenCalledWith({
      status: 'ACCEPTED',
      finalPurchaseUnits: 12,
      productSupplierId: 33,
    } satisfies LineEditResult);
  });

  it('permite cambiar de proveedor y evalúa el MOQ del candidato elegido', async () => {
    await setup();

    const c = ctrl();
    // Display (6 u) con MOQ 1: 6 unidades son válidas para el nuevo proveedor.
    c.form.patchValue({ finalPurchaseUnits: 6, productSupplierId: 35 });
    fixture.detectChanges();
    expect(c.form.invalid).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Mínimo del proveedor (MOQ): 1');

    c.submit();
    expect(closeSpy).toHaveBeenCalledWith({
      status: 'ACCEPTED',
      finalPurchaseUnits: 6,
      productSupplierId: 35,
    });
  });

  it('con estado DISCARDED envía finalPurchaseUnits en 0 sin validar MOQ', async () => {
    await setup();

    const c = ctrl();
    c.form.patchValue({ finalPurchaseUnits: 5, productSupplierId: 33, status: 'DISCARDED' });
    fixture.detectChanges();

    expect(c.form.invalid).toBe(false);
    c.submit();

    expect(closeSpy).toHaveBeenCalledWith({
      status: 'DISCARDED',
      finalPurchaseUnits: 0,
      productSupplierId: 33,
    });
  });

  it('rechaza unidades negativas o no enteras (las unidades de compra son enteras)', async () => {
    await setup();

    const c = ctrl();
    c.form.patchValue({ finalPurchaseUnits: -1 });
    c.form.markAllAsTouched();
    expect(c.form.invalid).toBe(true);
    expect(c.form.errors).toBeNull(); // el error vive en el control, no en el grupo
    expect(
      (
        component as unknown as {
          form: { controls: { finalPurchaseUnits: { hasError: (e: string) => boolean } } };
        }
      ).form.controls.finalPurchaseUnits.hasError('integer'),
    ).toBe(true);

    c.form.patchValue({ finalPurchaseUnits: 2.5 });
    expect(
      (
        component as unknown as {
          form: { controls: { finalPurchaseUnits: { hasError: (e: string) => boolean } } };
        }
      ).form.controls.finalPurchaseUnits.hasError('integer'),
    ).toBe(true);
  });

  it('cancelar cierra sin payload y sin llamar al PATCH', async () => {
    await setup();

    ctrl().cancel();
    expect(closeSpy).toHaveBeenCalledWith(undefined);
  });
});
