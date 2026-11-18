import { Routes } from '@angular/router';
import { Audit } from './audit';

/** Rutas de «Auditoría» (cargadas de forma diferida desde `app.routes.ts` bajo `/seguridad/auditoria`). */
export const AUDIT_ROUTES: Routes = [{ path: '', component: Audit, title: 'Auditoría' }];
