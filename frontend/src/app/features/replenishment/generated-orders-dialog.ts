import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatListModule } from '@angular/material/list';
import { GeneratedPurchaseOrder } from '../../api/models/generated-purchase-order';
import { ClpPipe } from '../../shared/pipes';

/** Datos de apertura del diálogo de borradores generados desde una corrida. */
export interface GeneratedOrdersDialogData {
  /** Órdenes creadas (una por proveedor), según respondió `POST /runs/{id}/purchase-orders`. */
  readonly orders: readonly GeneratedPurchaseOrder[];
}

/**
 * Diálogo de resultado de la generación de borradores de OC (`HU-PRV-10`): muestra las OC
 * `DRAFT` creadas por proveedor con su folio, cantidad de líneas y total neto.
 */
@Component({
  selector: 'pos-generated-orders-dialog',
  imports: [ClpPipe, MatButtonModule, MatDialogModule, MatListModule],
  template: `
    <h2 mat-dialog-title>Borradores de OC generados</h2>
    <mat-dialog-content>
      @if (data.orders.length > 0) {
        <mat-list>
          @for (order of data.orders; track order.id) {
            <mat-list-item>
              <span matListItemTitle>
                {{ order.number ?? 'OC #' + order.id }} — {{ order.supplierName ?? 'Proveedor' }}
              </span>
              <span matListItemLine>
                {{ order.linesCount ?? 0 }} línea(s) · {{ order.netTotal ?? 0 | clp }} neto
              </span>
            </mat-list-item>
          }
        </mat-list>
      } @else {
        <p>No se generaron órdenes de compra (no había líneas con proveedor por agrupar).</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-flat-button type="button" [mat-dialog-close]="true">Aceptar</button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GeneratedOrdersDialog {
  readonly data = inject<GeneratedOrdersDialogData>(MAT_DIALOG_DATA);
}
