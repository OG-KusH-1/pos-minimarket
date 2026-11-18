import { Routes } from '@angular/router';
import { ProductForm } from './product-form';
import { Products } from './products';

/** Rutas de «Productos» (cargadas de forma diferida desde `app.routes.ts` bajo `/catalogo/productos`). */
export const PRODUCTS_ROUTES: Routes = [
  { path: '', component: Products, title: 'Productos' },
  { path: 'nuevo', component: ProductForm, title: 'Nuevo producto' },
  { path: ':id', component: ProductForm, title: 'Ficha de producto' },
];
