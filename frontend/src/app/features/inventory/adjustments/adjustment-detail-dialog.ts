import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { StockAdjustmentLineResponse } from '../../../api/models/stock-adjustment-line-response';
import { StockAdjustmentResponse } from '../../../api/models/stock-adjustment-response';
import { ClpPipe, QtyPipe } from '../../../shared/pipes';
import {
  adjustmentReasonLabel,
  adjustmentStatusLabel,
  formatAdjustmentDateTime,
  formatAdjustmentUnitCost,
  formatUserRef,
} from './adjustments.models';

/** Datos de entrada para {@link AdjustmentDetailDialog}. */
export interface AdjustmentDetailDialogData {
  readonly adjustment: StockAdjustmentResponse;
  readonly canReadCosts: boolean;
  readonly canApprove: boolean;
  readonly currentUserId: number | null;
}

/** Acción elegida desde el diálogo de detalle cuando el ajuste está pendiente. */
export type AdjustmentDetailDialogResult = 'approve' | 'reject' | undefined;

/**
 * Diálogo de solo lectura con el detalle completo de un ajuste de stock y sus líneas (`HU-INV-05`).
 *
 * Muestra motivo, estado, trazabilidad de solicitante y aprobador, impacto económico (solo si el
 * usuario tiene permiso de costos/aprobación) y accesos directos para aprobar o rechazar respetando
 * la segregación de funciones (`RN-22`).
 */
@Component({
  selector: 'pos-adjustment-detail-dialog',
  imports: [ClpPipe, MatButtonModule, MatDialogModule, MatIconModule, MatTableModule, QtyPipe],
  template: `
    <h2 mat-dialog-title>Ajuste {{ data.adjustment.number || '#' + data.adjustment.id }}</h2>

    <mat-dialog-content class="detail-content">
      <div class="meta-grid">
        <div class="meta-item">
          <span class="meta-label">Estado</span>
          <span
            class="badge"
            [class.badge--pending]="data.adjustment.status === 'PENDING_APPROVAL'"
            [class.badge--approved]="data.adjustment.status === 'APPROVED'"
            [class.badge--rejected]="data.adjustment.status === 'REJECTED'"
          >
            {{ statusLabel }}
          </span>
        </div>

        <div class="meta-item">
          <span class="meta-label">Motivo</span>
          <strong>{{ reasonLabel }}</strong>
        </div>

        <div class="meta-item">
          <span class="meta-label">Solicitado por</span>
          <span>{{ requestedByLabel }}</span>
        </div>

        <div class="meta-item">
          <span class="meta-label">Fecha de solicitud</span>
          <span>{{ createdAtLabel }}</span>
        </div>

        @if (data.adjustment.approvedBy) {
          <div class="meta-item">
            <span class="meta-label">
              {{ data.adjustment.status === 'REJECTED' ? 'Rechazado por' : 'Aprobado por' }}
            </span>
            <span>{{ approvedByLabel }}</span>
          </div>

          <div class="meta-item">
            <span class="meta-label">Fecha resolución</span>
            <span>{{ approvedAtLabel }}</span>
          </div>
        }

        @if (showCosts()) {
          <div class="meta-item">
            <span class="meta-label">Impacto neto en costo</span>
            <strong>{{ data.adjustment.totalCostImpact | clp }}</strong>
          </div>
        }
      </div>

      @if (data.adjustment.notes) {
        <div class="note-box">
          <span class="meta-label">Observaciones</span>
          <p>{{ data.adjustment.notes }}</p>
        </div>
      }

      @if (data.adjustment.status === 'REJECTED' && data.adjustment.rejectionReason) {
        <div class="rejection-box" role="status">
          <span class="meta-label">Motivo del rechazo</span>
          <p>{{ data.adjustment.rejectionReason }}</p>
        </div>
      }

      @if (isPending() && data.canApprove && isOwnRequest()) {
        <p class="sod-notice" role="status">
          <mat-icon aria-hidden="true">info</mat-icon>
          <span
            >Segregación de funciones: quien solicita el ajuste no puede aprobarlo ni
            rechazarlo.</span
          >
        </p>
      }

      <div class="lines-section">
        <h3>Líneas ({{ lines.length }})</h3>
        <div class="table-scroll">
          <table mat-table [dataSource]="lines" class="lines-table">
            <ng-container matColumnDef="sku">
              <th mat-header-cell *matHeaderCellDef>SKU</th>
              <td mat-cell *matCellDef="let line">{{ line.sku || '—' }}</td>
            </ng-container>

            <ng-container matColumnDef="name">
              <th mat-header-cell *matHeaderCellDef>Producto</th>
              <td mat-cell *matCellDef="let line">{{ line.name || '—' }}</td>
            </ng-container>

            <ng-container matColumnDef="lotNumber">
              <th mat-header-cell *matHeaderCellDef>Lote</th>
              <td mat-cell *matCellDef="let line">{{ line.lotNumber || 'Sin lote' }}</td>
            </ng-container>

            <ng-container matColumnDef="quantityDelta">
              <th mat-header-cell *matHeaderCellDef class="align-end">Variación</th>
              <td mat-cell *matCellDef="let line" class="align-end">
                <span
                  class="delta"
                  [class.delta--in]="(line.quantityDelta ?? 0) > 0"
                  [class.delta--out]="(line.quantityDelta ?? 0) < 0"
                >
                  {{ (line.quantityDelta ?? 0) > 0 ? '+' : '' }}{{ line.quantityDelta | qty }}
                </span>
              </td>
            </ng-container>

            @if (showCosts()) {
              <ng-container matColumnDef="unitCost">
                <th mat-header-cell *matHeaderCellDef class="align-end">Costo unitario</th>
                <td mat-cell *matCellDef="let line" class="align-end">
                  {{ formatUnitCost(line.unitCost) }}
                </td>
              </ng-container>
            }

            <ng-container matColumnDef="movement">
              <th mat-header-cell *matHeaderCellDef class="align-end">Kardex</th>
              <td mat-cell *matCellDef="let line" class="align-end">
                {{ line.stockMovementId !== undefined ? '#' + line.stockMovementId : '—' }}
              </td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns()"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns()"></tr>
          </table>
        </div>
      </div>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      @if (canResolve()) {
        <button mat-stroked-button type="button" (click)="closeWith('reject')">Rechazar</button>
        <button mat-flat-button type="button" (click)="closeWith('approve')">Aprobar</button>
      }
      <button mat-button type="button" [mat-dialog-close]="undefined" cdkFocusInitial>
        Cerrar
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .detail-content {
      display: flex;
      flex-direction: column;
      gap: 16px;
      min-width: min(700px, 85vw);
      padding-top: 8px;
    }
    .meta-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
      padding: 12px;
      border-radius: 10px;
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .meta-item {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .meta-label {
      font-size: 0.78rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .note-box,
    .rejection-box {
      padding: 10px 12px;
      border-radius: 8px;
      border: 1px solid var(--mat-sys-outline-variant);

      p {
        margin: 4px 0 0;
        white-space: pre-line;
      }
    }
    .rejection-box {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      border-color: transparent;

      .meta-label {
        color: inherit;
        opacity: 0.85;
      }
    }
    .sod-notice {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      padding: 8px 12px;
      border-radius: 8px;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
      font-size: 0.875rem;
    }
    .lines-section h3 {
      margin: 0 0 8px;
      font-size: 0.95rem;
      font-weight: 500;
    }
    .table-scroll {
      overflow-x: auto;
    }
    .lines-table {
      width: 100%;
    }
    .align-end {
      text-align: right;
    }
    .delta {
      font-weight: 600;

      &--in {
        color: var(--mat-sys-primary);
      }
      &--out {
        color: var(--mat-sys-error);
      }
    }
    .badge {
      display: inline-flex;
      width: fit-content;
      align-items: center;
      padding: 2px 8px;
      border-radius: 999px;
      font-size: 0.75rem;
      font-weight: 500;
      background: var(--mat-sys-surface-container-high);

      &--pending {
        background: var(--mat-sys-tertiary-container);
        color: var(--mat-sys-on-tertiary-container);
      }
      &--approved {
        background: var(--mat-sys-primary-container);
        color: var(--mat-sys-on-primary-container);
      }
      &--rejected {
        background: var(--mat-sys-error-container);
        color: var(--mat-sys-on-error-container);
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdjustmentDetailDialog {
  protected readonly data = inject<AdjustmentDetailDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(
    MatDialogRef<AdjustmentDetailDialog, AdjustmentDetailDialogResult>,
  );

  protected readonly lines: readonly StockAdjustmentLineResponse[] =
    this.data.adjustment.lines ?? [];
  protected readonly statusLabel = adjustmentStatusLabel(this.data.adjustment.status);
  protected readonly reasonLabel = adjustmentReasonLabel(this.data.adjustment.reasonCode);
  protected readonly requestedByLabel = formatUserRef(this.data.adjustment.requestedBy);
  protected readonly approvedByLabel = formatUserRef(this.data.adjustment.approvedBy);
  protected readonly createdAtLabel = formatAdjustmentDateTime(this.data.adjustment.createdAt);
  protected readonly approvedAtLabel = formatAdjustmentDateTime(this.data.adjustment.approvedAt);

  protected readonly showCosts = computed(
    () =>
      this.data.canReadCosts &&
      this.data.adjustment.totalCostImpact !== undefined &&
      this.data.adjustment.totalCostImpact !== null,
  );
  protected readonly isPending = computed(() => this.data.adjustment.status === 'PENDING_APPROVAL');
  protected readonly isOwnRequest = computed(
    () =>
      this.data.currentUserId !== null &&
      this.data.adjustment.requestedBy?.id === this.data.currentUserId,
  );
  protected readonly canResolve = computed(
    () => this.isPending() && this.data.canApprove && !this.isOwnRequest(),
  );

  protected readonly displayedColumns = computed<readonly string[]>(() => {
    const cols = ['sku', 'name', 'lotNumber', 'quantityDelta'];
    if (this.showCosts()) {
      cols.push('unitCost');
    }
    cols.push('movement');
    return cols;
  });

  protected formatUnitCost(value: number | undefined): string {
    return formatAdjustmentUnitCost(value);
  }

  protected closeWith(action: 'approve' | 'reject'): void {
    this.dialogRef.close(action);
  }
}
