import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, filter, switchMap } from 'rxjs';
import { StockTransferResponse } from '../../../api/models/stock-transfer-response';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PageResponse } from '../../../shared/models/page-response';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { TransferCancelDialog, TransferCancelDialogData } from './transfer-cancel-dialog';
import {
  TransferDetailDialog,
  TransferDetailDialogData,
  TransferDetailDialogResult,
} from './transfer-detail-dialog';
import { TransferFormDialog, TransferFormDialogData } from './transfer-form-dialog';
import { TransferReceiveDialog, TransferReceiveDialogData } from './transfer-receive-dialog';
import {
  TRANSFER_DIRECTION_OPTIONS,
  TRANSFER_STATUS_OPTIONS,
  TransferDirectionCode,
  TransferStatusCode,
  formatStoreRef,
  formatTransferDateTime,
  isTransferDestination,
  isTransferOrigin,
  transferDirectionLabel,
  transferHasDifferences,
  transferStatusLabel,
} from './transfers.models';
import { TransfersService } from './transfers.service';

/**
 * Pantalla de transferencias de stock entre locales (`HU-INV-07`).
 *
 * Permite listar transferencias entrantes y salientes del local activo, crear y editar borradores
 * con lote específico o salida FEFO automática, despachar desde origen (`TRANSFER_OUT`),
 * recepcionar en destino (`TRANSFER_IN`) registrando diferencias y motivos, y cancelar borradores.
 */
@Component({
  selector: 'pos-transfers',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './transfers.html',
  styleUrl: './transfers.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Transfers implements OnInit, OnDestroy {
  private readonly transfersService = inject(TransfersService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly directionTpl =
    viewChild.required<TemplateRef<CellContext<StockTransferResponse>>>('directionTpl');
  private readonly linesTpl =
    viewChild.required<TemplateRef<CellContext<StockTransferResponse>>>('linesTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<StockTransferResponse>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<StockTransferResponse>>>('actionsTpl');

  protected readonly directionOptions = TRANSFER_DIRECTION_OPTIONS;
  protected readonly statusOptions = TRANSFER_STATUS_OPTIONS;
  protected readonly activeStoreId = this.storeContext.storeId;
  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );

  protected readonly canSend = computed(() => this.auth.hasPermission('inventory:transfer:send'));
  protected readonly canReceive = computed(() =>
    this.auth.hasPermission('inventory:transfer:receive'),
  );
  protected readonly canCancel = computed(() =>
    this.auth.hasPermission('inventory:transfer:cancel'),
  );

  protected readonly filterError = signal<string | null>(null);

  protected readonly filterForm = this.fb.group({
    direction: [null as TransferDirectionCode | null],
    status: [null as TransferStatusCode | null],
    from: [''],
    to: [''],
  });

  protected readonly dataSource = new PaginatedDataSource<StockTransferResponse>(
    (params) =>
      this.transfersService.listTransfers(
        {
          direction: (params.get('direction') as TransferDirectionCode | null) ?? undefined,
          status: (params.get('status') as TransferStatusCode | null) ?? undefined,
          from: params.get('from') ?? undefined,
          to: params.get('to') ?? undefined,
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<StockTransferResponse>>,
    { sort: [{ field: 'id', direction: 'desc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<StockTransferResponse>[]>(() => [
    { key: 'number', header: 'Folio', sortField: 'number' },
    { key: 'direction', header: 'Dirección', template: this.directionTpl() },
    {
      key: 'origin',
      header: 'Origen',
      cell: (row) => formatStoreRef(row.origin),
    },
    {
      key: 'destination',
      header: 'Destino',
      cell: (row) => formatStoreRef(row.destination),
    },
    { key: 'lines', header: 'Ítems', template: this.linesTpl(), align: 'end' },
    { key: 'status', header: 'Estado', sortField: 'status', template: this.statusTpl() },
    {
      key: 'sentAt',
      header: 'Despacho',
      sortField: 'sentAt',
      cell: (row) => formatTransferDateTime(row.sentAt),
    },
    {
      key: 'receivedAt',
      header: 'Recepción',
      sortField: 'receivedAt',
      cell: (row) => formatTransferDateTime(row.receivedAt),
    },
    { key: 'actions', header: 'Acciones', template: this.actionsTpl(), align: 'end' },
  ]);

  private initialized = false;
  private lastLoadedStoreId: number | null = null;

  constructor() {
    effect(() => {
      const storeId = this.storeContext.storeId();
      if (this.initialized && storeId !== this.lastLoadedStoreId) {
        this.lastLoadedStoreId = storeId;
        this.dataSource.reload();
      }
    });
  }

  ngOnInit(): void {
    this.initialized = true;
    this.lastLoadedStoreId = this.storeContext.storeId();
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    if (raw.from && raw.to && raw.from > raw.to) {
      this.filterError.set('La fecha "Desde" no puede ser posterior a "Hasta".');
      return;
    }
    this.filterError.set(null);

    const filters: Filters = {
      direction: raw.direction,
      status: raw.status,
      from: raw.from || null,
      to: raw.to || null,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterError.set(null);
    this.filterForm.reset({ direction: null, status: null, from: '', to: '' });
    this.dataSource.setFilters({});
  }

  protected openCreate(): void {
    if (!this.canSend()) {
      return;
    }
    const ref = this.dialog.open<TransferFormDialog, TransferFormDialogData, StockTransferResponse>(
      TransferFormDialog,
      {
        data: { mode: 'create' },
        width: '760px',
        maxWidth: '95vw',
      },
    );

    ref.afterClosed().subscribe((created) => {
      if (created) {
        this.snackBar.open(`Borrador ${created.number ?? ''} creado.`, 'Cerrar', {
          duration: 4000,
        });
        this.dataSource.reload();
      }
    });
  }

  protected openEdit(transfer: StockTransferResponse): void {
    if (!this.canEditRow(transfer)) {
      return;
    }
    const ref = this.dialog.open<TransferFormDialog, TransferFormDialogData, StockTransferResponse>(
      TransferFormDialog,
      {
        data: { mode: 'edit', transfer },
        width: '760px',
        maxWidth: '95vw',
      },
    );

    ref.afterClosed().subscribe((updated) => {
      if (updated) {
        this.snackBar.open(`Borrador ${updated.number ?? ''} actualizado.`, 'Cerrar', {
          duration: 4000,
        });
        this.dataSource.reload();
      }
    });
  }

  protected openDetail(
    transfer: StockTransferResponse,
    flags: { justDispatched?: boolean; justReceived?: boolean } = {},
  ): void {
    const ref = this.dialog.open<
      TransferDetailDialog,
      TransferDetailDialogData,
      TransferDetailDialogResult | undefined
    >(TransferDetailDialog, {
      data: {
        transfer,
        justDispatched: flags.justDispatched,
        justReceived: flags.justReceived,
        canSend: this.canSend(),
        canReceive: this.canReceive(),
        canCancel: this.canCancel(),
      },
      width: '780px',
      maxWidth: '95vw',
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) {
        return;
      }
      switch (result.action) {
        case 'edit':
          this.openEdit(transfer);
          break;
        case 'send':
          this.confirmAndSend(transfer);
          break;
        case 'receive':
          this.openReceive(transfer);
          break;
        case 'cancel':
          this.openCancel(transfer);
          break;
      }
    });
  }

  protected confirmAndSend(transfer: StockTransferResponse): void {
    if (!this.canSendRow(transfer) || transfer.id === undefined) {
      return;
    }
    const transferId = transfer.id;
    const destinationLabel = formatStoreRef(transfer.destination);
    const lineCount = transfer.lines?.length ?? 0;

    this.confirmDialog
      .confirm({
        title: `Despachar transferencia ${transfer.number ?? ''}`,
        message:
          `Se descontará el stock en el local de origen hacia ${destinationLabel} (${lineCount} línea(s)).\n` +
          'Los productos con lote sin selección manual se asignarán automáticamente por FEFO.',
        confirmLabel: 'Despachar',
      })
      .pipe(
        filter((confirmed) => confirmed),
        switchMap(() => this.transfersService.sendTransfer(transferId, withoutErrorNotification())),
      )
      .subscribe({
        next: (sent) => {
          this.snackBar.open(`Transferencia ${sent.number ?? ''} despachada.`, 'Cerrar', {
            duration: 4000,
          });
          this.dataSource.reload();
          this.openDetail(sent, { justDispatched: true });
        },
        error: (err: unknown) => {
          const msg = isApiError(err) ? err.message : 'No se pudo despachar la transferencia.';
          this.snackBar.open(msg, 'Cerrar', { duration: 6000 });
        },
      });
  }

  protected openReceive(transfer: StockTransferResponse): void {
    if (!this.canReceiveRow(transfer)) {
      return;
    }
    const ref = this.dialog.open<
      TransferReceiveDialog,
      TransferReceiveDialogData,
      StockTransferResponse
    >(TransferReceiveDialog, {
      data: { transfer },
      width: '760px',
      maxWidth: '95vw',
    });

    ref.afterClosed().subscribe((received) => {
      if (received) {
        const msg = transferHasDifferences(received)
          ? `Transferencia ${received.number ?? ''} recepcionada con diferencias.`
          : `Transferencia ${received.number ?? ''} recepcionada.`;
        this.snackBar.open(msg, 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
        this.openDetail(received, { justReceived: true });
      }
    });
  }

  protected openCancel(transfer: StockTransferResponse): void {
    if (!this.canCancelRow(transfer)) {
      return;
    }
    const ref = this.dialog.open<TransferCancelDialog, TransferCancelDialogData, boolean>(
      TransferCancelDialog,
      {
        data: { transfer },
        width: '480px',
        maxWidth: '95vw',
      },
    );

    ref.afterClosed().subscribe((cancelled) => {
      if (cancelled) {
        this.snackBar.open(`Transferencia ${transfer.number ?? ''} cancelada.`, 'Cerrar', {
          duration: 4000,
        });
        this.dataSource.reload();
      }
    });
  }

  protected canEditRow(row: StockTransferResponse): boolean {
    return row.status === 'DRAFT' && isTransferOrigin(row, this.activeStoreId()) && this.canSend();
  }

  protected canSendRow(row: StockTransferResponse): boolean {
    return row.status === 'DRAFT' && isTransferOrigin(row, this.activeStoreId()) && this.canSend();
  }

  protected canReceiveRow(row: StockTransferResponse): boolean {
    return (
      row.status === 'SENT' && isTransferDestination(row, this.activeStoreId()) && this.canReceive()
    );
  }

  protected canCancelRow(row: StockTransferResponse): boolean {
    return (
      row.status === 'DRAFT' && isTransferOrigin(row, this.activeStoreId()) && this.canCancel()
    );
  }

  protected isOriginRow(row: StockTransferResponse): boolean {
    return isTransferOrigin(row, this.activeStoreId());
  }

  protected directionLabel(row: StockTransferResponse): string {
    return transferDirectionLabel(row, this.activeStoreId());
  }

  protected statusLabel(status: StockTransferResponse['status']): string {
    return transferStatusLabel(status);
  }

  protected hasDifferences(row: StockTransferResponse): boolean {
    return transferHasDifferences(row);
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
}
