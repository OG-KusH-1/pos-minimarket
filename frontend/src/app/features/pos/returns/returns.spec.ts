import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { SaleResponse } from '../../../api/models/sale-response';
import { SaleReturnResponse } from '../../../api/models/sale-return-response';
import { SaleReturnableLinesResponse } from '../../../api/models/sale-returnable-lines-response';
import { ApiError } from '../../../core/http/api-error';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { SupervisorOverrideService } from '../../../shared/ui/override-dialog';
import { Returns } from './returns';
import { ReturnsService } from './returns.service';

/** Acceso a los miembros protegidos de la pantalla desde las pruebas. */
interface ReturnsInternals {
  searchControl: FormControl<string>;
  voidForm: Returns['voidForm'];
  sale(): SaleResponse | null;
  returnable(): SaleReturnableLinesResponse | null;
  operationResult(): SaleReturnResponse | null;
  canVoid(): boolean;
  canReturn(): boolean;
  statusNotice(): { severity: string; message: string } | null;
  searchSale(): Promise<void>;
  submitVoid(): Promise<void>;
  openReturnDialog(): void;
}

const SALE: SaleResponse = {
  id: 42,
  number: 'L01-V-000042',
  status: 'COMPLETED',
  soldAt: '2026-11-18T14:05:00-03:00',
  cashier: { id: 5, username: 'cajero1', fullName: 'Cajero Uno' },
  totalPayable: 9900,
  lines: [
    { id: 7, name: 'Coca-Cola 1.5L', quantity: 3, unitPriceGross: 1300, lineTotalGross: 3900 },
  ],
  payments: [{ id: 1, method: 'CASH', amount: 9900, status: 'APPROVED' }],
  returns: [],
};

const RETURNABLE: SaleReturnableLinesResponse = {
  saleId: 42,
  voidAllowed: true,
  returnDeadline: '2026-12-18T23:59:59-03:00',
  lines: [
    {
      saleLineId: 7,
      name: 'Coca-Cola 1.5L',
      quantity: 3,
      returnedQuantity: 0,
      returnableQuantity: 3,
      unitRefundAmount: 1300,
    },
  ],
};

const VOID_RESPONSE: SaleReturnResponse = {
  id: 90,
  number: 'L01-D-000090',
  saleId: 42,
  returnType: 'VOID',
  reasonCode: 'DEFECTIVE',
  refundTotal: 9900,
  lines: [
    { saleLineId: 7, name: 'Coca-Cola 1.5L', quantity: 3, restock: true, refundAmount: 9900 },
  ],
  refunds: [{ method: 'CASH', amount: 9900 }],
  createdAt: '2026-11-19T10:00:00-03:00',
  createdBy: 'supervisor1',
};

describe('Returns (pantalla de anulación y devolución)', () => {
  let getSaleByNumberMock: ReturnType<typeof vi.fn>;
  let getReturnableLinesMock: ReturnType<typeof vi.fn>;
  let getSaleByIdMock: ReturnType<typeof vi.fn>;
  let voidSaleMock: ReturnType<typeof vi.fn>;
  let createReturnMock: ReturnType<typeof vi.fn>;
  let confirmMock: ReturnType<typeof vi.fn>;
  let dialogOpenSpy: ReturnType<typeof vi.fn>;

  function createScreen(): {
    fixture: ComponentFixture<Returns>;
    screen: ReturnsInternals;
  } {
    const fixture = TestBed.createComponent(Returns);
    const screen = fixture.componentInstance as unknown as ReturnsInternals;
    return { fixture, screen };
  }

  beforeEach(async () => {
    getSaleByNumberMock = vi.fn().mockReturnValue(of(SALE));
    getReturnableLinesMock = vi.fn().mockReturnValue(of(RETURNABLE));
    getSaleByIdMock = vi.fn().mockReturnValue(of({ ...SALE, status: 'VOIDED' }));
    voidSaleMock = vi.fn().mockReturnValue(of(VOID_RESPONSE));
    createReturnMock = vi.fn();
    confirmMock = vi.fn().mockReturnValue(of(true));
    dialogOpenSpy = vi.fn();

    await TestBed.configureTestingModule({
      imports: [Returns],
      providers: [
        StoreContextService,
        {
          provide: ReturnsService,
          useValue: {
            getSaleByNumber: getSaleByNumberMock,
            getReturnableLines: getReturnableLinesMock,
            getSaleById: getSaleByIdMock,
            voidSale: voidSaleMock,
            createReturn: createReturnMock,
          },
        },
        { provide: ConfirmDialogService, useValue: { confirm: confirmMock } },
        {
          provide: SupervisorOverrideService,
          useValue: {
            withSupervisorOverride: (requestFn: (headers: Record<string, string>) => unknown) =>
              requestFn({}),
          },
        },
        { provide: MatDialog, useValue: { openDialogs: [], open: dialogOpenSpy } },
      ],
    }).compileComponents();
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('busca la venta por folio, carga sus líneas devolvibles y muestra el detalle', async () => {
    const { fixture, screen } = createScreen();
    screen.searchControl.setValue('L01-V-000042');
    await screen.searchSale();

    expect(getSaleByNumberMock).toHaveBeenCalledWith('L01-V-000042', expect.anything());
    expect(getReturnableLinesMock).toHaveBeenCalledWith(42, expect.anything());
    expect(screen.sale()?.number).toBe('L01-V-000042');
    expect(screen.returnable()?.voidAllowed).toBe(true);
    expect(screen.canVoid()).toBe(true);
    expect(screen.canReturn()).toBe(true);

    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('L01-V-000042');
    expect(text).toContain('Coca-Cola 1.5L');
  });

  it('muestra un aviso claro cuando la venta no se encuentra (404 del cajero o folio inexistente)', async () => {
    getSaleByNumberMock.mockReturnValue(
      throwError(() => new ApiError(404, 'NOT_FOUND', 'Venta no encontrada', 'trace-404', [], {})),
    );
    const { screen } = createScreen();
    screen.searchControl.setValue('L01-V-999999');
    await screen.searchSale();

    expect(screen.sale()).toBeNull();
    expect(screen.statusNotice()?.severity).toBe('error');
    expect(screen.statusNotice()?.message).toContain('No se encontró');
  });

  it('no busca con el campo vacío y no llama al backend', async () => {
    const { screen } = createScreen();
    screen.searchControl.setValue('   ');
    await screen.searchSale();
    expect(getSaleByNumberMock).not.toHaveBeenCalled();
    expect(screen.statusNotice()?.severity).toBe('warning');
  });

  it('anula la venta: pide confirmación, envía el motivo y refleja la respuesta (stock y reembolso)', async () => {
    const { screen } = createScreen();
    screen.searchControl.setValue('L01-V-000042');
    await screen.searchSale();

    screen.voidForm.controls.reasonCode.setValue('DEFECTIVE');
    screen.voidForm.controls.reasonText.setValue('Producto dañado detectado tras el cobro');
    await screen.submitVoid();

    expect(confirmMock).toHaveBeenCalledTimes(1);
    const confirmData = (confirmMock.mock.calls[0] as unknown as [{ title: string }])[0];
    expect(confirmData.title).toContain('L01-V-000042');

    expect(voidSaleMock).toHaveBeenCalledTimes(1);
    const [saleId, body, options] = voidSaleMock.mock.calls[0] as unknown as [
      number,
      Record<string, unknown>,
      { idempotencyKey?: string; overrideToken?: string },
    ];
    expect(saleId).toBe(42);
    expect(body).toEqual({
      reasonCode: 'DEFECTIVE',
      reasonText: 'Producto dañado detectado tras el cobro',
    });
    expect(options.idempotencyKey).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(options.overrideToken).toBeUndefined();

    expect(screen.operationResult()?.returnType).toBe('VOID');
    expect(screen.operationResult()?.refundTotal).toBe(9900);

    // Tras la anulación se recarga la venta y sus líneas devolvibles.
    await vi.waitFor(() => {
      expect(getSaleByIdMock).toHaveBeenCalledWith(42, expect.anything());
      expect(getReturnableLinesMock).toHaveBeenCalledTimes(2);
    });
  });

  it('no anula si el usuario cancela la confirmación', async () => {
    confirmMock.mockReturnValue(of(false));
    const { screen } = createScreen();
    screen.searchControl.setValue('L01-V-000042');
    await screen.searchSale();
    await screen.submitVoid();

    expect(voidSaleMock).not.toHaveBeenCalled();
    expect(screen.operationResult()).toBeNull();
  });

  it('bloquea la anulación y la devolución cuando el backend las reporta no permitidas', async () => {
    getReturnableLinesMock.mockReturnValue(
      of({ ...RETURNABLE, voidAllowed: false, lines: [] } as SaleReturnableLinesResponse),
    );
    const { screen } = createScreen();
    screen.searchControl.setValue('L01-V-000042');
    await screen.searchSale();

    expect(screen.canVoid()).toBe(false);
    expect(screen.canReturn()).toBe(false);

    await screen.submitVoid();
    expect(voidSaleMock).not.toHaveBeenCalled();

    screen.openReturnDialog();
    expect(dialogOpenSpy).not.toHaveBeenCalled();
  });

  it('reenvía la anulación con el token de override que entrega SupervisorOverrideService (SALE_VOID)', async () => {
    const overrideMock = vi
      .fn()
      .mockImplementation((requestFn: (headers: Record<string, string>) => unknown) =>
        requestFn({ 'X-Override-Token': 'tok-void-7' }),
      );
    (
      TestBed.inject(SupervisorOverrideService) as unknown as { withSupervisorOverride: unknown }
    ).withSupervisorOverride = overrideMock;

    const { screen } = createScreen();
    screen.searchControl.setValue('L01-V-000042');
    await screen.searchSale();
    await screen.submitVoid();

    expect(overrideMock).toHaveBeenCalledTimes(1);
    expect(voidSaleMock).toHaveBeenCalledTimes(1);
    const [, , options] = voidSaleMock.mock.calls[0] as unknown as [
      number,
      unknown,
      { overrideToken?: string },
    ];
    expect(options.overrideToken).toBe('tok-void-7');
    expect(screen.operationResult()?.returnType).toBe('VOID');
  });

  it('muestra un mensaje claro cuando el turno está cerrado (VOID_NOT_ALLOWED, RN-12)', async () => {
    voidSaleMock.mockReturnValue(
      throwError(() => new ApiError(422, 'VOID_NOT_ALLOWED', 'Turno cerrado', null, [], {})),
    );
    const { screen } = createScreen();
    screen.searchControl.setValue('L01-V-000042');
    await screen.searchSale();
    await screen.submitVoid();

    expect(screen.statusNotice()?.severity).toBe('error');
    expect(screen.statusNotice()?.message).toContain('turno original');
  });
});
