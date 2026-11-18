import { Routes } from '@angular/router';
import { Company } from './company';

/** Rutas de «Empresa» (cargadas de forma diferida desde `app.routes.ts` bajo `/configuracion/empresa`). */
export const COMPANY_ROUTES: Routes = [{ path: '', component: Company, title: 'Empresa' }];
