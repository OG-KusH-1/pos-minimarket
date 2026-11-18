import { CategoryResponse } from '../../../api/models/category-response';
import { CountLineResponse } from '../../../api/models/count-line-response';
import { InventoryCountResponse } from '../../../api/models/inventory-count-response';
import { QtyUnit } from '../../../shared/pipes';

/** Estados posibles del ciclo de vida de una toma física de inventario (`API.md` F.3). */
export type CountStatusCode = NonNullable<InventoryCountResponse['status']>;

/** Tipos de alcance posibles de una toma de inventario. */
export type CountTypeCode = NonNullable<InventoryCountResponse['countType']>;

/** Filtro rápido de estado de conteo en la grilla de líneas. */
export type CountedFilterMode = 'ALL' | 'PENDING' | 'COUNTED';

/** Opción plana de categoría para el selector de toma cíclica. */
export interface CategoryOption {
  readonly id: number;
  readonly label: string;
}

/** Opciones de filtro para el estado de la toma. */
export const COUNT_STATUS_OPTIONS: readonly {
  readonly value: CountStatusCode;
  readonly label: string;
}[] = [
  { value: 'OPEN', label: 'Abierta' },
  { value: 'SUBMITTED', label: 'En revisión' },
  { value: 'APPROVED', label: 'Aprobada' },
  { value: 'REJECTED', label: 'Rechazada' },
  { value: 'CANCELLED', label: 'Cancelada' },
];

/** Opciones de filtro para el alcance de la toma. */
export const COUNT_TYPE_OPTIONS: readonly {
  readonly value: CountTypeCode;
  readonly label: string;
}[] = [
  { value: 'FULL', label: 'Completa' },
  { value: 'CYCLIC', label: 'Cíclica' },
];

/** Opciones de filtro rápido para las líneas de conteo. */
export const COUNTED_FILTER_OPTIONS: readonly {
  readonly value: CountedFilterMode;
  readonly label: string;
}[] = [
  { value: 'ALL', label: 'Todas las líneas' },
  { value: 'PENDING', label: 'Pendientes de contar' },
  { value: 'COUNTED', label: 'Ya contadas' },
];

const DATETIME_FORMAT = new Intl.DateTimeFormat('es-CL', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

const UNIT_COST_FORMAT = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
});

/** Devuelve la etiqueta en español para un estado de toma de inventario. */
export function countStatusLabel(status: CountStatusCode | undefined): string {
  switch (status) {
    case 'OPEN':
      return 'Abierta';
    case 'SUBMITTED':
      return 'En revisión';
    case 'APPROVED':
      return 'Aprobada';
    case 'REJECTED':
      return 'Rechazada';
    case 'CANCELLED':
      return 'Cancelada';
    default:
      return '—';
  }
}

/** Devuelve la etiqueta en español para el alcance de una toma. */
export function countTypeLabel(countType: CountTypeCode | undefined): string {
  switch (countType) {
    case 'FULL':
      return 'Completa';
    case 'CYCLIC':
      return 'Cíclica';
    default:
      return '—';
  }
}

/** Traduce la unidad de medida del backend (`UNIT` | `KG`) a la unidad de `QtyPipe`. */
export function toQtyUnit(unitOfMeasure: CountLineResponse['unitOfMeasure']): QtyUnit {
  return unitOfMeasure === 'KG' ? 'kg' : 'u';
}

/** Formatea una fecha UTC ISO-8601 en horario local chileno (`dd-mm-aaaa HH:mm`). */
export function formatDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? '—' : DATETIME_FORMAT.format(parsed);
}

/** Formatea un costo unitario neto en CLP (hasta 2 decimales si aplica). */
export function formatUnitCost(value: number | undefined): string {
  if (value === undefined || value === null || !Number.isFinite(value)) {
    return '—';
  }
  return `$${UNIT_COST_FORMAT.format(value)}`;
}

/**
 * Aplana el árbol de categorías devuelto por `GET /api/v1/categories?tree=true` en opciones
 * legibles (`Padre / Hija`) conservando solo categorías activas cuando el campo viene informado.
 */
export function flattenCategories(tree: readonly CategoryResponse[]): CategoryOption[] {
  const options: CategoryOption[] = [];
  const visit = (nodes: readonly CategoryResponse[], prefix: string): void => {
    for (const node of nodes) {
      if (node.active === false) {
        continue;
      }
      const label = prefix && node.name ? `${prefix} / ${node.name}` : (node.name ?? '');
      if (node.id !== undefined && label) {
        options.push({ id: node.id, label });
      }
      if (node.children && node.children.length > 0) {
        visit(node.children, label);
      }
    }
  };
  visit(tree, '');
  return options;
}

/**
 * Determina si la UI puede revelar existencias teóricas y diferencias.
 *
 * Regla estricta de modo ciego (`HU-INV-06`, `API.md` F.2): cuando `blind` es `true`, ni el
 * stock teórico ni la diferencia se muestran mientras la toma esté abierta o si el usuario no
 * posee `inventory:count:approve`. Solo se revelan tras enviar/aprobar para quien puede aprobar.
 */
export function canRevealBlindValues(
  count: Pick<InventoryCountResponse, 'blind' | 'status'> | null | undefined,
  canApprove: boolean,
): boolean {
  if (!count) {
    return false;
  }
  if (!count.blind) {
    return true;
  }
  return canApprove && (count.status === 'SUBMITTED' || count.status === 'APPROVED');
}
