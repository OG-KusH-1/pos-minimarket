import { Routes } from '@angular/router';
import { Suppliers } from './suppliers';

/** Rutas de «Proveedores» (cargadas de forma diferida desde `app.routes.ts` bajo `/compras/proveedores`). */
export const SUPPLIERS_ROUTES: Routes = [{ path: '', component: Suppliers, title: 'Proveedores' }];
