import { Routes } from '@angular/router';
import { Sessions } from './sessions';

/** Rutas de «Caja» (cargadas de forma diferida desde `app.routes.ts` bajo `/caja`). */
export const SESSIONS_ROUTES: Routes = [{ path: '', component: Sessions, title: 'Caja' }];
