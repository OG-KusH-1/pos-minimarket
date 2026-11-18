import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { Receipts } from './receipts';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('Receipts', () => {
  let fixture: ComponentFixture<Receipts>;
  let http: HttpTestingController;
  let grantedPermissions: Set<string>;

  beforeEach(async () => {
    grantedPermissions = new Set([
      'purchasing:order:read',
      'purchasing:receipt:create',
      'purchasing:receipt:confirm',
      'purchasing:receipt:without-order',
      'catalog:cost:read',
    ]);

    await TestBed.configureTestingModule({
      imports: [Receipts],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            hasPermission: (perm: string) => grantedPermissions.has(perm),
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: signal(1),
            activeStore: signal({ id: 1, code: 'CTR', name: 'Local Centro' }),
          },
        },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: ConfirmDialogService, useValue: { confirm: vi.fn() } },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function flushInitialLoad(): void {
    const receiptsReq = http.expectOne((r) => r.url === '/api/v1/goods-receipts');
    flushJson(receiptsReq, {
      content: [
        {
          id: 20,
          number: 'RC-020',
          supplierName: 'Lácteos del Sur',
          purchaseOrderNumber: 'OC-010',
          status: 'CONFIRMED',
          netTotal: 85000,
          receivedAt: '2026-11-18T12:00:00Z',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['receivedAt,desc'],
    });

    const supReq = http.expectOne((r) => r.url === '/api/v1/suppliers');
    flushJson(supReq, { content: [{ id: 1, legalName: 'Lácteos del Sur', rut: '76.111.222-3' }] });
  }

  it('muestra listado de recepciones, columna Total neto y botón Recepción sin OC con permisos', async () => {
    fixture = TestBed.createComponent(Receipts);
    fixture.detectChanges();
    flushInitialLoad();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('RC-020');
    expect(text).toContain('Lácteos del Sur');
    expect(text).toContain('Recepción sin OC');
    expect(text).toContain('Recepcionar desde OC');
    const headers = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('th')).map(
      (th) => th.textContent?.trim(),
    );
    expect(headers).toContain('Total neto');
  });

  it('oculta Total neto sin catalog:cost:read y Recepción sin OC sin purchasing:receipt:without-order', async () => {
    grantedPermissions.delete('catalog:cost:read');
    grantedPermissions.delete('purchasing:receipt:without-order');

    fixture = TestBed.createComponent(Receipts);
    fixture.detectChanges();
    flushInitialLoad();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('RC-020');
    expect(text).not.toContain('Recepción sin OC');
    const headers = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('th')).map(
      (th) => th.textContent?.trim(),
    );
    expect(headers).not.toContain('Total neto');
  });
});
