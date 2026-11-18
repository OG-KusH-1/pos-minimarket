import { RoleLimitItemRequest } from '../../../api/models/role-limit-item-request';

/** Código de límite de rol soportado por la API (`ROLES_PERMISOS.md` D.3). */
export type RoleLimitCode = RoleLimitItemRequest['code'];

/** Definición de presentación y validación para cada límite por rol. */
export interface RoleLimitDefinition {
  readonly code: RoleLimitCode;
  readonly label: string;
  readonly unit: '%' | 'CLP';
  readonly max?: number;
}

/** Catálogo ordenado de los 10 límites numéricos configurables por rol. */
export const ROLE_LIMIT_DEFINITIONS: readonly RoleLimitDefinition[] = [
  {
    code: 'DISCOUNT_ITEM_MAX_PCT',
    label: 'Descuento máximo por ítem',
    unit: '%',
    max: 100,
  },
  {
    code: 'DISCOUNT_TOTAL_MAX_PCT',
    label: 'Descuento máximo al total (%)',
    unit: '%',
    max: 100,
  },
  {
    code: 'DISCOUNT_TOTAL_MAX_AMOUNT',
    label: 'Descuento máximo al total (CLP)',
    unit: 'CLP',
  },
  {
    code: 'SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT',
    label: 'Merma auto-aprobable máxima',
    unit: 'CLP',
  },
  {
    code: 'SHRINKAGE_APPROVE_MAX_AMOUNT',
    label: 'Aprobación máxima de merma',
    unit: 'CLP',
  },
  {
    code: 'ADJUSTMENT_APPROVE_MAX_AMOUNT',
    label: 'Aprobación máxima de ajuste de stock',
    unit: 'CLP',
  },
  {
    code: 'CASH_OUT_MAX_AMOUNT',
    label: 'Retiro máximo de efectivo en caja',
    unit: 'CLP',
  },
  {
    code: 'RETURN_MAX_AMOUNT',
    label: 'Devolución máxima autorizable',
    unit: 'CLP',
  },
  {
    code: 'CREDIT_LIMIT_MAX_ASSIGN',
    label: 'Cupo máximo de crédito asignable',
    unit: 'CLP',
  },
  {
    code: 'CREDIT_ADJUSTMENT_MAX_AMOUNT',
    label: 'Ajuste máximo de cuenta corriente',
    unit: 'CLP',
  },
];

const CLP_FORMAT = new Intl.NumberFormat('es-CL', { maximumFractionDigits: 0 });

/**
 * Formatea el valor de un límite para mostrarlo en pantalla.
 *
 * Según `ROLES_PERMISOS.md` D.3, `null` (o `undefined`) representa ilimitado y se muestra como `∞`.
 */
export function formatRoleLimitValue(value: number | null | undefined, unit: '%' | 'CLP'): string {
  if (value === null || value === undefined) {
    return '∞';
  }
  return unit === '%' ? `${value} %` : `$${CLP_FORMAT.format(value)}`;
}
