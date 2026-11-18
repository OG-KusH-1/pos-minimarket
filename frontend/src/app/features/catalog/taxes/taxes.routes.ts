import { Routes } from '@angular/router';
import { Taxes } from './taxes';

/** Rutas de «Impuestos» (cargadas de forma diferida desde `app.routes.ts` bajo `/catalogo/impuestos`). */
export const TAXES_ROUTES: Routes = [{ path: '', component: Taxes, title: 'Impuestos' }];
