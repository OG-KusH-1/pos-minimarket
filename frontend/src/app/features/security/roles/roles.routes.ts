import { Routes } from '@angular/router';
import { Roles } from './roles';

/** Rutas de «Roles y permisos» (cargadas de forma diferida desde `app.routes.ts` bajo `/seguridad/roles`). */
export const ROLES_ROUTES: Routes = [{ path: '', component: Roles, title: 'Roles y permisos' }];
