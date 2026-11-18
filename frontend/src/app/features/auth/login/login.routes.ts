import { Routes } from '@angular/router';
import { Login } from './login';

/** Rutas de «Iniciar sesión» (cargadas de forma diferida desde `app.routes.ts` bajo `/login`). */
export const LOGIN_ROUTES: Routes = [{ path: '', component: Login, title: 'Iniciar sesión' }];
