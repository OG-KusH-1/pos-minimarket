/**
 * Contrato genérico de paginación de la API (API.md A.5).
 *
 * OpenAPI / Springdoc genera un esquema concreto por entidad (`PageResponseProductSummary`,
 * `PageResponseUserSummary`, etc. en `src/app/api/models`), sin tipo genérico compartido; esta
 * interfaz se mantiene como contrato estructural genérico para `PaginatedDataSource<T>`.
 */

/** Dirección de orden en el formato `campo,asc|desc` de A.5. */
export type SortDirection = 'asc' | 'desc';

/** Respuesta paginada estándar `PageResponse{content, page, size, totalElements, totalPages, sort}`. */
export interface PageResponse<T> {
  readonly content: readonly T[];
  /** Número de página, base 0. */
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
  /** Órdenes aplicados (`campo,asc|desc`); puede venir vacío. */
  readonly sort: readonly string[];
}
