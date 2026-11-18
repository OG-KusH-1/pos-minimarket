import { Routes } from '@angular/router';
import { Kardex } from './kardex';

/** Rutas de «Kardex» (cargadas de forma diferida desde `app.routes.ts` bajo `/inventario/kardex`). */
export const KARDEX_ROUTES: Routes = [{ path: '', component: Kardex, title: 'Kardex' }];
