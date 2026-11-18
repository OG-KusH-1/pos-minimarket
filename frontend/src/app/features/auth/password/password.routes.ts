import { Routes } from '@angular/router';
import { Password } from './password';

/** Rutas de «Cambiar contraseña» (cargadas de forma diferida desde `app.routes.ts` bajo `/cambiar-clave`). */
export const PASSWORD_ROUTES: Routes = [
  { path: '', component: Password, title: 'Cambiar contraseña' },
];
