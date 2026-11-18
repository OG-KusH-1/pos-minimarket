import { Routes } from '@angular/router';
import { Adjustments } from './adjustments';

/** Rutas de «Ajustes de inventario» (cargadas de forma diferida desde `app.routes.ts` bajo `/inventario/ajustes`). */
export const ADJUSTMENTS_ROUTES: Routes = [
  { path: '', component: Adjustments, title: 'Ajustes de inventario' },
];
