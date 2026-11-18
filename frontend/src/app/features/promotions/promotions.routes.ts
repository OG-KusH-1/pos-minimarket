import { Routes } from '@angular/router';
import { Promotions } from './promotions';

/** Rutas de «Promociones» (cargadas de forma diferida desde `app.routes.ts` bajo `/promociones`). */
export const PROMOTIONS_ROUTES: Routes = [
  { path: '', component: Promotions, title: 'Promociones' },
];
