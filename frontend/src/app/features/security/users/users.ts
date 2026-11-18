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
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { filter, switchMap } from 'rxjs';
import { RoleResponse } from '../../../api/models/role-response';
import { StoreResponse } from '../../../api/models/store-response';
import { UserSummary } from '../../../api/models/user-summary';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { PageResponse } from '../../../shared/models/page-response';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { TemporaryPasswordDialog } from './temporary-password-dialog';
import { UserFormDialog, UserFormDialogResult } from './user-form-dialog';
import { UserSessionsDialog } from './user-sessions-dialog';
import { UsersService } from './users.service';

/**
 * Pantalla de administración de usuarios, roles asignados, locales y sesiones (`HU-SEG-04`, `HU-SEG-11`).
 */
@Component({
  selector: 'pos-users',
  imports: [
    MatButtonModule,
    MatChipsModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    MatTooltipModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './users.html',
  styleUrl: './users.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Users implements OnInit, OnDestroy {
  private readonly usersService = inject(UsersService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly rolesTpl = viewChild.required<TemplateRef<CellContext<UserSummary>>>('rolesTpl');
  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<UserSummary>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<UserSummary>>>('actionsTpl');

  protected readonly roles = signal<readonly RoleResponse[]>([]);
  protected readonly stores = signal<readonly StoreResponse[]>([]);

  protected readonly currentUserId = computed(() => this.auth.user()?.id ?? null);
  protected readonly canCreate = computed(() => this.auth.hasPermission('security:user:create'));
  protected readonly canUpdate = computed(() => this.auth.hasPermission('security:user:update'));
  protected readonly canDisable = computed(() => this.auth.hasPermission('security:user:disable'));
  protected readonly canResetCredentials = computed(() =>
    this.auth.hasPermission('security:user:reset-credentials'),
  );
  protected readonly canRevokeSessions = computed(() =>
    this.auth.hasPermission('security:session:revoke'),
  );

  protected readonly filterForm = this.fb.group({
    q: [''],
    roleId: [null as number | null],
    storeId: [null as number | null],
    active: [null as boolean | null],
    locked: [null as boolean | null],
  });

  protected readonly dataSource = new PaginatedDataSource<UserSummary>(
    (params) =>
      this.usersService.listUsers(
        {
          q: params.get('q') ?? undefined,
          roleId: this.parseNumberParam(params.get('roleId')),
          storeId: this.parseNumberParam(params.get('storeId')),
          active: this.parseBooleanParam(params.get('active')),
          locked: this.parseBooleanParam(params.get('locked')),
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as ReturnType<typeof this.usersService.listUsers> &
        import('rxjs').Observable<PageResponse<UserSummary>>,
    { sort: [{ field: 'username', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<UserSummary>[]>(() => [
    { key: 'username', header: 'Usuario', sortField: 'username' },
    { key: 'fullName', header: 'Nombre completo', sortField: 'fullName' },
    { key: 'roleCodes', header: 'Roles', template: this.rolesTpl() },
    { key: 'status', header: 'Estado', template: this.statusTpl() },
    { key: 'actions', header: 'Acciones', template: this.actionsTpl(), align: 'end' },
  ]);

  ngOnInit(): void {
    this.loadCatalogs();
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    const filters: Filters = {
      q: raw.q.trim() || null,
      roleId: raw.roleId,
      storeId: raw.storeId,
      active: raw.active,
      locked: raw.locked,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ q: '', roleId: null, storeId: null, active: null, locked: null });
    this.dataSource.setFilters({});
  }

  protected openCreate(): void {
    const ref = this.dialog.open<UserFormDialog, unknown, UserFormDialogResult>(UserFormDialog, {
      data: { roles: this.roles(), stores: this.stores() },
      width: '560px',
    });

    ref.afterClosed().subscribe((result) => {
      if (result?.mode === 'created') {
        this.dataSource.reload();
        if (result.user.temporaryPassword) {
          this.dialog.open(TemporaryPasswordDialog, {
            data: {
              username: result.user.username ?? '',
              temporaryPassword: result.user.temporaryPassword,
              mode: 'created',
            },
            width: '460px',
          });
        }
      }
    });
  }

  protected openEdit(row: UserSummary): void {
    if (row.id === undefined) {
      return;
    }
    this.usersService.getUser(row.id).subscribe((user) => {
      const ref = this.dialog.open<UserFormDialog, unknown, UserFormDialogResult>(UserFormDialog, {
        data: { user, roles: this.roles(), stores: this.stores() },
        width: '560px',
      });

      ref.afterClosed().subscribe((result) => {
        if (result?.mode === 'updated') {
          this.snackBar.open('Usuario actualizado correctamente.', 'Cerrar', { duration: 4000 });
          this.dataSource.reload();
        }
      });
    });
  }

  protected openSessions(row: UserSummary): void {
    if (row.id === undefined) {
      return;
    }
    this.dialog.open(UserSessionsDialog, {
      data: { userId: row.id, username: row.username ?? '' },
      width: '680px',
    });
  }

  protected resetPassword(row: UserSummary): void {
    if (row.id === undefined) {
      return;
    }
    const userId = row.id;
    this.confirmDialog
      .confirm({
        title: 'Restablecer contraseña',
        message: `¿Generar una nueva contraseña temporal para "${row.username}"?`,
        confirmLabel: 'Restablecer',
      })
      .pipe(
        filter(Boolean),
        switchMap(() => this.usersService.resetPassword(userId)),
      )
      .subscribe((res) => {
        if (res.temporaryPassword) {
          this.dialog.open(TemporaryPasswordDialog, {
            data: {
              username: row.username ?? '',
              temporaryPassword: res.temporaryPassword,
              mode: 'reset',
            },
            width: '460px',
          });
        }
      });
  }

  protected resetPin(row: UserSummary): void {
    if (row.id === undefined) {
      return;
    }
    const userId = row.id;
    this.confirmDialog
      .confirm({
        title: 'Restablecer PIN',
        message: `¿Restablecer el PIN de "${row.username}"? Si estaba bloqueado, también se desbloqueará.`,
        confirmLabel: 'Restablecer PIN',
      })
      .pipe(
        filter(Boolean),
        switchMap(() => this.usersService.resetPin(userId)),
      )
      .subscribe(() => {
        this.snackBar.open('PIN restablecido correctamente.', 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
      });
  }

  protected unlock(row: UserSummary): void {
    if (row.id === undefined) {
      return;
    }
    const userId = row.id;
    this.confirmDialog
      .confirm({
        title: 'Desbloquear usuario',
        message: `¿Desbloquear la cuenta y el PIN de "${row.username}"?`,
        confirmLabel: 'Desbloquear',
      })
      .pipe(
        filter(Boolean),
        switchMap(() => this.usersService.unlockUser(userId)),
      )
      .subscribe(() => {
        this.snackBar.open('Usuario desbloqueado.', 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
      });
  }

  protected toggleActive(row: UserSummary): void {
    if (row.id === undefined) {
      return;
    }
    const userId = row.id;
    const isCurrentlyActive = row.active !== false;
    this.confirmDialog
      .confirm({
        title: isCurrentlyActive ? 'Desactivar usuario' : 'Activar usuario',
        message: isCurrentlyActive
          ? `¿Desactivar a "${row.username}"? Se revocarán sus sesiones activas.`
          : `¿Reactivar la cuenta de "${row.username}"?`,
        confirmLabel: isCurrentlyActive ? 'Desactivar' : 'Activar',
        destructive: isCurrentlyActive,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          isCurrentlyActive
            ? this.usersService.disableUser(userId)
            : this.usersService.enableUser(userId),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(
          isCurrentlyActive ? 'Usuario desactivado.' : 'Usuario reactivado.',
          'Cerrar',
          { duration: 4000 },
        );
        this.dataSource.reload();
      });
  }

  private loadCatalogs(): void {
    this.usersService.listRoles().subscribe({
      next: (page) => this.roles.set(page.content ?? []),
    });
    this.usersService.listStores().subscribe({
      next: (stores) => this.stores.set(stores ?? []),
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
