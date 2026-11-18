import { Routes } from '@angular/router';
import { Customers } from './customers';

/** Rutas de «Clientes» (cargadas de forma diferida desde `app.routes.ts` bajo `/clientes`). */
export const CUSTOMERS_ROUTES: Routes = [{ path: '', component: Customers, title: 'Clientes' }];
