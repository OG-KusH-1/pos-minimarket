import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { SaleResponse } from '../../../api/models/sale-response';
import { SaleReturnResponse } from '../../../api/models/sale-return-response';
import { SaleReturnableLinesResponse } from '../../../api/models/sale-returnable-lines-response';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { generateUuid } from '../../../core/http/uuid';
import { ClpPipe, QtyPipe } from '../../../shared/pipes';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  OVERRIDE_TOKEN_HEADER,
  SupervisorOverrideService,
} from '../../../shared/ui/override-dialog';
import {
  RETURN_REASON_OPTIONS,
  ReturnReasonCode,
  formatDateTime,
  paymentMethodLabel,
  resolveReturnsErrorMessage,
  returnTypeLabel,
  saleStatusLabel,
} from './returns.models';
import { ReturnsService } from './returns.service';
import { ReturnLinesDialog, ReturnLinesDialogData } from './return-lines-dialog';

/** Mensaje breve de estado operativo mostrado en la parte superior de la pantalla. */
interface ReturnsStatusNotice {
  readonly severity: 'info' | 'warning' | 'error';
  readonly message: string;
}

/**
 * Pantalla POS de anulación y devolución de ventas (`HU-POS-14`, `HU-POS-15`; ruta
 * `/pos/devoluciones`).
 *
 * Flujo con teclado (flujo 4 de `API.md` F.4):
 * 1. Búsqueda de la venta por folio/número (`GET /sales/by-number/{n}`) y presentación de su
 *    detalle junto con las líneas devolvibles (`GET /sales/{id}/returnable`).
 * 2. Anulación total con confirmación (`ConfirmDialogService`) y motivo (`POST /sales/{id}/void`,
 *    `[I][T][O:SALE_VOID]`): el backend reingresa el stock y reversa los pagos.
 * 3. Devolución parcial en {@link ReturnLinesDialog} (`POST /sales/{id}/returns`,
 *    `[I][T][O:SALE_RETURN]`): cantidades topadas a lo devolvible real y flag `restock` por línea.
 *
 * Los permisos y el alcance por cajero los resuelve el backend: si el rol no tiene permiso
 * directo responde `OVERRIDE_REQUIRED` y `SupervisorOverrideService` pide el PIN del supervisor
 * y reintenta la MISMA petición (mismo `Idempotency-Key`).
 */
@Component({
  selector: 'pos-returns',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTooltipModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  templateUrl: './returns.html',
  styleUrl: './returns.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Returns implements OnInit {
  private readonly returnsService = inject(ReturnsService);
  private readonly storeContext = inject(StoreContextService);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly supervisorOverride = inject(SupervisorOverrideService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  /** Venta cargada tras la búsqueda (detalle de `GET /sales/by-number/{n}`). */
  protected readonly sale = signal<SaleResponse | null>(null);

  /** Líneas devolvibles de la venta cargada (`GET /sales/{id}/returnable`). */
  protected readonly returnable = signal<SaleReturnableLinesResponse | null>(null);

  /** Indica si la búsqueda está en curso. */
  protected readonly searching = signal(false);

  /** Indica si se están recargando las líneas devolvibles. */
  protected readonly loadingReturnable = signal(false);

  /** Resultado de la última anulación o devolución confirmada. */
  protected readonly operationResult = signal<SaleReturnResponse | null>(null);

  /** Aviso transitorio de operación (éxito, advertencia o error). */
  protected readonly statusNotice = signal<ReturnsStatusNotice | null>(null);

  /** Indica si la anulación se está enviando. */
  protected readonly submittingVoid = signal(false);

  /** Campo de búsqueda del folio/número de venta. */
  protected readonly searchControl = new FormControl('', { nonNullable: true });

  /** Motivo de la anulación total (`VoidSaleRequest`: `reasonCode` obligatorio, detalle opcional). */
  protected readonly voidForm = new FormGroup({
    reasonCode: new FormControl<ReturnReasonCode>('CUSTOMER_REQUEST', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    reasonText: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(500)],
    }),
  });

  protected readonly reasonOptions = RETURN_REASON_OPTIONS;

  /** `true` cuando la venta admite anulación según el backend (`ReturnableLinesResponse.voidAllowed`). */
  protected readonly canVoid = computed(() => this.returnable()?.voidAllowed === true);

  /** `true` cuando queda al menos una línea con cantidad devolvible > 0. */
  protected readonly canReturn = computed(() => {
    const lines = this.returnable()?.lines ?? [];
    return lines.some((line) => (line.returnableQuantity ?? 0) > 0);
  });

  /** Explicación breve de por qué la anulación está bloqueada (tooltip del botón). */
  protected readonly voidDisabledHint = computed(() => {
    if (this.canVoid()) {
      return '';
    }
    const status = this.sale()?.status;
    if (status === 'VOIDED') {
      return 'La venta ya está anulada.';
    }
    return this.loadingReturnable()
      ? 'Consultando si la venta admite anulación…'
      : 'El backend reporta que la venta no admite anulación (p. ej. turno original cerrado, RN-12).';
  });

  /**
   * Registra el atajo `F2` para devolver el foco al campo de búsqueda (misma convención que el
   * POS de ventas, `HU-POS-20`). Los diálogos abiertos mantienen su propio foco.
   */
  ngOnInit(): void {
    const captureHandler = (event: KeyboardEvent): void => {
      if (event.defaultPrevented || event.key !== 'F2' || this.dialog.openDialogs.length > 0) {
        return;
      }
      event.preventDefault();
      this.focusSearch();
    };
    document.addEventListener('keydown', captureHandler, { capture: true });
    this.destroyRef.onDestroy(() =>
      document.removeEventListener('keydown', captureHandler, { capture: true }),
    );
    this.focusSearch();
  }

  /**
   * Busca la venta por folio/número y carga sus líneas devolvibles (flujo 4 de F.4, paso 1).
   * El foco vuelve al campo de búsqueda para encadenar operaciones solo con teclado.
   */
  protected async searchSale(): Promise<void> {
    if (this.searching()) {
      return;
    }
    const query = this.searchControl.value.trim();
    if (!query) {
      this.setNotice('warning', 'Ingresa el folio o número de la venta a buscar.');
      this.focusSearch();
      return;
    }

    this.searching.set(true);
    this.statusNotice.set(null);
    this.operationResult.set(null);
    try {
      const sale = await firstValueFrom(
        this.returnsService.getSaleByNumber(query, withoutErrorNotification()),
      );
      if (sale.id === undefined) {
        this.sale.set(null);
        this.returnable.set(null);
        this.setNotice('error', 'La venta llegó sin identificador; no se puede operar.');
        return;
      }
      this.sale.set(sale);
      this.voidForm.reset({ reasonCode: 'CUSTOMER_REQUEST', reasonText: '' });
      await this.loadReturnable(sale.id);
      this.setNotice('info', `Venta ${sale.number ?? query} cargada.`);
    } catch (error) {
      this.sale.set(null);
      this.returnable.set(null);
      this.setNotice('error', resolveReturnsErrorMessage(error, 'No se pudo cargar la venta.'));
    } finally {
      this.searching.set(false);
      this.focusSearch();
    }
  }

  /**
   * Anula totalmente la venta (`POST /sales/{id}/void`): pide confirmación explícita, motivo y
   * override de supervisor si el rol no tiene permiso directo (`O:SALE_VOID`).
   */
  protected async submitVoid(): Promise<void> {
    const sale = this.sale();
    if (!sale?.id || !this.canVoid() || this.submittingVoid()) {
      return;
    }
    if (this.voidForm.invalid) {
      this.voidForm.markAllAsTouched();
      this.setNotice('warning', 'Selecciona el motivo de la anulación.');
      return;
    }

    const saleId = sale.id;
    const numberLabel = sale.number ?? `#${saleId}`;
    const confirmed = await firstValueFrom(
      this.confirmDialog.confirm({
        title: `Anular venta ${numberLabel}`,
        message:
          'Se anulará la venta completa: el stock reingresará al local (movimiento SALE_VOID), ' +
          'los pagos se reversarán y se emitirá la nota de crédito. Esta acción no se puede deshacer. ¿Deseas continuar?',
        confirmLabel: 'Anular venta',
        destructive: true,
      }),
    );
    if (!confirmed) {
      this.setNotice('warning', 'Anulación cancelada.');
      return;
    }

    const { reasonCode, reasonText } = this.voidForm.getRawValue();
    const trimmedReason = reasonText.trim();
    const body = {
      reasonCode,
      ...(trimmedReason ? { reasonText: trimmedReason } : {}),
    };

    this.submittingVoid.set(true);
    this.statusNotice.set(null);
    // Una sola clave de idempotencia y un solo HttpContext por operación para que el reintento
    // con X-Override-Token (override SALE_VOID) reenvíe la misma clave (ADR-14, L-04).
    const idempotencyKey = generateUuid();
    const context = withoutErrorNotification();

    this.supervisorOverride
      .withSupervisorOverride((overrideHeaders) =>
        this.returnsService.voidSale(saleId, body, {
          idempotencyKey,
          overrideToken: overrideHeaders[OVERRIDE_TOKEN_HEADER],
          context,
        }),
      )
      .subscribe({
        next: (response) => {
          this.submittingVoid.set(false);
          this.handleOperationSuccess(response);
        },
        error: (error: unknown) => {
          this.submittingVoid.set(false);
          this.setNotice('error', resolveReturnsErrorMessage(error, 'No se pudo anular la venta.'));
        },
      });
  }

  /** Abre el diálogo de devolución parcial con las líneas devolvibles ya consultadas. */
  protected openReturnDialog(): void {
    const sale = this.sale();
    const returnable = this.returnable();
    if (!sale?.id || !returnable || !this.canReturn()) {
      return;
    }

    const data: ReturnLinesDialogData = {
      saleId: sale.id,
      saleNumber: sale.number,
      returnable,
      saleLines: sale.lines,
    };

    this.dialog
      .open<ReturnLinesDialog, ReturnLinesDialogData, SaleReturnResponse | undefined>(
        ReturnLinesDialog,
        {
          data,
          width: '860px',
          maxWidth: '96vw',
          autoFocus: 'first-tabbable',
        },
      )
      .afterClosed()
      .subscribe((result) => {
        if (result) {
          this.handleOperationSuccess(result);
        }
        this.focusSearch();
      });
  }

  protected saleStatusLabel = saleStatusLabel;
  protected paymentMethodLabel = paymentMethodLabel;
  protected returnTypeLabel = returnTypeLabel;
  protected formatDateTime = formatDateTime;

  /** Limpia la pantalla y vuelve al estado inicial (sin venta cargada). */
  protected clearScreen(): void {
    this.sale.set(null);
    this.returnable.set(null);
    this.operationResult.set(null);
    this.statusNotice.set(null);
    this.searchControl.reset('');
    this.voidForm.reset({ reasonCode: 'CUSTOMER_REQUEST', reasonText: '' });
    this.focusSearch();
  }

  /** Registra el resultado, informa al usuario y recarga venta y líneas devolvibles. */
  private handleOperationSuccess(response: SaleReturnResponse): void {
    this.operationResult.set(response);
    const isVoid = response.returnType === 'VOID';
    this.setNotice(
      'info',
      isVoid
        ? `Venta anulada (${response.number ?? ''}). El stock reingresó y los pagos se reversaron.`
        : `Devolución ${response.number ?? ''} registrada. El detalle quedó en el panel de resultado.`,
    );
    const saleId = response.saleId ?? this.sale()?.id;
    if (saleId !== undefined) {
      void this.reloadSale(saleId);
    }
  }

  /** Vuelve a consultar la venta y sus líneas devolvibles tras una operación exitosa. */
  private async reloadSale(saleId: number): Promise<void> {
    try {
      const sale = await firstValueFrom(
        this.returnsService.getSaleById(saleId, withoutErrorNotification()),
      );
      this.sale.set(sale);
      this.voidForm.reset({ reasonCode: 'CUSTOMER_REQUEST', reasonText: '' });
      await this.loadReturnable(saleId);
    } catch {
      this.setNotice(
        'warning',
        'La operación se registró, pero no se pudo refrescar el detalle de la venta.',
      );
    }
  }

  /** Carga (o recarga) las líneas devolvibles; un fallo no bloquea la anulación. */
  private async loadReturnable(saleId: number): Promise<void> {
    this.loadingReturnable.set(true);
    try {
      const returnable = await firstValueFrom(
        this.returnsService.getReturnableLines(saleId, withoutErrorNotification()),
      );
      this.returnable.set(returnable);
    } catch (error) {
      this.returnable.set(null);
      this.setNotice(
        'warning',
        resolveReturnsErrorMessage(error, 'No se pudieron cargar las líneas devolvibles.'),
      );
    } finally {
      this.loadingReturnable.set(false);
    }
  }

  private setNotice(severity: ReturnsStatusNotice['severity'], message: string): void {
    this.statusNotice.set({ severity, message });
  }

  private focusSearch(): void {
    this.searchInput()?.nativeElement.focus();
  }
}
