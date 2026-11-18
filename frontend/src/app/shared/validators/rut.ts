/**
 * RUT chileno (RN-30, MODELO_DATOS): validación por módulo 11 y utilidades de formato.
 *
 * Son funciones puras sin dependencias de Angular: las reutilizan el validador de formularios,
 * `RutPipe` y cualquier lógica de negocio.
 */

/** Cuerpo de 1 a 8 dígitos seguido del dígito verificador (0-9 o K), ya normalizado. */
const NORMALIZED_RUT = /^(\d{1,8})([0-9K])$/;

/** Elimina puntos, guiones y espacios y pasa a mayúsculas (`12.345.678-k` -> `12345678K`). */
export function normalizeRut(value: string): string {
  return value.replace(/[.\-\s]/g, '').toUpperCase();
}

/**
 * Calcula el dígito verificador (módulo 11) de un cuerpo numérico.
 *
 * Los dígitos se multiplican de derecha a izquierda por la serie 2,3,4,5,6,7 (cíclica); el
 * resultado es `11 - (suma mod 11)`, donde 11 se escribe `0` y 10 se escribe `K`.
 *
 * @param body Solo dígitos, sin dígito verificador.
 */
export function computeRutCheckDigit(body: string): string {
  let sum = 0;
  let factor = 2;
  for (let i = body.length - 1; i >= 0; i--) {
    sum += Number(body[i]) * factor;
    factor = factor === 7 ? 2 : factor + 1;
  }
  const result = 11 - (sum % 11);
  if (result === 11) {
    return '0';
  }
  return result === 10 ? 'K' : String(result);
}

/**
 * Indica si un texto es un RUT válido. Acepta con o sin puntos y guion, y `k`/`K`.
 * Devuelve `false` (nunca lanza) ante `null`, vacío o formato inválido.
 */
export function isValidRut(value: string | null | undefined): boolean {
  if (typeof value !== 'string') {
    return false;
  }
  const match = NORMALIZED_RUT.exec(normalizeRut(value));
  if (!match) {
    return false;
  }
  const [, body, checkDigit] = match;
  return computeRutCheckDigit(body as string) === checkDigit;
}

/**
 * Formatea un RUT como `12.345.678-5`. No valida el dígito verificador (para eso, `isValidRut`);
 * si el texto no tiene forma de RUT se devuelve sin cambios.
 */
export function formatRut(value: string): string {
  const match = NORMALIZED_RUT.exec(normalizeRut(value));
  if (!match) {
    return value;
  }
  const [, body, checkDigit] = match;
  return `${(body as string).replace(/\B(?=(\d{3})+$)/g, '.')}-${checkDigit}`;
}
