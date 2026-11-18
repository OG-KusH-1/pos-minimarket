import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { filter, switchMap } from 'rxjs';
import { StoreResponse } from '../../../api/models/store-response';
import { TerminalResponse } from '../../../api/models/terminal-response';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { PageResponse } from '../../../shared/models/page-response';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  CellContext,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { StoreFormDialog } from './store-form-dialog';
import { StoresService } from './stores.service';
import { TerminalFormDialog } from './terminal-form-dialog';

/**
 * Pantalla de administración de locales y terminales POS (`HU-CFG-02`).
 *
 * Permite listar, crear, editar y activar/desactivar locales y gestionar las terminales POS
 * pertenecientes al local seleccionado.
 */
@Component({
  selector: 'pos-stores',
  imports: [
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatMenuModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTableModule,
    PaginatedTable,
  ],
  templateUrl: './stores.html',
  styleUrl: './stores.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Stores implements OnInit, OnDestroy {
  private readonly storesService = inject(StoresService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);

  private readonly storeStatusTpl =
    viewChild.required<TemplateRef<CellContext<StoreResponse>>>('storeStatusTpl');
  private readonly storeActionsTpl =
    viewChild.required<TemplateRef<CellContext<StoreResponse>>>('storeActionsTpl');

  protected readonly canManageStores = computed(() =>
    this.auth.hasPermission('config:store:manage'),
  );
  protected readonly canManageTerminals = computed(() =>
    this.auth.hasPermission('config:terminal:manage'),
  );

  protected readonly activeFilter = signal<boolean | null>(null);
  protected readonly selectedStore = signal<StoreResponse | null>(null);
  protected readonly terminals = signal<readonly TerminalResponse[]>([]);
  protected readonly terminalsLoading = signal(false);
  protected readonly terminalsError = signal<string | null>(null);
  protected readonly terminalColumns: readonly string[] = [
    'code',
    'name',
    'status',
    'lastSeenAt',
    'actions',
  ];

  protected readonly dataSource = new PaginatedDataSource<StoreResponse>(
    (params) =>
      this.storesService.listStores(
        {
          active: this.parseBooleanParam(params.get('active')),
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as ReturnType<typeof this.storesService.listStores> &
        import('rxjs').Observable<PageResponse<StoreResponse>>,
    { sort: [{ field: 'code', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<StoreResponse>[]>(() => [
    { key: 'code', header: 'Código', sortField: 'code' },
    { key: 'name', header: 'Nombre', sortField: 'name' },
    { key: 'address', header: 'Dirección' },
    { key: 'commune', header: 'Comuna' },
    {
      key: 'siiBranchCode',
      header: 'Sucursal SII',
      cell: (row) => row.siiBranchCode || '—',
    },
    { key: 'active', header: 'Estado', sortField: 'active', template: this.storeStatusTpl() },
    { key: 'actions', header: 'Acciones', template: this.storeActionsTpl(), align: 'end' },
  ]);

  ngOnInit(): void {
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected onActiveFilterChange(active: boolean | null): void {
    this.activeFilter.set(active);
    this.dataSource.setFilters({ active });
  }

  /** Selecciona un local y carga sus terminales POS. */
  protected selectStore(store: StoreResponse): void {
    if (store.id === undefined) {
      return;
    }
    this.selectedStore.set(store);
    this.loadTerminals(store.id);
  }

  protected openCreateStore(): void {
    const ref = this.dialog.open(StoreFormDialog, { data: {}, width: '540px' });
    ref.afterClosed().subscribe((created) => {
      if (created) {
        this.snackBar.open('Local creado correctamente.', 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
        this.selectedStore.set(created);
        if (created.id !== undefined) {
          this.loadTerminals(created.id);
        }
      }
    });
  }

  protected openEditStore(store: StoreResponse): void {
    const ref = this.dialog.open(StoreFormDialog, { data: { store }, width: '540px' });
    ref.afterClosed().subscribe((updated) => {
      if (updated) {
        this.snackBar.open('Local actualizado correctamente.', 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
        if (this.selectedStore()?.id === updated.id) {
          this.selectedStore.set(updated);
        }
      }
    });
  }

  protected toggleStoreActive(store: StoreResponse): void {
    if (store.id === undefined) {
      return;
    }
    const storeId = store.id;
    const isActive = store.active !== false;

    this.confirmDialog
      .confirm({
        title: isActive ? 'Desactivar local' : 'Activar local',
        message: isActive
          ? `¿Desactivar el local "${store.name}" (${store.code})? No se permitirá si tiene turnos de caja abiertos.`
          : `¿Reactivar el local "${store.name}" (${store.code})?`,
        confirmLabel: isActive ? 'Sí, desactivar' : 'Sí, activar',
        destructive: isActive,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          isActive
            ? this.storesService.deactivateStore(storeId)
            : this.storesService.activateStore(storeId),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(isActive ? 'Local desactivado.' : 'Local activado.', 'Cerrar', {
          duration: 4000,
        });
        this.dataSource.reload();
        if (this.selectedStore()?.id === storeId) {
          this.selectedStore.update((current) =>
            current ? { ...current, active: !isActive } : current,
          );
        }
      });
  }

  protected openCreateTerminal(): void {
    const store = this.selectedStore();
    if (store?.id === undefined) {
      return;
    }
    const storeId = store.id;
    const ref = this.dialog.open(TerminalFormDialog, {
      data: { storeId, storeName: store.name ?? store.code ?? '' },
      width: '460px',
    });
    ref.afterClosed().subscribe((created) => {
      if (created) {
        this.snackBar.open('Terminal creada correctamente.', 'Cerrar', { duration: 4000 });
        this.loadTerminals(storeId);
      }
    });
  }

  protected openEditTerminal(terminal: TerminalResponse): void {
    const store = this.selectedStore();
    if (store?.id === undefined) {
      return;
    }
    const storeId = store.id;
    const ref = this.dialog.open(TerminalFormDialog, {
      data: { storeId, storeName: store.name ?? store.code ?? '', terminal },
      width: '460px',
    });
    ref.afterClosed().subscribe((updated) => {
      if (updated) {
        this.snackBar.open('Terminal actualizada correctamente.', 'Cerrar', { duration: 4000 });
        this.loadTerminals(storeId);
      }
    });
  }

  protected toggleTerminalActive(terminal: TerminalResponse): void {
    const store = this.selectedStore();
    if (store?.id === undefined || terminal.id === undefined) {
      return;
    }
    const storeId = store.id;
    const terminalId = terminal.id;
    const isActive = terminal.active !== false;

    this.confirmDialog
      .confirm({
        title: isActive ? 'Desactivar terminal' : 'Activar terminal',
        message: isActive
          ? `¿Desactivar la terminal "${terminal.code}" (${terminal.name})? No se permitirá si tiene un turno abierto.`
          : `¿Reactivar la terminal "${terminal.code}" (${terminal.name})?`,
        confirmLabel: isActive ? 'Sí, desactivar' : 'Sí, activar',
        destructive: isActive,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          isActive
            ? this.storesService.deactivateTerminal(storeId, terminalId)
            : this.storesService.activateTerminal(storeId, terminalId),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(isActive ? 'Terminal desactivada.' : 'Terminal activada.', 'Cerrar', {
          duration: 4000,
        });
        this.loadTerminals(storeId);
      });
  }

  private loadTerminals(storeId: number): void {
    this.terminalsLoading.set(true);
    this.terminalsError.set(null);

    this.storesService.listTerminals(storeId, withoutErrorNotification()).subscribe({
      next: (list) => {
        this.terminals.set(list);
        this.terminalsLoading.set(false);
      },
      error: (err) => {
        this.terminals.set([]);
        this.terminalsLoading.set(false);
        this.terminalsError.set(
          isApiError(err) ? err.message : 'No se pudieron cargar las terminales del local.',
        );
      },
    });
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }

  private parseBooleanParam(raw: string | null): boolean | undefined {
    if (raw === 'true') {
      return true;
    }
    if (raw === 'false') {
      return false;
    }
    return undefined;
  }
}
