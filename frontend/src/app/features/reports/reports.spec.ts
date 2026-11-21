import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { HttpRequest } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../api/api-configuration';
import { AuthService } from '../../core/auth/auth.service';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { StoreContextService } from '../../core/store-context/store-context.service';
import { Reports } from './reports';
import { REPORT_PERMISSIONS } from './reports.models';

function flushJsonBlob(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

function flushBlobError(req: TestRequest, status: number, body: unknown): void {
  req.flush(new Blob([JSON.stringify(body)], { type: 'application/problem+json' }), {
    status,
    statusText: 'Error',
  });
}

const ALL_REPORT_PERMISSIONS: readonly string[] = [
  REPORT_PERMISSIONS.salesRead,
  REPORT_PERMISSIONS.marginRead,
  REPORT_PERMISSIONS.inventoryRead,
  REPORT_PERMISSIONS.purchasingRead,
  REPORT_PERMISSIONS.shrinkageRead,
  REPORT_PERMISSIONS.cashRead,
  REPORT_PERMISSIONS.exportExecute,
  REPORT_PERMISSIONS.costRead,
  REPORT_PERMISSIONS.categoryRead,
  REPORT_PERMISSIONS.userRead,
];

describe('Reports', () => {
  let fixture: ComponentFixture<Reports>;
  let component: Reports;
  let http: HttpTestingController;
  let permissions: Set<string>;
  let activeStoreId: ReturnType<typeof signal<number | null>>;
  let snackBarOpenMock: ReturnType<typeof vi.fn>;

  async function setup(
    grantedPermissions: readonly string[] = ALL_REPORT_PERMISSIONS,
  ): Promise<void> {
    permissions = new Set(grantedPermissions);
    activeStoreId = signal<number | null>(1);
    snackBarOpenMock = vi.fn();

    await TestBed.configureTestingModule({
      imports: [Reports],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snackBarOpenMock } },
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
            activeStore: () => ({ id: 1, code: 'L01', name: 'Local Centro', active: true }),
            availableStores: () => [
              { id: 1, code: 'L01', name: 'Local Centro', active: true },
              { id: 2, code: 'L02', name: 'Local Norte', active: true },
            ],
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(Reports);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  }

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  function flushInitialAuxAndReport(
    reportPath: string,
    reportBody: unknown,
    opts: { categories?: unknown[]; users?: unknown[] } = {},
  ): void {
    fixture.detectChanges();

    if (
      permissions.has(REPORT_PERMISSIONS.salesRead) &&
      permissions.has(REPORT_PERMISSIONS.categoryRead)
    ) {
      const catReq = http.expectOne((r) => r.url === '/api/v1/categories');
      flushJsonBlob(
        catReq,
        opts.categories ?? [{ id: 10, name: 'Bebidas', active: true, children: [] }],
      );
    }

    if (
      permissions.has(REPORT_PERMISSIONS.cashRead) &&
      permissions.has(REPORT_PERMISSIONS.userRead)
    ) {
      const userReq = http.expectOne((r) => r.url === '/api/v1/users');
      flushJsonBlob(userReq, {
        content: opts.users ?? [{ id: 5, username: 'cajero1', fullName: 'Ana Cajera' }],
      });
    }

    const repReq = http.expectOne((r) => r.url === reportPath);
    flushJsonBlob(repReq, reportBody);
  }

  async function waitForLoaded(): Promise<void> {
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((component as unknown as { loading: () => boolean }).loading()).toBe(false);
    });
  }

  function expectOneAsync(predicate: (req: HttpRequest<unknown>) => boolean): Promise<TestRequest> {
    return vi.waitFor(() => {
      fixture.detectChanges();
      return http.expectOne(predicate);
    });
  }

  function clickButtonContaining(text: string): HTMLButtonElement {
    const buttons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ) as HTMLButtonElement[];
    const match = buttons.find((btn) => btn.textContent?.includes(text));
    if (!match) {
      throw new Error(`No se encontró botón con texto "${text}"`);
    }
    match.click();
    fixture.detectChanges();
    return match;
  }

  function selectTabByText(label: string): void {
    const tabs = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[role="tab"]'),
    ) as HTMLElement[];
    const target = tabs.find((tab) => tab.textContent?.trim() === label);
    if (!target) {
      throw new Error(`No se encontró pestaña "${label}"`);
    }
    target.click();
    fixture.detectChanges();
  }

  it('renderiza <h1>Reportes</h1> exacto, consulta reporte de ventas inicial y muestra fila de totales', async () => {
    await setup();
    flushInitialAuxAndReport('/api/v1/reports/sales', {
      generatedAt: '2026-11-20T18:30:00Z',
      filters: { from: '2026-10-22', to: '2026-11-20', storeIds: [1] },
      rows: [
        {
          key: '2026-11-20',
          label: '2026-11-20',
          transactions: 28,
          grossTotal: 250000,
          netTotal: 210084,
          taxTotal: 39916,
          discountTotal: 5000,
          returnsTotal: 0,
          avgTicket: 8929,
        },
      ],
      totals: {
        key: 'TOTAL',
        label: 'Total',
        transactions: 28,
        grossTotal: 250000,
        netTotal: 210084,
        taxTotal: 39916,
        discountTotal: 5000,
        returnsTotal: 0,
        avgTicket: 8929,
      },
    });

    await waitForLoaded();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toBe('Reportes');
    expect(el.textContent).toContain('2026-11-20');
    expect(el.textContent).toContain('$250.000');
    expect(el.textContent).toContain('Total');
  });

  it('filtra pestañas y oculta botones de export cuando faltan permisos', async () => {
    await setup([REPORT_PERMISSIONS.cashRead]);
    flushInitialAuxAndReport('/api/v1/reports/cash-differences', {
      rows: [
        {
          sessionId: 1,
          number: 'L01-TU-000001',
          businessDate: '2026-11-20',
          cashier: 'Ana',
          terminal: 'Caja 1',
          expected: 100000,
          counted: 99000,
          difference: -1000,
          approvedBy: 'Gerente',
        },
      ],
      totals: { number: 'Total', expected: 100000, counted: 99000, difference: -1000 },
    });

    await waitForLoaded();
    const el = fixture.nativeElement as HTMLElement;
    const tabLabels = Array.from(el.querySelectorAll('[role="tab"]')).map((t) =>
      t.textContent?.trim(),
    );
    expect(tabLabels).toEqual(['Diferencias caja']);
    expect(el.textContent).toContain('L01-TU-000001');
    expect(el.textContent).not.toContain('Exportar Excel');
    expect(el.textContent).not.toContain('Exportar PDF');
  });

  it('oculta métrica de margen en ABC sin report:margin:read y oculta Valorización en Sin rotación sin catalog:cost:read', async () => {
    await setup([REPORT_PERMISSIONS.salesRead, REPORT_PERMISSIONS.inventoryRead]);
    flushInitialAuxAndReport('/api/v1/reports/sales', { rows: [], totals: null });
    await waitForLoaded();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No hay datos');

    const abcMetrics = (
      component as unknown as { availableAbcMetrics: () => { value: string }[] }
    ).availableAbcMetrics();
    expect(abcMetrics.map((m) => m.value)).toEqual(['amount', 'qty']);

    selectTabByText('Sin rotación');
    const noRotReq = await expectOneAsync((r) => r.url === '/api/v1/reports/no-rotation');
    flushJsonBlob(noRotReq, {
      rows: [{ productId: 8, sku: 'ARZ-1', name: 'Arroz 1kg', stock: 12, daysWithoutSale: 40 }],
    });

    await waitForLoaded();
    const el = fixture.nativeElement as HTMLElement;
    const tableText = el.querySelector('table')?.textContent ?? '';
    expect(tableText).toContain('Arroz 1kg');
    expect(tableText).not.toContain('Valorización');
  });

  it('muestra columna Valorización en Sin rotación cuando posee catalog:cost:read', async () => {
    await setup([REPORT_PERMISSIONS.inventoryRead, REPORT_PERMISSIONS.costRead]);
    flushInitialAuxAndReport('/api/v1/reports/no-rotation', {
      rows: [
        {
          productId: 8,
          sku: 'ARZ-1',
          name: 'Arroz 1kg',
          stock: 12,
          valuation: 18000,
          daysWithoutSale: 40,
        },
      ],
    });

    await waitForLoaded();
    const el = fixture.nativeElement as HTMLElement;
    const tableText = el.querySelector('table')?.textContent ?? '';
    expect(tableText).toContain('Valorización');
    expect(tableText).toContain('$18.000');
  });

  it('muestra aviso claro cuando solo tiene report:dashboard:read y ningún reporte tabular', async () => {
    await setup([REPORT_PERMISSIONS.dashboardRead]);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toBe('Reportes');
    expect(el.textContent).toContain('No tienes reportes detallados habilitados');
  });

  it('valida formulario de filtros: from <= to y rango máximo de 366 días inclusive sin disparar HTTP', async () => {
    await setup();
    flushInitialAuxAndReport('/api/v1/reports/sales', { rows: [], totals: null });
    await waitForLoaded();

    const comp = component as unknown as {
      filterForm: Reports['filterForm'];
    };

    // 1. Rango invertido (from > to)
    comp.filterForm.patchValue({ from: '2026-11-20', to: '2026-11-10' });
    fixture.detectChanges();
    expect(comp.filterForm.invalid).toBe(true);
    expect(comp.filterForm.hasError('rangeInverted')).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'La fecha Desde no puede ser posterior a la fecha Hasta',
    );
    clickButtonContaining('Consultar');
    http.expectNone((r) => r.url.startsWith('/api/v1/reports/'));

    // 2. Exactamente 366 días en año bisiesto (2024-01-01 al 2024-12-31) => válido
    comp.filterForm.patchValue({ from: '2024-01-01', to: '2024-12-31' });
    fixture.detectChanges();
    expect(comp.filterForm.valid).toBe(true);

    // 3. Exactamente 366 días (2026-01-01 al 2027-01-01) => válido
    comp.filterForm.patchValue({ from: '2026-01-01', to: '2027-01-01' });
    fixture.detectChanges();
    expect(comp.filterForm.valid).toBe(true);

    // 4. 367 días (2026-01-01 al 2027-01-02) => inválido (supera máximo de 366 días)
    comp.filterForm.patchValue({ from: '2026-01-01', to: '2027-01-02' });
    fixture.detectChanges();
    expect(comp.filterForm.invalid).toBe(true);
    expect(comp.filterForm.hasError('maxRangeExceeded')).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'El rango máximo permitido es de 366 días',
    );
    clickButtonContaining('Consultar');
    clickButtonContaining('Exportar Excel');
    http.expectNone((r) => r.url.startsWith('/api/v1/reports/'));
  });

  it('permite rango vacío en valorización de inventario y exige fechas emparejadas si se informa una', async () => {
    await setup([REPORT_PERMISSIONS.inventoryRead]);
    flushInitialAuxAndReport('/api/v1/reports/no-rotation', { rows: [] });
    await waitForLoaded();

    selectTabByText('Valorización');
    const valReq1 = await expectOneAsync((r) => r.url === '/api/v1/reports/inventory-valuation');
    flushJsonBlob(valReq1, {
      rows: [{ key: '1', label: 'Abarrotes', qty: 100, valuation: 250000, pct: 100 }],
      totals: { label: 'Total', qty: 100, valuation: 250000, pct: 100 },
    });
    await waitForLoaded();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Abarrotes');

    const comp = component as unknown as { filterForm: Reports['filterForm'] };
    comp.filterForm.patchValue({ from: '2026-11-01', to: '' });
    fixture.detectChanges();
    expect(comp.filterForm.hasError('incompleteRange')).toBe(true);
    clickButtonContaining('Consultar');
    http.expectNone((r) => r.url === '/api/v1/reports/inventory-valuation');

    clickButtonContaining('Stock actual');
    const valReq2 = await expectOneAsync((r) => r.url === '/api/v1/reports/inventory-valuation');
    expect(valReq2.request.params.has('from')).toBe(false);
    expect(valReq2.request.params.has('to')).toBe(false);
    flushJsonBlob(valReq2, { rows: [], totals: null });
    await waitForLoaded();
  });

  it('deshabilita validaciones de controles específicos de otra pestaña al cambiar de reporte', async () => {
    await setup([REPORT_PERMISSIONS.salesRead]);
    flushInitialAuxAndReport('/api/v1/reports/sales', { rows: [], totals: null });
    await waitForLoaded();

    selectTabByText('Ranking productos');
    const rankReq = await expectOneAsync((r) => r.url === '/api/v1/reports/product-ranking');
    flushJsonBlob(rankReq, { rows: [] });
    await waitForLoaded();

    const comp = component as unknown as { filterForm: Reports['filterForm'] };
    comp.filterForm.patchValue({ rankingLimit: 250 });
    fixture.detectChanges();
    expect(comp.filterForm.invalid).toBe(true);

    selectTabByText('Ventas');
    const salesReq = await expectOneAsync((r) => r.url === '/api/v1/reports/sales');
    expect(comp.filterForm.valid).toBe(true);
    flushJsonBlob(salesReq, { rows: [], totals: null });
    await waitForLoaded();
  });

  it('cambia de pestaña, envía filtros multi-local y específicos y actualiza la tabla', async () => {
    await setup();
    flushInitialAuxAndReport('/api/v1/reports/sales', { rows: [], totals: null });
    await waitForLoaded();

    selectTabByText('Ranking productos');
    const rankReq1 = await expectOneAsync((r) => r.url === '/api/v1/reports/product-ranking');
    flushJsonBlob(rankReq1, { rows: [] });
    await waitForLoaded();

    const comp = component as unknown as { filterForm: Reports['filterForm'] };
    comp.filterForm.patchValue({
      from: '2026-11-01',
      to: '2026-11-15',
      storeIds: [1, 2],
      rankingOrder: 'least',
      rankingMetric: 'amount',
      rankingLimit: 5,
      categoryId: 10,
    });
    clickButtonContaining('Consultar');

    const rankReq2 = await expectOneAsync((r) => r.url === '/api/v1/reports/product-ranking');
    expect(rankReq2.request.params.get('from')).toBe('2026-11-01');
    expect(rankReq2.request.params.get('to')).toBe('2026-11-15');
    expect(rankReq2.request.params.getAll('storeIds')).toEqual(['1', '2']);
    expect(rankReq2.request.params.get('order')).toBe('least');
    expect(rankReq2.request.params.get('metric')).toBe('amount');
    expect(rankReq2.request.params.get('limit')).toBe('5');
    expect(rankReq2.request.params.get('categoryId')).toBe('10');

    flushJsonBlob(rankReq2, {
      rows: [
        {
          rank: 1,
          productId: 2,
          sku: 'AG-01',
          name: 'Agua Mineral 500ml',
          category: 'Bebidas',
          qty: 3,
          netSales: 2500,
          transactions: 2,
        },
      ],
    });

    await waitForLoaded();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Agua Mineral 500ml');
    expect(text).toContain('$2.500');
  });

  it('recarga el reporte activo cuando cambia el local en StoreContextService', async () => {
    await setup([REPORT_PERMISSIONS.purchasingRead]);
    flushInitialAuxAndReport('/api/v1/reports/purchases-by-supplier', { rows: [], totals: null });
    await waitForLoaded();

    activeStoreId.set(2);
    fixture.detectChanges();

    const reloadReq = await expectOneAsync(
      (r) => r.url === '/api/v1/reports/purchases-by-supplier',
    );
    expect(reloadReq.request.params.getAll('storeIds')).toEqual(['2']);
    flushJsonBlob(reloadReq, { rows: [], totals: null });
    await waitForLoaded();
  });

  it('exporta a XLSX y PDF con los filtros activos y dispara la descarga en el navegador', async () => {
    await setup();
    flushInitialAuxAndReport('/api/v1/reports/sales', { rows: [], totals: null });
    await waitForLoaded();

    const createObjectUrlMock = vi.fn(() => 'blob:http://localhost/export-file');
    const revokeObjectUrlMock = vi.fn();
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: createObjectUrlMock,
      revokeObjectURL: revokeObjectUrlMock,
    });

    const downloadedFiles: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloadedFiles.push(this.download);
    });

    const comp = component as unknown as {
      filterForm: Reports['filterForm'];
      exportingFormat: () => string | null;
    };
    comp.filterForm.patchValue({
      from: '2026-11-01',
      to: '2026-11-20',
      storeIds: [1, 2],
      salesGroupBy: 'category',
    });

    clickButtonContaining('Exportar Excel');
    const xlsxReq = await expectOneAsync((r) => r.url === '/api/v1/reports/sales/export');
    expect(xlsxReq.request.params.get('format')).toBe('xlsx');
    expect(xlsxReq.request.params.get('groupBy')).toBe('category');
    expect(xlsxReq.request.params.getAll('storeIds')).toEqual(['1', '2']);
    const xlsxBlob = new Blob(['xlsx-bytes'], { type: 'application/octet-stream' });
    xlsxReq.flush(xlsxBlob, {
      headers: { 'Content-Disposition': 'attachment; filename="ventas-nov.xlsx"' },
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(createObjectUrlMock).toHaveBeenCalledWith(xlsxBlob);
      expect(downloadedFiles).toContain('ventas-nov.xlsx');
      expect(revokeObjectUrlMock).toHaveBeenCalledWith('blob:http://localhost/export-file');
      expect(comp.exportingFormat()).toBeNull();
    });

    clickButtonContaining('Exportar PDF');
    const pdfReq = await expectOneAsync((r) => r.url === '/api/v1/reports/sales/export');
    expect(pdfReq.request.params.get('format')).toBe('pdf');
    const pdfBlob = new Blob(['%PDF-1.4'], { type: 'application/pdf' });
    pdfReq.flush(pdfBlob, {
      headers: { 'Content-Disposition': 'attachment; filename="ventas-nov.pdf"' },
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(createObjectUrlMock).toHaveBeenCalledWith(pdfBlob);
      expect(downloadedFiles).toContain('ventas-nov.pdf');
      expect(comp.exportingFormat()).toBeNull();
    });
  });

  it('muestra error de API cuando falla la carga y permite reintentar', async () => {
    await setup([REPORT_PERMISSIONS.purchasingRead]);
    fixture.detectChanges();

    const failReq = http.expectOne((r) => r.url === '/api/v1/reports/purchases-by-supplier');
    flushBlobError(failReq, 400, {
      status: 400,
      code: 'VALIDATION_ERROR',
      detail: 'Rango de fechas inválido.',
    });

    await waitForLoaded();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Rango de fechas inválido.',
    );

    clickButtonContaining('Reintentar');
    const retryReq = await expectOneAsync((r) => r.url === '/api/v1/reports/purchases-by-supplier');
    flushJsonBlob(retryReq, {
      rows: [{ supplierId: 1, name: 'Proveedor Central', orders: 2, receipts: 2, netTotal: 50000 }],
      totals: { name: 'Total', orders: 2, receipts: 2, netTotal: 50000 },
    });

    await waitForLoaded();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Proveedor Central');
  });
});
