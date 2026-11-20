import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Observable, catchError, of, throwError } from 'rxjs';
import { SaleReturnResponse } from '../../../api/models/sale-return-response';
import { ApiError } from '../../../core/http/api-error';
import {
  OVERRIDE_TOKEN_HEADER,
  SupervisorOverrideService,
} from '../../../shared/ui/override-dialog';
import { ReturnLineRow } from './returns.models';
import { ReturnLinesDialog, ReturnLinesDialogData } from './return-lines-dialog';
import { ReturnsService } from './returns.service';

/** Acceso a los miembros protegidos del diálogo desde las pruebas. */
interface DialogInternals {
  form: ReturnLinesDialog['form'];
  rows: readonly ReturnLineRow[];
  hasSelectedLines(): boolean;
  estimatedRefund(): number;
  errorMessage(): string | null;
  toggleAllRestock(checked: boolean): void;
  fillMaxQuantities(): void;
  setMaxQuantity(index: number): void;
  submit(): void;
}

const SAMPLE_DATA: ReturnLinesDialogData = {
  saleId: 42,
  saleNumber: 'L01-V-000042',
  returnable: {
    saleId: 42,
    voidAllowed: true,
    returnDeadline: '2026-12-01T23:59:59-03:00',
    lines: [
      {
        saleLineId: 7,
        name: 'Coca-Cola 1.5L',
        quantity: 3,
        returnedQuantity: 0,
        returnableQuantity: 3,
        unitRefundAmount: 1300,
      },
      {
        saleLineId: 9,
        name: 'Pan hallulla (kg)',
        quantity: 0.75,
        returnedQuantity: 0,
        returnableQuantity: 0.75,
        unitRefundAmount: 8990,
      },
      {
        saleLineId: 12,
        name: 'Pack cerveza x6',
        quantity: 2,
        returnedQuantity: 1,
        returnableQuantity: 1,
        unitRefundAmount: 6000,
      },
    ],
  },
  saleLines: [
    { id: 7, name: 'Coca-Cola 1.5L', quantity: 3, saleType: 'UNIT' as const },
    { id: 9, name: 'Pan hallulla (kg)', quantity: 0.75, saleType: 'WEIGHT' as const },
    { id: 12, name: 'Pack cerveza x6', quantity: 2, saleType: 'PACK' as const },
  ],
};

const SAMPLE_RESPONSE: SaleReturnResponse = {
  id: 91,
  number: 'L01-D-000091',
  saleId: 42,
  returnType: 'RETURN',
  refundTotal: 2600,
  lines: [
    { saleLineId: 7, name: 'Coca-Cola 1.5L', quantity: 2, restock: true, refundAmount: 2600 },
  ],
};

type OverrideRequestFn = (headers: Record<string, string>) => Observable<unknown>;

describe('ReturnLinesDialog (líneas devolvibles)', () => {
  let createReturnMock: ReturnType<typeof vi.fn>;
  let closeSpy: ReturnType<typeof vi.fn>;
  /** Comportamiento del stub de `SupervisorOverrideService`; se puede redefinir por prueba. */
  let overrideImpl: (requestFn: OverrideRequestFn) => Observable<unknown>;

  function createInternals(): DialogInternals {
    const fixture = TestBed.createComponent(ReturnLinesDialog);
    return fixture.componentInstance as unknown as DialogInternals;
  }

  beforeEach(async () => {
    createReturnMock = vi.fn().mockReturnValue(of(SAMPLE_RESPONSE));
    closeSpy = vi.fn();
    overrideImpl = (requestFn) => requestFn({});

    await TestBed.configureTestingModule({
      imports: [ReturnLinesDialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: SAMPLE_DATA },
        { provide: MatDialogRef, useValue: { close: closeSpy, disableClose: false } },
        { provide: ReturnsService, useValue: { createReturn: createReturnMock } },
        {
          provide: SupervisorOverrideService,
          useValue: {
            withSupervisorOverride: (requestFn: OverrideRequestFn) => overrideImpl(requestFn),
          },
        },
      ],
    }).compileComponents();
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('arma las filas con el tope devolvible real y el tipo de venta de la línea', () => {
    const dialog = createInternals();
    expect(dialog.rows.map((row) => row.returnableQuantity)).toEqual([3, 0.75, 1]);
    expect(dialog.rows.map((row) => row.integerQuantity)).toEqual([true, false, true]);
    expect(dialog.rows[2]?.returnedQuantity).toBe(1);
  });

  it('no deja pedir más de lo devolvible: la cantidad sobre el tope invalida la fila y bloquea el envío', () => {
    const dialog = createInternals();
    const firstLine = dialog.form.controls.lines.at(0);
    firstLine.controls.quantity.setValue(5);
    firstLine.controls.quantity.markAsTouched();

    expect(firstLine.controls.quantity.hasError('max')).toBe(true);
    expect(dialog.form.invalid).toBe(true);
    expect(dialog.hasSelectedLines()).toBe(true);

    dialog.submit();
    expect(createReturnMock).not.toHaveBeenCalled();
    expect(closeSpy).not.toHaveBeenCalled();
    expect(dialog.errorMessage()).toContain('fuera de lo devolvible');
  });

  it('exige cantidad entera en UNIT/PACK y hasta 3 decimales en pesables (WEIGHT)', () => {
    const dialog = createInternals();

    dialog.form.controls.lines.at(0).controls.quantity.setValue(2.5);
    expect(dialog.form.controls.lines.at(0).controls.quantity.hasError('integerQuantity')).toBe(
      true,
    );

    dialog.form.controls.lines.at(1).controls.quantity.setValue(0.1234);
    expect(dialog.form.controls.lines.at(1).controls.quantity.hasError('maxDecimals')).toBe(true);

    dialog.form.controls.lines.at(1).controls.quantity.setValue(0.123);
    expect(dialog.form.controls.lines.at(1).controls.quantity.valid).toBe(true);
  });

  it('acepta el tope exacto por fila con el botón «Máx.» y con «Devolver todo»', () => {
    const dialog = createInternals();

    dialog.setMaxQuantity(0);
    expect(dialog.form.controls.lines.at(0).controls.quantity.value).toBe(3);
    expect(dialog.form.controls.lines.at(0).controls.quantity.valid).toBe(true);

    dialog.fillMaxQuantities();
    expect(
      dialog.form.controls.lines.controls
        .map((group) => group.controls.quantity.value)
        .every((value, index) => value === dialog.rows[index]?.returnableQuantity),
    ).toBe(true);
    // Reembolso estimado: round(3×1300) + round(0,75×8990) + round(1×6000).
    expect(dialog.estimatedRefund()).toBe(3900 + 6743 + 6000);
  });

  it('sin líneas seleccionadas no envía y muestra aviso', () => {
    const dialog = createInternals();
    dialog.submit();
    expect(createReturnMock).not.toHaveBeenCalled();
    expect(dialog.errorMessage()).toContain('al menos una línea');
  });

  it('envía el payload correcto: solo líneas con cantidad > 0, con restock por línea y sin refunds', () => {
    const dialog = createInternals();

    dialog.form.controls.lines.at(0).controls.quantity.setValue(2);
    dialog.form.controls.lines.at(0).controls.restock.setValue(false);
    dialog.form.controls.lines.at(2).controls.quantity.setValue(1);
    // La línea pesable queda en 0: no debe viajar en el payload.

    dialog.submit();

    expect(createReturnMock).toHaveBeenCalledTimes(1);
    const [saleId, body, options] = createReturnMock.mock.calls[0] as unknown as [
      number,
      Record<string, unknown>,
      { idempotencyKey?: string; overrideToken?: string },
    ];
    expect(saleId).toBe(42);
    expect(body).toEqual({
      reasonCode: 'CUSTOMER_REQUEST',
      lines: [
        { saleLineId: 7, quantity: 2, restock: false },
        { saleLineId: 12, quantity: 1, restock: true },
      ],
    });
    // Sin `refunds`: el backend reembolsa por los mismos medios en proporción (F.3).
    expect(body).not.toHaveProperty('refunds');
    expect(options.idempotencyKey).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(closeSpy).toHaveBeenCalledWith(SAMPLE_RESPONSE);
  });

  it('el conmutador global de restock marca o desmarca todas las filas y así se envía', () => {
    const dialog = createInternals();
    dialog.form.controls.lines.at(0).controls.quantity.setValue(1);
    dialog.form.controls.lines.at(1).controls.quantity.setValue(0.25);
    dialog.form.controls.lines.at(2).controls.quantity.setValue(1);

    dialog.toggleAllRestock(false);
    expect(
      dialog.form.controls.lines.controls.every((group) => group.controls.restock.value === false),
    ).toBe(true);

    dialog.toggleAllRestock(true);
    expect(
      dialog.form.controls.lines.controls.every((group) => group.controls.restock.value === true),
    ).toBe(true);

    dialog.submit();
    const [, body] = createReturnMock.mock.calls[0] as unknown as [number, Record<string, unknown>];
    const lines = body['lines'] as { restock?: boolean }[];
    expect(lines.every((line) => line.restock === true)).toBe(true);
  });

  it('reintenta con X-Override-Token conservando la misma Idempotency-Key (override SALE_RETURN)', () => {
    const overrideRequired = new ApiError(
      422,
      'OVERRIDE_REQUIRED',
      'Se requiere autorización de supervisor.',
      null,
      [],
      { action: 'SALE_RETURN', context: { saleId: 42 } },
    );
    let retriedWithToken = false;
    // Emula SupervisorOverrideService: primera llamada sin headers; ante OVERRIDE_REQUIRED
    // repite la MISMA operación con X-Override-Token.
    overrideImpl = (requestFn) =>
      requestFn({}).pipe(
        catchError((error: unknown) => {
          if (retriedWithToken) {
            throw error;
          }
          retriedWithToken = true;
          return requestFn({ [OVERRIDE_TOKEN_HEADER]: 'tok-ret-9' });
        }),
      );
    createReturnMock.mockReturnValueOnce(throwError(() => overrideRequired));

    const dialog = createInternals();
    dialog.form.controls.lines.at(0).controls.quantity.setValue(3);

    dialog.submit();

    expect(createReturnMock).toHaveBeenCalledTimes(2);
    const [, , firstOptions] = createReturnMock.mock.calls[0] as unknown as [
      number,
      unknown,
      { idempotencyKey?: string; overrideToken?: string },
    ];
    const [, , retryOptions] = createReturnMock.mock.calls[1] as unknown as [
      number,
      unknown,
      { idempotencyKey?: string; overrideToken?: string },
    ];
    expect(firstOptions.overrideToken).toBeUndefined();
    expect(retryOptions.overrideToken).toBe('tok-ret-9');
    expect(retryOptions.idempotencyKey).toBe(firstOptions.idempotencyKey);
    expect(closeSpy).toHaveBeenCalledWith(SAMPLE_RESPONSE);
  });

  it('muestra un mensaje claro cuando el backend rechaza por cantidad sobre lo devolvible', () => {
    const quantityExceeded = new ApiError(
      422,
      'RETURN_QUANTITY_EXCEEDED',
      'La cantidad supera lo devolvible de la línea.',
      null,
      [],
      {},
    );
    createReturnMock.mockReturnValue(throwError(() => quantityExceeded));

    const dialog = createInternals();
    dialog.form.controls.lines.at(0).controls.quantity.setValue(3);

    dialog.submit();

    expect(dialog.errorMessage()).toContain('devolvible');
    expect(closeSpy).not.toHaveBeenCalled();
  });
});
