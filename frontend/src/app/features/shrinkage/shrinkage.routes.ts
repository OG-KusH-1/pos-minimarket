import { Routes } from '@angular/router';
import { Shrinkage } from './shrinkage';

/** Rutas de «Mermas» (cargadas de forma diferida desde `app.routes.ts` bajo `/mermas`). */
export const SHRINKAGE_ROUTES: Routes = [{ path: '', component: Shrinkage, title: 'Mermas' }];
