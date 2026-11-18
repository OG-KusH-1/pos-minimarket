import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { Imports } from './imports';
import { MAX_IMPORT_FILE_BYTES } from './imports.service';

function flushJsonBlob(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('Imports', () => {
  let fixture: ComponentFixture<Imports>;
  let http: HttpTestingController;
  let grantedPermissions: Set<string>;
  let confirmSpy: ReturnType<typeof vi.fn>;

  async function mountWithPermissions(permissions: readonly string[]): Promise<void> {
    grantedPermissions = new Set(permissions);
    confirmSpy = vi.fn(() => of(true));

    await TestBed.configureTestingModule({
      imports: [Imports],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: ConfirmDialogService, useValue: { confirm: confirmSpy } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) => grantedPermissions.has(code),
          },
        },
        {
          provide: StoreContextService,
          useValue: {
            storeId: signal(1),
            activeStore: signal({ id: 1, code: 'SUC-1', name: 'Casa Matriz' }),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Imports);
    fixture.detectChanges();

    if (grantedPermissions.has('catalog:product:export')) {
      flushJsonBlob(
        http.expectOne((r) => r.url === '/api/v1/categories'),
        [{ id: 1, name: 'Bebidas', active: true, children: [] }],
      );
      flushJsonBlob(
        http.expectOne((r) => r.url === '/api/v1/brands'),
        { content: [{ id: 2, name: 'CCU', active: true }] },
      );
      fixture.detectChanges();
    }
  }

  function selectFile(file: File): void {
    const input = (fixture.nativeElement as HTMLElement).querySelector(
      'input[type="file"]',
    ) as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function clickButtonContaining(text: string): HTMLButtonElement {
    const buttons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ) as HTMLButtonElement[];
    const match = buttons.find((btn) => btn.textContent?.includes(text));
    if (!match) {
      throw new Error(`No se encontró el botón con texto "${text}"`);
    }
    match.click();
    fixture.detectChanges();
    return match;
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('renderiza el título y oculta las secciones cuando faltan permisos', async () => {
    await mountWithPermissions([]);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent?.trim()).toBe('Importaciones');
    expect(el.textContent).toContain('catalog:product:import');
  });

  it('descarga plantilla XLSX al presionar el botón correspondiente', async () => {
    await mountWithPermissions(['catalog:product:import']);

    clickButtonContaining('Descargar plantilla XLSX');

    const req = http.expectOne((r) => r.url === '/api/v1/catalog/imports/templates/PRODUCTS');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('format')).toBe('xlsx');
    req.flush(new Blob(['xlsx'], { type: 'application/octet-stream' }), {
      headers: { 'Content-Disposition': 'attachment; filename="plantilla-productos.xlsx"' },
    });
    fixture.detectChanges();
  });

  it('rechaza archivos mayores a 20 MB sin enviar petición al servidor', async () => {
    await mountWithPermissions(['catalog:product:import']);

    const oversized = new File(['x'], 'enorme.csv', { type: 'text/csv' });
    Object.defineProperty(oversized, 'size', { value: MAX_IMPORT_FILE_BYTES + 1 });
    selectFile(oversized);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('supera el límite máximo de 20 MB');

    const validateBtn = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Validar archivo'),
    ) as HTMLButtonElement;
    expect(validateBtn.disabled).toBe(true);
  });

  it('ejecuta dryRun=true, muestra resumen y tabla de errores por fila y campo y deshabilita Confirmar', async () => {
    await mountWithPermissions(['catalog:product:import']);

    selectFile(new File(['sku,price\nA,0'], 'productos.csv', { type: 'text/csv' }));
    clickButtonContaining('Validar archivo');

    const req = http.expectOne((r) => r.url === '/api/v1/catalog/imports');
    expect(req.request.params.get('dryRun')).toBe('true');
    flushJsonBlob(req, {
      id: 77,
      importType: 'PRODUCTS',
      dryRun: true,
      status: 'VALIDATED',
      totalRows: 5,
      okRows: 3,
      errorRows: 2,
      errors: [
        { row: 2, field: 'sku', message: 'El SKU ya existe' },
        { row: 4, field: 'priceGross', message: 'Debe ser mayor a 0' },
      ],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('Detalle de errores (2)');
      expect(text).toContain('priceGross');
      expect(text).toContain('Debe ser mayor a 0');
      expect(text).toContain('El SKU ya existe');
    });

    const confirmBtn = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((b) => b.textContent?.includes('Confirmar importación')) as HTMLButtonElement;
    expect(confirmBtn.disabled).toBe(true);
  });

  it('habilita y ejecuta Confirmar importación cuando dryRun=true no tiene errores', async () => {
    await mountWithPermissions(['catalog:product:import']);

    selectFile(new File(['sku,price\nA,990'], 'ok.csv', { type: 'text/csv' }));
    clickButtonContaining('Validar archivo');

    flushJsonBlob(
      http.expectOne((r) => r.url === '/api/v1/catalog/imports'),
      {
        id: 88,
        importType: 'PRODUCTS',
        dryRun: true,
        status: 'VALIDATED',
        totalRows: 3,
        okRows: 3,
        errorRows: 0,
        errors: [],
      },
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'Archivo validado sin errores',
      );
    });

    clickButtonContaining('Confirmar importación');
    expect(confirmSpy).toHaveBeenCalledTimes(1);

    const confirmReq = http.expectOne('/api/v1/catalog/imports/88/confirm');
    expect(confirmReq.request.method).toBe('POST');
    flushJsonBlob(confirmReq, {
      id: 88,
      importType: 'PRODUCTS',
      dryRun: false,
      status: 'COMPLETED',
      totalRows: 3,
      okRows: 3,
      errorRows: 0,
      errors: [],
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'Importación completada y aplicada correctamente',
      );
    });
  });

  it('muestra aviso de costos omitidos sin catalog:cost:read y exporta catálogo en la segunda pestaña', async () => {
    await mountWithPermissions(['catalog:product:import', 'catalog:product:export']);

    const tabs = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[role="tab"]'),
    ) as HTMLElement[];
    expect(tabs.length).toBeGreaterThan(1);
    tabs[1]!.click();
    fixture.detectChanges();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'omitirá las columnas de costo',
      );
    });

    clickButtonContaining('Exportar catálogo');
    const exportReq = http.expectOne((r) => r.url === '/api/v1/products/export');
    expect(exportReq.request.method).toBe('GET');
    expect(exportReq.request.params.get('format')).toBe('xlsx');
    exportReq.flush(new Blob(['excel'], { type: 'application/octet-stream' }), {
      headers: { 'Content-Disposition': 'attachment; filename="catalogo.xlsx"' },
    });
    fixture.detectChanges();
  });
});
