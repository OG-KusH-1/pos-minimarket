import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { ProductForm } from './product-form';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('ProductForm', () => {
  let fixture: ComponentFixture<ProductForm>;
  let http: HttpTestingController;
  let grantedPermissions: Set<string>;
  let confirmSpy: ReturnType<typeof vi.fn>;

  async function mountForm(
    params: Record<string, string>,
    permissions: readonly string[],
  ): Promise<void> {
    grantedPermissions = new Set(permissions);
    confirmSpy = vi.fn(() => of(true));

    await TestBed.configureTestingModule({
      imports: [ProductForm],
      providers: [
        provideApiConfiguration(''),
        provideRouter([]),
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
            storeId: () => 1,
            availableStores: () => [{ id: 1, code: 'L1', name: 'Local Central' }],
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of(convertToParamMap(params)),
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProductForm);
    fixture.detectChanges();
  }

  async function flushAuxiliaryCatalogs(): Promise<void> {
    flushJson(
      http.expectOne((r) => r.url === '/api/v1/categories'),
      [{ id: 1, name: 'Abarrotes', active: true, children: [] }],
    );
    flushJson(
      http.expectOne((r) => r.url === '/api/v1/brands'),
      { content: [{ id: 2, name: 'Marca Propia', active: true }] },
    );
    flushJson(
      http.expectOne((r) => r.url === '/api/v1/taxes'),
      [
        { id: 10, code: 'IVA', name: 'IVA 19%', rate: 19, taxType: 'VAT', active: true },
        {
          id: 11,
          code: 'IABA_10',
          name: 'Azucaradas 10%',
          rate: 10,
          taxType: 'ADDITIONAL',
          active: true,
        },
      ],
    );
    flushJson(
      http.expectOne((r) => r.url === '/api/v1/scale-barcode-configs'),
      [],
    );

    const comp = fixture.componentInstance as unknown as {
      taxes: ProductForm['taxes'];
    };
    await vi.waitFor(() => {
      expect(comp.taxes()).toHaveLength(2);
    });
    fixture.detectChanges();
  }

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  describe('modo creación', () => {
    it('valida dígitos verificadores EAN-13, EAN-8 y UPC-A al agregar códigos iniciales', async () => {
      await mountForm({}, ['catalog:product:create', 'config:tax:manage']);
      await flushAuxiliaryCatalogs();

      const comp = fixture.componentInstance as unknown as {
        barcodeForm: ProductForm['barcodeForm'];
        pendingBarcodes: ProductForm['pendingBarcodes'];
        addBarcodeEntry(): void;
      };

      comp.barcodeForm.patchValue({ code: '7801234567890', barcodeType: 'EAN13' });
      expect(comp.barcodeForm.controls.code.hasError('ean13Invalid')).toBe(true);
      comp.addBarcodeEntry();
      expect(comp.pendingBarcodes()).toHaveLength(0);

      comp.barcodeForm.patchValue({
        code: '7801234567894',
        barcodeType: 'EAN13',
        isPrimary: true,
      });
      expect(comp.barcodeForm.controls.code.valid).toBe(true);
      comp.addBarcodeEntry();
      expect(comp.pendingBarcodes()).toEqual([
        { code: '7801234567894', barcodeType: 'EAN13', isPrimary: true },
      ]);

      comp.barcodeForm.patchValue({ code: '12345678', barcodeType: 'EAN8' });
      expect(comp.barcodeForm.controls.code.hasError('ean8Invalid')).toBe(true);

      comp.barcodeForm.patchValue({ code: '036000291459', barcodeType: 'UPC_A' });
      expect(comp.barcodeForm.controls.code.hasError('upcaInvalid')).toBe(true);
    });

    it('exige producto base y factor 2..1000 en saleType=PACK y deshabilita lotControlled', async () => {
      await mountForm({}, [
        'catalog:product:create',
        'catalog:product:update-price',
        'config:tax:manage',
      ]);
      await flushAuxiliaryCatalogs();

      const router = TestBed.inject(Router);
      const navSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const comp = fixture.componentInstance as unknown as {
        generalForm: ProductForm['generalForm'];
        packForm: ProductForm['packForm'];
        baseProductOptions: ProductForm['baseProductOptions'];
        generalError: ProductForm['generalError'];
        saveGeneral(): void;
      };

      comp.generalForm.patchValue({
        sku: 'PACK-6',
        name: 'Pack Bebida 6x350',
        categoryId: 1,
        lotControlled: true,
        saleType: 'PACK',
      });

      const baseReq = http.expectOne(
        (r) => r.url === '/api/v1/products' && r.params.get('saleType') === 'UNIT',
      );
      flushJson(baseReq, {
        content: [{ id: 50, sku: 'BEB-350', name: 'Bebida Lata 350ml' }],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
        sort: [],
      });
      await vi.waitFor(() => {
        expect(comp.baseProductOptions()).toHaveLength(1);
      });
      fixture.detectChanges();

      expect(comp.generalForm.controls.unitOfMeasure.value).toBe('UNIT');
      expect(comp.generalForm.controls.lotControlled.disabled).toBe(true);
      expect(comp.generalForm.controls.lotControlled.value).toBe(false);

      comp.packForm.patchValue({ baseProductId: null, factor: 1 });
      comp.saveGeneral();
      expect(comp.packForm.controls.baseProductId.hasError('required')).toBe(true);
      expect(comp.packForm.controls.factor.hasError('min')).toBe(true);
      expect(comp.generalError()).toContain('producto base');

      comp.packForm.patchValue({ baseProductId: 50, factor: 6 });
      comp.generalForm.controls.initialPriceGross.setValue(4500);
      comp.saveGeneral();

      const createReq = http.expectOne((r) => r.url === '/api/v1/products' && r.method === 'POST');
      expect(createReq.request.body).toMatchObject({
        sku: 'PACK-6',
        saleType: 'PACK',
        unitOfMeasure: 'UNIT',
        lotControlled: false,
        initialPriceGross: 4500,
        pack: { baseProductId: 50, factor: 6 },
      });
      flushJson(createReq, { id: 99, sku: 'PACK-6', name: 'Pack Bebida 6x350', version: 0 });
      await vi.waitFor(() => {
        expect(navSpy).toHaveBeenCalledWith(['/catalogo/productos', 99]);
      });
    });
  });

  describe('modo ficha / edición y visibilidad de costos', () => {
    async function flushLoadedProduct(withCosts: boolean): Promise<void> {
      const prodReq = await vi.waitFor(() => http.expectOne('/api/v1/products/42'));
      flushJson(prodReq, {
        id: 42,
        sku: 'ACE-1L',
        name: 'Aceite Vegetal 1L',
        category: { id: 1, name: 'Abarrotes' },
        saleType: 'UNIT',
        unitOfMeasure: 'UNIT',
        lotControlled: false,
        allowManualDiscount: true,
        priceGross: 2490,
        netPrice: 2092,
        avgCost: withCosts ? 1500 : null,
        lastCost: withCosts ? 1520 : null,
        marginPct: withCosts ? 28.3 : null,
        vatExempt: false,
        taxes: [{ id: 10, code: 'IVA', name: 'IVA 19%', rate: 19, taxType: 'VAT' }],
        barcodes: [{ id: 7, code: '7801234567894', barcodeType: 'EAN13', isPrimary: true }],
        active: true,
        version: 3,
      });

      const histReq = await vi.waitFor(() =>
        http.expectOne((r) => r.url === '/api/v1/products/42/price-history'),
      );
      flushJson(histReq, {
        content: [
          {
            id: 1,
            changeType: 'SALE_PRICE',
            oldPriceGross: 2290,
            newPriceGross: 2490,
            oldCost: withCosts ? 1400 : null,
            newCost: withCosts ? 1500 : null,
            reason: 'Lista vigente',
            changedAt: '2026-11-10T12:00:00Z',
          },
        ],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        sort: [],
      });

      await vi.waitFor(() => {
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Aceite Vegetal 1L');
      });
    }

    async function selectTab(label: string): Promise<void> {
      const tabs = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('[role="tab"]'),
      );
      const target = tabs.find((tab) => tab.textContent?.includes(label));
      expect(target).toBeDefined();
      target?.click();
      await vi.waitFor(() => {
        fixture.detectChanges();
        expect(target?.getAttribute('aria-selected')).toBe('true');
      });
    }

    it('oculta costos, margen y columnas de costo en historial si falta catalog:cost:read', async () => {
      await mountForm({ id: '42' }, ['catalog:product:read', 'catalog:product:update-price']);
      await flushAuxiliaryCatalogs();
      await flushLoadedProduct(false);
      await selectTab('Precio y costos');

      const comp = fixture.componentInstance as unknown as {
        historyColumns: ProductForm['historyColumns'];
      };
      const keys = comp.historyColumns().map((c) => c.key);
      expect(keys).not.toContain('oldCost');
      expect(keys).not.toContain('newCost');

      await vi.waitFor(() => {
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Aceite Vegetal 1L');
        expect(text).toContain('Precio de venta');
        expect(text).not.toContain('Costos y margen');
        expect(text).not.toContain('Costo promedio');
        expect(text).not.toContain('Margen bruto');
      });
    });

    it('muestra costos y permite actualizar costo promedio cuando posee catalog:cost:read y catalog:cost:update', async () => {
      await mountForm({ id: '42' }, [
        'catalog:product:read',
        'catalog:cost:read',
        'catalog:cost:update',
      ]);
      await flushAuxiliaryCatalogs();
      await flushLoadedProduct(true);
      await selectTab('Precio y costos');

      const comp = fixture.componentInstance as unknown as {
        historyColumns: ProductForm['historyColumns'];
        costForm: ProductForm['costForm'];
        saveCost(): void;
      };
      const keys = comp.historyColumns().map((c) => c.key);
      expect(keys).toContain('oldCost');
      expect(keys).toContain('newCost');

      await vi.waitFor(() => {
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Costos y margen');
        expect(text).toContain('$1.500');
        expect(text).toContain('28,3 %');
      });

      comp.costForm.patchValue({ newAvgCost: 1600, reason: 'Corrección factura' });
      comp.saveCost();

      const costReq = http.expectOne('/api/v1/products/42/cost');
      expect(costReq.request.method).toBe('PUT');
      expect(costReq.request.body).toEqual({
        newAvgCost: 1600,
        reason: 'Corrección factura',
        version: 3,
      });
      flushJson(costReq, {
        id: 42,
        sku: 'ACE-1L',
        name: 'Aceite Vegetal 1L',
        category: { id: 1, name: 'Abarrotes' },
        saleType: 'UNIT',
        unitOfMeasure: 'UNIT',
        priceGross: 2490,
        netPrice: 2092,
        avgCost: 1600,
        lastCost: 1520,
        marginPct: 23.52,
        taxes: [{ id: 10, code: 'IVA', name: 'IVA 19%', rate: 19, taxType: 'VAT' }],
        barcodes: [],
        version: 4,
      });

      const reloadHistReq = await vi.waitFor(() =>
        http.expectOne((r) => r.url === '/api/v1/products/42/price-history'),
      );
      flushJson(reloadHistReq, {
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
        sort: [],
      });

      await vi.waitFor(() => {
        fixture.detectChanges();
        const updatedText = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(updatedText).toContain('$1.600');
      });
    });

    it('valida coherencia de vatExempt vs IVA antes de guardar impuestos', async () => {
      await mountForm({ id: '42' }, ['catalog:product:read', 'config:tax:manage']);
      await flushAuxiliaryCatalogs();
      await flushLoadedProduct(false);

      const comp = fixture.componentInstance as unknown as {
        onVatExemptChange(val: boolean): void;
        taxesValidationError: ProductForm['taxesValidationError'];
        saveTaxes(): void;
      };

      comp.onVatExemptChange(true);
      expect(comp.taxesValidationError()).toBeNull();
      comp.saveTaxes();

      const taxReq = http.expectOne('/api/v1/products/42/taxes');
      expect(taxReq.request.method).toBe('PUT');
      expect(taxReq.request.body).toEqual({ vatExempt: true, taxIds: [] });
      flushJson(taxReq, []);

      await flushLoadedProduct(false);
    });
  });
});
