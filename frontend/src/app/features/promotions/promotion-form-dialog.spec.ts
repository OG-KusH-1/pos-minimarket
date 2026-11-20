import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AbstractControl, FormArray, FormGroup } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideApiConfiguration } from '../../api/api-configuration';
import { PromotionFormDialog, PromotionFormDialogData } from './promotion-form-dialog';
import { PromotionType } from './promotion-validators';

/** Acceso tipado (de prueba) a los miembros protegidos del diálogo. */
interface DialogProbe {
  form: FormGroup;
  addItem: () => void;
  submit: () => void;
}

/** Forma cruda del payload enviado por `buildPayload`. */
interface PromotionPayload {
  name: string;
  promotionType: PromotionType;
  validFrom?: string;
  validTo?: string;
  timeFrom?: string;
  timeTo?: string;
  daysOfWeek?: number[];
  percent?: number;
  amount?: number;
  buyQty?: number;
  payQty?: number;
  minQty?: number;
  comboPrice?: number;
  maxApplicationsPerSale?: number;
  stackable?: boolean;
  priority?: number;
  items: { targetType: string; targetId: number; requiredQty?: number }[];
  version?: number;
}

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('PromotionFormDialog (formulario por tipo de promoción)', () => {
  let fixture: ComponentFixture<PromotionFormDialog>;
  let http: HttpTestingController;
  let dialogRefMock: { close: ReturnType<typeof vi.fn> };
  let comp: DialogProbe;

  /** Monta el diálogo y devuelve una sonda tipada de sus miembros protegidos. */
  async function mount(data: PromotionFormDialogData = { promotionId: null }): Promise<void> {
    dialogRefMock = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [PromotionFormDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRefMock },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PromotionFormDialog);
    fixture.detectChanges();
    comp = fixture.componentInstance as unknown as DialogProbe;
  }

  afterEach(() => http?.verify());

  /** Resuelve un control del formulario por ruta (solo lectura de estado en pruebas). */
  function ctrl(path: string): AbstractControl {
    const control = comp.form.get(path);
    if (!control) {
      throw new Error(`Control no encontrado: ${path}`);
    }
    return control;
  }

  /** Devuelve la fila de objetivo en la posición dada como grupo tipado. */
  function itemAt(index: number): FormGroup {
    return (comp.form.get('items') as FormArray).at(index) as FormGroup;
  }

  /** Completa los campos comunes obligatorios y un objetivo PRODUCT válido. */
  function fillValidBase(): void {
    ctrl('name').setValue('Promoción de prueba');
    ctrl('validFrom').setValue('2026-11-19T10:00');
    comp.addItem();
    itemAt(0).get('targetType')?.setValue('PRODUCT');
    itemAt(0).get('targetId')?.setValue(1);
    itemAt(0).get('requiredQty')?.setValue(1);
  }

  it('PERCENT: guarda solo el porcentaje y rechaza valores fuera de (0, 100]', async () => {
    await mount();
    fillValidBase();

    ctrl('percent').setValue(0);
    expect(ctrl('percent').hasError('positive')).toBe(true);
    expect(comp.form.invalid).toBe(true);

    ctrl('percent').setValue(150);
    expect(ctrl('percent').hasError('max')).toBe(true);

    ctrl('percent').setValue(15);
    expect(comp.form.valid).toBe(true);

    comp.submit();
    const req = http.expectOne('/api/v1/promotions');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as PromotionPayload;
    expect(body.promotionType).toBe('PERCENT');
    expect(body.percent).toBe(15);
    // Los campos ajenos al tipo se envían como undefined (se omiten en el JSON).
    expect(body.amount).toBeUndefined();
    expect(body.buyQty).toBeUndefined();
    expect(body.payQty).toBeUndefined();
    expect(body.minQty).toBeUndefined();
    expect(body.comboPrice).toBeUndefined();
    expect(body.items).toEqual([{ targetType: 'PRODUCT', targetId: 1, requiredQty: 1 }]);
    expect(body.validFrom).toMatch(/Z$/); // Vigencia convertida a UTC ISO-8601.
    flushJson(req, { id: 10, name: body.name, promotionType: 'PERCENT', percent: 15 });
    await fixture.whenStable();
    expect(dialogRefMock.close).toHaveBeenCalledWith(
      expect.objectContaining({ id: 10, percent: 15 }),
    );
  });

  it('FIXED_AMOUNT: exige un monto mayor que cero y lo envía en `amount`', async () => {
    await mount();
    fillValidBase();
    ctrl('promotionType').setValue('FIXED_AMOUNT');

    ctrl('amount').setValue(-500);
    expect(ctrl('amount').hasError('positive')).toBe(true);

    ctrl('amount').setValue(500);
    comp.submit();
    const req = http.expectOne('/api/v1/promotions');
    const body = req.request.body as PromotionPayload;
    expect(body.promotionType).toBe('FIXED_AMOUNT');
    expect(body.amount).toBe(500);
    expect(body.percent).toBeUndefined();
    flushJson(req, { id: 11, name: body.name, promotionType: 'FIXED_AMOUNT' });
    await fixture.whenStable();
  });

  it('BUY_X_PAY_Y: valida M < N y envía buyQty/payQty sin campos de otros tipos', async () => {
    await mount();
    fillValidBase();
    ctrl('promotionType').setValue('BUY_X_PAY_Y');

    ctrl('buyQty').setValue(3);
    ctrl('payQty').setValue(3);
    expect(ctrl('payQty').hasError('payQtyNotLowerThanBuyQty')).toBe(true);

    ctrl('payQty').setValue(2);
    expect(comp.form.valid).toBe(true);

    comp.submit();
    const req = http.expectOne('/api/v1/promotions');
    const body = req.request.body as PromotionPayload;
    expect(body.buyQty).toBe(3);
    expect(body.payQty).toBe(2);
    expect(body.percent).toBeUndefined();
    expect(body.amount).toBeUndefined();
    flushJson(req, { id: 12, name: body.name, promotionType: 'BUY_X_PAY_Y' });
    await fixture.whenStable();
  });

  it('QUANTITY_PRICE: exige minQty y precio unitario mayores que cero', async () => {
    await mount();
    fillValidBase();
    ctrl('promotionType').setValue('QUANTITY_PRICE');

    ctrl('minQty').setValue(0);
    expect(ctrl('minQty').hasError('positive')).toBe(true);

    ctrl('minQty').setValue(5.5);
    ctrl('amount').setValue(900);
    expect(comp.form.valid).toBe(true);

    comp.submit();
    const req = http.expectOne('/api/v1/promotions');
    const body = req.request.body as PromotionPayload;
    expect(body.promotionType).toBe('QUANTITY_PRICE');
    expect(body.minQty).toBe(5.5);
    expect(body.amount).toBe(900);
    expect(body.buyQty).toBeUndefined();
    flushJson(req, { id: 13, name: body.name, promotionType: 'QUANTITY_PRICE' });
    await fixture.whenStable();
  });

  it('COMBO: exige dos o más objetivos y solo de tipo PRODUCT', async () => {
    await mount();
    fillValidBase();
    ctrl('promotionType').setValue('COMBO');

    // Con un solo PRODUCT: inválido.
    ctrl('comboPrice').setValue(3500);
    expect(ctrl('items').hasError('comboNeedsTwoProducts')).toBe(true);
    expect(comp.form.invalid).toBe(true);

    // Un objetivo CATEGORY entre los PRODUCT: inválido.
    comp.addItem();
    itemAt(1).get('targetType')?.setValue('CATEGORY');
    itemAt(1).get('targetId')?.setValue(2);
    expect(ctrl('items').hasError('comboOnlyProducts')).toBe(true);

    // Dos PRODUCT: válido.
    itemAt(1).get('targetType')?.setValue('PRODUCT');
    itemAt(1).get('targetId')?.setValue(2);
    expect(ctrl('items').hasError('comboNeedsTwoProducts')).toBe(false);
    expect(ctrl('items').hasError('comboOnlyProducts')).toBe(false);
    expect(comp.form.valid).toBe(true);

    comp.submit();
    const req = http.expectOne('/api/v1/promotions');
    const body = req.request.body as PromotionPayload;
    expect(body.comboPrice).toBe(3500);
    expect(body.items).toHaveLength(2);
    flushJson(req, { id: 14, name: body.name, promotionType: 'COMBO' });
    await fixture.whenStable();
  });

  it('vigencias: validTo debe ser posterior a validFrom y la pareja de horas es ambos-o-ninguno', async () => {
    await mount();
    fillValidBase();
    ctrl('percent').setValue(10);

    ctrl('validTo').setValue('2026-11-18T10:00');
    expect(ctrl('validTo').hasError('validToNotAfterValidFrom')).toBe(true);

    ctrl('validTo').setValue('2026-11-19T18:00');
    expect(ctrl('validTo').hasError('validToNotAfterValidFrom')).toBe(false);

    ctrl('timeFrom').setValue('09:00');
    expect(ctrl('timeTo').hasError('timePairIncomplete')).toBe(true);

    ctrl('timeTo').setValue('21:00');
    expect(comp.form.valid).toBe(true);

    comp.submit();
    const req = http.expectOne('/api/v1/promotions');
    const body = req.request.body as PromotionPayload;
    expect(body.timeFrom).toBe('09:00');
    expect(body.timeTo).toBe('21:00');
    flushJson(req, { id: 15, name: body.name, promotionType: 'PERCENT' });
    await fixture.whenStable();
  });

  it('alcance: exige al menos un local cuando allStores es falso y envía días y máximo de usos', async () => {
    await mount();
    fillValidBase();
    ctrl('percent').setValue(10);

    ctrl('allStores').setValue(false);
    expect(ctrl('storeIds').hasError('storeIdsRequired')).toBe(true);

    ctrl('storeIds').setValue([1, 3]);
    ctrl('daysOfWeek').setValue([6, 7, 6]);
    ctrl('maxApplicationsPerSale').setValue(2);
    expect(comp.form.valid).toBe(true);

    comp.submit();
    const req = http.expectOne('/api/v1/promotions');
    const body = req.request.body as PromotionPayload;
    expect(body.daysOfWeek).toEqual([6, 7]); // Deduplicado y ordenado; sin días = todos.
    expect(body.maxApplicationsPerSale).toBe(2);
    flushJson(req, { id: 16, name: body.name, promotionType: 'PERCENT' });
    await fixture.whenStable();
  });

  it('edición: carga GET /api/v1/promotions/{id}, precarga su tipo y envía PUT con `version`', async () => {
    await mount({ promotionId: 7 });

    const getReq = http.expectOne('/api/v1/promotions/7');
    expect(getReq.request.method).toBe('GET');
    flushJson(getReq, {
      id: 7,
      name: '3x2 cárnico',
      description: 'Lleva 3 paga 2',
      promotionType: 'BUY_X_PAY_Y',
      priority: 50,
      stackable: true,
      buyQty: 3,
      payQty: 2,
      validFrom: '2026-11-20T13:00:00.000Z',
      validTo: '2026-11-30T02:59:00.000Z',
      daysOfWeek: [6, 7],
      timeFrom: '08:00',
      timeTo: '22:00',
      allStores: true,
      maxApplicationsPerSale: 1,
      items: [
        { id: 1, targetType: 'PRODUCT', targetId: 5, requiredQty: 1, targetName: 'Longaniza' },
      ],
      active: true,
      version: 2,
    });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(ctrl('name').value).toBe('3x2 cárnico');
    expect(ctrl('buyQty').value).toBe(3);
    expect(ctrl('payQty').value).toBe(2);
    expect(ctrl('percent').value).toBeNull();
    expect(ctrl('items').value).toHaveLength(1);

    comp.submit();
    const putReq = http.expectOne('/api/v1/promotions/7');
    expect(putReq.request.method).toBe('PUT');
    const body = putReq.request.body as PromotionPayload;
    expect(body.version).toBe(2);
    expect(body.buyQty).toBe(3);
    expect(body.payQty).toBe(2);
    expect(body.items[0]).toMatchObject({ targetType: 'PRODUCT', targetId: 5 });
    flushJson(putReq, { id: 7, name: '3x2 cárnico', version: 3 });
    await fixture.whenStable();
    expect(dialogRefMock.close).toHaveBeenCalledWith(
      expect.objectContaining({ id: 7, version: 3 }),
    );
  });
});
