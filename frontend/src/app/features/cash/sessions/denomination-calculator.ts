/** Definición de una denominación de billete o moneda de peso chileno (CLP). */
export interface ClpDenomination {
  readonly value: number;
  readonly label: string;
  readonly kind: 'BILL' | 'COIN';
}

/** Catálogo oficial de billetes y monedas vigentes en Chile (CLP). */
export const CLP_DENOMINATIONS: readonly ClpDenomination[] = [
  { value: 20000, label: '$20.000', kind: 'BILL' },
  { value: 10000, label: '$10.000', kind: 'BILL' },
  { value: 5000, label: '$5.000', kind: 'BILL' },
  { value: 2000, label: '$2.000', kind: 'BILL' },
  { value: 1000, label: '$1.000', kind: 'BILL' },
  { value: 500, label: '$500', kind: 'COIN' },
  { value: 100, label: '$100', kind: 'COIN' },
  { value: 50, label: '$50', kind: 'COIN' },
  { value: 10, label: '$10', kind: 'COIN' },
];

/** Mapa de denominación (clave numérica como texto) a cantidad de unidades contadas. */
export type DenominationQuantities = Readonly<Record<string, number>>;

/**
 * Calculadora de arqueo de efectivo por denominación CLP (`HU-CAJ-03`).
 *
 * Calcula `Σ denominación × cantidad = contado` sin aritmética de punto flotante y genera el mapa
 * `denominationDetail` requerido por `POST /api/v1/cash-sessions/{id}/close`.
 */
export class DenominationCalculator {
  private readonly knownValues: ReadonlySet<number>;

  constructor(readonly denominations: readonly ClpDenomination[] = CLP_DENOMINATIONS) {
    this.knownValues = new Set(denominations.map((d) => d.value));
  }

  /** Crea un diccionario inicializado en `0` para todas las denominaciones configuradas. */
  emptyQuantities(): Record<string, number> {
    const map: Record<string, number> = {};
    for (const denom of this.denominations) {
      map[String(denom.value)] = 0;
    }
    return map;
  }

  /** Normaliza una cantidad ingresada a un entero mayor o igual a cero. */
  normalizeQuantity(raw: unknown): number {
    if (typeof raw === 'number') {
      return Number.isFinite(raw) && raw > 0 ? Math.trunc(raw) : 0;
    }
    if (typeof raw === 'string' && raw.trim() !== '') {
      const parsed = Number(raw);
      return Number.isFinite(parsed) && parsed > 0 ? Math.trunc(parsed) : 0;
    }
    return 0;
  }

  /** Calcula el subtotal en CLP de una denominación (`denominación × cantidad`). */
  lineSubtotal(denomination: number, quantity: unknown): number {
    if (!Number.isSafeInteger(denomination) || denomination <= 0) {
      return 0;
    }
    return denomination * this.normalizeQuantity(quantity);
  }

  /**
   * Calcula el total contado (`Σ denominación × cantidad`) para las denominaciones conocidas.
   */
  calculateTotal(quantities: Readonly<Record<string, unknown>>): number {
    let total = 0;
    for (const denom of this.denominations) {
      const qty = this.normalizeQuantity(quantities[String(denom.value)]);
      total += denom.value * qty;
    }
    return total;
  }

  /**
   * Construye el objeto `denominationDetail` para enviar al backend, omitiendo denominaciones en 0.
   * Si no hay ninguna denominación positiva devuelve `undefined`.
   */
  buildDetail(quantities: Readonly<Record<string, unknown>>): Record<string, number> | undefined {
    const detail: Record<string, number> = {};
    let hasPositive = false;
    for (const denom of this.denominations) {
      const key = String(denom.value);
      const qty = this.normalizeQuantity(quantities[key]);
      if (qty > 0 && this.knownValues.has(denom.value)) {
        detail[key] = qty;
        hasPositive = true;
      }
    }
    return hasPositive ? detail : undefined;
  }

  /** Calcula la diferencia de arqueo (`contado - esperado`) en pesos CLP. */
  difference(counted: number, expected: number): number {
    const c = Number.isFinite(counted) ? Math.round(counted) : 0;
    const e = Number.isFinite(expected) ? Math.round(expected) : 0;
    return c - e;
  }

  /** Indica si la diferencia absoluta supera la tolerancia configurada (`|diff| > tolerance`). */
  exceedsTolerance(difference: number, tolerance: number): boolean {
    const tol = Number.isFinite(tolerance) && tolerance > 0 ? Math.round(tolerance) : 0;
    return Math.abs(Math.round(difference)) > tol;
  }
}
