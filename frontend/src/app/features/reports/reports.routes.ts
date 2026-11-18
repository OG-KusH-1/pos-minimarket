import { Routes } from '@angular/router';
import { Reports } from './reports';

/** Rutas de «Reportes» (cargadas de forma diferida desde `app.routes.ts` bajo `/reportes`). */
export const REPORTS_ROUTES: Routes = [{ path: '', component: Reports, title: 'Reportes' }];
