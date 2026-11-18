import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { map } from 'rxjs';
import { AuditLogResponse } from '../../../api/models/audit-log-response';
import { AuditVerifyResponse } from '../../../api/models/audit-verify-response';
import { UserSummary } from '../../../api/models/user-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { PageResponse } from '../../../shared/models/page-response';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { AuditDetailDialog, AuditDetailDialogData } from './audit-detail-dialog';
import {
  AUDIT_ACTION_OPTIONS,
  AUDIT_ENTITY_TYPE_OPTIONS,
  AuditFilterOption,
  auditActionLabel,
  auditEntityTypeLabel,
  formatAuditDateTime,
} from './audit.models';
import { AuditService } from './audit.service';

/**
 * Pantalla de consulta de auditoría inmutable y verificación de cadena de hash SHA-256
 * (`HU-SEG-08`, `HU-SEG-09`, ADR-18).
 */
@Component({
  selector: 'pos-audit',
  imports: [
    MatAutocompleteModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './audit.html',
  styleUrl: './audit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Audit implements OnInit, OnDestroy {
  private readonly auditService = inject(AuditService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly actionTpl =
    viewChild.required<TemplateRef<CellContext<AuditLogResponse>>>('actionTpl');
  private readonly entityTpl =
    viewChild.required<TemplateRef<CellContext<AuditLogResponse>>>('entityTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<AuditLogResponse>>>('actionsTpl');

  protected readonly canVerify = computed(() => this.auth.hasPermission('security:audit:verify'));
  protected readonly canReadUsers = computed(() => this.auth.hasPermission('security:user:read'));

  protected readonly stores = computed(() => this.storeContext.availableStores());
  protected readonly storeNamesMap = computed(() => {
    const entries = this.stores().map((store) => [store.id, store.name] as const);
    return new Map<number, string>(entries);
  });

  protected readonly users = signal<readonly UserSummary[]>([]);
  protected readonly filterError = signal<string | null>(null);

  private readonly actionTerm = signal('');
  private readonly entityTypeTerm = signal('');

  protected readonly filteredActionOptions = computed(() =>
    this.filterOptions(AUDIT_ACTION_OPTIONS, this.actionTerm()),
  );
  protected readonly filteredEntityTypeOptions = computed(() =>
    this.filterOptions(AUDIT_ENTITY_TYPE_OPTIONS, this.entityTypeTerm()),
  );

  protected readonly verifying = signal(false);
  protected readonly verifyResult = signal<AuditVerifyResponse | null>(null);
  protected readonly verifyError = signal<string | null>(null);

  protected readonly filterForm = this.fb.group({
    from: [''],
    to: [''],
    action: [''],
    entityType: [''],
    entityId: [''],
    userId: [null as number | null],
    storeId: [null as number | null],
  });

  protected readonly verifyForm = this.fb.group({
    fromId: [null as number | null],
    toId: [null as number | null],
  });

  protected readonly dataSource = new PaginatedDataSource<AuditLogResponse>(
    (params) => {
      const sort = params.getAll('sort');
      return this.auditService
        .listLogs(
          {
            from: params.get('from') ?? undefined,
            to: params.get('to') ?? undefined,
            action: params.get('action') ?? undefined,
            entityType: params.get('entityType') ?? undefined,
            entityId: params.get('entityId') ?? undefined,
            userId: this.parseNumberParam(params.get('userId')),
            storeId: this.parseNumberParam(params.get('storeId')),
            page: this.parseNumberParam(params.get('page')),
            size: this.parseNumberParam(params.get('size')),
            sort: sort && sort.length > 0 ? sort : undefined,
          },
          withoutErrorNotification(),
        )
        .pipe(
          map((res): PageResponse<AuditLogResponse> => ({
            content: res.content ?? [],
            page: res.page ?? 0,
            size: res.size ?? 20,
            totalElements: res.totalElements ?? 0,
            totalPages: res.totalPages ?? 0,
            sort: res.sort ?? [],
          })),
        );
    },
    { sort: [{ field: 'occurredAt', direction: 'desc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<AuditLogResponse>[]>(() => [
    {
      key: 'id',
      header: 'ID',
      sortField: 'id',
      cell: (row) => (row.id !== undefined ? `#${row.id}` : '—'),
    },
    {
      key: 'occurredAt',
      header: 'Fecha y hora',
      sortField: 'occurredAt',
      cell: (row) => formatAuditDateTime(row.occurredAt),
    },
    {
      key: 'action',
      header: 'Acción',
      sortField: 'action',
      template: this.actionTpl(),
    },
    {
      key: 'entityType',
      header: 'Entidad',
      sortField: 'entityType',
      template: this.entityTpl(),
    },
    {
      key: 'username',
      header: 'Usuario',
      sortField: 'username',
      cell: (row) =>
        row.username || (row.userId !== undefined ? `Usuario #${row.userId}` : 'Sistema'),
    },
    {
      key: 'storeId',
      header: 'Local',
      sortField: 'storeId',
      cell: (row) => this.formatStore(row.storeId),
    },
    {
      key: 'reason',
      header: 'Motivo',
      cell: (row) => row.reason || '—',
    },
    {
      key: 'actions',
      header: 'Detalle',
      template: this.actionsTpl(),
      align: 'end',
    },
  ]);

  ngOnInit(): void {
    this.filterForm.controls.action.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((val) => this.actionTerm.set(val));
    this.filterForm.controls.entityType.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((val) => this.entityTypeTerm.set(val));

    if (this.canReadUsers()) {
      this.auditService.listUsers(withoutErrorNotification()).subscribe({
        next: (list) => this.users.set(list),
        error: () => {
          // Si falla la carga auxiliar de usuarios, la pantalla igual permite filtrar por los demás criterios.
        },
      });
    }
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    if (raw.from && raw.to && raw.from > raw.to) {
      this.filterError.set('La fecha «Desde» no puede ser posterior a «Hasta».');
      return;
    }
    this.filterError.set(null);

    const parsedUserId =
      raw.userId !== null && Number.isFinite(Number(raw.userId)) && Number(raw.userId) > 0
        ? Math.trunc(Number(raw.userId))
        : null;

    const filters: Filters = {
      from: raw.from ? this.toStartOfDayIso(raw.from) : null,
      to: raw.to ? this.toEndOfDayIso(raw.to) : null,
      action: raw.action.trim() || null,
      entityType: raw.entityType.trim() || null,
      entityId: raw.entityId.trim() || null,
      userId: parsedUserId,
      storeId: raw.storeId,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterError.set(null);
    this.filterForm.reset({
      from: '',
      to: '',
      action: '',
      entityType: '',
      entityId: '',
      userId: null,
      storeId: null,
    });
    this.actionTerm.set('');
    this.entityTypeTerm.set('');
    this.dataSource.setFilters({});
  }

  protected verifyChain(): void {
    const raw = this.verifyForm.getRawValue();
    const hasFrom = this.hasInputValue(raw.fromId);
    const hasTo = this.hasInputValue(raw.toId);
    const fromId = this.normalizeOptionalId(raw.fromId);
    const toId = this.normalizeOptionalId(raw.toId);

    if ((hasFrom && fromId === undefined) || (hasTo && toId === undefined)) {
      this.verifyError.set('Los identificadores de rango deben ser enteros positivos.');
      this.verifyResult.set(null);
      return;
    }

    if (fromId !== undefined && toId !== undefined && fromId > toId) {
      this.verifyError.set('El ID inicial no puede ser mayor que el ID final.');
      this.verifyResult.set(null);
      return;
    }

    this.verifying.set(true);
    this.verifyError.set(null);
    this.verifyResult.set(null);

    this.auditService.verifyChain({ fromId, toId }, withoutErrorNotification()).subscribe({
      next: (result) => {
        this.verifyResult.set(result);
        this.verifying.set(false);
      },
      error: (err: unknown) => {
        this.verifyError.set(
          err instanceof Error
            ? err.message
            : 'No se pudo verificar la integridad de la cadena de auditoría.',
        );
        this.verifying.set(false);
      },
    });
  }

  protected clearVerification(): void {
    this.verifyForm.reset({ fromId: null, toId: null });
    this.verifyResult.set(null);
    this.verifyError.set(null);
  }

  protected openDetail(id: number | undefined): void {
    if (id === undefined || id === null) {
      return;
    }
    this.dialog.open<AuditDetailDialog, AuditDetailDialogData>(AuditDetailDialog, {
      data: { logId: id, storeNames: this.storeNamesMap() },
      width: '760px',
      maxWidth: '95vw',
    });
  }

  protected actionLabel(action?: string): string {
    return auditActionLabel(action);
  }

  protected entityTypeLabel(entityType?: string): string {
    return auditEntityTypeLabel(entityType);
  }

  protected formatStore(storeId?: number): string {
    if (storeId === undefined || storeId === null) {
      return 'Global';
    }
    return this.storeNamesMap().get(storeId) ?? `Local #${storeId}`;
  }

  private filterOptions(
    options: readonly AuditFilterOption[],
    term: string,
  ): readonly AuditFilterOption[] {
    const needle = term.trim().toLowerCase();
    if (!needle) {
      return options;
    }
    return options.filter(
      (opt) => opt.value.toLowerCase().includes(needle) || opt.label.toLowerCase().includes(needle),
    );
  }

  private hasInputValue(value: number | null | undefined): boolean {
    return value !== null && value !== undefined && (value as unknown) !== '';
  }

  private normalizeOptionalId(value: number | null): number | undefined {
    if (!this.hasInputValue(value)) {
      return undefined;
    }
    const num = Number(value);
    if (!Number.isInteger(num) || num < 1) {
      return undefined;
    }
    return num;
  }

  private toStartOfDayIso(dateStr: string): string | null {
    const date = new Date(`${dateStr}T00:00:00`);
    return Number.isNaN(date.getTime()) ? null : date.toISOString();
  }

  private toEndOfDayIso(dateStr: string): string | null {
    const date = new Date(`${dateStr}T23:59:59.999`);
    return Number.isNaN(date.getTime()) ? null : date.toISOString();
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
}
