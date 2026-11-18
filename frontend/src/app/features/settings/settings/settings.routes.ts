import { Routes } from '@angular/router';
import { Settings } from './settings';

/** Rutas de «Parámetros» (cargadas de forma diferida desde `app.routes.ts` bajo `/configuracion/parametros`). */
export const SETTINGS_ROUTES: Routes = [{ path: '', component: Settings, title: 'Parámetros' }];
