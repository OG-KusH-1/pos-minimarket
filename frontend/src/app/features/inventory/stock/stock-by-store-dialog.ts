import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { StockByStoreResponse } from '../../../api/models/stock-by-store-response';
import { StockResponse } from '../../../api/models/stock-response';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { QtyPipe } from '../../../shared/pipes';
import { toQtyUnit } from './stock.models';
import { StockService } from './stock.service';

/** Datos de entrada para {@link StockByStoreDialog}. */
export interface StockByStoreDialogData {
  readonly item: StockResponse;
}

/**
 * Diálogo de solo lectura que muestra las existencias de un producto desglosadas por los
 * locales asignados al usuario, la cantidad en tránsito y el total de empresa (`HU-INV-01`).
 */
@Component({
  selector: 'pos-stock-by-store-dialog',
  imports: [MatButtonModule, MatDialogModule, MatProgressBarModule, MatTableModule, QtyPipe],
  template: `
    <h2 mat-dialog-title>Stock por local</h2>
    <mat-dialog-content class="content">
      <div class="product-header">
        <strong>{{ data.item.name }}</strong>
        <span class="sku">SKU: {{ data.item.sku }}</span>
      </div>

      @if (loading()) {
        <mat-progress-bar mode="indeterminate" aria-label="Cargando stock por local" />
      } @else if (error(); as err) {
        <div class="error-box" role="alert">
          <span>{{ err }}</span>
          <button mat-stroked-button type="button" (click)="load()">Reintentar</button>
        </div>
      } @else if (detail(); as info) {
        <div class="totals-row">
          <div class="total-card">
            <span class="total-label">Total empresa</span>
            <strong class="total-value">{{ info.companyQuantity | qty: unit }}</strong>
          </div>
          <div class="total-card">
            <span class="total-label">En tránsito (entrante)</span>
            <strong class="total-value">{{ info.inTransit | qty: unit }}</strong>
          </div>
        </div>

        @if ((info.stores?.length ?? 0) === 0) {
          <p class="empty" role="status">No hay locales asignados con registro de stock.</p>
        } @else {
          <table
            mat-table
            [dataSource]="info.stores ?? []"
            class="stores-table"
            aria-label="Stock por local asignado"
          >
            <ng-container matColumnDef="storeName">
              <th mat-header-cell *matHeaderCellDef>Local</th>
              <td mat-cell *matCellDef="let s">
                <span>{{ s.storeName }}</span>
                @if (s.storeId === activeStoreId()) {
                  <span class="active-badge">Local activo</span>
                }
              </td>
            </ng-container>
            <ng-container matColumnDef="quantity">
              <th mat-header-cell *matHeaderCellDef class="align-end">Stock</th>
              <td mat-cell *matCellDef="let s" class="align-end">{{ s.quantity | qty: unit }}</td>
            </ng-container>
            <ng-container matColumnDef="minQty">
              <th mat-header-cell *matHeaderCellDef class="align-end">Mínimo</th>
              <td mat-cell *matCellDef="let s" class="align-end">
                {{ s.minQty !== undefined && s.minQty !== null ? (s.minQty | qty: unit) : '—' }}
              </td>
            </ng-container>
            <ng-container matColumnDef="maxQty">
              <th mat-header-cell *matHeaderCellDef class="align-end">Máximo</th>
              <td mat-cell *matCellDef="let s" class="align-end">
                {{ s.maxQty !== undefined && s.maxQty !== null ? (s.maxQty | qty: unit) : '—' }}
              </td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
          </table>
        }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="true">Cerrar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content {
      min-width: 480px;
      max-height: 65vh;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .product-header {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .sku {
      font-size: 0.85rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .totals-row {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
    }
    .total-card {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 10px 12px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .total-label {
      font-size: 0.8rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .total-value {
      font-size: 1.1rem;
    }
    .stores-table {
      width: 100%;
    }
    .align-end {
      text-align: right;
    }
    .active-badge {
      display: inline-block;
      margin-left: 8px;
      padding: 1px 8px;
      border-radius: 999px;
      font-size: 0.72rem;
      font-weight: 500;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .error-box {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 12px;
      border-radius: 8px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .empty {
      margin: 0;
      padding: 16px;
      text-align: center;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StockByStoreDialog implements OnInit {
  protected readonly data = inject<StockByStoreDialogData>(MAT_DIALOG_DATA);
  private readonly stockService = inject(StockService);
  private readonly storeContext = inject(StoreContextService);

  protected readonly unit = toQtyUnit(this.data.item.unitOfMeasure);
  protected readonly activeStoreId = this.storeContext.storeId;
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly detail = signal<StockByStoreResponse | null>(null);

  protected readonly displayedColumns = ['storeName', 'quantity', 'minQty', 'maxQty'] as const;

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    const productId = this.data.item.productId;
    if (productId === undefined) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);

    this.stockService.getStockByStore(productId, withoutErrorNotification()).subscribe({
      next: (res) => {
        this.detail.set(res);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(isApiError(err) ? err.message : 'No se pudo consultar el stock por local.');
        this.loading.set(false);
      },
    });
  }
}
