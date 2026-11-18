import { Routes } from '@angular/router';
import { Dashboard } from './dashboard';

/** Rutas de «Inicio» (cargadas de forma diferida desde `app.routes.ts` bajo `/inicio`). */
export const DASHBOARD_ROUTES: Routes = [{ path: '', component: Dashboard, title: 'Inicio' }];
