import { HttpClient, HttpParams } from '@angular/common/http';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { Signal, signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { PageResponse, SortDirection } from '../../models/page-response';

/** Tamaño de página por defecto y máximo del servidor (API.md A.5). */
export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 200;

/** Un criterio de orden: se envía como `sort=campo,asc|desc`. */
export interface SortCriterion {
  readonly field: string;
  readonly direction: SortDirection;
}

/** Valor de un filtro. `null`, `undefined` y `''` se omiten; un arreglo se envía repetido. */
export type FilterValue = string | number | boolean | null | undefined;
export type Filters = Readonly<Record<string, FilterValue | readonly FilterValue[]>>;

/** Estado de consulta de una tabla paginada. */
export interface PageQuery {
  /** Página base 0. */
  readonly page: number;
  readonly size: number;
  readonly sort: readonly SortCriterion[];
  readonly filters: Filters;
}

/** Función que ejecuta la consulta con los query params ya construidos. */
export type PageFetcher<T> = (params: HttpParams) => Observable<PageResponse<T>>;

/**
 * Traduce un {@link PageQuery} a query params de la API (A.5): `page` (base 0), `size` (acotado a
 * 1..200), un `sort=campo,dir` por criterio (repetible) y cada filtro con valor como parámetro
 * propio (los arreglos se repiten). Función pura, reutilizable fuera de la tabla.
 */
export function buildPageParams(query: PageQuery): HttpParams {
  let params = new HttpParams()
    .set('page', Math.max(0, Math.trunc(query.page)))
    .set('size', Math.min(MAX_PAGE_SIZE, Math.max(1, Math.trunc(query.size))));
  for (const { field, direction } of query.sort) {
    params = params.append('sort', `${field},${direction}`);
  }
  for (const [name, raw] of Object.entries(query.filters)) {
    const values: readonly FilterValue[] = Array.isArray(raw) ? raw : [raw as FilterValue];
    for (const value of values) {
      if (value !== null && value !== undefined && value !== '') {
        params = params.append(name, String(value));
      }
    }
  }
  return params;
}

/**
 * Crea un {@link PageFetcher} que hace `GET url` con los parámetros de paginación. Suprime el
 * snackbar automático (`withoutErrorNotification`) porque la tabla muestra el error en su estado.
 */
export function httpPageFetcher<T>(http: HttpClient, url: string): PageFetcher<T> {
  return (params) =>
    http.get<PageResponse<T>>(url, { params, context: withoutErrorNotification() });
}

/** Opciones iniciales de {@link PaginatedDataSource}. */
export interface PaginatedDataSourceOptions {
  readonly pageSize?: number;
  readonly sort?: readonly SortCriterion[];
  readonly filters?: Filters;
}

/**
 * Origen de datos paginado en el servidor para `PaginatedTableComponent` (o cualquier vista).
 *
 * Mantiene el estado de consulta (página, tamaño, orden, filtros) y el resultado como signals de
 * solo lectura. Cada cambio de consulta cancela la petición anterior (evita que una respuesta
 * vieja pise a una nueva) y vuelve a cargar. Cambiar filtros u orden regresa a la página 0.
 *
 * Ciclo de vida: no depende del contexto de inyección; el dueño (componente) debe llamar
 * {@link destroy} al destruirse. La carga inicial es explícita con {@link reload}.
 *
 * Errores: si la petición falla, `error` guarda el mensaje (ya traducido por `errorInterceptor`
 * a `ApiError`) y `rows` queda vacío; el interceptor ya notificó al usuario.
 * Extensión: `PageFetcher` desacopla la fuente; para filtros tipados, envolver `setFilters`.
 */
export class PaginatedDataSource<T> {
  private readonly _rows = signal<readonly T[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);
  private readonly _totalElements = signal(0);
  private readonly _query = signal<PageQuery>({
    page: 0,
    size: DEFAULT_PAGE_SIZE,
    sort: [],
    filters: {},
  });
  private subscription: Subscription | null = null;

  readonly rows: Signal<readonly T[]> = this._rows.asReadonly();
  readonly loading: Signal<boolean> = this._loading.asReadonly();
  readonly error: Signal<string | null> = this._error.asReadonly();
  readonly totalElements: Signal<number> = this._totalElements.asReadonly();
  readonly query: Signal<PageQuery> = this._query.asReadonly();

  constructor(
    private readonly fetcher: PageFetcher<T>,
    options: PaginatedDataSourceOptions = {},
  ) {
    this._query.set({
      page: 0,
      size: options.pageSize ?? DEFAULT_PAGE_SIZE,
      sort: options.sort ?? [],
      filters: options.filters ?? {},
    });
  }

  /** Cambia de página y/o tamaño (base 0) y recarga. */
  setPage(page: number, size: number = this._query().size): void {
    this._query.update((q) => ({ ...q, page, size }));
    this.reload();
  }

  /** Reemplaza el orden (vacío = orden por defecto del servidor); vuelve a la página 0. */
  setSort(sort: readonly SortCriterion[]): void {
    this._query.update((q) => ({ ...q, sort, page: 0 }));
    this.reload();
  }

  /** Reemplaza los filtros; vuelve a la página 0. */
  setFilters(filters: Filters): void {
    this._query.update((q) => ({ ...q, filters, page: 0 }));
    this.reload();
  }

  /** Ejecuta la consulta actual cancelando la anterior en vuelo. */
  reload(): void {
    this.subscription?.unsubscribe();
    this._loading.set(true);
    this._error.set(null);
    this.subscription = this.fetcher(buildPageParams(this._query())).subscribe({
      next: (response) => {
        this._rows.set(response.content);
        this._totalElements.set(response.totalElements);
        this._loading.set(false);
      },
      error: (error: unknown) => {
        this._rows.set([]);
        this._totalElements.set(0);
        this._error.set(error instanceof Error ? error.message : 'No se pudieron cargar los datos');
        this._loading.set(false);
      },
    });
  }

  /** Cancela la petición en vuelo. Llamar desde `ngOnDestroy` del dueño. */
  destroy(): void {
    this.subscription?.unsubscribe();
    this.subscription = null;
  }
}
