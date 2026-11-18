import { Routes } from '@angular/router';
import { Brands } from './brands';

/** Rutas de «Marcas» (cargadas de forma diferida desde `app.routes.ts` bajo `/catalogo/marcas`). */
export const BRANDS_ROUTES: Routes = [{ path: '', component: Brands, title: 'Marcas' }];
