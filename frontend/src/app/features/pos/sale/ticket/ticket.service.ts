import { HttpClient, HttpContext } from '@angular/common/http';
import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiConfiguration } from '../../../../api/api-configuration';
import { reprint } from '../../../../api/fn/sales/reprint';
import { ticket } from '../../../../api/fn/sales/ticket';
import { TicketResponse } from '../../../../api/models/ticket-response';
import { withoutErrorNotification } from '../../../../core/http/http-context-tokens';
import {
  OVERRIDE_TOKEN_HEADER,
  SupervisorOverrideService,
} from '../../../../shared/ui/override-dialog';
import { unwrapApiResponse } from '../cart/pos-http.utils';

/**
 * Reglas CSS de impresión térmica 80 mm aplicadas tanto en el documento del `iframe`
 * oculto como en los estilos del componente `TicketComponent` (ADR-28).
 */
export const TICKET_80MM_PAGE_CSS = `
@page {
  size: 80mm auto;
  margin: 0;
}

* {
  box-sizing: border-box;
}

html,
body {
  margin: 0;
  padding: 0;
  background: #ffffff;
  color: #000000;
  font-family: 'Courier New', Courier, monospace;
  font-size: 11px;
  line-height: 1.35;
}

.ticket-sheet {
  width: 76mm;
  max-width: 80mm;
  margin: 0 auto;
  padding: 4mm 3mm;
  color: #000000;
  background: #ffffff;
}

.ticket-center {
  text-align: center;
}

.ticket-bold {
  font-weight: 700;
}

.ticket-copy-banner {
  margin: 4px 0;
  padding: 2px 4px;
  border: 1px dashed #000000;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-align: center;
  text-transform: uppercase;
}

.ticket-divider {
  border: 0;
  border-top: 1px dashed #000000;
  margin: 6px 0;
}

.ticket-meta-row,
.ticket-total-row,
.ticket-payment-row,
.ticket-tax-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.ticket-total-grand {
  font-size: 13px;
  font-weight: 700;
  margin-top: 2px;
}

.ticket-lines {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ticket-line-name {
  font-weight: 700;
  word-break: break-word;
}

.ticket-line-detail {
  display: flex;
  justify-content: space-between;
  gap: 6px;
}

.ticket-line-discount {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
}

.ticket-note-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  text-align: center;
}
`;

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

/**
 * Impresora de tickets mediante `iframe` oculto (`ADR-28`).
 * Evita abrir ventanas emergentes bloqueadas por el navegador e inyecta `@page { size: 80mm auto }`.
 */
@Injectable({ providedIn: 'root' })
export class IframeTicketPrinter {
  private readonly document = inject(DOCUMENT);

  /** Construye el HTML imprimible autocontenido para un ticket de 80 mm. */
  buildPrintDocumentHtml(ticketHtml: string, title = 'Comprobante POS'): string {
    return `<!doctype html>
<html lang="es">
  <head>
    <meta charset="utf-8" />
    <title>${escapeHtml(title)}</title>
    <style>${TICKET_80MM_PAGE_CSS}</style>
  </head>
  <body>${ticketHtml}</body>
</html>`;
  }

  /**
   * Renderiza el contenido de `sourceElement` dentro de un `iframe` oculto y dispara
   * `contentWindow.print()`, removiendo el `iframe` tras finalizar la impresión.
   */
  printElement(sourceElement: HTMLElement, title = 'Comprobante POS'): boolean {
    const body = this.document.body;
    if (!body) {
      return false;
    }

    const iframe = this.document.createElement('iframe');
    iframe.setAttribute('aria-hidden', 'true');
    iframe.setAttribute('data-testid', 'ticket-print-iframe');
    iframe.tabIndex = -1;
    iframe.style.position = 'fixed';
    iframe.style.right = '0';
    iframe.style.bottom = '0';
    iframe.style.width = '0';
    iframe.style.height = '0';
    iframe.style.border = '0';
    iframe.style.visibility = 'hidden';

    body.appendChild(iframe);

    const frameDoc = iframe.contentDocument;
    if (!frameDoc) {
      iframe.remove();
      return false;
    }

    const cleanup = (): void => {
      if (iframe.parentNode) {
        iframe.remove();
      }
    };

    const html = this.buildPrintDocumentHtml(sourceElement.innerHTML, title);
    frameDoc.open();
    frameDoc.write(html);
    frameDoc.close();

    const frameWin = iframe.contentWindow;
    if (frameWin) {
      frameWin.addEventListener?.('afterprint', cleanup, { once: true });
      frameWin.focus?.();
      if (typeof frameWin.print === 'function') {
        frameWin.print();
      }
    }

    setTimeout(cleanup, 1500);
    return true;
  }
}

/**
 * Servicio POS para consultar y reimprimir comprobantes de venta (`GET /sales/{id}/ticket`
 * y `POST /sales/{id}/reprint`).
 */
@Injectable({ providedIn: 'root' })
export class TicketService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);
  private readonly overrideService = inject(SupervisorOverrideService);

  /** Obtiene los datos estructurados del ticket térmico (`GET /sales/{id}/ticket`). */
  getTicket(saleId: number, context?: HttpContext): Observable<TicketResponse> {
    return unwrapApiResponse(ticket(this.http, this.apiConfig.rootUrl, { id: saleId }, context));
  }

  /**
   * Solicita una reimpresión del ticket (`POST /sales/{id}/reprint`).
   * Maneja automáticamente el desafío `422 OVERRIDE_REQUIRED` (`RECEIPT_REPRINT`)
   * abriendo el diálogo de supervisor y reintentando con `X-Override-Token`.
   */
  reprintTicket(saleId: number, context?: HttpContext): Observable<TicketResponse> {
    const safeContext = context ?? withoutErrorNotification();
    return this.overrideService.withSupervisorOverride((headers) => {
      const overrideToken = headers[OVERRIDE_TOKEN_HEADER];
      return unwrapApiResponse(
        reprint(
          this.http,
          this.apiConfig.rootUrl,
          {
            id: saleId,
            ...(overrideToken ? { 'X-Override-Token': overrideToken } : {}),
          },
          safeContext,
        ),
      );
    });
  }
}
