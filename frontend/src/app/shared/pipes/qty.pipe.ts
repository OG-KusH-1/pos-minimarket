import { Pipe, PipeTransform } from '@angular/core';

/** Unidades de medida admitidas: kilogramos (pesables) o unidades. */
export type QtyUnit = 'kg' | 'u';

const QTY_NUMBER_FORMAT = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 3,
  useGrouping: true,
});

/**
 * Formatea cantidades de inventario/venta (A.5: hasta 3 decimales): coma decimal, punto de
 * miles, sin ceros sobrantes (`2` -> `2`, `1.5` -> `1,5`, `0.1234` -> `0,123`) y unidad opcional
 * (`transform(1.5, 'kg')` -> `1,5 kg`). `null`, `undefined` y no finitos dan `''`.
 * Extensión: para otra unidad, ampliar {@link QtyUnit}.
 */
@Pipe({ name: 'qty' })
export class QtyPipe implements PipeTransform {
  transform(value: number | null | undefined, unit?: QtyUnit | null): string {
    if (value === null || value === undefined || !Number.isFinite(value)) {
      return '';
    }
    let text = QTY_NUMBER_FORMAT.format(value);
    if (text === '-0') {
      text = '0';
    }
    return unit ? `${text} ${unit}` : text;
  }
}
