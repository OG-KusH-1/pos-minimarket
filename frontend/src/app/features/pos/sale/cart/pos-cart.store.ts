import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { CartLineInput } from '../../../../api/models/cart-line-input';
import { CartQuoteResponse } from '../../../../api/models/cart-quote-response';
import { DiscountInput } from '../../../../api/models/discount-input';
import { PosProductResult } from '../../../../api/models/pos-product-result';
import { RequiredOverride } from '../../../../api/models/required-override';
import { ScanResultResponse } from '../../../../api/models/scan-result-response';
import { withoutErrorNotification } from '../../../../core/http/http-context-tokens';
import { generateUuid } from '../../../../core/http/uuid';
import { StoreContextService } from '../../../../core/store-context/store-context.service';
import { PosCartApiService } from './pos-cart-api.service';
import {
  CartLine,
  GrantedOverride,
  POS_CART_STORAGE_KEY,
  PersistedPosCart,
  PosSaleType,
  PosUnitOfMeasure,
  requiredOverrideKey,
} from './pos-cart.models';
import { extractPosErrorMessage } from './pos-http.utils';

interface CartSnapshot {
  readonly lines: readonly CartLine[];
  readonly totalDiscount: DiscountInput | null;
  readonly selectedLineIndex: number | null;
  readonly ageConfirmed: boolean;
  readonly customerId: number | null;
  readonly quote: CartQuoteResponse | null;
  readonly grantedOverrides: readonly GrantedOverride[];
}

/**
 * Estado reactivo del carrito POS con persistencia automática en `localStorage` (ADR-25).
 *
 * Centraliza las líneas del carrito, el descuento global, la confirmación de mayoría de edad
 * (`HU-POS-13`), los tokens de autorización de supervisor ya obtenidos (`HU-POS-06`), la clave
 * de idempotencia de la venta en curso (ADR-14) y la cotización autoritativa contra
 * `POST /api/v1/pos/cart/quote`.
 *
 * Ante cualquier error de validación de negocio devuelto por `/pos/cart/quote` (p. ej. producto
 * que no admite descuento manual o cantidad decimal en producto por unidad), revierte el cambio
 * optimista al último estado válido y propaga el `ApiError` para que la UI lo informe sin dejar
 * el carrito en un estado inconsistente.
 */
@Injectable({ providedIn: 'root' })
export class PosCartStore {
  private readonly cartApi = inject(PosCartApiService);
  private readonly storeContext = inject(StoreContextService);

  private readonly _lines = signal<readonly CartLine[]>([]);
  private readonly _totalDiscount = signal<DiscountInput | null>(null);
  private readonly _selectedLineIndex = signal<number | null>(null);
  private readonly _ageConfirmed = signal(false);
  private readonly _customerId = signal<number | null>(null);
  private readonly _idempotencyKey = signal(generateUuid());
  private readonly _quote = signal<CartQuoteResponse | null>(null);
  private readonly _quoteLoading = signal(false);
  private readonly _quoteError = signal<string | null>(null);
  private readonly _grantedOverrides = signal<readonly GrantedOverride[]>([]);

  private quoteSeq = 0;

  readonly lines = this._lines.asReadonly();
  readonly totalDiscount = this._totalDiscount.asReadonly();
  readonly selectedLineIndex = this._selectedLineIndex.asReadonly();
  readonly ageConfirmed = this._ageConfirmed.asReadonly();
  readonly customerId = this._customerId.asReadonly();
  readonly idempotencyKey = this._idempotencyKey.asReadonly();
  readonly quote = this._quote.asReadonly();
  readonly quoteLoading = this._quoteLoading.asReadonly();
  readonly quoteError = this._quoteError.asReadonly();
  readonly grantedOverrides = this._grantedOverrides.asReadonly();

  readonly isEmpty = computed(() => this._lines().length === 0);
  readonly lineCount = computed(() => this._lines().length);
  readonly totalItemsCount = computed(() =>
    this._lines().reduce((acc, line) => acc + (line.saleType === 'WEIGHT' ? 1 : line.quantity), 0),
  );
  readonly selectedLine = computed(() => {
    const idx = this._selectedLineIndex();
    const currentLines = this._lines();
    if (idx === null || idx < 0 || idx >= currentLines.length) {
      return null;
    }
    return currentLines[idx] ?? null;
  });

  readonly requiresAgeConfirmation = computed(
    () =>
      this._quote()?.requiresAgeConfirmation ??
      this._lines().some((line) => line.restricted === true),
  );

  readonly localSubtotalGross = computed(() =>
    this._lines().reduce(
      (sum, line) => sum + (line.embeddedPrice ?? Math.round(line.unitPriceGross * line.quantity)),
      0,
    ),
  );

  readonly subtotalGross = computed(
    () => this._quote()?.subtotalGross ?? this.localSubtotalGross(),
  );
  readonly discountTotal = computed(() => this._quote()?.discountTotal ?? 0);
  readonly totalGross = computed(() => this._quote()?.totalGross ?? this.subtotalGross());
  readonly netTotal = computed(() => this._quote()?.netTotal ?? 0);
  readonly taxTotal = computed(() => this._quote()?.taxTotal ?? 0);
  readonly exemptTotal = computed(() => this._quote()?.exemptTotal ?? 0);
  readonly taxBreakdown = computed(() => this._quote()?.taxBreakdown ?? []);
  readonly roundingAdjustment = computed(() => this._quote()?.roundingAdjustment ?? 0);
  readonly totalPayable = computed(() => this._quote()?.totalPayable ?? this.totalGross());

  readonly requiredOverrides = computed<readonly RequiredOverride[]>(
    () => this._quote()?.requiredOverrides ?? [],
  );

  /** Overrides exigidos por la cotización actual que aún no cuentan con autorización vigente. */
  readonly pendingOverrides = computed<readonly RequiredOverride[]>(() => {
    const grantedKeys = new Set(this._grantedOverrides().map((item) => item.key));
    return this.requiredOverrides().filter((req) => !grantedKeys.has(requiredOverrideKey(req)));
  });

  /** Valor listo para el encabezado `X-Override-Token` (tokens separados por coma). */
  readonly overrideTokenHeader = computed<string | undefined>(() => {
    const requiredKeys = new Set(this.requiredOverrides().map((req) => requiredOverrideKey(req)));
    const activeTokens = this._grantedOverrides()
      .filter((item) => requiredKeys.has(item.key))
      .map((item) => item.overrideToken);
    return activeTokens.length > 0 ? activeTokens.join(',') : undefined;
  });

  /** `true` cuando el carrito está listo para avanzar a pago (sin bloqueos pendientes). */
  readonly canProceedToPayment = computed(
    () =>
      !this.isEmpty() &&
      !this._quoteLoading() &&
      this._quoteError() === null &&
      (!this.requiresAgeConfirmation() || this._ageConfirmed()) &&
      this.pendingOverrides().length === 0,
  );

  constructor() {
    this.restoreFromStorage();
  }

  /** Recarga el estado persistido desde `localStorage` (útil al iniciar sesión o en pruebas). */
  restoreFromStorage(): void {
    const persisted = readPersistedCart();
    if (!persisted) {
      return;
    }
    this._lines.set(persisted.lines);
    this._totalDiscount.set(persisted.totalDiscount);
    this._ageConfirmed.set(persisted.ageConfirmed);
    this._customerId.set(persisted.customerId);
    this._idempotencyKey.set(persisted.idempotencyKey);
    this._selectedLineIndex.set(persisted.lines.length > 0 ? persisted.lines.length - 1 : null);
    this._quote.set(null);
    this._quoteError.set(null);
    this._grantedOverrides.set([]);
  }

  /** Selecciona una línea del carrito por índice (o `null` para quitar selección). */
  selectLine(index: number | null): void {
    const length = this._lines().length;
    if (index === null || index < 0 || index >= length) {
      this._selectedLineIndex.set(null);
      return;
    }
    this._selectedLineIndex.set(index);
  }

  /** Mueve la selección relativa en el carrito (`-1` arriba, `1` abajo). */
  moveSelection(delta: -1 | 1): void {
    const length = this._lines().length;
    if (length === 0) {
      this._selectedLineIndex.set(null);
      return;
    }
    const current = this._selectedLineIndex();
    if (current === null) {
      this._selectedLineIndex.set(delta > 0 ? 0 : length - 1);
      return;
    }
    const next = Math.max(0, Math.min(length - 1, current + delta));
    this._selectedLineIndex.set(next);
  }

  /**
   * Agrega un ítem resuelto por el lector o entrada directa de código (`HU-POS-01`).
   * Si el mismo producto ya existe sin precio embebido de balanza, incrementa su cantidad.
   */
  async addFromScan(
    scan: ScanResultResponse,
    scannedCode?: string,
  ): Promise<CartQuoteResponse | null> {
    const product = scan.product;
    if (!product?.productId) {
      return null;
    }
    const quantity = normalizeQuantity(scan.quantity ?? 1);
    return this.upsertProduct(product, quantity, scannedCode, scan.embeddedPrice);
  }

  /** Agrega un producto seleccionado desde la búsqueda manual (`HU-POS-02`). */
  async addProduct(product: PosProductResult, quantity = 1): Promise<CartQuoteResponse | null> {
    if (!product.productId) {
      return null;
    }
    return this.upsertProduct(product, normalizeQuantity(quantity), product.primaryBarcode);
  }

  /** Actualiza la cantidad de una línea existente (`HU-POS-03`). */
  async updateQuantity(lineIndex: number, quantity: number): Promise<CartQuoteResponse | null> {
    const current = this._lines();
    const target = current[lineIndex];
    if (!target) {
      return null;
    }
    const normalized = normalizeQuantity(quantity);
    const snapshot = this.takeSnapshot();
    const nextLines = current.map((line, idx) =>
      idx === lineIndex ? { ...line, quantity: normalized, embeddedPrice: undefined } : line,
    );
    this._lines.set(nextLines);
    this._selectedLineIndex.set(lineIndex);
    this.persist();

    return this.requoteWithRollback(snapshot);
  }

  /** Quita una línea del carrito (`HU-POS-03` / `Supr`). */
  async removeLine(lineIndex: number): Promise<CartQuoteResponse | null> {
    const current = this._lines();
    if (lineIndex < 0 || lineIndex >= current.length) {
      return null;
    }
    const nextLines = current.filter((_, idx) => idx !== lineIndex);
    this._lines.set(nextLines);
    if (nextLines.length === 0) {
      this._selectedLineIndex.set(null);
      this._ageConfirmed.set(false);
      this._totalDiscount.set(null);
      this._quote.set(null);
      this._quoteError.set(null);
      this._grantedOverrides.set([]);
      this.persist();
      this.quoteSeq += 1;
      return null;
    }

    if (!nextLines.some((line) => line.restricted)) {
      this._ageConfirmed.set(false);
    }
    this._selectedLineIndex.set(Math.min(lineIndex, nextLines.length - 1));
    this.persist();
    try {
      return await this.requote();
    } catch {
      return null;
    }
  }

  /** Aplica o elimina el descuento manual de una línea (`HU-POS-05`, `HU-POS-06`). */
  async applyLineDiscount(
    lineIndex: number,
    discount: DiscountInput | null,
  ): Promise<CartQuoteResponse | null> {
    const current = this._lines();
    const target = current[lineIndex];
    if (!target) {
      return null;
    }
    const snapshot = this.takeSnapshot();
    const nextLines = current.map((line, idx) => {
      if (idx !== lineIndex) {
        return line;
      }
      if (discount === null) {
        const rest = { ...line };
        delete rest.manualDiscount;
        return rest;
      }
      return { ...line, manualDiscount: discount };
    });
    this._lines.set(nextLines);
    this._selectedLineIndex.set(lineIndex);
    this.persist();

    return this.requoteWithRollback(snapshot);
  }

  /** Aplica o elimina el descuento global sobre el total del carrito (`HU-POS-05`, `HU-POS-06`). */
  async applyTotalDiscount(discount: DiscountInput | null): Promise<CartQuoteResponse | null> {
    const snapshot = this.takeSnapshot();
    this._totalDiscount.set(discount);
    this.persist();
    return this.requoteWithRollback(snapshot);
  }

  /** Registra un token de supervisor otorgado para un `RequiredOverride` del carrito. */
  recordGrantedOverride(required: RequiredOverride, overrideToken: string): void {
    if (!required.action || !required.context) {
      return;
    }
    const key = requiredOverrideKey(required);
    const entry: GrantedOverride = {
      key,
      action: required.action,
      context: required.context,
      overrideToken,
    };
    const filtered = this._grantedOverrides().filter((item) => item.key !== key);
    this._grantedOverrides.set([...filtered, entry]);
  }

  /** Confirma (o revierte) la verificación de mayoría de edad del cliente (`HU-POS-13`, `F7`). */
  confirmAge(confirmed = true): void {
    this._ageConfirmed.set(confirmed);
    this.persist();
  }

  /** Asocia un cliente opcional al carrito (punto de extensión para T77). */
  async setCustomerId(customerId: number | null): Promise<CartQuoteResponse | null> {
    const snapshot = this.takeSnapshot();
    this._customerId.set(customerId);
    this.persist();
    return this.requoteWithRollback(snapshot);
  }

  /** Limpia por completo el carrito y genera una nueva `Idempotency-Key` para la siguiente venta. */
  clear(): void {
    this.quoteSeq += 1;
    this._lines.set([]);
    this._totalDiscount.set(null);
    this._selectedLineIndex.set(null);
    this._ageConfirmed.set(false);
    this._customerId.set(null);
    this._idempotencyKey.set(generateUuid());
    this._quote.set(null);
    this._quoteLoading.set(false);
    this._quoteError.set(null);
    this._grantedOverrides.set([]);
    removePersistedCart();
  }

  /** Convierte las líneas actuales al DTO `CartLineInput[]` esperado por `/pos/cart/quote` y `/sales`. */
  toCartLineInputs(): CartLineInput[] {
    return this._lines().map((line) => ({
      productId: line.productId,
      quantity: line.quantity,
      ...(line.scannedCode ? { scannedCode: line.scannedCode } : {}),
      ...(line.embeddedPrice !== undefined ? { embeddedPrice: line.embeddedPrice } : {}),
      ...(line.manualDiscount ? { manualDiscount: line.manualDiscount } : {}),
    }));
  }

  /**
   * Recalcula la cotización del carrito contra `POST /api/v1/pos/cart/quote`.
   * Si no hay líneas o no hay contexto operativo (`storeId` y `terminalId`), evita llamadas inválidas.
   */
  async requote(): Promise<CartQuoteResponse | null> {
    const currentLines = this._lines();
    if (currentLines.length === 0) {
      this._quote.set(null);
      this._quoteError.set(null);
      this._quoteLoading.set(false);
      return null;
    }
    if (!this.hasOperationalContext()) {
      return null;
    }

    const seq = ++this.quoteSeq;
    this._quoteLoading.set(true);
    this._quoteError.set(null);

    const totalDiscount = this._totalDiscount();
    const customerId = this._customerId();
    try {
      const response = await firstValueFrom(
        this.cartApi.quote(
          {
            lines: this.toCartLineInputs(),
            ...(totalDiscount ? { totalDiscount } : {}),
            ...(customerId !== null ? { customerId } : {}),
          },
          withoutErrorNotification(),
        ),
      );
      if (seq !== this.quoteSeq) {
        return null;
      }
      this._quote.set(response);
      this.pruneStaleOverrides(response.requiredOverrides ?? []);
      this._quoteLoading.set(false);
      return response;
    } catch (error: unknown) {
      if (seq === this.quoteSeq) {
        this._quoteLoading.set(false);
        this._quoteError.set(extractPosErrorMessage(error, 'No se pudo cotizar el carrito.'));
      }
      throw error;
    }
  }

  private async upsertProduct(
    product: PosProductResult,
    quantity: number,
    scannedCode?: string,
    embeddedPrice?: number,
  ): Promise<CartQuoteResponse | null> {
    const productId = product.productId;
    if (productId === undefined) {
      return null;
    }
    const currentLines = this._lines();
    const snapshot = this.takeSnapshot();

    const mergeIndex =
      embeddedPrice === undefined
        ? currentLines.findIndex(
            (line) => line.productId === productId && line.embeddedPrice === undefined,
          )
        : -1;

    let nextLines: CartLine[];
    let nextSelected: number;

    const existing = mergeIndex >= 0 ? currentLines[mergeIndex] : undefined;
    if (existing) {
      const summed = normalizeQuantity(existing.quantity + quantity);
      nextLines = currentLines.map((line, idx) =>
        idx === mergeIndex
          ? {
              ...line,
              quantity: summed,
              stockOnHand: product.stockOnHand ?? line.stockOnHand,
            }
          : line,
      );
      nextSelected = mergeIndex;
    } else {
      const newLine: CartLine = {
        lineId: generateUuid(),
        productId,
        sku: product.sku ?? '',
        name: product.name ?? `Producto #${productId}`,
        saleType: (product.saleType ?? 'UNIT') as PosSaleType,
        unitOfMeasure: (product.unitOfMeasure ?? 'UNIT') as PosUnitOfMeasure,
        unitPriceGross: product.priceGross ?? 0,
        quantity,
        ...(scannedCode ? { scannedCode } : {}),
        ...(embeddedPrice !== undefined ? { embeddedPrice } : {}),
        restricted: Boolean(product.restricted),
        ...(product.stockOnHand !== undefined ? { stockOnHand: product.stockOnHand } : {}),
        ...(product.packFactor !== undefined ? { packFactor: product.packFactor } : {}),
      };
      nextLines = [...currentLines, newLine];
      nextSelected = nextLines.length - 1;
    }

    this._lines.set(nextLines);
    this._selectedLineIndex.set(nextSelected);
    this.persist();

    return this.requoteWithRollback(snapshot);
  }

  private async requoteWithRollback(snapshot: CartSnapshot): Promise<CartQuoteResponse | null> {
    if (!this.hasOperationalContext()) {
      return null;
    }
    try {
      return await this.requote();
    } catch (error: unknown) {
      const message =
        this._quoteError() ?? extractPosErrorMessage(error, 'No se pudo cotizar el carrito.');
      this.restoreSnapshot(snapshot);
      this._quoteError.set(message);
      this.persist();
      return null;
    }
  }

  private hasOperationalContext(): boolean {
    return this.storeContext.storeId() !== null && this.storeContext.terminalId() !== null;
  }

  private pruneStaleOverrides(required: readonly RequiredOverride[]): void {
    const validKeys = new Set(required.map((item) => requiredOverrideKey(item)));
    const remaining = this._grantedOverrides().filter((item) => validKeys.has(item.key));
    if (remaining.length !== this._grantedOverrides().length) {
      this._grantedOverrides.set(remaining);
    }
  }

  private takeSnapshot(): CartSnapshot {
    return {
      lines: this._lines(),
      totalDiscount: this._totalDiscount(),
      selectedLineIndex: this._selectedLineIndex(),
      ageConfirmed: this._ageConfirmed(),
      customerId: this._customerId(),
      quote: this._quote(),
      grantedOverrides: this._grantedOverrides(),
    };
  }

  private restoreSnapshot(snapshot: CartSnapshot): void {
    this._lines.set(snapshot.lines);
    this._totalDiscount.set(snapshot.totalDiscount);
    this._selectedLineIndex.set(snapshot.selectedLineIndex);
    this._ageConfirmed.set(snapshot.ageConfirmed);
    this._customerId.set(snapshot.customerId);
    this._quote.set(snapshot.quote);
    this._grantedOverrides.set(snapshot.grantedOverrides);
    this._quoteLoading.set(false);
    this._quoteError.set(null);
  }

  private persist(): void {
    const lines = this._lines();
    if (lines.length === 0 && this._totalDiscount() === null) {
      removePersistedCart();
      return;
    }
    const payload: PersistedPosCart = {
      version: 1,
      idempotencyKey: this._idempotencyKey(),
      lines,
      totalDiscount: this._totalDiscount(),
      ageConfirmed: this._ageConfirmed(),
      customerId: this._customerId(),
    };
    writePersistedCart(payload);
  }
}

function normalizeQuantity(value: number): number {
  return Math.round(value * 1000) / 1000;
}

function readPersistedCart(): PersistedPosCart | null {
  try {
    const raw = globalThis.localStorage?.getItem(POS_CART_STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    if (!isRecord(parsed) || parsed['version'] !== 1 || !Array.isArray(parsed['lines'])) {
      return null;
    }
    const lines = parsed['lines'].filter(isValidCartLine);
    const idempotencyKey =
      typeof parsed['idempotencyKey'] === 'string' && parsed['idempotencyKey'] !== ''
        ? parsed['idempotencyKey']
        : generateUuid();
    const totalDiscount = isValidDiscountInput(parsed['totalDiscount'])
      ? parsed['totalDiscount']
      : null;
    const ageConfirmed = parsed['ageConfirmed'] === true;
    const customerId =
      typeof parsed['customerId'] === 'number' && Number.isSafeInteger(parsed['customerId'])
        ? parsed['customerId']
        : null;
    return {
      version: 1,
      idempotencyKey,
      lines,
      totalDiscount,
      ageConfirmed,
      customerId,
    };
  } catch {
    return null;
  }
}

function writePersistedCart(payload: PersistedPosCart): void {
  try {
    globalThis.localStorage?.setItem(POS_CART_STORAGE_KEY, JSON.stringify(payload));
  } catch {
    // Almacenamiento lleno o bloqueado: el carrito sigue operando en memoria.
  }
}

function removePersistedCart(): void {
  try {
    globalThis.localStorage?.removeItem(POS_CART_STORAGE_KEY);
  } catch {
    // Ignorado cuando localStorage no está disponible.
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isValidDiscountInput(value: unknown): value is DiscountInput {
  if (!isRecord(value)) {
    return false;
  }
  const kindValid = value['kind'] === 'PERCENT' || value['kind'] === 'AMOUNT';
  return (
    kindValid &&
    typeof value['value'] === 'number' &&
    Number.isFinite(value['value']) &&
    value['value'] > 0
  );
}

function isValidCartLine(value: unknown): value is CartLine {
  if (!isRecord(value)) {
    return false;
  }
  return (
    typeof value['lineId'] === 'string' &&
    typeof value['productId'] === 'number' &&
    Number.isFinite(value['productId']) &&
    typeof value['name'] === 'string' &&
    typeof value['quantity'] === 'number' &&
    value['quantity'] > 0 &&
    typeof value['unitPriceGross'] === 'number'
  );
}
