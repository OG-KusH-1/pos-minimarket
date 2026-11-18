import { Routes } from '@angular/router';
import { Stores } from './stores';

/** Rutas de «Locales y terminales» (cargadas de forma diferida desde `app.routes.ts` bajo `/configuracion/locales`). */
export const STORES_ROUTES: Routes = [
  { path: '', component: Stores, title: 'Locales y terminales' },
];
