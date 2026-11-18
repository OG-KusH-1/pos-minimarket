import { Routes } from '@angular/router';
import { Returns } from './returns';

/** Rutas de «Devoluciones» (cargadas de forma diferida desde `app.routes.ts` bajo `/pos/devoluciones`). */
export const RETURNS_ROUTES: Routes = [{ path: '', component: Returns, title: 'Devoluciones' }];
