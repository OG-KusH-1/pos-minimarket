import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AuditLogResponse } from '../../../api/models/audit-log-response';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import {
  auditActionLabel,
  auditEntityTypeLabel,
  computeAuditDiff,
  formatAuditDateTime,
  formatAuditJson,
} from './audit.models';
import { AuditService } from './audit.service';

/** Datos de entrada para {@link AuditDetailDialog}. */
export interface AuditDetailDialogData {
  readonly logId: number;
  readonly storeNames?: ReadonlyMap<number, string>;
}

/**
 * Diálogo de solo lectura con el detalle completo de un evento de auditoría: metadatos de contexto,
 * hash SHA-256 y comparación antes/después (`oldValue` / `newValue`) (`HU-SEG-08`).
 */
@Component({
  selector: 'pos-audit-detail-dialog',
  imports: [MatButtonModule, MatDialogModule, MatIconModule, MatProgressBarModule],
  templateUrl: './audit-detail-dialog.html',
  styleUrl: './audit-detail-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuditDetailDialog implements OnInit {
  protected readonly data = inject<AuditDetailDialogData>(MAT_DIALOG_DATA);
  private readonly auditService = inject(AuditService);

  protected readonly entry = signal<AuditLogResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly diffEntries = computed(() => {
    const current = this.entry();
    if (!current) {
      return [];
    }
    return computeAuditDiff(current.oldValue as unknown, current.newValue as unknown);
  });

  protected readonly oldJson = computed(() => formatAuditJson(this.entry()?.oldValue as unknown));

  protected readonly newJson = computed(() => formatAuditJson(this.entry()?.newValue as unknown));

  ngOnInit(): void {
    this.loadEntry();
  }

  protected loadEntry(): void {
    this.loading.set(true);
    this.error.set(null);
    this.auditService.getLog(this.data.logId, withoutErrorNotification()).subscribe({
      next: (res) => {
        this.entry.set(res);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(
          err instanceof Error ? err.message : 'No se pudo cargar el registro de auditoría.',
        );
        this.loading.set(false);
      },
    });
  }

  protected actionLabel(action?: string): string {
    return auditActionLabel(action);
  }

  protected entityTypeLabel(entityType?: string): string {
    return auditEntityTypeLabel(entityType);
  }

  protected formatDateTime(iso?: string): string {
    return formatAuditDateTime(iso);
  }

  protected formatStore(storeId?: number): string {
    if (storeId === undefined || storeId === null) {
      return 'Global';
    }
    return this.data.storeNames?.get(storeId) ?? `Local #${storeId}`;
  }

  protected formatUser(entry: AuditLogResponse): string {
    if (entry.username && entry.userId !== undefined) {
      return `${entry.username} (#${entry.userId})`;
    }
    if (entry.username) {
      return entry.username;
    }
    if (entry.userId !== undefined) {
      return `Usuario #${entry.userId}`;
    }
    return 'Sistema';
  }
}
