/** Opción de catálogo para sugerencias de filtro en la bitácora de auditoría. */
export interface AuditFilterOption {
  readonly value: string;
  readonly label: string;
}

/** Diferencia campo a campo entre `oldValue` y `newValue` de un registro de auditoría. */
export interface AuditDiffEntry {
  readonly field: string;
  readonly status: 'added' | 'removed' | 'changed' | 'unchanged';
  readonly oldText: string;
  readonly newText: string;
}

/** Catálogo conocido de acciones auditadas (`ROLES_PERMISOS.md` D.5). */
export const AUDIT_ACTION_OPTIONS: readonly AuditFilterOption[] = [
  { value: 'AUTH_LOGIN_SUCCESS', label: 'Inicio de sesión exitoso' },
  { value: 'AUTH_LOGIN_FAILED', label: 'Inicio de sesión fallido' },
  { value: 'AUTH_LOCKED', label: 'Cuenta bloqueada' },
  { value: 'AUTH_PIN_LOCKED', label: 'PIN bloqueado' },
  { value: 'AUTH_TOKEN_REUSE_DETECTED', label: 'Reutilización de token detectada' },
  { value: 'USER_CREATED', label: 'Usuario creado' },
  { value: 'USER_UPDATED', label: 'Usuario actualizado' },
  { value: 'USER_DISABLED', label: 'Usuario desactivado' },
  { value: 'USER_ENABLED', label: 'Usuario reactivado' },
  { value: 'USER_CREDENTIALS_RESET', label: 'Credenciales restablecidas' },
  { value: 'SESSIONS_REVOKED', label: 'Sesiones revocadas' },
  { value: 'COMPANY_UPDATED', label: 'Empresa actualizada' },
  { value: 'STORE_CHANGED', label: 'Local modificado' },
  { value: 'ROLE_PERMISSIONS_CHANGED', label: 'Permisos de rol modificados' },
  { value: 'ROLE_LIMIT_CHANGED', label: 'Límites de rol modificados' },
  { value: 'SETTING_CHANGED', label: 'Parámetro modificado' },
  { value: 'TAX_CHANGED', label: 'Impuesto modificado' },
  { value: 'PRODUCT_PRICE_CHANGED', label: 'Precio de producto modificado' },
  { value: 'PRODUCT_COST_CHANGED', label: 'Costo promedio modificado' },
  { value: 'PRODUCT_TAXES_CHANGED', label: 'Impuestos de producto modificados' },
  { value: 'PRODUCT_DEACTIVATED', label: 'Producto desactivado' },
  { value: 'PROMOTION_CHANGED', label: 'Promoción modificada' },
  { value: 'SALE_DISCOUNT_OVER_LIMIT', label: 'Descuento sobre límite autorizado' },
  { value: 'SALE_VOIDED', label: 'Venta anulada' },
  { value: 'SALE_RETURNED', label: 'Venta devuelta' },
  { value: 'SALE_RESTRICTED_AGE_CONFIRMED', label: 'Edad confirmada en venta restringida' },
  { value: 'RECEIPT_REPRINTED', label: 'Comprobante reimpreso' },
  { value: 'SUSPENDED_SALE_DELETED', label: 'Venta suspendida eliminada' },
  { value: 'STOCK_ADJUSTMENT_REQUESTED', label: 'Ajuste de stock solicitado' },
  { value: 'STOCK_ADJUSTMENT_APPROVED', label: 'Ajuste de stock aprobado' },
  { value: 'STOCK_ADJUSTMENT_REJECTED', label: 'Ajuste de stock rechazado' },
  { value: 'INVENTORY_COUNT_APPROVED', label: 'Toma de inventario aprobada' },
  { value: 'INVENTORY_COUNT_REJECTED', label: 'Toma de inventario rechazada' },
  { value: 'TRANSFER_SENT', label: 'Transferencia enviada' },
  { value: 'TRANSFER_RECEIVED', label: 'Transferencia recibida' },
  { value: 'LOT_UPDATED', label: 'Lote actualizado' },
  { value: 'SHRINKAGE_CREATED', label: 'Merma registrada' },
  { value: 'SHRINKAGE_APPROVED', label: 'Merma aprobada' },
  { value: 'SHRINKAGE_REJECTED', label: 'Merma rechazada' },
  { value: 'GOODS_RECEIPT_CONFIRMED', label: 'Recepción de mercadería confirmada' },
  { value: 'PRODUCT_SUPPLIER_CHANGED', label: 'Relación producto-proveedor modificada' },
  { value: 'PURCHASE_ORDER_SENT', label: 'Orden de compra enviada' },
  { value: 'PURCHASE_ORDER_CANCELLED', label: 'Orden de compra cancelada' },
  { value: 'CASH_SESSION_OPENED', label: 'Turno de caja abierto' },
  { value: 'CASH_SESSION_CLOSED', label: 'Turno de caja cerrado' },
  { value: 'CASH_OUT', label: 'Retiro de caja' },
  { value: 'CASH_IN', label: 'Ingreso de caja' },
  { value: 'CASH_DIFFERENCE_APPROVED', label: 'Diferencia de caja aprobada' },
  { value: 'CUSTOMER_CREDIT_LIMIT_CHANGED', label: 'Cupo de crédito modificado' },
  { value: 'CREDIT_OVER_LIMIT', label: 'Venta sobre cupo de crédito' },
  { value: 'CUSTOMER_ACCOUNT_ADJUSTED', label: 'Cuenta corriente ajustada' },
  { value: 'OVERRIDE_GRANTED', label: 'Autorización de supervisor otorgada' },
  { value: 'OVERRIDE_DENIED', label: 'Autorización de supervisor denegada' },
  { value: 'DATA_EXPORTED', label: 'Datos exportados' },
  { value: 'DATA_IMPORTED', label: 'Datos importados' },
];

/** Catálogo conocido de tipos de entidad auditados (`ROLES_PERMISOS.md` D.5). */
export const AUDIT_ENTITY_TYPE_OPTIONS: readonly AuditFilterOption[] = [
  { value: 'user', label: 'Usuario' },
  { value: 'role', label: 'Rol' },
  { value: 'company', label: 'Empresa' },
  { value: 'store', label: 'Local' },
  { value: 'setting', label: 'Parámetro' },
  { value: 'tax', label: 'Impuesto' },
  { value: 'product', label: 'Producto' },
  { value: 'promotion', label: 'Promoción' },
  { value: 'sale', label: 'Venta' },
  { value: 'sale_return', label: 'Devolución' },
  { value: 'suspended_sale', label: 'Venta suspendida' },
  { value: 'stock_adjustment', label: 'Ajuste de stock' },
  { value: 'inventory_count', label: 'Toma de inventario' },
  { value: 'stock_transfer', label: 'Transferencia' },
  { value: 'lot', label: 'Lote' },
  { value: 'shrinkage', label: 'Merma' },
  { value: 'goods_receipt', label: 'Recepción' },
  { value: 'product_supplier', label: 'Producto-proveedor' },
  { value: 'purchase_order', label: 'Orden de compra' },
  { value: 'cash_session', label: 'Turno de caja' },
  { value: 'cash_movement', label: 'Movimiento de caja' },
  { value: 'customer', label: 'Cliente' },
  { value: 'supervisor_authorization', label: 'Autorización de supervisor' },
  { value: 'report', label: 'Reporte' },
  { value: 'import_job', label: 'Importación' },
];

const ACTION_LABELS = new Map<string, string>(
  AUDIT_ACTION_OPTIONS.map((option) => [option.value, option.label]),
);

const ENTITY_TYPE_LABELS = new Map<string, string>(
  AUDIT_ENTITY_TYPE_OPTIONS.map((option) => [option.value, option.label]),
);

const DATE_TIME_FORMATTER = new Intl.DateTimeFormat('es-CL', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

/** Devuelve la etiqueta legible de una acción de auditoría o el código original si es desconocido. */
export function auditActionLabel(action: string | undefined | null): string {
  if (!action) {
    return '—';
  }
  return ACTION_LABELS.get(action) ?? action;
}

/** Devuelve la etiqueta legible de un tipo de entidad o el código original si es desconocido. */
export function auditEntityTypeLabel(entityType: string | undefined | null): string {
  if (!entityType) {
    return '—';
  }
  return ENTITY_TYPE_LABELS.get(entityType) ?? entityType;
}

/** Formatea una marca temporal ISO-8601 a formato local legible con segundos. */
export function formatAuditDateTime(iso: string | undefined | null): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return DATE_TIME_FORMATTER.format(date);
}

/**
 * Formatea un valor JSON arbitrario (`oldValue` / `newValue`) para mostrarlo legible en detalle.
 * Devuelve `null` si el valor es nulo o indefinido.
 */
export function formatAuditJson(value: unknown): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value === 'string') {
    return value;
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

/**
 * Compara campo a campo dos valores JSON planos (`oldValue` y `newValue`) para resumir qué cambió.
 * Si ninguno de los dos es un objeto plano, retorna un arreglo vacío (la vista muestra el JSON bruto).
 */
export function computeAuditDiff(oldValue: unknown, newValue: unknown): readonly AuditDiffEntry[] {
  const oldObj = asPlainObject(oldValue);
  const newObj = asPlainObject(newValue);
  if (!oldObj && !newObj) {
    return [];
  }

  const keys = Array.from(
    new Set([...Object.keys(oldObj ?? {}), ...Object.keys(newObj ?? {})]),
  ).sort();

  return keys.map((field) => {
    const hasOld = oldObj !== null && Object.prototype.hasOwnProperty.call(oldObj, field);
    const hasNew = newObj !== null && Object.prototype.hasOwnProperty.call(newObj, field);
    const oldVal = hasOld ? oldObj[field] : undefined;
    const newVal = hasNew ? newObj[field] : undefined;
    const oldText = formatInlineValue(oldVal);
    const newText = formatInlineValue(newVal);

    let status: AuditDiffEntry['status'] = 'unchanged';
    if (!hasOld && hasNew) {
      status = 'added';
    } else if (hasOld && !hasNew) {
      status = 'removed';
    } else if (!areJsonValuesEqual(oldVal, newVal)) {
      status = 'changed';
    }

    return { field, status, oldText, newText };
  });
}

function asPlainObject(value: unknown): Record<string, unknown> | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null;
  }
  return value as Record<string, unknown>;
}

function formatInlineValue(value: unknown): string {
  if (value === undefined) {
    return '—';
  }
  if (value === null) {
    return 'null';
  }
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function areJsonValuesEqual(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) {
    return true;
  }
  try {
    return JSON.stringify(a) === JSON.stringify(b);
  } catch {
    return false;
  }
}
