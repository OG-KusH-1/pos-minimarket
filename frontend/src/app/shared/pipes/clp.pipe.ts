import { Pipe, PipeTransform } from '@angular/core';

const CLP_NUMBER_FORMAT = new Intl.NumberFormat('es-CL', {
  maximumFractionDigits: 0,
  useGrouping: true,
});

/**
 * Formatea dinero en pesos chilenos: sin decimales y con punto como separador de miles
 * (`1234567` -> `$1.234.567`, `-500` -> `-$500`). Es CLP: el monto no tiene decimales (A.5).
 *
 * Decisión: se usa `Intl.NumberFormat('es-CL')` propio y no `CurrencyPipe`, para no depender de
 * registrar el locale en `app.config.ts`. `null`, `undefined` y valores no finitos dan `''`.
 * Un monto que se redondea a cero nunca muestra signo (`-0.4` -> `$0`).
 */
@Pipe({ name: 'clp' })
export class ClpPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    if (value === null || value === undefined || !Number.isFinite(value)) {
      return '';
    }
    const rounded = Math.round(Math.abs(value));
    const sign = value < 0 && rounded !== 0 ? '-' : '';
    return `${sign}$${CLP_NUMBER_FORMAT.format(rounded)}`;
  }
}
