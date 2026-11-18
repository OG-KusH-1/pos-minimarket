import { Routes } from '@angular/router';
import { Categories } from './categories';

/** Rutas de «Categorías» (cargadas de forma diferida desde `app.routes.ts` bajo `/catalogo/categorias`). */
export const CATEGORIES_ROUTES: Routes = [{ path: '', component: Categories, title: 'Categorías' }];
