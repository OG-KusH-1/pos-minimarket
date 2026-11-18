import { Routes } from '@angular/router';
import { CountDetail } from './count-detail';
import { Counts } from './counts';

/**
 * Rutas de «Tomas de inventario» (cargadas de forma diferida desde `app.routes.ts` bajo
 * `/inventario/tomas`).
 */
export const COUNTS_ROUTES: Routes = [
  { path: '', component: Counts, title: 'Tomas de inventario' },
  { path: ':id', component: CountDetail, title: 'Detalle de toma de inventario' },
];
