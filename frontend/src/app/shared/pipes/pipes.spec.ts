import { ClpPipe } from './clp.pipe';
import { QtyPipe } from './qty.pipe';
import { RutPipe } from './rut.pipe';

describe('ClpPipe', () => {
  const pipe = new ClpPipe();

  it.each([
    [1234567, '$1.234.567'],
    [1000, '$1.000'],
    [999, '$999'],
    [0, '$0'],
    [-1500, '-$1.500'],
  ])('formatea %d como %s', (value, expected) => {
    expect(pipe.transform(value)).toBe(expected);
  });

  it('no usa decimales: redondea al peso', () => {
    expect(pipe.transform(1234.5)).toBe('$1.235');
    expect(pipe.transform(10.4)).toBe('$10');
  });

  it('no muestra signo si el monto se redondea a cero', () => {
    expect(pipe.transform(-0.4)).toBe('$0');
    expect(pipe.transform(-0)).toBe('$0');
  });

  it('devuelve vacío para nulo, indefinido y no finitos', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform(Number.NaN)).toBe('');
    expect(pipe.transform(Infinity)).toBe('');
  });
});

describe('QtyPipe', () => {
  const pipe = new QtyPipe();

  it('muestra enteros sin decimales', () => {
    expect(pipe.transform(2)).toBe('2');
    expect(pipe.transform(0)).toBe('0');
  });

  it('usa coma decimal y hasta 3 decimales sin ceros sobrantes', () => {
    expect(pipe.transform(1.5)).toBe('1,5');
    expect(pipe.transform(0.125)).toBe('0,125');
    expect(pipe.transform(0.1234)).toBe('0,123');
    expect(pipe.transform(2.1)).toBe('2,1');
  });

  it('agrupa miles con punto', () => {
    expect(pipe.transform(1234.5)).toBe('1.234,5');
  });

  it('agrega la unidad', () => {
    expect(pipe.transform(1.5, 'kg')).toBe('1,5 kg');
    expect(pipe.transform(3, 'u')).toBe('3 u');
    expect(pipe.transform(3, null)).toBe('3');
  });

  it('maneja negativos y evita -0', () => {
    expect(pipe.transform(-2.5)).toBe('-2,5');
    expect(pipe.transform(-0.0001)).toBe('0');
  });

  it('devuelve vacío para nulo, indefinido y no finitos', () => {
    expect(pipe.transform(null, 'kg')).toBe('');
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform(Number.NaN)).toBe('');
  });
});

describe('RutPipe', () => {
  const pipe = new RutPipe();

  it('formatea un RUT sin puntos', () => {
    expect(pipe.transform('12345678-5')).toBe('12.345.678-5');
  });

  it('formatea un RUT sin guion', () => {
    expect(pipe.transform('123456785')).toBe('12.345.678-5');
  });

  it('normaliza un RUT ya formateado y pasa la K a mayúscula', () => {
    expect(pipe.transform('12.345.678-5')).toBe('12.345.678-5');
    expect(pipe.transform('7.903.292-k')).toBe('7.903.292-K');
  });

  it('formatea cuerpos cortos', () => {
    expect(pipe.transform('1-9')).toBe('1-9');
    expect(pipe.transform('123456-7')).toBe('123.456-7');
  });

  it('devuelve vacío para nulo y vacío, y el texto original si no es un RUT', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform('')).toBe('');
    expect(pipe.transform('abc')).toBe('abc');
  });
});
