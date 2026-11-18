import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, inject, input } from '@angular/core';
import { SalePaymentResponse } from '../../../../api/models/sale-payment-response';
import { TicketResponse } from '../../../../api/models/ticket-response';
import { ClpPipe, QtyPipe, RutPipe } from '../../../../shared/pipes';
import { paymentMethodLabel } from '../payment/payment.models';

/**
 * Componente presentacional de boleta/comprobante térmico de 80 mm (`HU-POS-20`, `ADR-28`).
 * Mantiene reglas de `@page { size: 80mm auto }` y sirve tanto para vista previa como para
 * fuente HTML del `iframe` de impresión.
 */
@Component({
  selector: 'pos-ticket',
  imports: [ClpPipe, DatePipe, QtyPipe, RutPipe],
  templateUrl: './ticket.component.html',
  styleUrl: './ticket.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TicketComponent {
  /** Referencia DOM del comprobante usada por `IframeTicketPrinter`. */
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Datos completos del ticket entregados por `GET /sales/{id}/ticket` o `POST /sales/{id}/reprint`. */
  readonly ticket = input.required<TicketResponse>();

  /** Devuelve la etiqueta legible en español del método de pago del comprobante. */
  protected methodLabel(payment: SalePaymentResponse): string {
    return paymentMethodLabel(payment.method);
  }

  /** Formatea comuna y ciudad de la sucursal evitando referencias globales en la plantilla. */
  protected formatLocation(commune?: string, city?: string): string {
    return [commune, city].filter((part): part is string => Boolean(part)).join(', ');
  }
}
