import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  AbstractControl,
  FormControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, Subscription, filter, forkJoin, map, of, switchMap } from 'rxjs';
import { BarcodeRequest } from '../../../api/models/barcode-request';
import { BarcodeResponse } from '../../../api/models/barcode-response';
import { BrandResponse } from '../../../api/models/brand-response';
import { PriceHistoryResponse } from '../../../api/models/price-history-response';
import { ProductRequest } from '../../../api/models/product-request';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { ScaleBarcodeConfigResponse } from '../../../api/models/scale-barcode-config-response';
import { TaxResponse } from '../../../api/models/tax-response';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PageResponse } from '../../../shared/models/page-response';
import { ClpPipe } from '../../../shared/pipes';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import {
  BARCODE_TYPE_LABELS,
  BarcodeType,
  CategoryOption,
  MAX_PRODUCT_IMAGE_BYTES,
  PRICE_CHANGE_TYPE_LABELS,
  RESTRICTED_TYPE_LABELS,
  RestrictedType,
  SALE_TYPE_LABELS,
  SHORTCUT_PATTERN,
  SKU_PATTERN,
  SaleType,
  UnitOfMeasure,
  extractFileIdFromImageUrl,
  flattenCategories,
  formatMarginPct,
  formatUnitCost,
  integerValidator,
  trimmedMinLength,
  validateBarcodeValue,
} from './product-validators';
import { ProductsService } from './products.service';

/** Opción simplificada de producto base para configurar un pack. */
export interface BaseProductOption {
  readonly id: number;
  readonly sku: string;
  readonly name: string;
}

const ALLOWED_IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);

/**
 * Pantalla de creación y ficha completa de producto (`HU-CAT-01`, `HU-CAT-02`, `HU-CAT-03`,
 * `HU-CAT-04`, `HU-CAT-06`, `HU-CAT-08`, `HU-CAT-13`).
 *
 * - En modo creación (`/catalogo/productos/nuevo`), permite definir datos generales, precio
 *   inicial, impuestos, pack (si `saleType=PACK`) y códigos de barras iniciales.
 * - En modo ficha (`/catalogo/productos/:id`), organiza en pestañas los datos generales,
 *   códigos de barras, pack, impuestos, precio y costos, imagen e historial de precios.
 */
@Component({
  selector: 'pos-product-form',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTabsModule,
    MatTooltipModule,
    PaginatedTable,
    ReactiveFormsModule,
    RouterLink,
  ],
  templateUrl: './product-form.html',
  styleUrl: './product-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductForm implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly productsService = inject(ProductsService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly clpPipe = new ClpPipe();
  private readonly datePipe = new DatePipe('en-US');

  private routeSub: Subscription | null = null;

  protected readonly productId = signal<number | null>(null);
  protected readonly isEdit = computed(() => this.productId() !== null);
  protected readonly product = signal<ProductResponse | null>(null);

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly savingGeneral = signal(false);
  protected readonly savingBarcode = signal(false);
  protected readonly savingPack = signal(false);
  protected readonly savingTaxes = signal(false);
  protected readonly savingPrice = signal(false);
  protected readonly savingCost = signal(false);
  protected readonly savingImage = signal(false);

  protected readonly generalError = signal<string | null>(null);
  protected readonly barcodeError = signal<string | null>(null);
  protected readonly packError = signal<string | null>(null);
  protected readonly taxesError = signal<string | null>(null);
  protected readonly priceError = signal<string | null>(null);
  protected readonly costError = signal<string | null>(null);
  protected readonly imageError = signal<string | null>(null);

  protected readonly categories = signal<readonly CategoryOption[]>([]);
  protected readonly brands = signal<readonly BrandResponse[]>([]);
  protected readonly taxes = signal<readonly TaxResponse[]>([]);
  protected readonly scaleConfigs = signal<readonly ScaleBarcodeConfigResponse[]>([]);
  protected readonly baseProductOptions = signal<readonly BaseProductOption[]>([]);
  protected readonly loadingBaseProducts = signal(false);

  protected readonly selectedTaxIds = signal<ReadonlySet<number>>(new Set());
  protected readonly pendingBarcodes = signal<readonly BarcodeRequest[]>([]);
  protected readonly imagePreviewUrl = signal<string | null>(null);

  protected readonly canCreate = computed(() => this.auth.hasPermission('catalog:product:create'));
  protected readonly canUpdate = computed(() => this.auth.hasPermission('catalog:product:update'));
  protected readonly canDeactivate = computed(() =>
    this.auth.hasPermission('catalog:product:deactivate'),
  );
  protected readonly canUpdatePrice = computed(() =>
    this.auth.hasPermission('catalog:product:update-price'),
  );
  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));
  protected readonly canUpdateCost = computed(() => this.auth.hasPermission('catalog:cost:update'));
  protected readonly canManageTaxes = computed(() => this.auth.hasPermission('config:tax:manage'));

  protected readonly canEditGeneral = computed(() =>
    this.isEdit() ? this.canUpdate() : this.canCreate(),
  );

  protected readonly saleTypeLabels = SALE_TYPE_LABELS;
  protected readonly restrictedTypeLabels = RESTRICTED_TYPE_LABELS;
  protected readonly barcodeTypeLabels = BARCODE_TYPE_LABELS;
  protected readonly barcodeTypes: readonly BarcodeType[] = [
    'EAN13',
    'EAN8',
    'UPC_A',
    'INTERNAL',
    'SCALE_PLU',
  ];

  protected readonly hasActivePriceScale = computed(() =>
    this.scaleConfigs().some((cfg) => cfg.active !== false && cfg.embeddedType === 'PRICE'),
  );

  /** Formulario principal de datos generales del producto. */
  protected readonly generalForm = this.fb.group(
    {
      sku: ['', [Validators.required, Validators.pattern(SKU_PATTERN)]],
      name: ['', [trimmedMinLength(2), Validators.maxLength(200)]],
      description: ['', [Validators.maxLength(2000)]],
      categoryId: [null as number | null, [Validators.required]],
      brandId: [null as number | null],
      saleType: ['UNIT' as SaleType, [Validators.required]],
      unitOfMeasure: ['UNIT' as UnitOfMeasure, [Validators.required]],
      restrictedType: [null as RestrictedType | null],
      lotControlled: [false],
      shelfLifeDays: [
        null as number | null,
        [Validators.min(1), Validators.max(3650), integerValidator],
      ],
      shortcutCode: ['', [Validators.pattern(SHORTCUT_PATTERN)]],
      allowManualDiscount: [true],
      initialPriceGross: [0 as number | null, [Validators.min(0), integerValidator]],
    },
    { validators: (group) => this.validateGeneralCoherence(group) },
  );

  /** Buscador de producto base (`saleType=UNIT`) para definir un pack. */
  protected readonly baseSearchControl = new FormControl('', { nonNullable: true });

  /** Formulario de configuración de pack (`HU-CAT-06`). */
  protected readonly packForm = this.fb.group({
    baseProductId: [null as number | null, [Validators.required]],
    factor: [
      2 as number | null,
      [Validators.required, Validators.min(2), Validators.max(1000), integerValidator],
    ],
  });

  /** Formulario de ingreso de código de barras (`HU-CAT-02`). */
  protected readonly barcodeForm = this.fb.group({
    code: [
      '',
      [
        (control: AbstractControl<string>): ValidationErrors | null =>
          validateBarcodeValue(
            control.value,
            (control.parent?.get('barcodeType')?.value as BarcodeType | undefined) ?? 'EAN13',
          ),
      ],
    ],
    barcodeType: ['EAN13' as BarcodeType, [Validators.required]],
    isPrimary: [false],
  });

  /** Formulario de exención e impuestos del producto. */
  protected readonly taxesForm = this.fb.group({
    vatExempt: [false],
  });

  /** Formulario de cambio de precio de venta bruto (CLP). */
  protected readonly priceForm = this.fb.group({
    newPriceGross: [1, [Validators.required, Validators.min(1), integerValidator]],
    reason: ['', [trimmedMinLength(3), Validators.maxLength(250)]],
  });

  /** Formulario de ajuste manual de costo promedio. */
  protected readonly costForm = this.fb.group({
    newAvgCost: [0, [Validators.required, Validators.min(0)]],
    reason: ['', [trimmedMinLength(3), Validators.maxLength(250)]],
  });

  /** Señal reactiva del `saleType` actual en el formulario general. */
  protected readonly selectedSaleType = signal<SaleType>('UNIT');

  /** Error de coherencia tributaria (`vatExempt` vs impuestos seleccionados). */
  protected readonly taxesValidationError = computed<string | null>(() => {
    const vatExempt = this.taxesForm.controls.vatExempt.value;
    const selected = this.selectedTaxIds();
    const selectedTaxes = this.taxes().filter((t) => t.id !== undefined && selected.has(t.id));
    const hasVat = selectedTaxes.some((t) => t.taxType === 'VAT');
    if (vatExempt && hasVat) {
      return 'Un producto exento de IVA no puede incluir un impuesto de tipo IVA.';
    }
    if (!vatExempt && !hasVat && this.taxes().some((t) => t.taxType === 'VAT')) {
      return 'Un producto afecto a IVA debe incluir el impuesto IVA vigente.';
    }
    return null;
  });

  /** Historial paginado de precios y costos; retorna página vacía en modo creación. */
  protected readonly historyDataSource = new PaginatedDataSource<PriceHistoryResponse>(
    (params): Observable<PageResponse<PriceHistoryResponse>> => {
      const id = this.productId();
      if (id === null) {
        return of({
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          sort: [],
        });
      }
      return this.productsService
        .listPriceHistory(
          {
            id,
            page: this.parseNumberParam(params.get('page')),
            size: this.parseNumberParam(params.get('size')),
            sort: params.getAll('sort') ?? undefined,
          },
          withoutErrorNotification(),
        )
        .pipe(
          map((res) => ({
            content: res.content ?? [],
            page: res.page ?? 0,
            size: res.size ?? 20,
            totalElements: res.totalElements ?? 0,
            totalPages: res.totalPages ?? 0,
            sort: res.sort ?? [],
          })),
        );
    },
    { sort: [{ field: 'changedAt', direction: 'desc' }] },
  );

  /** Columnas de historial; `oldCost` y `newCost` se ocultan sin `catalog:cost:read`. */
  protected readonly historyColumns = computed<readonly TableColumn<PriceHistoryResponse>[]>(() => {
    const cols: TableColumn<PriceHistoryResponse>[] = [
      {
        key: 'changedAt',
        header: 'Fecha',
        sortField: 'changedAt',
        cell: (row) =>
          row.changedAt ? (this.datePipe.transform(row.changedAt, 'dd/MM/yyyy HH:mm') ?? '') : '—',
      },
      {
        key: 'changeType',
        header: 'Tipo',
        cell: (row) => (row.changeType ? PRICE_CHANGE_TYPE_LABELS[row.changeType] : '—'),
      },
      {
        key: 'oldPriceGross',
        header: 'Precio anterior',
        align: 'end',
        cell: (row) =>
          row.oldPriceGross !== undefined && row.oldPriceGross !== null
            ? this.clpPipe.transform(row.oldPriceGross) || '$0'
            : '—',
      },
      {
        key: 'newPriceGross',
        header: 'Precio nuevo',
        align: 'end',
        cell: (row) =>
          row.newPriceGross !== undefined && row.newPriceGross !== null
            ? this.clpPipe.transform(row.newPriceGross) || '$0'
            : '—',
      },
    ];

    if (this.canReadCosts()) {
      cols.push(
        {
          key: 'oldCost',
          header: 'Costo anterior',
          align: 'end',
          cell: (row) => formatUnitCost(row.oldCost),
        },
        {
          key: 'newCost',
          header: 'Costo nuevo',
          align: 'end',
          cell: (row) => formatUnitCost(row.newCost),
        },
      );
    }

    cols.push({
      key: 'reason',
      header: 'Motivo',
      cell: (row) => row.reason || '—',
    });

    return cols;
  });

  ngOnInit(): void {
    this.setupFormSubscriptions();
    this.loadAuxiliaryCatalogs();

    this.routeSub = this.route.paramMap.subscribe((params) => {
      const rawId = params.get('id');
      if (rawId === null) {
        this.enterCreateMode();
        return;
      }
      const parsed = Number(rawId);
      if (!Number.isInteger(parsed) || parsed <= 0) {
        this.productId.set(null);
        this.loadError.set('Identificador de producto inválido.');
        return;
      }
      this.enterEditMode(parsed);
    });
  }

  ngOnDestroy(): void {
    this.routeSub?.unsubscribe();
    this.historyDataSource.destroy();
    this.revokeImagePreview();
  }

  protected loadProduct(): void {
    const id = this.productId();
    if (id === null) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);

    this.productsService.getProduct(id, withoutErrorNotification()).subscribe({
      next: (prod) => {
        this.applyLoadedProduct(prod);
        this.loading.set(false);
        this.historyDataSource.reload();
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.loadError.set(
          isApiError(err) ? err.message : 'No se pudo cargar la ficha del producto.',
        );
      },
    });
  }

  protected searchBaseProducts(): void {
    const q = this.baseSearchControl.value.trim() || undefined;
    this.loadingBaseProducts.set(true);
    this.productsService
      .listProducts(
        { saleType: 'UNIT', active: true, q, size: 50, sort: ['name,asc'] },
        withoutErrorNotification(),
      )
      .subscribe({
        next: (page) => {
          const currentId = this.productId();
          const fetched: BaseProductOption[] = (page.content ?? [])
            .filter(
              (p): p is ProductSummary & { id: number; sku: string; name: string } =>
                p.id !== undefined && p.id !== currentId && Boolean(p.sku) && Boolean(p.name),
            )
            .map((p) => ({ id: p.id, sku: p.sku, name: p.name }));
          this.mergeCurrentPackBaseOption(fetched);
          this.baseProductOptions.set(fetched);
          this.loadingBaseProducts.set(false);
        },
        error: () => {
          this.loadingBaseProducts.set(false);
        },
      });
  }

  /** Guarda un producto nuevo (`POST`) o actualiza sus datos generales (`PUT`). */
  protected saveGeneral(): void {
    if (!this.canEditGeneral() || this.savingGeneral()) {
      return;
    }
    this.generalForm.markAllAsTouched();
    if (this.generalForm.invalid) {
      return;
    }

    if (!this.isEdit() && this.selectedSaleType() === 'PACK') {
      this.packForm.markAllAsTouched();
      if (this.packForm.invalid) {
        this.generalError.set('Define el producto base y el factor del pack antes de guardar.');
        return;
      }
    }

    if (!this.isEdit() && this.canManageTaxes() && this.taxesValidationError()) {
      this.generalError.set(this.taxesValidationError());
      return;
    }

    const raw = this.generalForm.getRawValue();
    const shortcut = raw.shortcutCode.trim().toUpperCase();
    const description = raw.description.trim();

    if (!this.isEdit()) {
      const initialPrice =
        this.canUpdatePrice() && raw.initialPriceGross !== null
          ? Math.round(Number(raw.initialPriceGross))
          : 0;
      const packRaw = this.packForm.getRawValue();
      const request: ProductRequest = {
        sku: raw.sku.trim(),
        name: raw.name.trim(),
        description: description || undefined,
        categoryId: raw.categoryId!,
        brandId: raw.brandId ?? undefined,
        saleType: raw.saleType,
        unitOfMeasure: raw.unitOfMeasure,
        restrictedType: raw.restrictedType ?? undefined,
        lotControlled: raw.saleType === 'PACK' ? false : raw.lotControlled,
        shelfLifeDays: raw.shelfLifeDays !== null ? Number(raw.shelfLifeDays) : undefined,
        shortcutCode: shortcut || undefined,
        allowManualDiscount: raw.allowManualDiscount,
        initialPriceGross: initialPrice,
        vatExempt: this.canManageTaxes() ? this.taxesForm.controls.vatExempt.value : false,
        taxIds: this.canManageTaxes() ? Array.from(this.selectedTaxIds()) : undefined,
        barcodes: this.pendingBarcodes().length > 0 ? [...this.pendingBarcodes()] : undefined,
        pack:
          raw.saleType === 'PACK' && packRaw.baseProductId !== null
            ? {
                baseProductId: packRaw.baseProductId,
                factor: Math.round(Number(packRaw.factor ?? 2)),
              }
            : undefined,
      };

      this.savingGeneral.set(true);
      this.generalError.set(null);

      this.productsService.createProduct(request, withoutErrorNotification()).subscribe({
        next: (created) => {
          this.savingGeneral.set(false);
          this.snackBar.open('Producto creado correctamente.', 'Cerrar', { duration: 4000 });
          if (created.id !== undefined) {
            void this.router.navigate(['/catalogo/productos', created.id]);
          } else {
            void this.router.navigate(['/catalogo/productos']);
          }
        },
        error: (err) => {
          this.savingGeneral.set(false);
          this.applyFormErrors(this.generalForm, err, this.generalError);
        },
      });
      return;
    }

    const id = this.productId();
    const current = this.product();
    if (id === null || !current) {
      return;
    }

    const updateRequest: ProductRequest = {
      sku: raw.sku.trim(),
      name: raw.name.trim(),
      description: description || undefined,
      categoryId: raw.categoryId!,
      brandId: raw.brandId ?? undefined,
      saleType: raw.saleType,
      unitOfMeasure: raw.unitOfMeasure,
      restrictedType: raw.restrictedType ?? undefined,
      lotControlled: raw.saleType === 'PACK' ? false : raw.lotControlled,
      shelfLifeDays: raw.shelfLifeDays !== null ? Number(raw.shelfLifeDays) : undefined,
      shortcutCode: shortcut || undefined,
      allowManualDiscount: raw.allowManualDiscount,
      version: current.version,
    };

    this.savingGeneral.set(true);
    this.generalError.set(null);

    this.productsService.updateProduct(id, updateRequest, withoutErrorNotification()).subscribe({
      next: (updated) => {
        this.savingGeneral.set(false);
        this.applyLoadedProduct(updated);
        this.snackBar.open('Producto actualizado correctamente.', 'Cerrar', { duration: 4000 });
      },
      error: (err) => {
        this.savingGeneral.set(false);
        this.applyFormErrors(this.generalForm, err, this.generalError);
      },
    });
  }

  /** Agrega un código de barras a la lista pendiente (creación) o al backend (edición). */
  protected addBarcodeEntry(): void {
    this.barcodeForm.markAllAsTouched();
    if (this.barcodeForm.invalid || this.savingBarcode()) {
      return;
    }

    const raw = this.barcodeForm.getRawValue();
    const code = raw.code.trim();
    const barcodeType = raw.barcodeType;
    const isPrimary = raw.isPrimary;

    if (barcodeType === 'SCALE_PLU' && !this.isScalePluAllowed()) {
      this.barcodeError.set(
        'SCALE_PLU solo se permite en productos pesables o cuando existe una balanza activa de precio embebido.',
      );
      return;
    }

    this.barcodeError.set(null);

    if (!this.isEdit()) {
      const duplicate = this.pendingBarcodes().some((item) =>
        barcodeType === 'SCALE_PLU'
          ? item.barcodeType === 'SCALE_PLU' && item.code === code
          : item.barcodeType !== 'SCALE_PLU' && item.code === code,
      );
      if (duplicate) {
        this.barcodeError.set(`El código '${code}' ya fue agregado en esta ficha.`);
        return;
      }
      this.pendingBarcodes.update((list) => {
        const cleaned = isPrimary ? list.map((b) => ({ ...b, isPrimary: false })) : list;
        return [...cleaned, { code, barcodeType, isPrimary }];
      });
      this.barcodeForm.reset({ code: '', barcodeType, isPrimary: false });
      return;
    }

    const id = this.productId();
    if (id === null || !this.canUpdate()) {
      return;
    }
    this.savingBarcode.set(true);
    this.productsService
      .addBarcode(id, { code, barcodeType, isPrimary }, withoutErrorNotification())
      .subscribe({
        next: () => {
          this.savingBarcode.set(false);
          this.barcodeForm.reset({ code: '', barcodeType, isPrimary: false });
          this.snackBar.open('Código de barras agregado.', 'Cerrar', { duration: 3000 });
          this.loadProduct();
        },
        error: (err) => {
          this.savingBarcode.set(false);
          this.applyFormErrors(this.barcodeForm, err, this.barcodeError);
        },
      });
  }

  protected removePendingBarcode(index: number): void {
    this.pendingBarcodes.update((list) => list.filter((_, i) => i !== index));
  }

  protected markPendingBarcodePrimary(index: number): void {
    this.pendingBarcodes.update((list) =>
      list.map((item, i) => ({ ...item, isPrimary: i === index })),
    );
  }

  protected setPrimaryBarcode(barcode: BarcodeResponse): void {
    const id = this.productId();
    if (id === null || barcode.id === undefined || !this.canUpdate() || this.savingBarcode()) {
      return;
    }
    this.savingBarcode.set(true);
    this.barcodeError.set(null);
    this.productsService.setPrimaryBarcode(id, barcode.id, withoutErrorNotification()).subscribe({
      next: () => {
        this.savingBarcode.set(false);
        this.snackBar.open('Código principal actualizado.', 'Cerrar', { duration: 3000 });
        this.loadProduct();
      },
      error: (err) => {
        this.savingBarcode.set(false);
        this.barcodeError.set(
          isApiError(err) ? err.message : 'No se pudo marcar el código principal.',
        );
      },
    });
  }

  protected deleteBarcode(barcode: BarcodeResponse): void {
    const id = this.productId();
    const bid = barcode.id;
    if (id === null || bid === undefined || !this.canUpdate() || this.savingBarcode()) {
      return;
    }
    this.confirmDialog
      .confirm({
        title: 'Eliminar código de barras',
        message: `¿Eliminar el código "${barcode.code}"?`,
        confirmLabel: 'Eliminar',
        destructive: true,
      })
      .pipe(
        filter(Boolean),
        switchMap(() => {
          this.savingBarcode.set(true);
          this.barcodeError.set(null);
          return this.productsService.deleteBarcode(id, bid, withoutErrorNotification());
        }),
      )
      .subscribe({
        next: () => {
          this.savingBarcode.set(false);
          this.snackBar.open('Código de barras eliminado.', 'Cerrar', { duration: 3000 });
          this.loadProduct();
        },
        error: (err) => {
          this.savingBarcode.set(false);
          this.barcodeError.set(
            isApiError(err) ? err.message : 'No se pudo eliminar el código de barras.',
          );
        },
      });
  }

  /** Guarda la definición de pack en modo edición (`PUT /products/{id}/pack`). */
  protected savePack(): void {
    const id = this.productId();
    if (id === null || !this.canUpdate() || this.savingPack()) {
      return;
    }
    this.packForm.markAllAsTouched();
    if (this.packForm.invalid) {
      return;
    }
    const raw = this.packForm.getRawValue();
    if (raw.baseProductId === null) {
      return;
    }
    this.savingPack.set(true);
    this.packError.set(null);
    this.productsService
      .setPack(
        id,
        {
          baseProductId: raw.baseProductId,
          factor: Math.round(Number(raw.factor ?? 2)),
        },
        withoutErrorNotification(),
      )
      .subscribe({
        next: (pack) => {
          this.savingPack.set(false);
          this.product.update((prev) => (prev ? { ...prev, pack } : prev));
          this.snackBar.open('Configuración de pack guardada.', 'Cerrar', { duration: 4000 });
        },
        error: (err) => {
          this.savingPack.set(false);
          this.applyFormErrors(this.packForm, err, this.packError);
        },
      });
  }

  protected isTaxSelected(taxId: number | undefined): boolean {
    return taxId !== undefined && this.selectedTaxIds().has(taxId);
  }

  protected isTaxDisabled(tax: TaxResponse): boolean {
    if (!this.canManageTaxes()) {
      return true;
    }
    return this.taxesForm.controls.vatExempt.value && tax.taxType === 'VAT';
  }

  protected toggleTax(tax: TaxResponse, checked: boolean): void {
    if (tax.id === undefined || this.isTaxDisabled(tax)) {
      return;
    }
    const taxId = tax.id;
    this.selectedTaxIds.update((prev) => {
      const next = new Set(prev);
      if (checked) {
        next.add(taxId);
      } else {
        next.delete(taxId);
      }
      return next;
    });
  }

  protected onVatExemptChange(vatExempt: boolean): void {
    if (!this.canManageTaxes()) {
      return;
    }
    this.taxesForm.controls.vatExempt.setValue(vatExempt);
    if (vatExempt) {
      const vatIds = new Set(
        this.taxes()
          .filter((t) => t.taxType === 'VAT' && t.id !== undefined)
          .map((t) => t.id!),
      );
      this.selectedTaxIds.update((prev) => {
        const next = new Set(prev);
        for (const id of vatIds) {
          next.delete(id);
        }
        return next;
      });
    } else {
      const hasAnyVat = this.taxes().some(
        (t) => t.taxType === 'VAT' && t.id !== undefined && this.selectedTaxIds().has(t.id),
      );
      if (!hasAnyVat) {
        const defaultVat = this.findDefaultVatTax();
        if (defaultVat?.id !== undefined) {
          const vatId = defaultVat.id;
          this.selectedTaxIds.update((prev) => {
            const next = new Set(prev);
            next.add(vatId);
            return next;
          });
        }
      }
    }
  }

  /** Guarda impuestos del producto en modo edición (`PUT /products/{id}/taxes`). */
  protected saveTaxes(): void {
    const id = this.productId();
    if (id === null || !this.canManageTaxes() || this.savingTaxes()) {
      return;
    }
    const validationErr = this.taxesValidationError();
    if (validationErr) {
      this.taxesError.set(validationErr);
      return;
    }
    this.savingTaxes.set(true);
    this.taxesError.set(null);

    const vatExempt = this.taxesForm.controls.vatExempt.value;
    const taxIds = Array.from(this.selectedTaxIds());

    this.productsService
      .updateTaxes(id, { vatExempt, taxIds }, withoutErrorNotification())
      .subscribe({
        next: (updatedTaxes) => {
          this.savingTaxes.set(false);
          this.snackBar.open('Impuestos actualizados correctamente.', 'Cerrar', { duration: 4000 });
          this.product.update((prev) =>
            prev ? { ...prev, vatExempt, taxes: updatedTaxes } : prev,
          );
          this.loadProduct();
        },
        error: (err) => {
          this.savingTaxes.set(false);
          this.taxesError.set(
            isApiError(err) ? err.message : 'No se pudieron actualizar los impuestos.',
          );
        },
      });
  }

  /** Cambia el precio de venta bruto (CLP) del producto (`PUT /products/{id}/price`). */
  protected savePrice(): void {
    const id = this.productId();
    const current = this.product();
    if (id === null || !current || !this.canUpdatePrice() || this.savingPrice()) {
      return;
    }
    this.priceForm.markAllAsTouched();
    if (this.priceForm.invalid) {
      return;
    }
    const raw = this.priceForm.getRawValue();
    this.savingPrice.set(true);
    this.priceError.set(null);

    this.productsService
      .changePrice(
        id,
        {
          newPriceGross: Math.round(Number(raw.newPriceGross)),
          reason: raw.reason.trim(),
          version: current.version ?? 0,
        },
        withoutErrorNotification(),
      )
      .subscribe({
        next: (updated) => {
          this.savingPrice.set(false);
          this.applyLoadedProduct(updated);
          this.priceForm.controls.reason.reset('');
          this.historyDataSource.reload();
          this.snackBar.open('Precio actualizado correctamente.', 'Cerrar', { duration: 4000 });
        },
        error: (err) => {
          this.savingPrice.set(false);
          this.applyFormErrors(this.priceForm, err, this.priceError);
        },
      });
  }

  /** Ajusta manualmente el costo promedio (`PUT /products/{id}/cost`). */
  protected saveCost(): void {
    const id = this.productId();
    const current = this.product();
    if (id === null || !current || !this.canUpdateCost() || this.savingCost()) {
      return;
    }
    this.costForm.markAllAsTouched();
    if (this.costForm.invalid) {
      return;
    }
    const raw = this.costForm.getRawValue();
    this.savingCost.set(true);
    this.costError.set(null);

    this.productsService
      .changeCost(
        id,
        {
          newAvgCost: Number(raw.newAvgCost),
          reason: raw.reason.trim(),
          version: current.version ?? 0,
        },
        withoutErrorNotification(),
      )
      .subscribe({
        next: (updated) => {
          this.savingCost.set(false);
          this.applyLoadedProduct(updated);
          this.costForm.controls.reason.reset('');
          this.historyDataSource.reload();
          this.snackBar.open('Costo actualizado correctamente.', 'Cerrar', { duration: 4000 });
        },
        error: (err) => {
          this.savingCost.set(false);
          this.applyFormErrors(this.costForm, err, this.costError);
        },
      });
  }

  /** Sube un archivo de imagen y lo asocia al producto (`HU-CAT-13`). */
  protected onImageSelected(event: Event): void {
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0];
    const id = this.productId();
    if (!file || id === null || !this.canUpdate() || this.savingImage()) {
      return;
    }

    if (file.type && !ALLOWED_IMAGE_TYPES.has(file.type)) {
      this.imageError.set('Formato no permitido. Usa PNG, JPEG o WebP.');
      if (input) {
        input.value = '';
      }
      return;
    }
    if (file.size > MAX_PRODUCT_IMAGE_BYTES) {
      this.imageError.set('La imagen supera el tamaño máximo de 5 MB.');
      if (input) {
        input.value = '';
      }
      return;
    }

    this.savingImage.set(true);
    this.imageError.set(null);
    const storeId = this.resolveStoreIdForFiles();

    this.productsService
      .uploadProductImage(file, storeId, withoutErrorNotification())
      .pipe(
        switchMap((uploaded) => {
          if (uploaded.id === undefined) {
            throw new Error('Respuesta sin identificador de archivo.');
          }
          return this.productsService.updateImage(id, uploaded.id, withoutErrorNotification());
        }),
      )
      .subscribe({
        next: (updated) => {
          this.savingImage.set(false);
          this.applyLoadedProduct(updated);
          this.snackBar.open('Imagen del producto actualizada.', 'Cerrar', { duration: 4000 });
          if (input) {
            input.value = '';
          }
        },
        error: (err) => {
          this.savingImage.set(false);
          this.imageError.set(
            isApiError(err) ? err.message : 'No se pudo subir la imagen del producto.',
          );
          if (input) {
            input.value = '';
          }
        },
      });
  }

  /** Desvincula la imagen actual del producto (`DELETE /products/{id}/image`). */
  protected removeImage(): void {
    const id = this.productId();
    if (id === null || !this.canUpdate() || this.savingImage()) {
      return;
    }
    this.confirmDialog
      .confirm({
        title: 'Quitar imagen',
        message: '¿Quitar la imagen asociada a este producto?',
        confirmLabel: 'Quitar imagen',
        destructive: true,
      })
      .pipe(
        filter(Boolean),
        switchMap(() => {
          this.savingImage.set(true);
          this.imageError.set(null);
          return this.productsService.deleteImage(id, withoutErrorNotification());
        }),
      )
      .subscribe({
        next: () => {
          this.savingImage.set(false);
          this.revokeImagePreview();
          this.product.update((prev) => (prev ? { ...prev, imageUrl: undefined } : prev));
          this.snackBar.open('Imagen eliminada.', 'Cerrar', { duration: 3000 });
        },
        error: (err) => {
          this.savingImage.set(false);
          this.imageError.set(isApiError(err) ? err.message : 'No se pudo quitar la imagen.');
        },
      });
  }

  protected toggleProductActive(): void {
    const current = this.product();
    const id = this.productId();
    if (!current || id === null || !this.canDeactivate()) {
      return;
    }
    const isCurrentlyActive = current.active !== false;
    this.confirmDialog
      .confirm({
        title: isCurrentlyActive ? 'Desactivar producto' : 'Activar producto',
        message: isCurrentlyActive
          ? `¿Desactivar "${current.name}" (${current.sku})?`
          : `¿Reactivar "${current.name}" (${current.sku})?`,
        confirmLabel: isCurrentlyActive ? 'Desactivar' : 'Activar',
        destructive: isCurrentlyActive,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          isCurrentlyActive
            ? this.productsService.deactivateProduct(id)
            : this.productsService.activateProduct(id),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(
          isCurrentlyActive ? 'Producto desactivado.' : 'Producto reactivado.',
          'Cerrar',
          { duration: 4000 },
        );
        this.loadProduct();
      });
  }

  protected formatCost(value: number | null | undefined): string {
    return formatUnitCost(value);
  }

  protected formatMargin(value: number | null | undefined): string {
    return formatMarginPct(value);
  }

  private setupFormSubscriptions(): void {
    this.generalForm.controls.saleType.valueChanges.subscribe((saleType) => {
      this.selectedSaleType.set(saleType);
      this.syncSaleTypeDependencies(saleType);
    });

    this.barcodeForm.controls.barcodeType.valueChanges.subscribe(() => {
      this.barcodeForm.controls.code.updateValueAndValidity({ emitEvent: false });
    });
  }

  private syncSaleTypeDependencies(saleType: SaleType): void {
    const targetUom: UnitOfMeasure = saleType === 'WEIGHT' ? 'KG' : 'UNIT';
    if (this.generalForm.controls.unitOfMeasure.value !== targetUom) {
      this.generalForm.controls.unitOfMeasure.setValue(targetUom, { emitEvent: false });
    }
    const lotControl = this.generalForm.controls.lotControlled;
    if (saleType === 'PACK') {
      lotControl.setValue(false, { emitEvent: false });
      lotControl.disable({ emitEvent: false });
    } else if (this.canEditGeneral()) {
      lotControl.enable({ emitEvent: false });
    }
    if (saleType === 'PACK' && this.baseProductOptions().length === 0) {
      this.searchBaseProducts();
    }
  }

  private loadAuxiliaryCatalogs(): void {
    forkJoin({
      categories: this.productsService.listCategories(true, withoutErrorNotification()),
      brands: this.productsService.listBrands(
        { size: 200, sort: ['name,asc'] },
        withoutErrorNotification(),
      ),
      taxes: this.productsService.listTaxes(withoutErrorNotification()),
      scaleConfigs: this.productsService.listScaleConfigs(withoutErrorNotification()),
    }).subscribe({
      next: ({ categories, brands, taxes, scaleConfigs }) => {
        this.categories.set(flattenCategories(categories ?? []));
        this.brands.set(brands.content ?? []);
        this.taxes.set(taxes ?? []);
        this.scaleConfigs.set(scaleConfigs ?? []);

        if (!this.isEdit() && this.selectedTaxIds().size === 0) {
          const defaultVat = this.findDefaultVatTax();
          if (defaultVat?.id !== undefined) {
            this.selectedTaxIds.set(new Set([defaultVat.id]));
          }
        }
      },
      error: () => {
        // Auxiliares toleran fallo parcial sin bloquear la vista.
      },
    });
  }

  private enterCreateMode(): void {
    this.productId.set(null);
    this.product.set(null);
    this.loadError.set(null);
    this.revokeImagePreview();
    this.pendingBarcodes.set([]);
    this.selectedSaleType.set('UNIT');

    this.generalForm.reset({
      sku: '',
      name: '',
      description: '',
      categoryId: null,
      brandId: null,
      saleType: 'UNIT',
      unitOfMeasure: 'UNIT',
      restrictedType: null,
      lotControlled: false,
      shelfLifeDays: null,
      shortcutCode: '',
      allowManualDiscount: true,
      initialPriceGross: 0,
    });
    this.packForm.reset({ baseProductId: null, factor: 2 });
    this.taxesForm.reset({ vatExempt: false });

    const defaultVat = this.findDefaultVatTax();
    this.selectedTaxIds.set(defaultVat?.id !== undefined ? new Set([defaultVat.id]) : new Set());

    if (!this.canCreate()) {
      this.generalForm.disable({ emitEvent: false });
      this.packForm.disable({ emitEvent: false });
    } else {
      this.generalForm.enable({ emitEvent: false });
      if (!this.canUpdatePrice()) {
        this.generalForm.controls.initialPriceGross.disable({ emitEvent: false });
      }
      this.packForm.enable({ emitEvent: false });
    }
  }

  private enterEditMode(id: number): void {
    this.productId.set(id);
    this.pendingBarcodes.set([]);
    this.loadProduct();
  }

  private applyLoadedProduct(prod: ProductResponse): void {
    this.product.set(prod);
    const saleType = prod.saleType ?? 'UNIT';
    this.selectedSaleType.set(saleType);

    this.generalForm.reset(
      {
        sku: prod.sku ?? '',
        name: prod.name ?? '',
        description: prod.description ?? '',
        categoryId: prod.category?.id ?? null,
        brandId: prod.brand?.id ?? null,
        saleType,
        unitOfMeasure: prod.unitOfMeasure ?? (saleType === 'WEIGHT' ? 'KG' : 'UNIT'),
        restrictedType: prod.restrictedType ?? null,
        lotControlled: prod.lotControlled ?? false,
        shelfLifeDays: prod.shelfLifeDays ?? null,
        shortcutCode: prod.shortcutCode ?? '',
        allowManualDiscount: prod.allowManualDiscount ?? true,
        initialPriceGross: prod.priceGross ?? 0,
      },
      { emitEvent: false },
    );

    if (!this.canUpdate()) {
      this.generalForm.disable({ emitEvent: false });
      this.packForm.disable({ emitEvent: false });
      this.barcodeForm.disable({ emitEvent: false });
    } else {
      this.generalForm.enable({ emitEvent: false });
      this.packForm.enable({ emitEvent: false });
      this.barcodeForm.enable({ emitEvent: false });
      this.syncSaleTypeDependencies(saleType);
    }

    if (prod.pack?.baseProductId !== undefined) {
      this.packForm.reset(
        {
          baseProductId: prod.pack.baseProductId,
          factor: prod.pack.factor ?? 2,
        },
        { emitEvent: false },
      );
      const list = [...this.baseProductOptions()];
      this.mergeCurrentPackBaseOption(list, prod);
      this.baseProductOptions.set(list);
    }
    if (saleType === 'PACK' && this.baseProductOptions().length === 0) {
      this.searchBaseProducts();
    }

    this.taxesForm.reset({ vatExempt: prod.vatExempt ?? false }, { emitEvent: false });
    this.selectedTaxIds.set(
      new Set((prod.taxes ?? []).map((t) => t.id).filter((id): id is number => id !== undefined)),
    );

    this.priceForm.controls.newPriceGross.setValue(Math.max(1, prod.priceGross ?? 1), {
      emitEvent: false,
    });
    if (prod.avgCost !== undefined && prod.avgCost !== null) {
      this.costForm.controls.newAvgCost.setValue(prod.avgCost, { emitEvent: false });
    }

    const fileId = extractFileIdFromImageUrl(prod.imageUrl);
    if (fileId !== null) {
      this.loadImagePreview(fileId);
    } else {
      this.revokeImagePreview();
    }
  }

  private mergeCurrentPackBaseOption(
    target: BaseProductOption[],
    sourceProduct: ProductResponse | null = this.product(),
  ): void {
    const pack = sourceProduct?.pack;
    if (pack?.baseProductId === undefined || !pack.baseSku || !pack.baseName) {
      return;
    }
    if (!target.some((opt) => opt.id === pack.baseProductId)) {
      target.unshift({
        id: pack.baseProductId,
        sku: pack.baseSku,
        name: pack.baseName,
      });
    }
  }

  private findDefaultVatTax(): TaxResponse | undefined {
    return this.taxes().find((t) => t.code === 'IVA' && t.active !== false);
  }

  private isScalePluAllowed(): boolean {
    const saleType = this.selectedSaleType();
    if (saleType === 'WEIGHT') {
      return true;
    }
    return saleType === 'UNIT' && this.hasActivePriceScale();
  }

  private validateGeneralCoherence(group: AbstractControl): ValidationErrors | null {
    const saleType = group.get('saleType')?.value as SaleType | undefined;
    const uomControl = group.get('unitOfMeasure');
    const uom = uomControl?.value as UnitOfMeasure | undefined;
    if (!saleType || !uomControl || !uom) {
      return null;
    }
    const isWeight = saleType === 'WEIGHT';
    const isKg = uom === 'KG';
    const existing = { ...(uomControl.errors ?? {}) };
    if (isWeight !== isKg) {
      uomControl.setErrors({ ...existing, uomMismatch: true });
      return { uomMismatch: true };
    }
    if ('uomMismatch' in existing) {
      delete existing['uomMismatch'];
      uomControl.setErrors(Object.keys(existing).length > 0 ? existing : null);
    }
    return null;
  }

  private loadImagePreview(fileId: number): void {
    const storeId = this.resolveStoreIdForFiles();
    this.productsService
      .downloadProductImage(fileId, storeId, withoutErrorNotification())
      .subscribe({
        next: (blob) => {
          this.revokeImagePreview();
          if (typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function') {
            this.imagePreviewUrl.set(URL.createObjectURL(blob));
          }
        },
        error: () => {
          this.revokeImagePreview();
        },
      });
  }

  private revokeImagePreview(): void {
    const current = this.imagePreviewUrl();
    if (current && typeof URL !== 'undefined' && typeof URL.revokeObjectURL === 'function') {
      URL.revokeObjectURL(current);
    }
    this.imagePreviewUrl.set(null);
  }

  private resolveStoreIdForFiles(): number | null {
    return this.storeContext.storeId() ?? this.storeContext.availableStores()[0]?.id ?? null;
  }

  private applyFormErrors(
    form: AbstractControl,
    err: unknown,
    bannerSignal: { set(val: string | null): void },
  ): void {
    if (!isApiError(err)) {
      bannerSignal.set('No se pudo completar la operación.');
      return;
    }
    if (err.hasFieldErrors) {
      let mapped = false;
      for (const fieldErr of err.errors) {
        const control = form.get(fieldErr.field);
        if (control) {
          control.setErrors({ server: fieldErr.message });
          control.markAsTouched();
          mapped = true;
        }
      }
      if (!mapped) {
        bannerSignal.set(err.message);
      }
      return;
    }
    bannerSignal.set(err.message);
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
}
