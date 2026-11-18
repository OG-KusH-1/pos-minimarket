import { ComponentType } from '@angular/cdk/portal';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize, firstValueFrom } from 'rxjs';
import { CashSessionResponse } from '../../../api/models/cash-session-response';
import { DiscountInput } from '../../../api/models/discount-input';
import { Line } from '../../../api/models/line';
import { PosProductResult } from '../../../api/models/pos-product-result';
import { RequiredOverride } from '../../../api/models/required-override';
import { ScanResultResponse } from '../../../api/models/scan-result-response';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ClpPipe, QtyPipe } from '../../../shared/pipes';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { CartLine, requiredOverrideKey } from './cart/pos-cart.models';
import { PosCartApiService } from './cart/pos-cart-api.service';
import { PosCartStore } from './cart/pos-cart.store';
import {
  DiscountScope,
  PosDiscountDialog,
  PosDiscountDialogData,
  PosDiscountDialogResult,
} from './cart/pos-discount-dialog';
import { PosOverrideService } from './cart/pos-override.service';
import { PosQuantityDialog, PosQuantityDialogData } from './cart/pos-quantity-dialog';
import { PosKeyAction } from './keyboard/pos-keyboard.models';
import { PosKeyboardService } from './keyboard/pos-keyboard.service';
import { PosShortcutsDialog } from './keyboard/pos-shortcuts-dialog';
import { SaleResponse } from '../../../api/models/sale-response';
import { SuspendedSaleResponse } from '../../../api/models/suspended-sale-response';
import { PosPaymentDialogData, PosPaymentDialogResult } from './payment/payment.models';
import { PosPaymentDialog } from './payment/pos-payment-dialog';
import { BarcodeScannerDetector } from './search/barcode-scanner-detector';
import { PosSearchBar } from './search/pos-search-bar';
import { PosSuspendDialog, PosSuspendDialogData } from './suspended/pos-suspend-dialog';
import {
  PosSuspendedListDialog,
  PosSuspendedListDialogData,
} from './suspended/pos-suspended-list-dialog';
import { SuspendedSaleService } from './suspended/suspended-sale.service';
import { PosTicketDialog, PosTicketDialogData } from './ticket/pos-ticket-dialog';

/** Mensaje breve de estado operativo mostrado al cajero en la barra superior del POS. */
interface PosStatusNotice {
  readonly severity: 'info' | 'warning' | 'error';
  readonly message: string;
}

/**
 * Pantalla principal del punto de venta (Parte 1: carrito, escáner, cotización, descuentos y
 * confirmación de edad).
 *
 * Orquesta:
 * - {@link BarcodeScannerDetector} para distinguir lecturas HID rápidas (`< 30 ms`) de teclado.
 * - {@link PosKeyboardService} para los atajos `HU-POS-20` (`F1`–`F12`, `Esc`, `Supr`).
 * - {@link PosCartStore} para persistencia en `localStorage` (`ADR-25`) y cotización server-side.
 * - {@link PosOverrideService} para autorizaciones de supervisor (`L-05`).
 *
 * Los flujos de cobro, ventas suspendidas e impresión de boleta (`F8`–`F12`) se completan en la
 * Parte 2 (`T47`) sobre este contenedor sin romper sus señales ni contratos.
 */
@Component({
  selector: 'pos-sale',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatDividerModule,
    MatIconModule,
    MatTooltipModule,
    PosSearchBar,
    QtyPipe,
  ],
  templateUrl: './sale.html',
  styleUrl: './sale.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sale implements OnInit {
  protected readonly cart = inject(PosCartStore);
  protected readonly keyboard = inject(PosKeyboardService);
  protected readonly storeContext = inject(StoreContextService);
  protected readonly auth = inject(AuthService);

  private readonly cartApi = inject(PosCartApiService);
  private readonly overrideService = inject(PosOverrideService);
  private readonly suspendedSaleService = inject(SuspendedSaleService);
  private readonly scanner = inject(BarcodeScannerDetector);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly searchBar = viewChild(PosSearchBar);

  /** Sesión de caja abierta en la terminal actual (o `null` si no existe / no se pudo consultar). */
  protected readonly cashSession = signal<CashSessionResponse | null>(null);
  protected readonly cashSessionLoading = signal(false);
  protected readonly cashSessionChecked = signal(false);

  /** Última venta registrada en esta sesión para consulta o reimpresión rápida de ticket. */
  protected readonly lastCompletedSale = signal<SaleResponse | null>(null);

  /** Aviso transitorio de operación (éxito, advertencia o cancelación). */
  protected readonly statusNotice = signal<PosStatusNotice | null>(null);

  /** Indica si existe local y terminal seleccionados para operar en el POS. */
  protected readonly hasStoreAndTerminal = computed(
    () => this.storeContext.storeId() !== null && this.storeContext.terminalId() !== null,
  );

  /** Lista de overrides pendientes requeridos por la cotización vigente del servidor. */
  protected readonly pendingNonAgeOverrides = computed(() => this.cart.pendingOverrides());

  /** Permiso operativo de descuento manual del cajero actual. */
  protected readonly canDiscount = computed(() => this.auth.hasPermission('sales:discount:apply'));

  private initialized = false;
  private lastContextKey = '';

  constructor() {
    effect(() => {
      const storeId = this.storeContext.storeId();
      const terminalId = this.storeContext.terminalId();
      const nextKey = `${storeId ?? 'none'}:${terminalId ?? 'none'}`;
      if (!this.initialized || nextKey === this.lastContextKey) {
        return;
      }
      this.lastContextKey = nextKey;
      void this.refreshContextData();
    });
  }

  ngOnInit(): void {
    this.cart.restoreFromStorage();
    this.lastContextKey = `${this.storeContext.storeId() ?? 'none'}:${this.storeContext.terminalId() ?? 'none'}`;
    this.initialized = true;
    const captureHandler = (event: KeyboardEvent) => this.handleGlobalKeydown(event);
    document.addEventListener('keydown', captureHandler, { capture: true });
    this.destroyRef.onDestroy(() =>
      document.removeEventListener('keydown', captureHandler, { capture: true }),
    );
    void this.refreshContextData();
    this.focusScanInputSoon();
  }

  /**
   * Captura pulsaciones globales del POS en fase de captura:
   * 1. Ráfagas de lector HID (`< 30 ms` terminadas en `Enter`) antes que el input de búsqueda.
   * 2. Mapa de atajos `HU-POS-20` cuando no hay modales abiertos ni el teclado está suspendido.
   */
  private handleGlobalKeydown(event: KeyboardEvent): void {
    if (event.defaultPrevented || this.keyboard.suspended() || this.dialog.openDialogs.length > 0) {
      return;
    }

    const scannedCode = this.scanner.feed(event);
    if (scannedCode !== null) {
      event.preventDefault();
      event.stopPropagation();
      if (!this.hasStoreAndTerminal()) {
        this.setNotice('warning', 'Selecciona un local y una terminal antes de escanear.');
        return;
      }
      void this.resolveScannedBurst(scannedCode);
      return;
    }

    const action = this.keyboard.handleKeydown(event, {
      scanInputFocusedAndEmpty: this.searchBar()?.isScanInputFocusedAndEmpty() ?? false,
    });
    if (action !== null) {
      this.executeKeyAction(action);
    }
  }

  /** Resuelve un código leído por ráfaga del lector usando la barra de escaneo. */
  private async resolveScannedBurst(code: string): Promise<void> {
    const bar = this.searchBar();
    if (!bar) {
      return;
    }
    await bar.resolveCode(code);
  }

  /** Procesa un producto resuelto vía `GET /pos/products/resolve`. */
  protected async onScanResolved(payload: {
    readonly result: ScanResultResponse;
    readonly code: string;
  }): Promise<void> {
    const quote = await this.cart.addFromScan(payload.result, payload.code);
    if (quote) {
      const name = payload.result.product?.name ?? payload.result.product?.sku ?? 'Producto';
      this.setNotice('info', `Agregado: ${name}.`);
    } else if (this.cart.quoteError()) {
      this.setNotice('error', this.cart.quoteError()!);
    }
    this.focusScanInputSoon();
  }

  /** Procesa la selección manual de un producto desde la lista de búsqueda incremental. */
  protected async onProductSelected(product: PosProductResult): Promise<void> {
    if (!product.productId) {
      return;
    }

    let quantity = 1;
    if (product.saleType === 'WEIGHT') {
      const enteredWeight = await this.promptQuantity({
        productName: product.name ?? product.sku ?? 'Producto pesable',
        sku: product.sku ?? '',
        saleType: 'WEIGHT',
        unitOfMeasure: 'KG',
        currentQuantity: 1,
        stockOnHand: product.stockOnHand,
      });
      if (enteredWeight === undefined) {
        this.focusScanInputSoon();
        return;
      }
      quantity = enteredWeight;
    }

    const quote = await this.cart.addProduct(product, quantity);
    if (quote) {
      this.setNotice('info', `Agregado: ${product.name ?? product.sku}.`);
    } else if (this.cart.quoteError()) {
      this.setNotice('error', this.cart.quoteError()!);
    }
    this.focusScanInputSoon();
  }

  /** Selecciona una línea del carrito para acciones `F4`, `F6` y `Supr`. */
  protected selectLine(index: number): void {
    this.cart.selectLine(index);
  }

  /** Abre el editor de cantidad (`F4`) para la línea indicada o la actualmente seleccionada. */
  protected async editLineQuantity(lineIndex?: number): Promise<void> {
    const targetIndex = lineIndex ?? this.cart.selectedLineIndex();
    const lines = this.cart.lines();
    const line = targetIndex !== null ? lines[targetIndex] : undefined;
    if (targetIndex === null || !line) {
      this.setNotice('warning', 'Selecciona una línea del carrito para editar su cantidad.');
      this.focusScanInputSoon();
      return;
    }

    this.cart.selectLine(targetIndex);
    const nextQty = await this.promptQuantity({
      productName: line.name,
      sku: line.sku,
      saleType: line.saleType,
      unitOfMeasure: line.unitOfMeasure,
      currentQuantity: line.quantity,
      stockOnHand: line.stockOnHand,
    });
    if (nextQty === undefined) {
      this.focusScanInputSoon();
      return;
    }

    const quote = await this.cart.updateQuantity(targetIndex, nextQty);
    if (quote) {
      this.setNotice('info', `Cantidad actualizada para ${line.name}.`);
    } else if (this.cart.quoteError()) {
      this.setNotice('error', this.cart.quoteError()!);
    }
    this.focusScanInputSoon();
  }

  /** Quita la línea indicada o la actualmente seleccionada (`Supr`). */
  protected async removeSelectedOrLine(lineIndex?: number): Promise<void> {
    const targetIndex = lineIndex ?? this.cart.selectedLineIndex();
    const line = targetIndex !== null ? this.cart.lines()[targetIndex] : undefined;
    if (targetIndex === null || !line) {
      this.setNotice('warning', 'No hay ninguna línea seleccionada para quitar.');
      this.focusScanInputSoon();
      return;
    }

    await this.cart.removeLine(targetIndex);
    this.setNotice('info', `Se quitó "${line.name}" del carrito.`);
    this.focusScanInputSoon();
  }

  /** Abre el diálogo de descuento manual (`F6`) y gestiona override de supervisor si excede límites. */
  protected async openDiscountDialog(forcedScope?: DiscountScope): Promise<void> {
    if (this.cart.isEmpty()) {
      this.setNotice('warning', 'Agrega productos antes de aplicar un descuento.');
      this.focusScanInputSoon();
      return;
    }

    const selectedIndex = this.cart.selectedLineIndex();
    const selectedLine = selectedIndex !== null ? (this.cart.lines()[selectedIndex] ?? null) : null;
    const allowLineScope = selectedLine !== null;
    const initialScope: DiscountScope =
      forcedScope === 'LINE' && allowLineScope
        ? 'LINE'
        : forcedScope === 'TOTAL' || !allowLineScope
          ? 'TOTAL'
          : 'LINE';

    const subtotal =
      this.cart.subtotalGross() > 0 ? this.cart.subtotalGross() : this.cart.localSubtotalGross();
    const dialogData: PosDiscountDialogData = {
      initialScope,
      allowLineScope,
      lineLabel: selectedLine ? `${selectedLine.name} (${selectedLine.sku})` : undefined,
      lineMaxAmount: selectedLine
        ? Math.round(selectedLine.unitPriceGross * selectedLine.quantity)
        : undefined,
      totalMaxAmount: Math.max(0, Math.round(subtotal)),
      currentLineDiscount: selectedLine?.manualDiscount ?? null,
      currentTotalDiscount: this.cart.totalDiscount(),
    };

    const result = await this.openSuspendedDialog<
      PosDiscountDialog,
      PosDiscountDialogData,
      PosDiscountDialogResult
    >(PosDiscountDialog, dialogData, '460px');
    if (!result) {
      this.focusScanInputSoon();
      return;
    }

    if (result.scope === 'LINE' && selectedIndex !== null && selectedLine) {
      await this.applyLineDiscountWithOverride(selectedIndex, selectedLine, result.discount);
    } else {
      await this.applyTotalDiscountWithOverride(result.discount);
    }
    this.focusScanInputSoon();
  }

  /** Confirma o revoca la verificación de mayoría de edad (+18) para productos restringidos (`F7`). */
  protected toggleAgeConfirmation(): void {
    if (!this.cart.requiresAgeConfirmation()) {
      this.setNotice('info', 'El carrito actual no contiene productos con restricción +18.');
      this.focusScanInputSoon();
      return;
    }

    const nextConfirmed = !this.cart.ageConfirmed();
    this.cart.confirmAge(nextConfirmed);
    this.setNotice(
      nextConfirmed ? 'info' : 'warning',
      nextConfirmed
        ? 'Mayoría de edad (+18) confirmada para esta venta.'
        : 'Confirmación de edad (+18) desmarcada.',
    );
    this.focusScanInputSoon();
  }

  /** Solicita autorización de supervisor para un override pendiente específico del carrito. */
  protected async authorizePendingOverride(required: RequiredOverride): Promise<void> {
    this.keyboard.setSuspended(true);
    try {
      const token = await firstValueFrom(this.overrideService.authorizeRequiredOverride(required));
      if (token) {
        this.cart.recordGrantedOverride(required, token);
        this.setNotice('info', 'Autorización de supervisor registrada para la venta.');
      } else {
        this.setNotice('warning', 'Autorización de supervisor cancelada.');
      }
    } catch (error: unknown) {
      this.setNotice('error', error instanceof Error ? error.message : 'No fue posible autorizar.');
    } finally {
      this.keyboard.setSuspended(false);
      this.focusScanInputSoon();
    }
  }

  /** Solicita confirmación antes de vaciar el carrito actual (`Esc`). */
  protected async confirmAndClearCart(): Promise<void> {
    if (this.cart.isEmpty()) {
      this.searchBar()?.clearAndFocus();
      return;
    }

    this.keyboard.setSuspended(true);
    try {
      const confirmed = await firstValueFrom(
        this.confirmDialog.confirm({
          title: 'Vaciar venta en curso',
          message: `Se quitarán ${this.cart.lineCount()} línea(s) del carrito actual. ¿Deseas continuar?`,
          confirmLabel: 'Vaciar carrito',
          cancelLabel: 'Seguir editando',
          destructive: true,
        }),
      );
      if (confirmed) {
        this.cart.clear();
        this.setNotice('info', 'Carrito limpiado. Listo para una nueva venta.');
      }
    } finally {
      this.keyboard.setSuspended(false);
      this.focusScanInputSoon();
    }
  }

  /** Abre el modal de ayuda con el mapa de atajos configurados (`F1`). */
  protected async openShortcutsDialog(): Promise<void> {
    await this.openSuspendedDialog(PosShortcutsDialog, undefined, '520px');
    this.focusScanInputSoon();
  }

  /** Recarga la sesión de caja actual de la terminal seleccionada. */
  protected async loadCashSession(): Promise<void> {
    if (!this.hasStoreAndTerminal()) {
      this.cashSession.set(null);
      this.cashSessionChecked.set(false);
      return;
    }

    this.cashSessionLoading.set(true);
    try {
      const session = await firstValueFrom(
        this.cartApi.getCurrentCashSession(withoutErrorNotification()),
      );
      this.cashSession.set(session);
    } catch {
      this.cashSession.set(null);
    } finally {
      this.cashSessionLoading.set(false);
      this.cashSessionChecked.set(true);
    }
  }

  /** Retorna la línea cotizada por el backend correspondiente al índice dado, si existe. */
  protected quotedLine(index: number): Line | undefined {
    return this.cart.quote()?.lines?.[index];
  }

  /** Descuento total en pesos de la línea según la cotización vigente del servidor. */
  protected lineDiscountDisplay(index: number): number {
    const quoted = this.quotedLine(index);
    if (!quoted) {
      return 0;
    }
    const total =
      (quoted.promotionDiscount ?? 0) +
      (quoted.manualDiscount ?? 0) +
      (quoted.proratedDiscount ?? 0);
    return Math.max(0, Math.round(total));
  }

  /** Total bruto a mostrar en la fila: usa la cotización oficial o el cálculo local provisional. */
  protected lineDisplayTotal(index: number, line: CartLine): number {
    const quoted = this.quotedLine(index);
    if (quoted?.lineTotalGross !== undefined) {
      return quoted.lineTotalGross;
    }
    return Math.round(line.unitPriceGross * line.quantity);
  }

  /** Etiqueta legible de una línea para describir descuentos y overrides en pantalla. */
  protected formatDiscountBadge(discount: DiscountInput | undefined): string {
    if (!discount) {
      return '';
    }
    if (discount.kind === 'PERCENT') {
      return `-${discount.value}%`;
    }
    return `-$${Math.round(discount.value).toLocaleString('es-CL')}`;
  }

  /** Descripción humana de un override requerido pendiente. */
  protected describeOverride(required: RequiredOverride): string {
    switch (required.action) {
      case 'DISCOUNT_ITEM_OVER_LIMIT':
        return 'Descuento de línea excede tu límite permitido';
      case 'DISCOUNT_TOTAL_OVER_LIMIT':
        return 'Descuento global excede tu límite permitido';
      default:
        return `Requiere autorización (${required.action ?? 'SUPERVISOR'})`;
    }
  }

  /** Clave estable para `@for` de overrides pendientes. */
  protected overrideTrackKey(required: RequiredOverride): string {
    return requiredOverrideKey(required);
  }

  /** Abre el modal de cobro simple/mixto (`F10` / `F12`) y registra la venta al confirmar. */
  protected openPaymentDialog(): void {
    if (this.dialog.openDialogs.length > 0) {
      return;
    }
    if (this.cart.isEmpty()) {
      this.setNotice('warning', 'Agrega productos al carrito antes de cobrar.');
      this.focusScanInputSoon();
      return;
    }
    if (!this.hasStoreAndTerminal()) {
      this.setNotice('warning', 'Selecciona un local y terminal antes de cobrar.');
      return;
    }
    if (!this.cashSession()) {
      this.setNotice('warning', 'Debes abrir un turno de caja antes de cobrar.');
      this.focusScanInputSoon();
      return;
    }
    if (this.cart.requiresAgeConfirmation() && !this.cart.ageConfirmed()) {
      this.setNotice('warning', 'Confirma mayoría de edad (+18 con F7) antes de cobrar.');
      this.focusScanInputSoon();
      return;
    }
    if (!this.cart.canProceedToPayment()) {
      this.setNotice('warning', 'Revisa las alertas del carrito antes de cobrar.');
      this.focusScanInputSoon();
      return;
    }

    const initialOverrideTokens = (this.cart.overrideTokenHeader() ?? '')
      .split(',')
      .map((token) => token.trim())
      .filter((token) => token.length > 0);

    const data: PosPaymentDialogData = {
      cartLines: this.cart.toCartLineInputs(),
      totalDiscount: this.cart.totalDiscount(),
      customerId: this.cart.customerId(),
      ageConfirmed: this.cart.ageConfirmed(),
      totalGross: this.cart.totalGross(),
      idempotencyKey: this.cart.idempotencyKey(),
      initialOverrideTokens,
    };

    this.dialog
      .open<PosPaymentDialog, PosPaymentDialogData, PosPaymentDialogResult | undefined>(
        PosPaymentDialog,
        {
          data,
          width: '620px',
          maxWidth: '96vw',
          disableClose: true,
        },
      )
      .afterClosed()
      .subscribe((result) => {
        if (!result?.sale) {
          this.focusScanInputSoon();
          return;
        }

        const sale = result.sale;
        this.lastCompletedSale.set(sale);
        this.cart.clear();
        this.setNotice(
          'info',
          `Venta ${sale.number ?? '#' + (sale.id ?? '')} registrada correctamente.`,
        );

        if (sale.id !== undefined) {
          this.openTicketDialog(sale.id, sale.number, true);
        } else {
          this.focusScanInputSoon();
        }
      });
  }

  /** Abre el diálogo para suspender el carrito en curso (`F8`). */
  protected openSuspendDialog(): void {
    if (this.dialog.openDialogs.length > 0) {
      return;
    }
    if (this.cart.isEmpty()) {
      this.setNotice('warning', 'Agrega productos antes de suspender la venta.');
      this.focusScanInputSoon();
      return;
    }
    if (!this.hasStoreAndTerminal()) {
      this.setNotice('warning', 'Selecciona un local y terminal antes de suspender.');
      return;
    }

    const data: PosSuspendDialogData = {
      lines: this.cart.toCartLineInputs(),
      totalDiscount: this.cart.totalDiscount(),
      customerId: this.cart.customerId(),
      lineCount: this.cart.lineCount(),
      estimatedTotal: this.cart.totalPayable(),
    };

    this.dialog
      .open<PosSuspendDialog, PosSuspendDialogData, SuspendedSaleResponse | undefined>(
        PosSuspendDialog,
        {
          data,
          width: '460px',
          maxWidth: '96vw',
        },
      )
      .afterClosed()
      .subscribe((suspended) => {
        if (suspended) {
          this.cart.clear();
          const labelSuffix = suspended.label ? ` («${suspended.label}»)` : '';
          this.setNotice('info', `Venta suspendida en espera${labelSuffix}.`);
        }
        this.focusScanInputSoon();
      });
  }

  /** Abre la lista de ventas suspendidas de la terminal para retomar o eliminar (`F9`). */
  protected openResumeSuspendedDialog(): void {
    if (this.dialog.openDialogs.length > 0) {
      return;
    }
    if (!this.hasStoreAndTerminal()) {
      this.setNotice(
        'warning',
        'Selecciona un local y terminal para consultar ventas suspendidas.',
      );
      return;
    }

    const data: PosSuspendedListDialogData = {
      hasActiveCart: !this.cart.isEmpty(),
    };

    this.dialog
      .open<PosSuspendedListDialog, PosSuspendedListDialogData, SuspendedSaleResponse | undefined>(
        PosSuspendedListDialog,
        {
          data,
          width: '560px',
          maxWidth: '96vw',
        },
      )
      .afterClosed()
      .subscribe((resumed) => {
        if (!resumed) {
          this.focusScanInputSoon();
          return;
        }

        void this.restoreSuspendedSale(resumed);
      });
  }

  /** Abre la vista previa e impresión/reimpresión del ticket térmico 80 mm. */
  protected openTicketDialog(saleId: number, saleNumber?: string, autoPrint = false): void {
    const data: PosTicketDialogData = { saleId, saleNumber, autoPrint };
    this.dialog
      .open<PosTicketDialog, PosTicketDialogData, void>(PosTicketDialog, {
        data,
        width: '460px',
        maxWidth: '96vw',
      })
      .afterClosed()
      .subscribe(() => {
        this.focusScanInputSoon();
      });
  }

  /** Gestiona el atajo `F12`: abre cobro si hay carrito o reabre el último ticket si está vacío. */
  protected handleFinalizeShortcut(): void {
    if (this.dialog.openDialogs.length > 0) {
      return;
    }
    if (!this.cart.isEmpty()) {
      this.openPaymentDialog();
      return;
    }
    const lastSale = this.lastCompletedSale();
    if (lastSale?.id !== undefined) {
      this.openTicketDialog(lastSale.id, lastSale.number, true);
      return;
    }
    this.setNotice('warning', 'No hay productos en el carrito para finalizar.');
    this.focusScanInputSoon();
  }

  private async restoreSuspendedSale(suspended: SuspendedSaleResponse): Promise<void> {
    try {
      await this.suspendedSaleService.restoreIntoCartStore(suspended, this.cart);
      const labelSuffix = suspended.label ? ` («${suspended.label}»)` : '';
      this.setNotice('info', `Venta suspendida${labelSuffix} retomada en el carrito.`);
    } catch {
      this.setNotice(
        'warning',
        'La venta suspendida se retomó, pero no fue posible actualizar su cotización.',
      );
    } finally {
      this.focusScanInputSoon();
    }
  }

  private async refreshContextData(): Promise<void> {
    if (!this.hasStoreAndTerminal()) {
      this.cashSession.set(null);
      this.cashSessionChecked.set(false);
      return;
    }
    await this.loadCashSession();
    if (!this.cart.isEmpty() && this.cart.quote() === null) {
      try {
        await this.cart.requote();
      } catch {
        // quoteError ya queda registrado en PosCartStore
      }
    }
  }

  private executeKeyAction(action: PosKeyAction): void {
    switch (action) {
      case 'help':
        void this.openShortcutsDialog();
        return;
      case 'search':
        this.focusScanInputSoon();
        return;
      case 'editQuantity':
        void this.editLineQuantity();
        return;
      case 'discount':
        void this.openDiscountDialog();
        return;
      case 'confirmAge':
        this.toggleAgeConfirmation();
        return;
      case 'suspend':
        this.openSuspendDialog();
        return;
      case 'resume':
        this.openResumeSuspendedDialog();
        return;
      case 'pay':
        this.openPaymentDialog();
        return;
      case 'finalize':
        this.handleFinalizeShortcut();
        return;
      case 'cancel':
        if (this.searchBar() && !this.searchBar()!.isScanInputFocusedAndEmpty()) {
          this.searchBar()!.clearAndFocus();
          return;
        }
        void this.confirmAndClearCart();
        return;
      case 'removeLine':
        void this.removeSelectedOrLine();
        return;
    }
  }

  private async applyLineDiscountWithOverride(
    lineIndex: number,
    previousLine: CartLine,
    nextDiscount: DiscountInput | null,
  ): Promise<void> {
    const previousDiscount = previousLine.manualDiscount ?? null;
    const quote = await this.cart.applyLineDiscount(lineIndex, nextDiscount);
    if (!quote) {
      this.setNotice('error', this.cart.quoteError() ?? 'No se pudo aplicar el descuento.');
      return;
    }

    if (nextDiscount === null) {
      this.setNotice('info', `Descuento quitado de "${previousLine.name}".`);
      return;
    }

    const matchingOverride = this.cart
      .pendingOverrides()
      .find(
        (entry) =>
          entry.action === 'DISCOUNT_ITEM_OVER_LIMIT' &&
          Number(entry.context?.['lineIndex']) === lineIndex,
      );

    if (!matchingOverride) {
      this.setNotice('info', `Descuento aplicado a "${previousLine.name}".`);
      return;
    }

    this.keyboard.setSuspended(true);
    try {
      const token = await firstValueFrom(
        this.overrideService.authorizeRequiredOverride(matchingOverride),
      );
      if (token) {
        this.cart.recordGrantedOverride(matchingOverride, token);
        this.setNotice('info', `Descuento autorizado para "${previousLine.name}".`);
      } else {
        await this.cart.applyLineDiscount(lineIndex, previousDiscount);
        this.setNotice('warning', 'Descuento de línea revertido: autorización cancelada.');
      }
    } finally {
      this.keyboard.setSuspended(false);
    }
  }

  private async applyTotalDiscountWithOverride(nextDiscount: DiscountInput | null): Promise<void> {
    const previousDiscount = this.cart.totalDiscount();
    const quote = await this.cart.applyTotalDiscount(nextDiscount);
    if (!quote) {
      this.setNotice('error', this.cart.quoteError() ?? 'No se pudo aplicar el descuento global.');
      return;
    }

    if (nextDiscount === null) {
      this.setNotice('info', 'Descuento global quitado.');
      return;
    }

    const matchingOverride = this.cart
      .pendingOverrides()
      .find((entry) => entry.action === 'DISCOUNT_TOTAL_OVER_LIMIT');

    if (!matchingOverride) {
      this.setNotice('info', 'Descuento global aplicado.');
      return;
    }

    this.keyboard.setSuspended(true);
    try {
      const token = await firstValueFrom(
        this.overrideService.authorizeRequiredOverride(matchingOverride),
      );
      if (token) {
        this.cart.recordGrantedOverride(matchingOverride, token);
        this.setNotice('info', 'Descuento global autorizado por supervisor.');
      } else {
        await this.cart.applyTotalDiscount(previousDiscount);
        this.setNotice('warning', 'Descuento global revertido: autorización cancelada.');
      }
    } finally {
      this.keyboard.setSuspended(false);
    }
  }

  private promptQuantity(data: PosQuantityDialogData): Promise<number | undefined> {
    return this.openSuspendedDialog<PosQuantityDialog, PosQuantityDialogData, number>(
      PosQuantityDialog,
      data,
      '420px',
    );
  }

  private openSuspendedDialog<TComponent, TData, TResult>(
    component: ComponentType<TComponent>,
    data: TData,
    width: string,
  ): Promise<TResult | undefined> {
    this.keyboard.setSuspended(true);
    const ref = this.dialog.open<TComponent, TData, TResult>(component, {
      data,
      width,
      autoFocus: 'first-tabbable',
    });
    return firstValueFrom(
      ref.afterClosed().pipe(
        finalize(() => {
          this.keyboard.setSuspended(false);
        }),
      ),
    );
  }

  private setNotice(severity: PosStatusNotice['severity'], message: string): void {
    this.statusNotice.set({ severity, message });
  }

  private focusScanInputSoon(): void {
    this.searchBar()?.focusScanInput();
  }
}
