import { Routes } from '@angular/router';
import { Scale } from './scale';

/** Rutas de «Códigos de balanza» (cargadas de forma diferida desde `app.routes.ts` bajo `/catalogo/balanza`). */
export const SCALE_ROUTES: Routes = [{ path: '', component: Scale, title: 'Códigos de balanza' }];
