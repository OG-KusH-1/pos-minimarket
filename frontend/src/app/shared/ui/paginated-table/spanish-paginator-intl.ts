import { MatPaginatorIntl } from '@angular/material/paginator';

/**
 * Textos en español del paginador. Se provee a nivel de {@link PaginatedTable} (no global) para
 * no tocar `app.config.ts`; si luego se provee globalmente, este provider puede retirarse.
 */
export function createSpanishPaginatorIntl(): MatPaginatorIntl {
  const intl = new MatPaginatorIntl();
  intl.itemsPerPageLabel = 'Filas por página:';
  intl.nextPageLabel = 'Página siguiente';
  intl.previousPageLabel = 'Página anterior';
  intl.firstPageLabel = 'Primera página';
  intl.lastPageLabel = 'Última página';
  intl.getRangeLabel = (page, pageSize, length) => {
    if (length === 0) {
      return '0 de 0';
    }
    const start = page * pageSize;
    const end = Math.min(start + pageSize, length);
    return `${start + 1} – ${end} de ${length}`;
  };
  return intl;
}
