import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideApiConfiguration } from './api/api-configuration';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';
import { errorInterceptor } from './core/http/error.interceptor';
import { idempotencyInterceptor } from './core/http/idempotency.interceptor';
import { storeContextInterceptor } from './core/store-context/store-context.interceptor';

/**
 * Configuración de la aplicación. Es zoneless (sin `zone.js`, que es el comportamiento por
 * defecto en Angular 22: no se importa ningún polyfill).
 *
 * Orden de los interceptores HTTP (importa: en la petición se ejecutan de izquierda a derecha y
 * en la respuesta de derecha a izquierda):
 * 1. `errorInterceptor`: el más externo, para ver el resultado FINAL (después del refresh y el
 *    reintento de `authInterceptor`) y no avisar de errores 401 ya recuperados.
 * 2. `authInterceptor`: Bearer y recuperación de 401. Al reintentar llama a `next`, por lo que
 *    los dos interceptores siguientes se vuelven a ejecutar sobre la petición reintentada.
 * 3. `storeContextInterceptor`: `X-Store-Id` / `X-Terminal-Id` (se reevalúan en el reintento).
 * 4. `idempotencyInterceptor`: `Idempotency-Key`; la clave vive en el `HttpContext` de la
 *    petición, así que el reintento reenvía la misma.
 *
 * La sesión se restaura antes de arrancar (`provideAppInitializer`): el refresh con la cookie
 * HttpOnly deja listo el usuario para que los guards decidan sin parpadeos. Nunca falla.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideApiConfiguration(''),
    provideHttpClient(
      withInterceptors([
        errorInterceptor,
        authInterceptor,
        storeContextInterceptor,
        idempotencyInterceptor,
      ]),
    ),
    provideAppInitializer(() => inject(AuthService).restoreSession()),
  ],
};
