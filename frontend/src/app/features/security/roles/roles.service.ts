import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiConfiguration } from '../../../api/api-configuration';
import { createRole } from '../../../api/fn/security/create-role';
import { deleteRole } from '../../../api/fn/security/delete-role';
import { getRole } from '../../../api/fn/security/get-role';
import { getRoleLimits } from '../../../api/fn/security/get-role-limits';
import { listPermissions } from '../../../api/fn/security/list-permissions';
import { listRoles, ListRoles$Params } from '../../../api/fn/security/list-roles';
import { replacePermissions } from '../../../api/fn/security/replace-permissions';
import { updateRole } from '../../../api/fn/security/update-role';
import { updateRoleLimits } from '../../../api/fn/security/update-role-limits';
import { PageResponseRoleResponse } from '../../../api/models/page-response-role-response';
import { PermissionResponse } from '../../../api/models/permission-response';
import { RoleLimitItemRequest } from '../../../api/models/role-limit-item-request';
import { RoleLimitResponse } from '../../../api/models/role-limit-response';
import { RoleRequest } from '../../../api/models/role-request';
import { RoleResponse } from '../../../api/models/role-response';
import { unwrapApiResponse, unwrapVoidApiResponse } from '../users/users.service';

/**
 * Servicio de acceso a la API de roles, permisos y límites numéricos (`API.md` F.2).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`.
 */
@Injectable({ providedIn: 'root' })
export class RolesService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista roles paginados. */
  listRoles(
    params?: ListRoles$Params,
    context?: HttpContext,
  ): Observable<PageResponseRoleResponse> {
    return unwrapApiResponse(listRoles(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle de un rol por ID. */
  getRole(id: number): Observable<RoleResponse> {
    return unwrapApiResponse(getRole(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Crea un rol nuevo (el servidor inicializa sus límites en 0, fail-closed). */
  createRole(body: RoleRequest, context?: HttpContext): Observable<RoleResponse> {
    return unwrapApiResponse(createRole(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza nombre y descripción de un rol (`code` es inmutable en el servidor). */
  updateRole(id: number, body: RoleRequest, context?: HttpContext): Observable<RoleResponse> {
    return unwrapApiResponse(updateRole(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Elimina un rol personalizado sin usuarios asignados. */
  deleteRole(id: number): Observable<void> {
    return unwrapVoidApiResponse(deleteRole(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Lista el catálogo completo de permisos disponibles en el sistema. */
  listPermissions(): Observable<PermissionResponse[]> {
    return unwrapApiResponse(listPermissions(this.http, this.apiConfig.rootUrl));
  }

  /** Reemplaza el conjunto de permisos asignados a un rol. */
  replacePermissions(id: number, permissionCodes: string[]): Observable<RoleResponse> {
    return unwrapApiResponse(
      replacePermissions(this.http, this.apiConfig.rootUrl, {
        id,
        body: { permissionCodes },
      }),
    );
  }

  /** Obtiene los límites numéricos configurados para un rol (`null` = ilimitado / `∞`). */
  getRoleLimits(id: number): Observable<RoleLimitResponse[]> {
    return unwrapApiResponse(getRoleLimits(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Actualiza los límites numéricos de un rol (`null` en `value` representa `∞`). */
  updateRoleLimits(id: number, limits: RoleLimitItemRequest[]): Observable<RoleLimitResponse[]> {
    return unwrapApiResponse(
      updateRoleLimits(this.http, this.apiConfig.rootUrl, {
        id,
        body: { limits },
      }),
    );
  }
}
