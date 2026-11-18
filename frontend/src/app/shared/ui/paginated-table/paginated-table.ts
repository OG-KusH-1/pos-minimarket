import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, TemplateRef, computed, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatPaginatorIntl, MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { PaginatedDataSource, SortCriterion } from './paginated-data-source';
import { createSpanishPaginatorIntl } from './spanish-paginator-intl';

/** Contexto de una celda con plantilla propia: la fila llega como `$implicit`. */
export interface CellContext<T> {
  readonly $implicit: T;
}

/**
 * Definición de una columna de {@link PaginatedTable}.
 *
 * - `key`: identificador único de la columna; si no hay `cell` ni `template`, se muestra `row[key]`.
 * - `sortField`: campo que se envía en `sort=campo,dir`; si falta, la columna no es ordenable.
 * - `cell`: función que devuelve el texto (p. ej. aplicando `ClpPipe` o `RutPipe` a mano).
 * - `template`: plantilla para contenido rico (botones de acción, chips); tiene prioridad sobre `cell`.
 */
export interface TableColumn<T> {
  readonly key: string;
  readonly header: string;
  readonly sortField?: string;
  readonly cell?: (row: T) => string;
  readonly template?: TemplateRef<CellContext<T>>;
  /** Alineación; `end` es la convención para montos y cantidades. */
  readonly align?: 'start' | 'end';
}

/**
 * Tabla genérica con paginación y orden en el servidor, estados de carga, vacío y error.
 *
 * Es solo presentación: el estado vive en el {@link PaginatedDataSource} que entrega el
 * llamador. Propiedad: el padre CREA y DESTRUYE el dataSource (`destroy()` en su `ngOnDestroy`);
 * la tabla nunca lo destruye, para poder reutilizarlo o reemplazarlo. Carga el primer `reload()` el
 * padre. La tabla pasa a esa fuente los
 * eventos de página y orden; así el mismo origen sirve a filtros externos (`setFilters`).
 *
 * Accesibilidad: usa `mat-table`/`mat-paginator`/`matSort`, navegables con teclado; el estado de
 * carga y los mensajes vacío/error se anuncian con `role="status"`/`role="alert"`.
 * Orden: una sola columna a la vez (el contrato admite varias; extender `onSort` si se requiere).
 */
@Component({
  selector: 'pos-paginated-table',
  imports: [
    MatButtonModule,
    MatPaginatorModule,
    MatProgressBarModule,
    MatSortModule,
    MatTableModule,
    NgTemplateOutlet,
  ],
  templateUrl: './paginated-table.html',
  styleUrl: './paginated-table.scss',
  providers: [{ provide: MatPaginatorIntl, useFactory: createSpanishPaginatorIntl }],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaginatedTable<T> {
  readonly dataSource = input.required<PaginatedDataSource<T>>();
  readonly columns = input.required<readonly TableColumn<T>[]>();
  /** Texto accesible de la tabla (se usa como `aria-label`). */
  readonly label = input('Tabla de resultados');
  readonly emptyMessage = input('No hay resultados para mostrar.');
  readonly pageSizeOptions = input<readonly number[]>([10, 20, 50, 100]);

  protected readonly columnKeys = computed(() => this.columns().map((column) => column.key));
  protected readonly activeSort = computed(() => this.dataSource().query().sort[0] ?? null);

  protected cellText(row: T, column: TableColumn<T>): string {
    if (column.cell) {
      return column.cell(row);
    }
    const value = (row as Record<string, unknown>)[column.key];
    return value === null || value === undefined ? '' : String(value);
  }

  protected onPage(event: PageEvent): void {
    this.dataSource().setPage(event.pageIndex, event.pageSize);
  }

  protected onSort(event: Sort): void {
    const criteria: readonly SortCriterion[] = event.direction
      ? [{ field: event.active, direction: event.direction }]
      : [];
    this.dataSource().setSort(criteria);
  }

  protected retry(): void {
    this.dataSource().reload();
  }
}
