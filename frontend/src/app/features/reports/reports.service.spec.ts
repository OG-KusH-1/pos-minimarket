import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../api/api-configuration';
import { ApiError } from '../../core/http/api-error';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { ReportsService, extractFileName, triggerBrowserDownload } from './reports.service';

function flushJsonBlob(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('ReportsService', () => {
  let service: ReportsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    service = TestBed.inject(ReportsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    http.verify();
  });

  it('getSalesReport consulta /api/v1/reports/sales con filtros multi-local y desenvuelve Blob JSON', async () => {
    const promise = firstValueFrom(
      service.getSalesReport({
        from: '2026-11-01',
        to: '2026-11-20',
        storeIds: [1, 2],
        groupBy: 'category',
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/reports/sales');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('from')).toBe('2026-11-01');
    expect(req.request.params.get('to')).toBe('2026-11-20');
    expect(req.request.params.getAll('storeIds')).toEqual(['1', '2']);
    expect(req.request.params.get('groupBy')).toBe('category');

    flushJsonBlob(req, {
      filters: { from: '2026-11-01', to: '2026-11-20', storeIds: [1, 2] },
      generatedAt: '2026-11-20T18:00:00Z',
      rows: [
        {
          key: '10',
          label: 'Bebidas',
          transactions: 45,
          grossTotal: 150000,
          netTotal: 126050,
          taxTotal: 23950,
          discountTotal: 2000,
          returnsTotal: 5000,
          avgTicket: 3333,
        },
      ],
      totals: {
        key: 'TOTAL',
        label: 'Total',
        transactions: 45,
        grossTotal: 150000,
        netTotal: 126050,
        taxTotal: 23950,
        discountTotal: 2000,
        returnsTotal: 5000,
        avgTicket: 3333,
      },
    });

    const result = await promise;
    expect(result.rows).toHaveLength(1);
    expect(result.rows?.[0]?.label).toBe('Bebidas');
    expect(result.totals?.grossTotal).toBe(150000);
  });

  it('consulta sales-by-cashier, sales-by-payment-method, product-ranking, no-rotation y abc', async () => {
    const cashierPromise = firstValueFrom(
      service.getSalesByCashier({ from: '2026-11-01', to: '2026-11-20' }),
    );
    const cashierReq = http.expectOne((r) => r.url === '/api/v1/reports/sales-by-cashier');
    flushJsonBlob(cashierReq, {
      rows: [{ userId: 3, name: 'Ana Cajera', transactions: 12, total: 95000, voids: 1 }],
      totals: { name: 'Total', transactions: 12, total: 95000, voids: 1 },
    });
    const cashierRes = await cashierPromise;
    expect(cashierRes.rows?.[0]?.name).toBe('Ana Cajera');

    const pmPromise = firstValueFrom(
      service.getSalesByPaymentMethod({ from: '2026-11-01', to: '2026-11-20' }),
    );
    const pmReq = http.expectOne((r) => r.url === '/api/v1/reports/sales-by-payment-method');
    flushJsonBlob(pmReq, {
      rows: [{ method: 'DEBIT', transactions: 8, total: 64000, pct: 67.37 }],
      totals: { method: 'TOTAL', transactions: 12, total: 95000, pct: 100 },
    });
    const pmRes = await pmPromise;
    expect(pmRes.rows?.[0]?.method).toBe('DEBIT');

    const rankPromise = firstValueFrom(
      service.getProductRanking({
        from: '2026-11-01',
        to: '2026-11-20',
        order: 'least',
        metric: 'amount',
        limit: 15,
        categoryId: 4,
      }),
    );
    const rankReq = http.expectOne((r) => r.url === '/api/v1/reports/product-ranking');
    expect(rankReq.request.params.get('order')).toBe('least');
    expect(rankReq.request.params.get('metric')).toBe('amount');
    expect(rankReq.request.params.get('limit')).toBe('15');
    expect(rankReq.request.params.get('categoryId')).toBe('4');
    flushJsonBlob(rankReq, {
      rows: [{ rank: 1, productId: 9, sku: 'SNK-1', name: 'Galletas', netSales: 1200 }],
    });
    const rankRes = await rankPromise;
    expect(rankRes.rows?.[0]?.sku).toBe('SNK-1');

    const noRotPromise = firstValueFrom(
      service.getNoRotation({ from: '2026-11-01', to: '2026-11-20', days: 45 }),
    );
    const noRotReq = http.expectOne((r) => r.url === '/api/v1/reports/no-rotation');
    expect(noRotReq.request.params.get('days')).toBe('45');
    flushJsonBlob(noRotReq, {
      rows: [{ productId: 11, sku: 'LAT-2', name: 'Atún', stock: 18, daysWithoutSale: 50 }],
    });
    const noRotRes = await noRotPromise;
    expect(noRotRes.rows?.[0]?.daysWithoutSale).toBe(50);

    const abcPromise = firstValueFrom(
      service.getAbc({ from: '2026-11-01', to: '2026-11-20', metric: 'margin' }),
    );
    const abcReq = http.expectOne((r) => r.url === '/api/v1/reports/abc');
    expect(abcReq.request.params.get('metric')).toBe('margin');
    flushJsonBlob(abcReq, {
      rows: [{ productId: 1, sku: 'BEB-1', name: 'Agua', metricValue: 55000, abcClass: 'A' }],
    });
    const abcRes = await abcPromise;
    expect(abcRes.rows?.[0]?.abcClass).toBe('A');
  });

  it('consulta margin, inventory-valuation (sin fechas), purchases, shrinkage y cash-differences', async () => {
    const marginPromise = firstValueFrom(
      service.getMarginReport({ from: '2026-11-01', to: '2026-11-20', groupBy: 'brand' }),
    );
    const marginReq = http.expectOne((r) => r.url === '/api/v1/reports/margin');
    expect(marginReq.request.params.get('groupBy')).toBe('brand');
    flushJsonBlob(marginReq, {
      rows: [{ key: '1', label: 'Marca Uno', netSales: 10000, cost: 6000, grossMargin: 4000 }],
    });
    expect((await marginPromise).rows?.[0]?.grossMargin).toBe(4000);

    const valPromise = firstValueFrom(service.getInventoryValuation({ groupBy: 'store' }));
    const valReq = http.expectOne((r) => r.url === '/api/v1/reports/inventory-valuation');
    expect(valReq.request.params.has('from')).toBe(false);
    expect(valReq.request.params.has('to')).toBe(false);
    expect(valReq.request.params.get('groupBy')).toBe('store');
    flushJsonBlob(valReq, {
      rows: [{ key: '1', label: 'Local Centro', qty: 300, valuation: 450000, pct: 100 }],
    });
    expect((await valPromise).rows?.[0]?.valuation).toBe(450000);

    const purchPromise = firstValueFrom(
      service.getPurchasesBySupplier({ from: '2026-11-01', to: '2026-11-20' }),
    );
    const purchReq = http.expectOne((r) => r.url === '/api/v1/reports/purchases-by-supplier');
    flushJsonBlob(purchReq, {
      rows: [{ supplierId: 5, name: 'Distribuidora Sur', orders: 4, fillRatePct: 95.5 }],
    });
    expect((await purchPromise).rows?.[0]?.fillRatePct).toBe(95.5);

    const shrinkPromise = firstValueFrom(
      service.getShrinkageReport({ from: '2026-11-01', to: '2026-11-20', groupBy: 'type' }),
    );
    const shrinkReq = http.expectOne((r) => r.url === '/api/v1/reports/shrinkage');
    expect(shrinkReq.request.params.get('groupBy')).toBe('type');
    flushJsonBlob(shrinkReq, {
      rows: [{ key: 'EXPIRED', label: 'Vencimiento', count: 3, cost: 12000, pctOfSales: 1.2 }],
    });
    expect((await shrinkPromise).rows?.[0]?.pctOfSales).toBe(1.2);

    const topShrinkPromise = firstValueFrom(
      service.getTopShrinkageProducts({ from: '2026-11-01', to: '2026-11-20', limit: 10 }),
    );
    const topShrinkReq = http.expectOne((r) => r.url === '/api/v1/reports/shrinkage/top-products');
    expect(topShrinkReq.request.params.get('limit')).toBe('10');
    flushJsonBlob(topShrinkReq, {
      rows: [{ key: '14', label: 'Yogurt 1L', count: 2, cost: 8000 }],
    });
    expect((await topShrinkPromise).rows?.[0]?.label).toBe('Yogurt 1L');

    const cashPromise = firstValueFrom(
      service.getCashDifferences({
        from: '2026-11-01',
        to: '2026-11-20',
        cashierId: 7,
        withDifference: true,
      }),
    );
    const cashReq = http.expectOne((r) => r.url === '/api/v1/reports/cash-differences');
    expect(cashReq.request.params.get('cashierId')).toBe('7');
    expect(cashReq.request.params.get('withDifference')).toBe('true');
    flushJsonBlob(cashReq, {
      rows: [{ sessionId: 21, number: 'L01-TU-000021', difference: -1500 }],
    });
    expect((await cashPromise).rows?.[0]?.difference).toBe(-1500);
  });

  it('exportReport descarga binario XLSX/PDF con filtros y extrae nombre desde Content-Disposition', async () => {
    const xlsxPromise = firstValueFrom(
      service.exportReport('sales', {
        format: 'xlsx',
        from: '2026-11-01',
        to: '2026-11-20',
        storeIds: [1, 2],
        salesGroupBy: 'store',
      }),
    );

    const xlsxReq = http.expectOne((r) => r.url === '/api/v1/reports/sales/export');
    expect(xlsxReq.request.method).toBe('GET');
    expect(xlsxReq.request.params.get('format')).toBe('xlsx');
    expect(xlsxReq.request.params.get('groupBy')).toBe('store');
    expect(xlsxReq.request.params.getAll('storeIds')).toEqual(['1', '2']);

    const xlsxBlob = new Blob(['xlsx-binary'], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    xlsxReq.flush(xlsxBlob, {
      headers: {
        'Content-Disposition': 'attachment; filename="reporte-ventas-2026.xlsx"',
      },
    });

    const xlsxFile = await xlsxPromise;
    expect(xlsxFile.fileName).toBe('reporte-ventas-2026.xlsx');
    expect(xlsxFile.blob).toBe(xlsxBlob);

    const pdfPromise = firstValueFrom(
      service.exportReport('cash-differences', {
        format: 'pdf',
        from: '2026-11-01',
        to: '2026-11-20',
        cashierId: 4,
        withDifference: true,
      }),
    );

    const pdfReq = http.expectOne((r) => r.url === '/api/v1/reports/cash-differences/export');
    expect(pdfReq.request.params.get('format')).toBe('pdf');
    expect(pdfReq.request.params.get('cashierId')).toBe('4');
    expect(pdfReq.request.params.get('withDifference')).toBe('true');

    const pdfBlob = new Blob(['%PDF-1.4'], { type: 'application/pdf' });
    pdfReq.flush(pdfBlob, {
      headers: {
        'Content-Disposition': "attachment; filename*=UTF-8''diferencias-caja-nov.pdf",
      },
    });

    const pdfFile = await pdfPromise;
    expect(pdfFile.fileName).toBe('diferencias-caja-nov.pdf');
    expect(pdfFile.blob).toBe(pdfBlob);

    const fallbackPromise = firstValueFrom(
      service.exportReport('shrinkage-top-products', {
        format: 'pdf',
        from: '2026-11-01',
        to: '2026-11-20',
        limit: 15,
      }),
    );
    const fallbackReq = http.expectOne(
      (r) => r.url === '/api/v1/reports/shrinkage/top-products/export',
    );
    expect(fallbackReq.request.params.get('limit')).toBe('15');
    fallbackReq.flush(new Blob(['%PDF'], { type: 'application/pdf' }));
    const fallbackFile = await fallbackPromise;
    expect(fallbackFile.fileName).toBe('reporte-mermas-top-productos.pdf');
  });

  it('convierte errores ProblemDetail en Blob a ApiError tipado', async () => {
    const promise = firstValueFrom(
      service.getSalesReport(
        { from: '2025-01-01', to: '2026-11-20', groupBy: 'day' },
        withoutErrorNotification(),
      ),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/reports/sales');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            status: 400,
            code: 'VALIDATION_ERROR',
            detail: 'El rango del reporte no puede superar 366 días.',
            errors: [{ field: 'to', code: 'MAX_RANGE_DAYS', message: 'Máximo 366 días.' }],
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 400, statusText: 'Bad Request' },
    );

    await expect(promise).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
      message: 'El rango del reporte no puede superar 366 días.',
    } satisfies Partial<ApiError>);
  });

  it('listCategories y listUsers consultan /api/v1/categories y /api/v1/users', async () => {
    const catPromise = firstValueFrom(service.listCategories());
    const catReq = http.expectOne((r) => r.url === '/api/v1/categories');
    expect(catReq.request.params.get('tree')).toBe('true');
    flushJsonBlob(catReq, [{ id: 1, name: 'Lácteos', children: [] }]);
    expect(await catPromise).toHaveLength(1);

    const usersPromise = firstValueFrom(service.listUsers());
    const usersReq = http.expectOne((r) => r.url === '/api/v1/users');
    expect(usersReq.request.params.get('active')).toBe('true');
    flushJsonBlob(usersReq, { content: [{ id: 5, username: 'cajero1', fullName: 'Cajero Uno' }] });
    expect((await usersPromise)[0]?.fullName).toBe('Cajero Uno');
  });

  it('extractFileName y triggerBrowserDownload operan con fallback y enlace blob', () => {
    expect(extractFileName(null, 'reporte.xlsx')).toBe('reporte.xlsx');
    expect(extractFileName('attachment; filename="ventas.pdf"', 'reporte.pdf')).toBe('ventas.pdf');

    const createObjectUrlMock = vi.fn(() => 'blob:http://localhost/report-1');
    const revokeObjectUrlMock = vi.fn();
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: createObjectUrlMock,
      revokeObjectURL: revokeObjectUrlMock,
    });
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {
      // No navega en jsdom
    });

    const blob = new Blob(['test'], { type: 'application/pdf' });
    triggerBrowserDownload({ blob, fileName: 'ventas.pdf' });

    expect(createObjectUrlMock).toHaveBeenCalledWith(blob);
    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(revokeObjectUrlMock).toHaveBeenCalledWith('blob:http://localhost/report-1');
  });
});
