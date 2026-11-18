import { Routes } from '@angular/router';
import { Stock } from './stock';

/** Rutas de «Stock» (cargadas de forma diferida desde `app.routes.ts` bajo `/inventario/stock`). */
export const STOCK_ROUTES: Routes = [{ path: '', component: Stock, title: 'Stock' }];
