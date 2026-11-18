import { FormControl } from '@angular/forms';
import { computeRutCheckDigit, isValidRut } from './rut';
import { rutValidator } from './rut.validator';

describe('isValidRut', () => {
  it.each([
    '12345678-5',
    '12.345.678-5',
    '123456785',
    '11.111.111-1',
    '11111112-K',
    '11.111.112-k',
  ])('acepta %s', (rut) => {
    expect(isValidRut(rut)).toBe(true);
  });

  it('calcula DV 0 (resultado 11) y K (resultado 10)', () => {
    expect(computeRutCheckDigit('11111112')).toBe('K');
    expect(computeRutCheckDigit('14')).toBe('0');
    expect(isValidRut('14-0')).toBe(true);
    expect(isValidRut('11.111.112-K')).toBe(true);
  });

  it.each(['12345678-9', '12.345.678-K', '11111111-2', '11111112-5'])('rechaza %s', (rut) => {
    expect(isValidRut(rut)).toBe(false);
  });

  it.each(['', '   ', '-', 'abc', '12345678', '123456789012-3', '12 345 678-5x', '5'])(
    'rechaza formato inválido %j',
    (rut) => {
      expect(isValidRut(rut)).toBe(false);
    },
  );

  it('rechaza nulo e indefinido sin lanzar', () => {
    expect(isValidRut(null)).toBe(false);
    expect(isValidRut(undefined)).toBe(false);
  });
});

describe('rutValidator', () => {
  it('no marca error en valores vacíos (para eso está required)', () => {
    expect(rutValidator(new FormControl(''))).toBeNull();
    expect(rutValidator(new FormControl(null))).toBeNull();
  });

  it('acepta un RUT válido y rechaza uno inválido', () => {
    expect(rutValidator(new FormControl('12.345.678-5'))).toBeNull();
    expect(rutValidator(new FormControl('12.345.678-0'))).toEqual({ rut: true });
  });

  it('rechaza valores que no son texto', () => {
    expect(rutValidator(new FormControl(12345678))).toEqual({ rut: true });
  });
});
