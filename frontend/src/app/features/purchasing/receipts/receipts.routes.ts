import { Routes } from '@angular/router';
import { Receipts } from './receipts';

/** Rutas de «Recepciones» (cargadas de forma diferida desde `app.routes.ts` bajo `/compras/recepciones`). */
export const RECEIPTS_ROUTES: Routes = [{ path: '', component: Receipts, title: 'Recepciones' }];
