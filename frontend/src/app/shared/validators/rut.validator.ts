import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { isValidRut } from './rut';

/**
 * Validador de formularios para RUT (módulo 11, RN-30).
 *
 * Un valor vacío es válido: combínese con `Validators.required` si el campo es obligatorio.
 * Error producido: `{ rut: true }`.
 */
export const rutValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value: unknown = control.value;
  if (value === null || value === undefined || value === '') {
    return null;
  }
  return typeof value === 'string' && isValidRut(value) ? null : { rut: true };
};
