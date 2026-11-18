import { Pipe, PipeTransform } from '@angular/core';
import { formatRut } from '../validators/rut';

/**
 * Muestra un RUT como `12.345.678-5`. Acepta el valor con o sin puntos/guion. Solo formatea: no
 * valida el dígito verificador. `null`/vacío dan `''` y un texto sin forma de RUT se devuelve tal cual.
 */
@Pipe({ name: 'rut' })
export class RutPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return value ? formatRut(value) : '';
  }
}
