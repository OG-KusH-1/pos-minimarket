import { Routes } from '@angular/router';
import { Transfers } from './transfers';

/** Rutas de «Transferencias» (cargadas de forma diferida desde `app.routes.ts` bajo `/inventario/transferencias`). */
export const TRANSFERS_ROUTES: Routes = [
  { path: '', component: Transfers, title: 'Transferencias' },
];
