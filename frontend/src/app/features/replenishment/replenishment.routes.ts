import { Routes } from '@angular/router';
import { RunDetail } from './run-detail';
import { Replenishment } from './replenishment';

/**
 * Rutas de «Reabastecimiento» (cargadas de forma diferida desde `app.routes.ts` bajo
 * `/reabastecimiento`): bandeja con pestañas y detalle de corrida con edición de líneas.
 */
export const REPLENISHMENT_ROUTES: Routes = [
  { path: '', component: Replenishment, title: 'Reabastecimiento' },
  { path: ':id', component: RunDetail, title: 'Detalle de corrida' },
];
