/**
 * Utilidades para decidir si una URL pertenece a la API propia y de qué tipo de endpoint es.
 *
 * Todos los interceptores comparten estas reglas para no filtrar credenciales ni cabeceras de
 * contexto hacia terceros: solo se tratan las URL del mismo origen bajo `/api/v1`. En desarrollo
 * `proxy.conf.json` reenvía `/api` al backend y en producción el servidor web hace lo mismo, de
 * modo que el cliente generado (T27) debe configurarse con `rootUrl` vacío o del mismo origen.
 */

/** Prefijo de la API (ADR: versionado por ruta, API.md F.1). */
export const API_BASE_PATH = '/api/v1';

/** Prefijo de los endpoints de autenticación; solo a ellos viaja la cookie de refresh (ADR-20). */
export const AUTH_BASE_PATH = `${API_BASE_PATH}/auth`;

/** Endpoints públicos de auth: no llevan Bearer ni disparan el flujo de refresh ante un 401. */
export const PUBLIC_AUTH_PATHS: readonly string[] = [
  `${AUTH_BASE_PATH}/login`,
  `${AUTH_BASE_PATH}/refresh`,
];

/** Patrón de un endpoint global [G]: ruta (sin `/api/v1`) y, opcionalmente, los métodos HTTP. */
export interface GlobalEndpointPattern {
  readonly path: RegExp;
  /** Si se omite, aplica a cualquier método. */
  readonly methods?: readonly string[];
}

/**
 * Endpoints marcados **[G]** (globales) en API.md F.2: NO requieren `X-Store-Id` (ni terminal).
 * Contrastada fila por fila con F.2: cada [G] está aquí y ningún endpoint sin [G] coincide
 * (lo verifica `store-context.interceptor.spec.ts`).
 *
 * Rutas relativas a `/api/v1`, sin query. Reglas:
 * - Auth ("todo [G]" salvo lo indicado): `login`, `refresh`, `logout`, `me`, `me/stores`,
 *   `me/password`, `me/pin`. NO lo son `pin-login` [T], `overrides` ni `session/terminal`.
 * - Security y Audit (secciones [G]): users, roles, permissions, audit-log.
 * - Organization: `company`, `stores` (CRUD, activar/desactivar) y SOLO `GET stores/{id}/terminals`.
 *   NO son [G] `POST stores/{id}/terminals`, `terminals/**`, `settings/**` ni `files/**`.
 * - Catálogo maestro, proveedores, promociones: todo [G] bajo categories, brands, taxes, products,
 *   scale-barcode-configs, catalog, suppliers, product-suppliers, promotions.
 * - Clientes: [G] el CRUD, activar/desactivar y `credit`; NO `account`, `payments` ni `adjustments`.
 * - Lo demás (ventas, caja, inventario, compras, mermas, reabastecimiento, reportes, `/pos/**`…)
 *   es local-scoped y recibe `X-Store-Id`.
 *
 * Nota de contraste con el backend (`pos.store-context.global-paths`): la configuración solo
 * lista login/refresh y rutas de infraestructura; el resto de los [G] lo declara el backend con la
 * anotación `@GlobalEndpoint` por controlador, así que F.2 es la fuente para esta lista.
 *
 * Mantenimiento: al agregar un [G], agregar aquí su patrón y su caso en el spec. Ante la duda no
 * listarlo: un header de más es inocuo, su ausencia en un endpoint local-scoped da error.
 */
export const GLOBAL_ENDPOINT_PATTERNS: readonly GlobalEndpointPattern[] = [
  { path: /^\/auth\/(login|refresh|logout)$/ },
  { path: /^\/auth\/me(\/(stores|password|pin))?$/ },
  { path: /^\/(users|roles|permissions|audit-log)(\/.*)?$/ },
  { path: /^\/company$/ },
  { path: /^\/stores(\/\d+(\/(deactivate|activate))?)?$/ },
  { path: /^\/stores\/\d+\/terminals$/, methods: ['GET'] },
  { path: /^\/(categories|brands|taxes|products|scale-barcode-configs|catalog)(\/.*)?$/ },
  { path: /^\/(suppliers|product-suppliers|promotions)(\/.*)?$/ },
  { path: /^\/customers(\/\d+(\/(deactivate|activate|credit))?)?$/ },
];

/**
 * Devuelve la ruta de una URL de la API sin el prefijo `/api/v1` y sin query ni fragmento
 * (por ejemplo `'/auth/login'`), o `null` si la URL no es de la API propia.
 *
 * @param url URL de la petición, relativa o absoluta.
 */
export function apiRelativePath(url: string): string | null {
  const origin = globalThis.location?.origin ?? 'http://localhost';
  let parsed: URL;
  try {
    parsed = new URL(url, origin);
  } catch {
    return null;
  }
  if (parsed.origin !== origin) {
    return null;
  }
  const { pathname } = parsed;
  if (pathname !== API_BASE_PATH && !pathname.startsWith(`${API_BASE_PATH}/`)) {
    return null;
  }
  return pathname.slice(API_BASE_PATH.length) || '/';
}

/** Indica si la URL es de la API propia. */
export function isApiUrl(url: string): boolean {
  return apiRelativePath(url) !== null;
}

/** Indica si la URL pertenece a `/api/v1/auth/*` (los únicos que reciben la cookie de refresh). */
export function isAuthUrl(url: string): boolean {
  const path = apiRelativePath(url);
  return path !== null && (path === '/auth' || path.startsWith('/auth/'));
}

/** Indica si la URL es `login` o `refresh` (públicos: sin Bearer ni reintento por 401). */
export function isPublicAuthUrl(url: string): boolean {
  const path = apiRelativePath(url);
  return path !== null && PUBLIC_AUTH_PATHS.includes(`${API_BASE_PATH}${path}`);
}

/**
 * Indica si la URL es un endpoint global [G] (no requiere `X-Store-Id`).
 * Una URL que no es de la API se considera "global" para que no se le agreguen cabeceras.
 *
 * @param url URL de la petición.
 * @param method Método HTTP (algunos patrones dependen de él).
 */
export function isGlobalEndpoint(url: string, method = 'GET'): boolean {
  const path = apiRelativePath(url);
  if (path === null) {
    return true;
  }
  const verb = method.toUpperCase();
  return GLOBAL_ENDPOINT_PATTERNS.some(
    (pattern) =>
      pattern.path.test(path) && (pattern.methods === undefined || pattern.methods.includes(verb)),
  );
}
