import { Routes } from '@angular/router';
import { Orders } from './orders';

/** Rutas de «Órdenes de compra» (cargadas de forma diferida desde `app.routes.ts` bajo `/compras/ordenes`). */
export const ORDERS_ROUTES: Routes = [{ path: '', component: Orders, title: 'Órdenes de compra' }];
