/**
 * Genera un UUID versión 4 para el header `Idempotency-Key` (ADR-14).
 *
 * `crypto.randomUUID()` solo existe en contextos seguros (HTTPS o localhost). Un POS en una red
 * local puede servirse por HTTP desde una IP, así que se cae a `crypto.getRandomValues`, que sí
 * está disponible, para no perder la idempotencia en ese escenario.
 *
 * @returns UUID v4 en minúsculas con el formato canónico 8-4-4-4-12.
 */
export function generateUuid(): string {
  if (typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40; // versión 4
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80; // variante RFC 4122
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
