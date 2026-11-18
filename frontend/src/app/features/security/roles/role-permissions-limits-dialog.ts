import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { forkJoin } from 'rxjs';
import { PermissionResponse } from '../../../api/models/permission-response';
import { RoleLimitItemRequest } from '../../../api/models/role-limit-item-request';
import { RoleLimitResponse } from '../../../api/models/role-limit-response';
import { RoleResponse } from '../../../api/models/role-response';
import { AuthService } from '../../../core/auth/auth.service';
import {
  ROLE_LIMIT_DEFINITIONS,
  RoleLimitCode,
  RoleLimitDefinition,
  formatRoleLimitValue,
} from './role-limits.metadata';
import { RolesService } from './roles.service';

/** Datos de entrada para {@link RolePermissionsLimitsDialog}. */
export interface RolePermissionsLimitsDialogData {
  readonly role: RoleResponse;
}

/** Grupo de permisos agrupados por módulo para la UI. */
export interface PermissionModuleGroup {
  readonly module: string;
  readonly permissions: readonly PermissionResponse[];
}

/** Controles tipados de una fila del formulario de límites. */
export type LimitRowGroup = FormGroup<{
  code: FormControl<RoleLimitCode>;
  unlimited: FormControl<boolean>;
  value: FormControl<number | null>;
}>;

/**
 * Diálogo para gestionar los permisos granulares (`HU-SEG-05`) y los límites numéricos (`HU-CFG-06`)
 * de un rol.
 *
 * En los límites, el valor `null` de la API representa ilimitado y se muestra como `∞`.
 */
@Component({
  selector: 'pos-role-permissions-limits-dialog',
  imports: [
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatTabsModule,
    ReactiveFormsModule,
  ],
  templateUrl: './role-permissions-limits-dialog.html',
  styleUrl: './role-permissions-limits-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RolePermissionsLimitsDialog implements OnInit {
  protected readonly data = inject<RolePermissionsLimitsDialogData>(MAT_DIALOG_DATA);
  private readonly rolesService = inject(RolesService);
  private readonly auth = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly loading = signal(true);
  protected readonly savingPermissions = signal(false);
  protected readonly savingLimits = signal(false);
  protected readonly permissionSearch = signal('');
  protected readonly allPermissions = signal<readonly PermissionResponse[]>([]);
  protected readonly selectedPermissions = signal<ReadonlySet<string>>(
    new Set(this.data.role.permissionCodes ?? []),
  );

  protected readonly canManagePermissions = computed(() =>
    this.auth.hasPermission('security:role:manage'),
  );
  protected readonly canManageLimits = computed(() =>
    this.auth.hasPermission('config:role-limit:manage'),
  );

  protected readonly limitDefinitions = ROLE_LIMIT_DEFINITIONS;
  protected readonly limitsArray = this.fb.array<LimitRowGroup>(
    ROLE_LIMIT_DEFINITIONS.map((def) => this.createLimitRow(def, 0)),
  );
  protected readonly limitsForm = this.fb.group({
    limits: this.limitsArray,
  });

  protected readonly groupedPermissions = computed<readonly PermissionModuleGroup[]>(() => {
    const term = this.permissionSearch().trim().toLowerCase();
    const filtered = this.allPermissions().filter((p) => {
      if (!term) {
        return true;
      }
      return (
        (p.code ?? '').toLowerCase().includes(term) ||
        (p.description ?? '').toLowerCase().includes(term) ||
        (p.module ?? '').toLowerCase().includes(term)
      );
    });

    const byModule = new Map<string, PermissionResponse[]>();
    for (const perm of filtered) {
      const mod = perm.module ?? 'otros';
      const list = byModule.get(mod) ?? [];
      list.push(perm);
      byModule.set(mod, list);
    }

    return Array.from(byModule.entries()).map(([module, permissions]) => ({
      module,
      permissions,
    }));
  });

  ngOnInit(): void {
    const roleId = this.data.role.id;
    if (roleId === undefined) {
      this.loading.set(false);
      return;
    }

    forkJoin({
      permissions: this.rolesService.listPermissions(),
      limits: this.rolesService.getRoleLimits(roleId),
    }).subscribe({
      next: ({ permissions, limits }) => {
        this.allPermissions.set(permissions);
        this.applyLimits(limits);
        if (!this.canManageLimits()) {
          this.limitsForm.disable({ emitEvent: false });
        }
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
      },
    });
  }

  protected isPermissionSelected(code: string | undefined): boolean {
    return code !== undefined && this.selectedPermissions().has(code);
  }

  protected togglePermission(code: string | undefined, checked: boolean): void {
    if (!code || !this.canManagePermissions()) {
      return;
    }
    this.selectedPermissions.update((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(code);
      } else {
        next.delete(code);
      }
      return next;
    });
  }

  protected toggleModule(group: PermissionModuleGroup, checked: boolean): void {
    if (!this.canManagePermissions()) {
      return;
    }
    this.selectedPermissions.update((current) => {
      const next = new Set(current);
      for (const perm of group.permissions) {
        if (perm.code) {
          if (checked) {
            next.add(perm.code);
          } else {
            next.delete(perm.code);
          }
        }
      }
      return next;
    });
  }

  protected isModuleAllSelected(group: PermissionModuleGroup): boolean {
    const selected = this.selectedPermissions();
    const codes = group.permissions.map((p) => p.code).filter((c): c is string => Boolean(c));
    return codes.length > 0 && codes.every((c) => selected.has(c));
  }

  protected savePermissions(): void {
    const roleId = this.data.role.id;
    if (roleId === undefined || !this.canManagePermissions() || this.savingPermissions()) {
      return;
    }
    this.savingPermissions.set(true);
    this.rolesService.replacePermissions(roleId, Array.from(this.selectedPermissions())).subscribe({
      next: () => {
        this.savingPermissions.set(false);
        this.snackBar.open('Permisos actualizados correctamente.', 'Cerrar', { duration: 4000 });
      },
      error: () => {
        this.savingPermissions.set(false);
      },
    });
  }

  protected displayLimitValue(index: number, def: RoleLimitDefinition): string {
    const row = this.limitsArray.at(index);
    const unlimited = row.controls.unlimited.value;
    const value = unlimited ? null : row.controls.value.value;
    return formatRoleLimitValue(value, def.unit);
  }

  protected saveLimits(): void {
    const roleId = this.data.role.id;
    if (roleId === undefined || !this.canManageLimits() || this.savingLimits()) {
      return;
    }
    if (this.limitsForm.invalid) {
      this.limitsForm.markAllAsTouched();
      return;
    }

    const items: RoleLimitItemRequest[] = this.limitsArray.controls.map((row) => {
      const { code, unlimited, value } = row.getRawValue();
      return {
        code,
        value: unlimited ? (null as unknown as number) : Number(value ?? 0),
      };
    });

    this.savingLimits.set(true);
    this.rolesService.updateRoleLimits(roleId, items).subscribe({
      next: (updated) => {
        this.applyLimits(updated);
        this.savingLimits.set(false);
        this.snackBar.open('Límites actualizados correctamente.', 'Cerrar', { duration: 4000 });
      },
      error: () => {
        this.savingLimits.set(false);
      },
    });
  }

  private applyLimits(limits: readonly RoleLimitResponse[]): void {
    const byCode = new Map<string, number | null | undefined>();
    for (const item of limits) {
      if (item.code) {
        byCode.set(item.code, item.value);
      }
    }

    ROLE_LIMIT_DEFINITIONS.forEach((def, index) => {
      const row = this.limitsArray.at(index);
      const rawValue = byCode.has(def.code) ? byCode.get(def.code) : 0;
      const isUnlimited = rawValue === null || rawValue === undefined;
      row.controls.unlimited.setValue(isUnlimited, { emitEvent: false });
      if (isUnlimited) {
        row.controls.value.setValue(null, { emitEvent: false });
        row.controls.value.disable({ emitEvent: false });
      } else {
        row.controls.value.setValue(rawValue, { emitEvent: false });
        row.controls.value.enable({ emitEvent: false });
      }
    });
  }

  private createLimitRow(def: RoleLimitDefinition, initialValue: number | null): LimitRowGroup {
    const isUnlimited = initialValue === null;
    const validators = [Validators.required, Validators.min(0)];
    if (def.max !== undefined) {
      validators.push(Validators.max(def.max));
    }

    const unlimitedControl = this.fb.control(isUnlimited);
    const valueControl = new FormControl<number | null>(initialValue, validators);
    if (isUnlimited) {
      valueControl.disable({ emitEvent: false });
    }

    unlimitedControl.valueChanges.subscribe((unlimited) => {
      if (unlimited) {
        valueControl.setValue(null, { emitEvent: false });
        valueControl.disable({ emitEvent: false });
      } else {
        if (valueControl.value === null) {
          valueControl.setValue(0, { emitEvent: false });
        }
        valueControl.enable({ emitEvent: false });
      }
    });

    return this.fb.group({
      code: this.fb.control(def.code),
      unlimited: unlimitedControl,
      value: valueControl,
    });
  }
}
