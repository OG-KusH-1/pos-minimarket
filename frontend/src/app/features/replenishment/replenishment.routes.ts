import { Routes } from '@angular/router';
import { Replenishment } from './replenishment';

/** Rutas de «Reabastecimiento» (cargadas de forma diferida desde `app.routes.ts` bajo `/reabastecimiento`). */
export const REPLENISHMENT_ROUTES: Routes = [
  { path: '', component: Replenishment, title: 'Reabastecimiento' },
];
