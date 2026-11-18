import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { ShrinkageResponse } from '../../api/models/shrinkage-response';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { ClpPipe, QtyPipe } from '../../shared/pipes';
import {
  extractFileIdFromUrl,
  formatShrinkageDateTime,
  formatShrinkageUnitCost,
  formatShrinkageUserRef,
  isSystemicShrinkage,
  shrinkageStatusLabel,
  shrinkageTypeLabel,
} from './shrinkage.models';
import { ShrinkageService } from './shrinkage.service';

/** Datos de entrada para {@link ShrinkageDetailDialog}. */
export interface ShrinkageDetailDialogData {
  readonly shrinkage: ShrinkageResponse;
  readonly canReadCosts: boolean;
  readonly canApprove: boolean;
  readonly currentUserId: number | null;
}

/** Acción elegida desde el diálogo de detalle cuando la merma está pendiente. */
export type ShrinkageDetailDialogResult = 'approve' | 'reject' | undefined;

/**
 * Diálogo de solo lectura con el detalle completo de una merma (`HU-MER-01..03`).
 *
 * Muestra tipo, origen sistémico (`affectsStock = false`), producto, lote, cantidad, costos (solo
 * con `shrinkage:record:approve` o `catalog:cost:read`), descarga segura de evidencia, trazabilidad
 * de usuarios y accesos directos para aprobar o rechazar respetando la segregación de funciones.
 */
@Component({
  selector: 'pos-shrinkage-detail-dialog',
  imports: [ClpPipe, MatButtonModule, MatDialogModule, MatIconModule, QtyPipe],
  template: `
    <h2 mat-dialog-title>Merma {{ data.shrinkage.number || '#' + data.shrinkage.id }}</h2>

    <mat-dialog-content class="detail-content">
      <div class="meta-grid">
        <div class="meta-item">
          <span class="meta-label">Estado</span>
          <div class="badges-row">
            <span
              class="badge"
              [class.badge--pending]="data.shrinkage.status === 'PENDING_APPROVAL'"
              [class.badge--approved]="data.shrinkage.status === 'APPROVED'"
              [class.badge--rejected]="data.shrinkage.status === 'REJECTED'"
            >
              {{ statusLabel }}
            </span>
            @if (data.shrinkage.autoApproved) {
              <span class="badge badge--info">Autoaprobada</span>
            }
            @if (systemic()) {
              <span class="badge badge--systemic">Sistémica · Sin movimiento de stock</span>
            }
          </div>
        </div>

        <div class="meta-item">
          <span class="meta-label">Tipo de merma</span>
          <strong>{{ typeLabel }}</strong>
        </div>

        <div class="meta-item">
          <span class="meta-label">Producto</span>
          <strong>
            {{ data.shrinkage.product?.name || '—' }}
            @if (data.shrinkage.product?.sku; as sku) {
              ({{ sku }})
            }
          </strong>
        </div>

        <div class="meta-item">
          <span class="meta-label">Lote</span>
          <span>
            {{ data.shrinkage.lot?.lotNumber || 'Sin lote' }}
            @if (data.shrinkage.lot?.expiryDate; as expiryDate) {
              · Vence: {{ expiryDate }}
            }
          </span>
        </div>

        <div class="meta-item">
          <span class="meta-label">Cantidad</span>
          <strong>{{ data.shrinkage.quantity | qty }}</strong>
        </div>

        <div class="meta-item">
          <span class="meta-label">Fecha de ocurrencia</span>
          <span>{{ occurredAtLabel }}</span>
        </div>

        <div class="meta-item">
          <span class="meta-label">Reportado por</span>
          <span>{{ reportedByLabel }}</span>
        </div>

        <div class="meta-item">
          <span class="meta-label">Responsable</span>
          <span>{{ responsibleLabel }}</span>
        </div>

        @if (data.shrinkage.approvedBy) {
          <div class="meta-item">
            <span class="meta-label">
              {{ data.shrinkage.status === 'REJECTED' ? 'Rechazado por' : 'Aprobado por' }}
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
            <span class="meta-label">Costo unitario</span>
            <span>{{ unitCostLabel }}</span>
          </div>

          <div class="meta-item">
            <span class="meta-label">Costo total</span>
            <strong>{{
              data.shrinkage.totalCost !== undefined && data.shrinkage.totalCost !== null
                ? (data.shrinkage.totalCost | clp)
                : '—'
            }}</strong>
          </div>
        }
      </div>

      @if (data.shrinkage.reason) {
        <div class="note-box">
          <span class="meta-label">Motivo / notas</span>
          <p>{{ data.shrinkage.reason }}</p>
        </div>
      }

      @if (data.shrinkage.status === 'REJECTED' && data.shrinkage.rejectionReason) {
        <div class="rejection-box" role="status">
          <span class="meta-label">Motivo del rechazo</span>
          <p>{{ data.shrinkage.rejectionReason }}</p>
        </div>
      }

      @if (evidenceFileId(); as fileId) {
        <div class="evidence-row">
          <button
            mat-stroked-button
            type="button"
            (click)="openEvidence(fileId)"
            [disabled]="loadingEvidence()"
          >
            <mat-icon>attach_file</mat-icon>
            {{ loadingEvidence() ? 'Descargando evidencia...' : 'Ver evidencia adjunta' }}
          </button>
          @if (evidenceError(); as err) {
            <span class="evidence-error" role="alert">{{ err }}</span>
          }
        </div>
      }

      @if (isPending() && data.canApprove && isOwnReport()) {
        <p class="sod-notice" role="status">
          <mat-icon aria-hidden="true">info</mat-icon>
          <span>
            Segregación de funciones: quien reporta la merma no puede aprobarla ni rechazarla.
          </span>
        </p>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      @if (isPending() && data.canApprove) {
        <button
          mat-stroked-button
          type="button"
          class="reject-btn"
          [disabled]="isOwnReport()"
          (click)="dialogRef.close('reject')"
        >
          <mat-icon>cancel</mat-icon>
          Rechazar
        </button>
        <button
          mat-flat-button
          type="button"
          [disabled]="isOwnReport()"
          (click)="dialogRef.close('approve')"
        >
          <mat-icon>check_circle</mat-icon>
          Aprobar
        </button>
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
      min-width: min(560px, 88vw);
      padding-top: 8px;
    }
    .meta-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
    }
    .meta-item {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .meta-label {
      font-size: 0.75rem;
      color: var(--mat-sys-on-surface-variant);
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }
    .badges-row {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 999px;
      font-size: 0.75rem;
      font-weight: 500;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface);
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
      &--systemic {
        background: var(--mat-sys-secondary-container);
        color: var(--mat-sys-on-secondary-container);
      }
      &--info {
        background: var(--mat-sys-surface-container-highest);
        color: var(--mat-sys-on-surface-variant);
      }
    }
    .note-box,
    .rejection-box {
      padding: 10px 12px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-low);
      p {
        margin: 4px 0 0;
        white-space: pre-wrap;
      }
    }
    .rejection-box {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .evidence-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
    }
    .evidence-error {
      font-size: 0.85rem;
      color: var(--mat-sys-error);
    }
    .sod-notice {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      padding: 8px 12px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container);
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.875rem;
    }
    .reject-btn {
      color: var(--mat-sys-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShrinkageDetailDialog {
  protected readonly data = inject<ShrinkageDetailDialogData>(MAT_DIALOG_DATA);
  protected readonly dialogRef = inject(
    MatDialogRef<ShrinkageDetailDialog, ShrinkageDetailDialogResult>,
  );
  private readonly shrinkageService = inject(ShrinkageService);

  protected readonly loadingEvidence = signal(false);
  protected readonly evidenceError = signal<string | null>(null);

  protected readonly statusLabel = shrinkageStatusLabel(this.data.shrinkage.status);
  protected readonly typeLabel = shrinkageTypeLabel(this.data.shrinkage.shrinkageType);
  protected readonly occurredAtLabel = formatShrinkageDateTime(this.data.shrinkage.occurredAt);
  protected readonly approvedAtLabel = formatShrinkageDateTime(this.data.shrinkage.approvedAt);
  protected readonly reportedByLabel = formatShrinkageUserRef(this.data.shrinkage.reportedBy);
  protected readonly responsibleLabel = formatShrinkageUserRef(this.data.shrinkage.responsible);
  protected readonly approvedByLabel = formatShrinkageUserRef(this.data.shrinkage.approvedBy);
  protected readonly unitCostLabel = formatShrinkageUnitCost(this.data.shrinkage.unitCost);

  protected readonly systemic = computed(() => isSystemicShrinkage(this.data.shrinkage));
  protected readonly isPending = computed(() => this.data.shrinkage.status === 'PENDING_APPROVAL');
  protected readonly isOwnReport = computed(
    () =>
      this.data.currentUserId !== null &&
      this.data.shrinkage.reportedBy?.id === this.data.currentUserId,
  );
  protected readonly showCosts = computed(
    () =>
      this.data.canReadCosts &&
      (this.data.shrinkage.totalCost !== undefined || this.data.shrinkage.unitCost !== undefined),
  );
  protected readonly evidenceFileId = computed(() =>
    extractFileIdFromUrl(this.data.shrinkage.evidenceUrl),
  );

  protected openEvidence(fileId: number): void {
    this.loadingEvidence.set(true);
    this.evidenceError.set(null);

    this.shrinkageService.downloadEvidence(fileId, withoutErrorNotification()).subscribe({
      next: (blob) => {
        this.loadingEvidence.set(false);
        const objectUrl = URL.createObjectURL(blob);
        window.open(objectUrl, '_blank', 'noopener');
        setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
      },
      error: () => {
        this.loadingEvidence.set(false);
        this.evidenceError.set('No se pudo abrir la evidencia adjunta.');
      },
    });
  }
}
