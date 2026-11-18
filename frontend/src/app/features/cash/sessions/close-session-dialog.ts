import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CashSessionResponse } from '../../../api/models/cash-session-response';
import { CashSessionSummaryResponse } from '../../../api/models/cash-session-summary-response';
import { CloseCashSessionRequest } from '../../../api/models/close-cash-session-request';
import { CountInput } from '../../../api/models/count-input';
import { isApiError } from '../../../core/http/api-error';
import { withIdempotency, withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { ClpPipe } from '../../../shared/pipes';
import {
  OverrideDialog,
  OverrideDialogData,
  OverrideResponse,
} from '../../../shared/ui/override-dialog';
import {
  DEFAULT_CASH_SETTINGS,
  PaymentMethodCode,
  formatUserDisplay,
  paymentMethodLabel,
  resolveCashErrorMessage,
} from './cash.models';
import { CashService } from './cash.service';
import { CLP_DENOMINATIONS, DenominationCalculator } from './denomination-calculator';

/** Datos de entrada para el diálogo de cierre de turno con arqueo. */
export interface CloseSessionDialogData {
  readonly session: CashSessionResponse;
}

/** Fila editable de arqueo por medio de pago. */
export interface MethodCountRow {
  readonly method: PaymentMethodCode;
  readonly label: string;
  readonly expected: number;
  readonly sales: number;
  readonly refunds: number;
  readonly creditPayments: number;
}

/**
 * Diálogo de cierre de turno con arqueo por medio de pago y calculadora de denominaciones CLP
 * (`HU-CAJ-03`, `HU-CAJ-04`, `HU-CAJ-07`).
 *
 * Calcula en vivo `Σ denominación × cantidad = contado` mediante {@link DenominationCalculator}.
 * Si la diferencia absoluta supera `cash.difference_tolerance`, ofrece cerrar autorizando en el acto
 * mediante `OverrideDialog` (`CASH_DIFFERENCE_APPROVAL`) o cerrar dejando la diferencia pendiente
 * de aprobación posterior por un supervisor (flujo SOD `RN-23`).
 */
@Component({
  selector: 'pos-close-session-dialog',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
  ],
  templateUrl: './close-session-dialog.html',
  styleUrl: './close-session-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CloseSessionDialog implements OnInit {
  private readonly cashService = inject(CashService);
  private readonly dialog = inject(MatDialog);
  private readonly dialogRef =
    inject<MatDialogRef<CloseSessionDialog, CashSessionResponse | undefined>>(MatDialogRef);
  protected readonly data = inject<CloseSessionDialogData>(MAT_DIALOG_DATA);

  protected readonly calculator = new DenominationCalculator(CLP_DENOMINATIONS);
  protected readonly denominations = this.calculator.denominations;
  protected readonly billDenominations = this.denominations.filter((d) => d.kind === 'BILL');
  protected readonly coinDenominations = this.denominations.filter((d) => d.kind === 'COIN');

  protected readonly loading = signal(true);
  protected readonly submitting = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly submitError = signal<string | null>(null);
  protected readonly summary = signal<CashSessionSummaryResponse | null>(null);
  protected readonly settings = signal(DEFAULT_CASH_SETTINGS);
  protected readonly methodRows = signal<readonly MethodCountRow[]>([]);

  /** Cantidades ingresadas por denominación CLP (clave = valor numérico en string). */
  protected readonly quantities = signal<Record<string, number>>(this.calculator.emptyQuantities());
  /** `true` cuando el contado de efectivo proviene del detalle de denominaciones. */
  protected readonly usingDenominations = signal(true);

  /** Monto contado por medio de pago, en señales reactivas sincronizadas con el formulario. */
  protected readonly countedValues = signal<Partial<Record<PaymentMethodCode, number | null>>>({
    CASH: 0,
  });

  protected readonly countsForm = new FormGroup<
    Partial<Record<PaymentMethodCode, FormControl<number | null>>>
  >({});
  protected readonly notesControl = new FormControl('', {
    nonNullable: true,
    validators: [Validators.maxLength(500)],
  });

  protected readonly denominationTotal = computed(() =>
    this.calculator.calculateTotal(this.quantities()),
  );

  protected readonly expectedTotal = computed(() =>
    this.methodRows().reduce((acc, row) => acc + row.expected, 0),
  );

  protected readonly countedTotal = computed(() => {
    const values = this.countedValues();
    return this.methodRows().reduce((acc, row) => {
      const val = values[row.method];
      return acc + (typeof val === 'number' && Number.isFinite(val) ? Math.round(val) : 0);
    }, 0);
  });

  protected readonly differenceTotal = computed(() =>
    this.calculator.difference(this.countedTotal(), this.expectedTotal()),
  );

  protected readonly exceedsTolerance = computed(() =>
    this.calculator.exceedsTolerance(this.differenceTotal(), this.settings().differenceTolerance),
  );

  protected readonly cashierDisplay = formatUserDisplay(this.data.session.cashier);

  ngOnInit(): void {
    this.loadInitialData();
  }

  protected loadInitialData(): void {
    const sessionId = this.data.session.id;
    if (sessionId === undefined) {
      this.loading.set(false);
      this.loadError.set('El turno no tiene un identificador válido.');
      return;
    }

    this.loading.set(true);
    this.loadError.set(null);

    forkJoin({
      summary: this.cashService.getSummary(sessionId, withoutErrorNotification()),
      settings: this.cashService
        .getCashSettings(withoutErrorNotification())
        .pipe(catchError(() => of(DEFAULT_CASH_SETTINGS))),
    }).subscribe({
      next: ({ summary, settings }) => {
        this.summary.set(summary);
        this.settings.set(settings);
        const rows = this.buildMethodRows(summary);
        this.methodRows.set(rows);
        this.initCountControls(rows);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loadError.set(
          resolveCashErrorMessage(err, 'No se pudo cargar el resumen del turno para cerrar.'),
        );
        this.loading.set(false);
      },
    });
  }

  protected onDenominationChange(denomValue: number, rawValue: string): void {
    const qty = this.calculator.normalizeQuantity(rawValue);
    this.quantities.update((current) => ({
      ...current,
      [String(denomValue)]: qty,
    }));
    this.usingDenominations.set(true);

    const total = this.calculator.calculateTotal(this.quantities());
    const cashCtrl = this.countsForm.controls.CASH;
    if (cashCtrl) {
      cashCtrl.setValue(total, { emitEvent: false });
      cashCtrl.markAsDirty();
    }
    this.countedValues.update((current) => ({ ...current, CASH: total }));
  }

  protected clearDenominations(): void {
    this.quantities.set(this.calculator.emptyQuantities());
    this.usingDenominations.set(true);
    const cashCtrl = this.countsForm.controls.CASH;
    if (cashCtrl) {
      cashCtrl.setValue(0, { emitEvent: false });
    }
    this.countedValues.update((current) => ({ ...current, CASH: 0 }));
  }

  protected onManualCountInput(method: PaymentMethodCode, rawValue: string): void {
    if (method === 'CASH') {
      this.usingDenominations.set(false);
    }
    const parsed = rawValue.trim() === '' ? null : Number(rawValue);
    const normalized = parsed !== null && Number.isFinite(parsed) ? Math.round(parsed) : null;
    const ctrl = this.countsForm.controls[method];
    if (ctrl && ctrl.value !== normalized) {
      ctrl.setValue(normalized, { emitEvent: false });
    }
    this.countedValues.update((current) => ({ ...current, [method]: normalized }));
  }

  protected rowDifference(row: MethodCountRow): number {
    const raw = this.countedValues()[row.method];
    const counted = typeof raw === 'number' && Number.isFinite(raw) ? raw : 0;
    return this.calculator.difference(counted, row.expected);
  }

  protected submitDirect(): void {
    if (!this.validateForms()) {
      return;
    }
    this.executeClose();
  }

  protected submitWithSupervisorOverride(): void {
    if (!this.validateForms()) {
      return;
    }
    const sessionId = this.data.session.id;
    if (sessionId === undefined) {
      return;
    }

    const dialogData: OverrideDialogData = {
      action: 'CASH_DIFFERENCE_APPROVAL',
      context: {
        cashSessionId: sessionId,
        differenceTotal: this.differenceTotal(),
      },
    };

    this.dialog
      .open<OverrideDialog, OverrideDialogData, OverrideResponse | undefined>(OverrideDialog, {
        data: dialogData,
        width: '460px',
      })
      .afterClosed()
      .subscribe((overrideRes) => {
        if (overrideRes?.overrideToken) {
          this.executeClose(overrideRes.overrideToken);
        }
      });
  }

  protected cancel(): void {
    this.dialogRef.close(undefined);
  }

  private executeClose(overrideToken?: string): void {
    const sessionId = this.data.session.id;
    if (sessionId === undefined || this.submitting()) {
      return;
    }

    const payload = this.buildPayload();
    this.submitting.set(true);
    this.dialogRef.disableClose = true;
    this.submitError.set(null);

    const context = withIdempotency(withoutErrorNotification());
    this.cashService.closeSession(sessionId, payload, { overrideToken, context }).subscribe({
      next: (closed) => {
        this.dialogRef.close(closed);
      },
      error: (err: unknown) => {
        this.applyError(err);
        this.submitting.set(false);
        this.dialogRef.disableClose = false;
      },
    });
  }

  private validateForms(): boolean {
    if (this.submitting() || this.loading()) {
      return false;
    }
    if (this.countsForm.invalid || this.notesControl.invalid) {
      this.countsForm.markAllAsTouched();
      this.notesControl.markAsTouched();
      return false;
    }
    return true;
  }

  private buildPayload(): CloseCashSessionRequest {
    const counts: CountInput[] = this.methodRows().map((row) => {
      const value = this.countsForm.controls[row.method]?.value ?? 0;
      const countedAmount = Math.max(0, Math.round(Number(value)));
      if (row.method === 'CASH' && this.usingDenominations()) {
        const denominationDetail = this.calculator.buildDetail(this.quantities());
        return {
          paymentMethod: row.method,
          countedAmount,
          ...(denominationDetail ? { denominationDetail } : {}),
        };
      }
      return {
        paymentMethod: row.method,
        countedAmount,
      };
    });

    const trimmedNotes = this.notesControl.value.trim();
    return {
      counts,
      ...(trimmedNotes ? { notes: trimmedNotes } : {}),
    };
  }

  private buildMethodRows(summary: CashSessionSummaryResponse): MethodCountRow[] {
    const byMethod = new Map(
      (summary.byMethod ?? []).filter((m) => m.method !== undefined).map((m) => [m.method!, m]),
    );

    const rows: MethodCountRow[] = [];
    const cashLine = byMethod.get('CASH');
    rows.push({
      method: 'CASH',
      label: paymentMethodLabel('CASH'),
      expected: Math.round(cashLine?.expected ?? summary.expectedCash ?? 0),
      sales: Math.round(cashLine?.sales ?? 0),
      refunds: Math.round(cashLine?.refunds ?? 0),
      creditPayments: Math.round(cashLine?.creditPayments ?? 0),
    });

    for (const item of summary.byMethod ?? []) {
      const method = item.method;
      if (!method || method === 'CASH') {
        continue;
      }
      const expected = Math.round(item.expected ?? 0);
      const sales = Math.round(item.sales ?? 0);
      const refunds = Math.round(item.refunds ?? 0);
      const creditPayments = Math.round(item.creditPayments ?? 0);
      if (expected !== 0 || sales !== 0 || refunds !== 0 || creditPayments !== 0) {
        rows.push({
          method,
          label: paymentMethodLabel(method),
          expected,
          sales,
          refunds,
          creditPayments,
        });
      }
    }
    return rows;
  }

  private initCountControls(rows: readonly MethodCountRow[]): void {
    for (const existingKey of Object.keys(this.countsForm.controls) as PaymentMethodCode[]) {
      this.countsForm.removeControl(existingKey);
    }
    const initialValues: Partial<Record<PaymentMethodCode, number | null>> = {};
    for (const row of rows) {
      const initial = row.method === 'CASH' ? 0 : null;
      initialValues[row.method] = initial;
      this.countsForm.addControl(
        row.method,
        new FormControl<number | null>(initial, {
          validators: [Validators.required, Validators.min(0), Validators.pattern(/^\d+$/)],
        }),
      );
    }
    this.countedValues.set(initialValues);
  }

  private applyError(err: unknown): void {
    if (isApiError(err) && err.hasFieldErrors) {
      this.submitError.set(err.errors.map((e) => e.message).join(' ') || err.message);
      return;
    }
    this.submitError.set(resolveCashErrorMessage(err, 'No se pudo cerrar el turno de caja.'));
  }
}
