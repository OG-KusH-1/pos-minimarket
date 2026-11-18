import { Routes } from '@angular/router';
import { Users } from './users';

/** Rutas de «Usuarios» (cargadas de forma diferida desde `app.routes.ts` bajo `/seguridad/usuarios`). */
export const USERS_ROUTES: Routes = [{ path: '', component: Users, title: 'Usuarios' }];
