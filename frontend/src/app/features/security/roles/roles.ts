import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  inject,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatSnackBar } from '@angular/material/snack-bar';
import { filter, switchMap } from 'rxjs';
import { RoleResponse } from '../../../api/models/role-response';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { PageResponse } from '../../../shared/models/page-response';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  CellContext,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { RoleFormDialog } from './role-form-dialog';
import { RolePermissionsLimitsDialog } from './role-permissions-limits-dialog';
import { RolesService } from './roles.service';

/**
 * Pantalla de administración de roles, permisos granulares y límites numéricos (`HU-SEG-05`, `HU-CFG-06`).
 */
@Component({
  selector: 'pos-roles',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, PaginatedTable],
  templateUrl: './roles.html',
  styleUrl: './roles.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Roles implements OnInit, OnDestroy {
  private readonly rolesService = inject(RolesService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);

  private readonly typeTpl = viewChild.required<TemplateRef<CellContext<RoleResponse>>>('typeTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<RoleResponse>>>('actionsTpl');

  protected readonly canManageRoles = computed(() =>
    this.auth.hasPermission('security:role:manage'),
  );

  protected readonly dataSource = new PaginatedDataSource<RoleResponse>(
    (params) =>
      this.rolesService.listRoles(
        {
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as ReturnType<typeof this.rolesService.listRoles> &
        import('rxjs').Observable<PageResponse<RoleResponse>>,
    { sort: [{ field: 'code', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<RoleResponse>[]>(() => [
    { key: 'code', header: 'Código', sortField: 'code' },
    { key: 'name', header: 'Nombre', sortField: 'name' },
    { key: 'description', header: 'Descripción' },
    { key: 'system', header: 'Tipo', template: this.typeTpl() },
    {
      key: 'userCount',
      header: 'Usuarios',
      align: 'end',
      cell: (row) => String(row.userCount ?? 0),
    },
    { key: 'actions', header: 'Acciones', template: this.actionsTpl(), align: 'end' },
  ]);

  ngOnInit(): void {
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected openCreate(): void {
    const ref = this.dialog.open(RoleFormDialog, {
      data: {},
      width: '480px',
    });
    ref.afterClosed().subscribe((created) => {
      if (created) {
        this.snackBar.open('Rol creado correctamente.', 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
      }
    });
  }

  protected openEdit(role: RoleResponse): void {
    const ref = this.dialog.open(RoleFormDialog, {
      data: { role },
      width: '480px',
    });
    ref.afterClosed().subscribe((updated) => {
      if (updated) {
        this.snackBar.open('Rol actualizado correctamente.', 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
      }
    });
  }

  protected openPermissionsAndLimits(role: RoleResponse): void {
    if (role.id === undefined) {
      return;
    }
    this.rolesService.getRole(role.id).subscribe((fullRole) => {
      const ref = this.dialog.open(RolePermissionsLimitsDialog, {
        data: { role: fullRole },
        width: '740px',
      });
      ref.afterClosed().subscribe(() => {
        this.dataSource.reload();
      });
    });
  }

  protected deleteRole(role: RoleResponse): void {
    if (role.id === undefined || role.system || (role.userCount ?? 0) > 0) {
      return;
    }
    const roleId = role.id;
    this.confirmDialog
      .confirm({
        title: 'Eliminar rol',
        message: `¿Eliminar el rol "${role.name}" (${role.code})? Esta acción no se puede deshacer.`,
        confirmLabel: 'Sí, eliminar',
        destructive: true,
      })
      .pipe(
        filter(Boolean),
        switchMap(() => this.rolesService.deleteRole(roleId)),
      )
      .subscribe(() => {
        this.snackBar.open('Rol eliminado.', 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
      });
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
}
