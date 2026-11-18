import { Routes } from '@angular/router';
import { Sale } from './sale';

/** Rutas de «Venta» (cargadas de forma diferida desde `app.routes.ts` bajo `/pos/venta`). */
export const SALE_ROUTES: Routes = [{ path: '', component: Sale, title: 'Venta' }];
