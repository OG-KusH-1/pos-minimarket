import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { ApiError } from '../../../core/http/api-error';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { ImportsService, extractFileName } from './imports.service';

function flushJsonBlob(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('ImportsService', () => {
  let service: ImportsService;
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
    service = TestBed.inject(ImportsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('createImport envía multipart a POST /api/v1/catalog/imports con dryRun y X-Store-Id y desenvuelve Blob JSON', async () => {
    const file = new File(['sku,name\nBEB-1,Agua'], 'productos.csv', { type: 'text/csv' });
    const promise = firstValueFrom(
      service.createImport({
        importType: 'PRODUCTS',
        dryRun: true,
        file,
        storeId: 3,
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/catalog/imports');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('importType')).toBe('PRODUCTS');
    expect(req.request.params.get('dryRun')).toBe('true');
    expect(req.request.headers.get('X-Store-Id')).toBe('3');
    expect(req.request.body instanceof FormData).toBe(true);

    flushJsonBlob(req, {
      id: 15,
      importType: 'PRODUCTS',
      dryRun: true,
      status: 'VALIDATED',
      totalRows: 1,
      okRows: 1,
      errorRows: 0,
      errors: [],
    });

    const job = await promise;
    expect(job.id).toBe(15);
    expect(job.status).toBe('VALIDATED');
    expect(job.okRows).toBe(1);
  });

  it('getImport y confirmImport consultan /api/v1/catalog/imports/{id} y /confirm', async () => {
    const getPromise = firstValueFrom(service.getImport(22));
    const getReq = http.expectOne('/api/v1/catalog/imports/22');
    expect(getReq.request.method).toBe('GET');
    flushJsonBlob(getReq, {
      id: 22,
      importType: 'INITIAL_STOCK',
      dryRun: true,
      status: 'VALIDATED',
      totalRows: 4,
      okRows: 2,
      errorRows: 2,
      errors: [{ row: 2, field: 'quantity', message: 'Debe ser mayor a 0' }],
    });
    const fetched = await getPromise;
    expect(fetched.errorRows).toBe(2);
    expect(fetched.errors?.[0]?.field).toBe('quantity');

    const confirmPromise = firstValueFrom(service.confirmImport(22, 5));
    const confirmReq = http.expectOne('/api/v1/catalog/imports/22/confirm');
    expect(confirmReq.request.method).toBe('POST');
    expect(confirmReq.request.headers.get('X-Store-Id')).toBe('5');
    flushJsonBlob(confirmReq, {
      id: 22,
      importType: 'INITIAL_STOCK',
      dryRun: false,
      status: 'COMPLETED',
      totalRows: 4,
      okRows: 4,
      errorRows: 0,
      errors: [],
    });
    const confirmed = await confirmPromise;
    expect(confirmed.status).toBe('COMPLETED');
  });

  it('downloadTemplate descarga binario y extrae el nombre desde Content-Disposition', async () => {
    const promise = firstValueFrom(service.downloadTemplate('PRODUCT_SUPPLIERS', 'csv'));
    const req = http.expectOne(
      (r) => r.url === '/api/v1/catalog/imports/templates/PRODUCT_SUPPLIERS',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('format')).toBe('csv');

    req.flush(new Blob(['sku,supplierRut\n'], { type: 'text/csv' }), {
      headers: {
        'Content-Type': 'text/csv',
        'Content-Disposition': 'attachment; filename="plantilla-proveedores.csv"',
      },
    });

    const result = await promise;
    expect(result.fileName).toBe('plantilla-proveedores.csv');
    expect(result.blob.size).toBeGreaterThan(0);
  });

  it('exportCatalog envía filtros y formato a GET /api/v1/products/export', async () => {
    const promise = firstValueFrom(
      service.exportCatalog({
        format: 'xlsx',
        q: 'bebida',
        categoryId: 4,
        active: true,
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/products/export');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('format')).toBe('xlsx');
    expect(req.request.params.get('q')).toBe('bebida');
    expect(req.request.params.get('categoryId')).toBe('4');
    expect(req.request.params.get('active')).toBe('true');

    req.flush(new Blob(['xlsx-bytes'], { type: 'application/octet-stream' }), {
      headers: {
        'Content-Disposition': "attachment; filename*=UTF-8''catalogo-2026.xlsx",
      },
    });

    const exported = await promise;
    expect(exported.fileName).toBe('catalogo-2026.xlsx');
  });

  it('desenvuelve ProblemDetail en Blob como ApiError con código FILE_INVALID', async () => {
    const file = new File(['x'], 'grande.xlsx');
    const promise = firstValueFrom(
      service.createImport(
        { importType: 'PRODUCTS', dryRun: true, file },
        withoutErrorNotification(),
      ),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/catalog/imports');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            type: 'about:blank',
            title: 'Archivo inválido',
            status: 400,
            detail: 'El archivo supera el máximo permitido de 20 MB.',
            code: 'FILE_INVALID',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 400, statusText: 'Bad Request' },
    );

    await expect(promise).rejects.toMatchObject({
      code: 'FILE_INVALID',
      message: 'El archivo supera el máximo permitido de 20 MB.',
    } satisfies Partial<ApiError>);
  });

  it('extractFileName usa el fallback si Content-Disposition no está presente', () => {
    expect(extractFileName(null, 'fallback.xlsx')).toBe('fallback.xlsx');
    expect(extractFileName('attachment; filename="demo.csv"', 'fallback.csv')).toBe('demo.csv');
  });
});
