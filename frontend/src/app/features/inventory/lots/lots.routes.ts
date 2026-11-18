import { Routes } from '@angular/router';
import { Lots } from './lots';

/** Rutas de «Lotes y vencimientos» (cargadas de forma diferida desde `app.routes.ts` bajo `/inventario/lotes`). */
export const LOTS_ROUTES: Routes = [{ path: '', component: Lots, title: 'Lotes y vencimientos' }];
