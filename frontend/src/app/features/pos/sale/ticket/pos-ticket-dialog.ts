import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { firstValueFrom } from 'rxjs';
import { TicketResponse } from '../../../../api/models/ticket-response';
import { withoutErrorNotification } from '../../../../core/http/http-context-tokens';
import { extractPosErrorMessage } from '../cart/pos-http.utils';
import { TicketComponent } from './ticket.component';
import { IframeTicketPrinter, TicketService } from './ticket.service';

/** Parámetros de apertura del diálogo de vista previa, impresión y reimpresión de ticket. */
export interface PosTicketDialogData {
  /** Identificador numérico de la venta (`GET /sales/{id}/ticket`). */
  readonly saleId: number;
  /** Número legible de venta para el encabezado inicial mientras carga. */
  readonly saleNumber?: string;
  /** Si es `true`, imprime automáticamente vía `iframe` apenas carga el ticket. */
  readonly autoPrint?: boolean;
}

/**
 * Diálogo modal de comprobante térmico 80 mm (`HU-POS-20`, `ADR-28`).
 * Carga `GET /sales/{id}/ticket`, imprime mediante `IframeTicketPrinter` y permite
 * solicitar copias vía `POST /sales/{id}/reprint` (con override de supervisor si aplica).
 */
@Component({
  selector: 'pos-ticket-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatProgressSpinnerModule,
    TicketComponent,
  ],
  templateUrl: './pos-ticket-dialog.html',
  styleUrl: './pos-ticket-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosTicketDialog {
  private readonly dialogRef = inject(MatDialogRef<PosTicketDialog, void>);
  private readonly ticketService = inject(TicketService);
  private readonly printer = inject(IframeTicketPrinter);

  /** Datos recibidos al abrir el modal. */
  protected readonly data = inject<PosTicketDialogData>(MAT_DIALOG_DATA);

  /** Instancia renderizada del comprobante para extraer su HTML hacia el `iframe`. */
  private readonly ticketComponent = viewChild(TicketComponent);

  /** Comprobante cargado actualmente (original o copia reimpresa). */
  protected readonly ticket = signal<TicketResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly reprinting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly statusNotice = signal<string | null>(null);
  private readonly pendingPrint = signal(false);

  constructor() {
    effect(() => {
      const shouldPrint = this.pendingPrint();
      const comp = this.ticketComponent();
      if (shouldPrint && comp) {
        this.pendingPrint.set(false);
        this.executePrint(comp);
      }
    });

    void this.loadTicket();
  }

  /** Carga (o recarga) el comprobante original desde `/sales/{id}/ticket`. */
  async loadTicket(): Promise<void> {
    this.loading.set(true);
    this.errorMessage.set(null);
    try {
      const response = await firstValueFrom(
        this.ticketService.getTicket(this.data.saleId, withoutErrorNotification()),
      );
      this.ticket.set(response);
      if (this.data.autoPrint) {
        this.pendingPrint.set(true);
      }
    } catch (err: unknown) {
      this.errorMessage.set(
        extractPosErrorMessage(err, 'No se pudo cargar el comprobante de la venta.'),
      );
    } finally {
      this.loading.set(false);
    }
  }

  /** Imprime manualmente el comprobante actualmente cargado en el `iframe` oculto. */
  printCurrent(): void {
    const comp = this.ticketComponent();
    if (!comp) {
      return;
    }
    this.executePrint(comp);
  }

  /**
   * Solicita una copia (`POST /sales/{id}/reprint`), gestiona override de supervisor si el
   * usuario actual no tiene `sales:receipt:reprint`, actualiza la vista con `COPIA` e imprime.
   */
  async reprintCopy(): Promise<void> {
    if (this.reprinting() || this.loading()) {
      return;
    }

    this.reprinting.set(true);
    this.errorMessage.set(null);
    this.statusNotice.set(null);
    try {
      const reprinted = await firstValueFrom(
        this.ticketService.reprintTicket(this.data.saleId, withoutErrorNotification()),
      );
      this.ticket.set(reprinted);
      this.statusNotice.set('Copia generada y enviada a impresión.');
      this.pendingPrint.set(true);
    } catch (err: unknown) {
      this.errorMessage.set(
        extractPosErrorMessage(
          err,
          'No se pudo reimprimir el comprobante. Verifica la autorización o el límite de copias.',
        ),
      );
    } finally {
      this.reprinting.set(false);
    }
  }

  /** Cierra el diálogo del ticket. */
  close(): void {
    this.dialogRef.close();
  }

  private executePrint(comp: TicketComponent): void {
    const number = this.ticket()?.number ?? this.data.saleNumber ?? String(this.data.saleId);
    const printed = this.printer.printElement(comp.elementRef.nativeElement, `Ticket ${number}`);
    if (!printed) {
      this.errorMessage.set('No fue posible inicializar el documento de impresión.');
    }
  }
}
