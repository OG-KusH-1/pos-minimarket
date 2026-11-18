import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { filter, switchMap } from 'rxjs';
import { CashClosingReceiptResponse } from '../../../api/models/cash-closing-receipt-response';
import { UserRef } from '../../../api/models/user-ref';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { ClpPipe, RutPipe } from '../../../shared/pipes';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  formatCashDate,
  formatCashDateTime,
  formatUserDisplay,
  paymentMethodLabel,
  resolveCashErrorMessage,
} from './cash.models';
import { CashService } from './cash.service';

/** Datos de entrada para el diálogo de comprobante de cierre de caja. */
export interface ClosingReceiptDialogData {
  readonly sessionId: number;
  readonly sessionNumber?: string;
}

/**
 * Diálogo de visualización del comprobante de cierre de caja (JSON estructurado) y descarga o
 * vista previa del PDF oficial (`HU-CAJ-05`), con aprobación de diferencia pendiente (`HU-CAJ-04`).
 */
@Component({
  selector: 'pos-closing-receipt-dialog',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatProgressSpinnerModule,
    RutPipe,
  ],
  templateUrl: './closing-receipt-dialog.html',
  styleUrl: './closing-receipt-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClosingReceiptDialog implements OnInit {
  private readonly cashService = inject(CashService);
  private readonly auth = inject(AuthService);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly dialogRef = inject<MatDialogRef<ClosingReceiptDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<ClosingReceiptDialogData>(MAT_DIALOG_DATA);

  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly pdfBusy = signal(false);
  protected readonly approving = signal(false);
  protected readonly receipt = signal<CashClosingReceiptResponse | null>(null);
  private mutated = false;

  protected readonly canApproveDifference = computed(() =>
    this.auth.hasPermission('cash:difference:approve'),
  );
  protected readonly currentUserId = computed(() => this.auth.user()?.id ?? null);

  protected readonly isOwnSession = computed(() => {
    const cashierId = this.receipt()?.session?.cashier?.id;
    const userId = this.currentUserId();
    return cashierId !== undefined && userId !== null && cashierId === userId;
  });

  ngOnInit(): void {
    this.loadReceipt();
  }

  protected loadReceipt(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.cashService
      .getClosingReceiptJson(this.data.sessionId, withoutErrorNotification())
      .subscribe({
        next: (res) => {
          this.receipt.set(res);
          this.loading.set(false);
        },
        error: (err: unknown) => {
          this.errorMessage.set(
            resolveCashErrorMessage(err, 'No se pudo cargar el comprobante de cierre.'),
          );
          this.loading.set(false);
        },
      });
  }

  protected openPdf(): void {
    this.fetchPdf('open');
  }

  protected downloadPdf(): void {
    this.fetchPdf('download');
  }

  protected approveDifference(): void {
    const session = this.receipt()?.session;
    if (!session?.id || this.approving()) {
      return;
    }

    this.confirmDialog
      .confirm({
        title: 'Aprobar diferencia de cierre',
        message: `¿Aprobar la diferencia del turno ${session.number ?? '#' + session.id}? Esta acción queda registrada en auditoría.`,
        confirmLabel: 'Aprobar diferencia',
      })
      .pipe(
        filter(Boolean),
        switchMap(() => {
          this.approving.set(true);
          this.errorMessage.set(null);
          return this.cashService.approveDifference(session.id!, withoutErrorNotification());
        }),
      )
      .subscribe({
        next: () => {
          this.mutated = true;
          this.approving.set(false);
          this.loadReceipt();
        },
        error: (err: unknown) => {
          this.approving.set(false);
          this.errorMessage.set(
            resolveCashErrorMessage(err, 'No se pudo aprobar la diferencia del turno.'),
          );
        },
      });
  }

  protected close(): void {
    this.dialogRef.close(this.mutated);
  }

  protected methodLabel(method: string | undefined): string {
    return paymentMethodLabel(method);
  }

  protected formatDate(date: string | undefined): string {
    return formatCashDate(date);
  }

  protected formatDateTime(iso: string | undefined): string {
    return formatCashDateTime(iso);
  }

  protected formatUser(user?: UserRef | null): string {
    return formatUserDisplay(user);
  }

  private fetchPdf(mode: 'open' | 'download'): void {
    if (this.pdfBusy()) {
      return;
    }
    this.pdfBusy.set(true);
    this.errorMessage.set(null);

    this.cashService
      .downloadClosingReceiptPdf(this.data.sessionId, withoutErrorNotification())
      .subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          const number =
            this.receipt()?.session?.number ?? this.data.sessionNumber ?? this.data.sessionId;
          if (mode === 'open') {
            const win = globalThis.open(url, '_blank', 'noopener');
            if (!win) {
              this.triggerDownload(url, `cierre-caja-${number}.pdf`);
            }
          } else {
            this.triggerDownload(url, `cierre-caja-${number}.pdf`);
          }
          setTimeout(() => URL.revokeObjectURL(url), 10000);
          this.pdfBusy.set(false);
        },
        error: (err: unknown) => {
          this.pdfBusy.set(false);
          this.errorMessage.set(
            resolveCashErrorMessage(err, 'No se pudo generar el PDF del comprobante.'),
          );
        },
      });
  }

  private triggerDownload(url: string, fileName: string): void {
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.rel = 'noopener';
    document.body.appendChild(link);
    link.click();
    link.remove();
  }
}
