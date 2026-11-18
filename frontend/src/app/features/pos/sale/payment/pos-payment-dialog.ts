import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { firstValueFrom, forkJoin } from 'rxjs';
import { CreateSaleRequest } from '../../../../api/models/create-sale-request';
import { PaymentInput } from '../../../../api/models/payment-input';
import { withoutErrorNotification } from '../../../../core/http/http-context-tokens';
import { generateUuid } from '../../../../core/http/uuid';
import { ClpPipe } from '../../../../shared/pipes';
import { extractPosErrorMessage } from '../cart/pos-http.utils';
import {
  AuthorizedCardPayment,
  PosElectronicPaymentMethod,
  PosPaymentDialogData,
  PosPaymentDialogResult,
  TransferPaymentEntry,
  paymentMethodLabel,
} from './payment.models';
import { PaymentService } from './payment.service';

const QUICK_CASH_BILLS = [1000, 2000, 5000, 10000, 20000] as const;

/**
 * Diálogo modal de cobro simple y mixto (`F10` / `F12`, `HU-POS-08`, `HU-POS-09`).
 *
 * Permite combinar pagos electrónicos (`DEBIT` / `CREDIT` autorizados contra `/pos/card-payments`,
 * y `TRANSFER` con referencia) con efectivo (`CASH`), aplicando el redondeo legal chileno
 * (Ley 20.956) sobre el saldo en efectivo y calculando el vuelto en tiempo real.
 *
 * Si el usuario cancela después de haber autorizado una o más tarjetas, las anula
 * automáticamente vía `POST /pos/card-payments/{id}/void` antes de cerrar.
 */
@Component({
  selector: 'pos-payment-dialog',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatDialogModule,
    MatDividerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
  ],
  templateUrl: './pos-payment-dialog.html',
  styleUrl: './pos-payment-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosPaymentDialog {
  protected readonly data = inject<PosPaymentDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef =
    inject<MatDialogRef<PosPaymentDialog, PosPaymentDialogResult | undefined>>(MatDialogRef);
  private readonly paymentService = inject(PaymentService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly quickBills = QUICK_CASH_BILLS;
  protected readonly cashRoundingEnabled = this.data.cashRoundingEnabled ?? true;

  protected readonly authorizedCards = signal<readonly AuthorizedCardPayment[]>([]);
  protected readonly transfers = signal<readonly TransferPaymentEntry[]>([]);
  protected readonly busyAction = signal<'card' | 'void' | 'sale' | null>(null);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly electronicMethod = signal<PosElectronicPaymentMethod>('DEBIT');
  protected readonly tenderedValue = signal<number | null>(
    this.paymentService.calculateCashBreakdown(
      this.data.totalGross,
      0,
      null,
      this.cashRoundingEnabled,
    ).cashPayable,
  );

  protected readonly nonCashTotal = computed(() => {
    const cardsSum = this.authorizedCards().reduce((acc, card) => acc + card.amount, 0);
    const transfersSum = this.transfers().reduce((acc, tr) => acc + tr.amount, 0);
    return cardsSum + transfersSum;
  });

  protected readonly breakdown = computed(() =>
    this.paymentService.calculateCashBreakdown(
      this.data.totalGross,
      this.nonCashTotal(),
      this.tenderedValue(),
      this.cashRoundingEnabled,
    ),
  );

  protected readonly hasInvalidZeroRoundedCash = computed(() => {
    const b = this.breakdown();
    return b.cashDue > 0 && b.cashPayable <= 0;
  });

  protected readonly canConfirmSale = computed(() => {
    if (this.busyAction() !== null || this.emailControl.invalid) {
      return false;
    }
    const b = this.breakdown();
    if (b.overpaidNonCash > 0 || this.hasInvalidZeroRoundedCash()) {
      return false;
    }
    if (b.cashDue > 0) {
      return b.tenderedAmount !== null && b.tenderedAmount >= b.cashPayable;
    }
    return this.nonCashTotal() === b.totalGross && b.totalGross > 0;
  });

  protected readonly cashTenderedControl = new FormControl<number | null>(this.tenderedValue(), {
    validators: [Validators.min(0), integerAmountValidator],
  });

  protected readonly electronicForm = new FormGroup({
    method: new FormControl<PosElectronicPaymentMethod>('DEBIT', { nonNullable: true }),
    amount: new FormControl<number | null>(Math.max(1, Math.round(this.data.totalGross)), {
      validators: [Validators.required, Validators.min(1), integerAmountValidator],
    }),
    transferReference: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(100)],
    }),
  });

  protected readonly emailControl = new FormControl('', {
    nonNullable: true,
    validators: [Validators.email, Validators.maxLength(150)],
  });

  constructor() {
    this.dialogRef.disableClose = true;

    this.cashTenderedControl.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((val) => {
        this.tenderedValue.set(typeof val === 'number' && Number.isFinite(val) ? val : null);
      });

    this.dialogRef
      .backdropClick()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        void this.cancel();
      });

    this.dialogRef
      .keydownEvents()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event) => {
        if (event.key === 'Escape') {
          event.preventDefault();
          void this.cancel();
        } else if (event.key === 'F12') {
          event.preventDefault();
          void this.confirmSale();
        }
      });
  }

  protected methodLabel(method: string | undefined): string {
    return paymentMethodLabel(method);
  }

  protected onElectronicMethodChange(method: PosElectronicPaymentMethod): void {
    this.electronicMethod.set(method);
    const refCtrl = this.electronicForm.controls.transferReference;
    if (method === 'TRANSFER') {
      refCtrl.setValidators([Validators.required, Validators.maxLength(100)]);
    } else {
      refCtrl.setValidators([Validators.maxLength(100)]);
    }
    refCtrl.updateValueAndValidity();
    this.errorMessage.set(null);
  }

  protected setExactCash(): void {
    const exact = this.breakdown().cashPayable;
    this.cashTenderedControl.setValue(exact);
    this.errorMessage.set(null);
  }

  protected setQuickCash(amount: number): void {
    this.cashTenderedControl.setValue(amount);
    this.errorMessage.set(null);
  }

  protected async addElectronicPayment(): Promise<void> {
    if (this.busyAction() !== null) {
      return;
    }
    this.errorMessage.set(null);
    if (this.electronicForm.invalid) {
      this.electronicForm.markAllAsTouched();
      return;
    }

    const raw = this.electronicForm.getRawValue();
    const amount = raw.amount !== null ? Math.round(raw.amount) : 0;
    const remaining = this.breakdown().cashDue;

    if (amount <= 0) {
      this.errorMessage.set('El monto del pago electrónico debe ser mayor que $0.');
      return;
    }
    if (amount > remaining) {
      this.errorMessage.set(
        `El monto supera el saldo pendiente ($${remaining.toLocaleString('es-CL')}).`,
      );
      return;
    }

    if (raw.method === 'TRANSFER') {
      const ref = raw.transferReference.trim();
      if (!ref) {
        this.electronicForm.controls.transferReference.markAsTouched();
        return;
      }
      this.transfers.update((prev) => [
        ...prev,
        {
          localId: generateUuid(),
          method: 'TRANSFER',
          amount,
          transferReference: ref,
        },
      ]);
      this.electronicForm.controls.transferReference.setValue('');
      this.syncAfterNonCashChange();
      return;
    }

    this.busyAction.set('card');
    try {
      const auth = await firstValueFrom(
        this.paymentService.authorizeCardPayment(raw.method, amount, withoutErrorNotification()),
      );
      if (!auth.id) {
        this.errorMessage.set('La pasarela no devolvió un identificador de autorización válido.');
        return;
      }
      this.authorizedCards.update((prev) => [
        ...prev,
        {
          id: auth.id!,
          method: raw.method === 'CREDIT' ? 'CREDIT' : 'DEBIT',
          amount,
          authorizationCode: auth.authorizationCode,
          cardBrand: auth.cardBrand,
          last4: auth.last4,
          gatewayReference: auth.gatewayReference,
        },
      ]);
      this.syncAfterNonCashChange();
    } catch (error: unknown) {
      this.errorMessage.set(
        extractPosErrorMessage(error, 'No se pudo autorizar el pago con tarjeta.'),
      );
    } finally {
      this.busyAction.set(null);
    }
  }

  protected async voidCard(card: AuthorizedCardPayment): Promise<void> {
    if (this.busyAction() !== null) {
      return;
    }
    this.errorMessage.set(null);
    this.busyAction.set('void');
    try {
      await firstValueFrom(
        this.paymentService.voidCardAuthorization(card.id, withoutErrorNotification()),
      );
      this.authorizedCards.update((prev) => prev.filter((item) => item.id !== card.id));
      this.syncAfterNonCashChange();
    } catch (error: unknown) {
      this.errorMessage.set(
        extractPosErrorMessage(error, 'No se pudo anular la autorización de tarjeta.'),
      );
    } finally {
      this.busyAction.set(null);
    }
  }

  protected removeTransfer(localId: string): void {
    if (this.busyAction() !== null) {
      return;
    }
    this.transfers.update((prev) => prev.filter((item) => item.localId !== localId));
    this.syncAfterNonCashChange();
  }

  protected async confirmSale(): Promise<void> {
    if (!this.canConfirmSale()) {
      this.cashTenderedControl.markAsTouched();
      this.emailControl.markAsTouched();
      return;
    }

    this.errorMessage.set(null);
    this.busyAction.set('sale');

    const b = this.breakdown();
    const payments: PaymentInput[] = [
      ...this.authorizedCards().map((card) => ({
        method: card.method,
        amount: card.amount,
        cardAuthorizationId: card.id,
      })),
      ...this.transfers().map((tr) => ({
        method: 'TRANSFER' as const,
        amount: tr.amount,
        transferReference: tr.transferReference,
      })),
    ];

    if (b.cashDue > 0 && b.cashPayable > 0 && b.tenderedAmount !== null) {
      payments.push({
        method: 'CASH',
        amount: b.cashPayable,
        tenderedAmount: b.tenderedAmount,
      });
    }

    const emailTo = this.emailControl.value.trim();
    const request: CreateSaleRequest = {
      lines: this.data.cartLines,
      ...(this.data.totalDiscount ? { totalDiscount: this.data.totalDiscount } : {}),
      ...(this.data.customerId !== null ? { customerId: this.data.customerId } : {}),
      ageConfirmed: this.data.ageConfirmed,
      ...(emailTo ? { emailTo } : {}),
      payments,
    };

    try {
      const sale = await firstValueFrom(
        this.paymentService.createSale(
          request,
          this.data.idempotencyKey,
          this.data.initialOverrideTokens,
          withoutErrorNotification(),
        ),
      );
      this.dialogRef.close({ sale });
    } catch (error: unknown) {
      this.errorMessage.set(extractPosErrorMessage(error, 'No se pudo completar la venta.'));
    } finally {
      this.busyAction.set(null);
    }
  }

  protected async cancel(): Promise<void> {
    if (this.busyAction() === 'sale' || this.busyAction() === 'card') {
      return;
    }
    const pendingCards = this.authorizedCards();
    if (pendingCards.length === 0) {
      this.dialogRef.close(undefined);
      return;
    }

    this.errorMessage.set(null);
    this.busyAction.set('void');
    try {
      await firstValueFrom(
        forkJoin(
          pendingCards.map((card) =>
            this.paymentService.voidCardAuthorization(card.id, withoutErrorNotification()),
          ),
        ),
      );
      this.authorizedCards.set([]);
      this.dialogRef.close(undefined);
    } catch (error: unknown) {
      this.errorMessage.set(
        extractPosErrorMessage(
          error,
          'No se pudieron anular las autorizaciones de tarjeta pendientes. Intenta nuevamente.',
        ),
      );
    } finally {
      this.busyAction.set(null);
    }
  }

  private syncAfterNonCashChange(): void {
    const nextBreakdown = this.breakdown();
    this.electronicForm.controls.amount.setValue(
      nextBreakdown.cashDue > 0 ? nextBreakdown.cashDue : null,
    );
    this.cashTenderedControl.setValue(
      nextBreakdown.cashPayable > 0 ? nextBreakdown.cashPayable : null,
    );
  }
}

function integerAmountValidator(control: AbstractControl): ValidationErrors | null {
  const value = control.value as number | null;
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return null;
  }
  return Number.isInteger(value) ? null : { wholeAmount: true };
}
