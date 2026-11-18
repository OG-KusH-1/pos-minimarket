import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../../api/api-configuration';
import { PosProductResult } from '../../../../api/models/pos-product-result';
import { ScanResultResponse } from '../../../../api/models/scan-result-response';
import { errorInterceptor } from '../../../../core/http/error.interceptor';
import { storeContextInterceptor } from '../../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../../core/store-context/store-context.service';
import { PosSearchBar } from './pos-search-bar';

function jsonBlob(payload: unknown): Blob {
  return new Blob([JSON.stringify(payload)], { type: 'application/json' });
}

@Component({
  selector: 'pos-search-bar-host',
  imports: [PosSearchBar],
  template: `
    <pos-search-bar
      [disabled]="disabled"
      (scanResolved)="resolved.push($event)"
      (productSelected)="selected.push($event)"
    />
  `,
})
class PosSearchBarHost {
  disabled = false;
  readonly resolved: { readonly result: ScanResultResponse; readonly code: string }[] = [];
  readonly selected: PosProductResult[] = [];
}

describe('PosSearchBar', () => {
  let fixture: ComponentFixture<PosSearchBarHost>;
  let http: HttpTestingController | undefined;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, storeContextInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });

    http = TestBed.inject(HttpTestingController);
    const storeContext = TestBed.inject(StoreContextService);
    storeContext.setStore(10);
    storeContext.setTerminal(2);

    fixture = TestBed.createComponent(PosSearchBarHost);
    fixture.detectChanges();
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('ejecuta búsqueda incremental con debounce y emite productSelected al elegir con flecha y Enter', async () => {
    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('[data-pos-scan-input]')!;
    input.focus();
    input.value = 'beb';
    input.dispatchEvent(new Event('input'));

    await vi.waitFor(() => {
      const req = http!.expectOne((r) => r.url === '/api/v1/pos/products/search');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('q')).toBe('beb');
      expect(req.request.params.get('limit')).toBe('20');
      req.flush(
        jsonBlob([
          {
            productId: 101,
            sku: 'BEB-01',
            name: 'Bebida Cola 1.5L',
            saleType: 'UNIT',
            unitOfMeasure: 'UNIT',
            priceGross: 1890,
            stockOnHand: 12,
          },
          {
            productId: 102,
            sku: 'BEB-02',
            name: 'Bebida Naranja 1.5L',
            saleType: 'UNIT',
            unitOfMeasure: 'UNIT',
            priceGross: 1790,
            stockOnHand: 8,
          },
        ]),
      );
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(el.querySelectorAll('.result-row')).toHaveLength(2);
    });

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true }));
    fixture.detectChanges();
    expect(
      el.querySelectorAll('.result-row')[1]?.classList.contains('result-row--highlighted'),
    ).toBe(true);

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toHaveLength(1);
    expect(fixture.componentInstance.selected[0]?.productId).toBe(102);
    expect(el.querySelector('.results-panel')).toBeNull();
    expect(input.value).toBe('');
    expect(document.activeElement).toBe(input);
  });

  it('resuelve un código o atajo al presionar Enter sin sugerencias y emite scanResolved', async () => {
    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('[data-pos-scan-input]')!;
    input.focus();
    input.value = 'PAN';
    input.dispatchEvent(new Event('input'));

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));

    const req = http!.expectOne((r) => r.url === '/api/v1/pos/products/resolve');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('code')).toBe('PAN');
    req.flush(
      jsonBlob({
        source: 'SHORTCUT',
        quantity: 1,
        product: {
          productId: 5,
          sku: 'PAN-01',
          name: 'Pan Marraqueta',
          saleType: 'WEIGHT',
          unitOfMeasure: 'KG',
          priceGross: 2100,
        },
      }),
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.componentInstance.resolved).toHaveLength(1);
    });

    expect(fixture.componentInstance.resolved[0]?.code).toBe('PAN');
    expect(fixture.componentInstance.resolved[0]?.result.product?.productId).toBe(5);
    expect(document.activeElement).toBe(input);

    // Esperamos que el timer de debounce (180ms) de 'PAN' haya pasado si estuviera pendiente;
    // resolveCode incrementa searchGeneration y vacía input sin disparar nueva búsqueda si se limpió antes.
    await new Promise((r) => setTimeout(r, 220));
    expect(input.value).toBe('');
  });

  it('muestra error CODE_NOT_FOUND en la barra y conserva el foco cuando resolve falla con 404', async () => {
    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('[data-pos-scan-input]')!;
    input.focus();
    input.value = '999999';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));

    const req = http!.expectOne((r) => r.url === '/api/v1/pos/products/resolve');
    req.flush(
      jsonBlob({
        type: 'https://pos.minimarket.cl/errors/code-not-found',
        title: 'Código no encontrado',
        status: 404,
        detail: 'No existe producto para el código 999999',
        errorCode: 'CODE_NOT_FOUND',
      }),
      { status: 404, statusText: 'Not Found' },
    );

    await vi.waitFor(() => {
      fixture.detectChanges();
      const errBanner = el.querySelector('.scan-error');
      expect(errBanner).not.toBeNull();
      expect(errBanner?.textContent).toContain('No existe producto para el código 999999');
    });

    expect(document.activeElement).toBe(input);
    // Dejamos pasar el debounce para que no quede timer pendiente tras resetTestingModule
    await new Promise((r) => setTimeout(r, 220));
  });
});
