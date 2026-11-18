import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { firstValueFrom } from 'rxjs';
import { SuspendedSaleResponse } from '../../../../api/models/suspended-sale-response';
import { withoutErrorNotification } from '../../../../core/http/http-context-tokens';
import { ClpPipe } from '../../../../shared/pipes';
import { ConfirmDialogService } from '../../../../shared/ui/confirm-dialog';
import { extractPosErrorMessage } from '../cart/pos-http.utils';
import { SuspendedSaleService } from './suspended-sale.service';

/** Datos de entrada para {@link PosSuspendedListDialog}. */
export interface PosSuspendedListDialogData {
  readonly hasActiveCart: boolean;
}

/**
 * Diálogo modal para listar, retomar o eliminar ventas suspendidas en la terminal (`F9`,
 * `HU-POS-04`).
 */
@Component({
  selector: 'pos-suspended-list-dialog',
  imports: [ClpPipe, MatButtonModule, MatDialogModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './pos-suspended-list-dialog.html',
  styleUrl: './pos-suspended-list-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosSuspendedListDialog implements OnInit {
  protected readonly data = inject<PosSuspendedListDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef =
    inject<MatDialogRef<PosSuspendedListDialog, SuspendedSaleResponse | undefined>>(MatDialogRef);
  private readonly suspendedService = inject(SuspendedSaleService);
  private readonly confirmDialog = inject(ConfirmDialogService);

  protected readonly loading = signal(true);
  protected readonly busyId = signal<number | null>(null);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly items = signal<readonly SuspendedSaleResponse[]>([]);

  private readonly timeFormatter = new Intl.DateTimeFormat('es-CL', {
    dateStyle: 'short',
    timeStyle: 'short',
  });

  ngOnInit(): void {
    void this.loadList();
  }

  protected async loadList(): Promise<void> {
    this.loading.set(true);
    this.errorMessage.set(null);
    try {
      const list = await firstValueFrom(this.suspendedService.list(withoutErrorNotification()));
      this.items.set(list);
    } catch (error: unknown) {
      this.errorMessage.set(
        extractPosErrorMessage(error, 'No se pudieron cargar las ventas suspendidas.'),
      );
    } finally {
      this.loading.set(false);
    }
  }

  protected async resumeSale(item: SuspendedSaleResponse): Promise<void> {
    if (!item.id || this.busyId() !== null) {
      return;
    }

    if (this.data.hasActiveCart) {
      const confirmed = await firstValueFrom(
        this.confirmDialog.confirm({
          title: 'Reemplazar carrito actual',
          message:
            'El carrito en curso tiene productos sin cobrar. Si retomas esta venta suspendida se reemplazará el carrito actual.',
          confirmLabel: 'Retomar venta',
          cancelLabel: 'Seguir editando',
          destructive: true,
        }),
      );
      if (!confirmed) {
        return;
      }
    }

    this.busyId.set(item.id);
    this.errorMessage.set(null);
    try {
      const resumed = await firstValueFrom(
        this.suspendedService.resume(item.id, withoutErrorNotification()),
      );
      this.dialogRef.close(resumed);
    } catch (error: unknown) {
      this.errorMessage.set(extractPosErrorMessage(error, 'No se pudo retomar la venta.'));
    } finally {
      this.busyId.set(null);
    }
  }

  protected async deleteSale(item: SuspendedSaleResponse): Promise<void> {
    if (!item.id || this.busyId() !== null) {
      return;
    }

    const label = item.label ? `"${item.label}"` : `#${item.id}`;
    const confirmed = await firstValueFrom(
      this.confirmDialog.confirm({
        title: 'Eliminar venta suspendida',
        message: `¿Deseas eliminar la venta suspendida ${label}? Esta acción no se puede deshacer.`,
        confirmLabel: 'Eliminar',
        cancelLabel: 'Cancelar',
        destructive: true,
      }),
    );
    if (!confirmed) {
      return;
    }

    this.busyId.set(item.id);
    this.errorMessage.set(null);
    try {
      await firstValueFrom(this.suspendedService.delete(item.id, withoutErrorNotification()));
      this.items.update((prev) => prev.filter((entry) => entry.id !== item.id));
    } catch (error: unknown) {
      this.errorMessage.set(
        extractPosErrorMessage(error, 'No se pudo eliminar la venta suspendida.'),
      );
    } finally {
      this.busyId.set(null);
    }
  }

  protected formatTimestamp(iso: string | undefined): string {
    if (!iso) {
      return '—';
    }
    const date = new Date(iso);
    return Number.isNaN(date.getTime()) ? iso : this.timeFormatter.format(date);
  }
}
