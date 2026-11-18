import { Routes } from '@angular/router';
import { Imports } from './imports';

/** Rutas de «Importaciones» (cargadas de forma diferida desde `app.routes.ts` bajo `/catalogo/importaciones`). */
export const IMPORTS_ROUTES: Routes = [{ path: '', component: Imports, title: 'Importaciones' }];
