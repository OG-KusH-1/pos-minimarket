/** Tipo de valor JSON admitido en runtime por los endpoints de `/api/v1/settings`. */
export type SettingJsonValue = boolean | number | string | readonly string[];

/** Alcance de un parámetro de configuración (`MODELO_DATOS.md` B.1). */
export type SettingScope = 'GLOBAL' | 'STORE';

/** Tipo de dato declarado en el catálogo de `settings`. */
export type SettingValueType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'JSON';

/** Grupo visual en la pantalla de parámetros. */
export type SettingGroup = 'ticket' | 'operation' | 'security';

/** Representación tipada en el frontend de un parámetro devuelto por `GET /api/v1/settings`. */
export interface SettingItem {
  readonly key: string;
  readonly scope: SettingScope;
  readonly storeId?: number;
  readonly value: SettingJsonValue;
  readonly valueType: SettingValueType;
  readonly description?: string;
}

/** Definición estática de un parámetro según `SettingCatalog.java` y `MODELO_DATOS.md` B.1. */
export interface SettingDefinition {
  readonly key: string;
  readonly group: SettingGroup;
  readonly label: string;
  readonly description: string;
  readonly valueType: SettingValueType;
  readonly storeOverrideAllowed: boolean;
  readonly min?: number;
  readonly max?: number;
  readonly unit?: string;
  readonly defaultValue: SettingJsonValue;
}

/** Límites oficiales para las líneas de encabezado y pie del ticket (`SettingCatalog.java`). */
export const MAX_TICKET_LINES = 6;
export const MAX_TICKET_LINE_LENGTH = 48;
export const MIN_TICKET_PAPER_WIDTH_MM = 58;
export const MAX_TICKET_PAPER_WIDTH_MM = 80;

/** Claves que componen el formulario de ticket por local (`HU-CFG-04`). */
export const TICKET_SETTING_KEYS = {
  headerLines: 'ticket.header_lines',
  footerLines: 'ticket.footer_lines',
  showTaxBreakdown: 'ticket.show_tax_breakdown',
  paperWidthMm: 'ticket.paper_width_mm',
} as const;

/** Catálogo oficial de los 19 parámetros de `settings` (`MODELO_DATOS.md` B.1). */
export const SETTING_DEFINITIONS: readonly SettingDefinition[] = [
  {
    key: 'ticket.header_lines',
    group: 'ticket',
    label: 'Líneas de encabezado del ticket',
    description:
      'Líneas de texto adicionales bajo los datos de la empresa (máx. 6 líneas de 48 caracteres).',
    valueType: 'JSON',
    storeOverrideAllowed: true,
    defaultValue: [],
  },
  {
    key: 'ticket.footer_lines',
    group: 'ticket',
    label: 'Líneas de pie del ticket',
    description: 'Mensajes finales al pie del comprobante (máx. 6 líneas de 48 caracteres).',
    valueType: 'JSON',
    storeOverrideAllowed: true,
    defaultValue: [],
  },
  {
    key: 'ticket.show_tax_breakdown',
    group: 'ticket',
    label: 'Mostrar desglose de impuestos',
    description: 'Imprime el detalle de monto neto, exento e IVA/adicionales en el ticket.',
    valueType: 'BOOLEAN',
    storeOverrideAllowed: true,
    defaultValue: true,
  },
  {
    key: 'ticket.paper_width_mm',
    group: 'ticket',
    label: 'Ancho del papel térmico',
    description: 'Ancho de impresión en milímetros (58 a 80 mm).',
    valueType: 'NUMBER',
    storeOverrideAllowed: true,
    min: MIN_TICKET_PAPER_WIDTH_MM,
    max: MAX_TICKET_PAPER_WIDTH_MM,
    unit: 'mm',
    defaultValue: 80,
  },
  {
    key: 'pos.cash_rounding.enabled',
    group: 'operation',
    label: 'Redondeo legal del efectivo',
    description: 'Aplica redondeo al múltiplo de $10 sobre el pago en efectivo (Ley 20.956).',
    valueType: 'BOOLEAN',
    storeOverrideAllowed: true,
    defaultValue: true,
  },
  {
    key: 'pos.suspended.max_per_terminal',
    group: 'operation',
    label: 'Máximo de ventas suspendidas por terminal',
    description: 'Cantidad máxima de ventas en espera simultáneas por caja (1 a 20).',
    valueType: 'NUMBER',
    storeOverrideAllowed: true,
    min: 1,
    max: 20,
    unit: 'ventas',
    defaultValue: 5,
  },
  {
    key: 'pos.suspended.ttl_hours',
    group: 'operation',
    label: 'Vigencia de ventas suspendidas',
    description: 'Horas antes de que expire automáticamente una venta suspendida (1 a 72 h).',
    valueType: 'NUMBER',
    storeOverrideAllowed: true,
    min: 1,
    max: 72,
    unit: 'horas',
    defaultValue: 24,
  },
  {
    key: 'pos.return.max_days',
    group: 'operation',
    label: 'Plazo máximo de devoluciones',
    description: 'Días desde la venta durante los cuales se admite una devolución (1 a 365 días).',
    valueType: 'NUMBER',
    storeOverrideAllowed: true,
    min: 1,
    max: 365,
    unit: 'días',
    defaultValue: 30,
  },
  {
    key: 'alerts.expiry.days_ahead',
    group: 'operation',
    label: 'Anticipación de alerta de vencimientos',
    description: 'Días previos al vencimiento para alertar lotes próximos a vencer (1 a 90 días).',
    valueType: 'NUMBER',
    storeOverrideAllowed: true,
    min: 1,
    max: 90,
    unit: 'días',
    defaultValue: 15,
  },
  {
    key: 'cash.difference_tolerance',
    group: 'operation',
    label: 'Tolerancia de diferencia en cierre de caja',
    description:
      'Diferencia absoluta en CLP permitida sin requerir aprobación de supervisor (0 a 50.000).',
    valueType: 'NUMBER',
    storeOverrideAllowed: true,
    min: 0,
    max: 50000,
    unit: 'CLP',
    defaultValue: 500,
  },
  {
    key: 'cash.default_opening_amount',
    group: 'operation',
    label: 'Fondo inicial sugerido al abrir turno',
    description: 'Monto sugerido de apertura de caja en CLP (0 a 1.000.000).',
    valueType: 'NUMBER',
    storeOverrideAllowed: true,
    min: 0,
    max: 1000000,
    unit: 'CLP',
    defaultValue: 50000,
  },
  {
    key: 'email.ticket.enabled',
    group: 'operation',
    label: 'Envío de ticket por correo',
    description: 'Habilita el envío automático o manual del comprobante por correo electrónico.',
    valueType: 'BOOLEAN',
    storeOverrideAllowed: true,
    defaultValue: true,
  },
  {
    key: 'security.password.min_length',
    group: 'security',
    label: 'Largo mínimo de contraseña',
    description: 'Cantidad mínima de caracteres exigida al crear o cambiar contraseñas (8 a 64).',
    valueType: 'NUMBER',
    storeOverrideAllowed: false,
    min: 8,
    max: 64,
    unit: 'caracteres',
    defaultValue: 8,
  },
  {
    key: 'security.lockout.max_attempts',
    group: 'security',
    label: 'Intentos fallidos antes de bloquear cuenta',
    description: 'Intentos de contraseña erróneos antes de bloquear el usuario (3 a 20).',
    valueType: 'NUMBER',
    storeOverrideAllowed: false,
    min: 3,
    max: 20,
    unit: 'intentos',
    defaultValue: 5,
  },
  {
    key: 'security.lockout.minutes',
    group: 'security',
    label: 'Duración del bloqueo de cuenta',
    description: 'Minutos que permanece bloqueada la cuenta tras superar los intentos (1 a 1440).',
    valueType: 'NUMBER',
    storeOverrideAllowed: false,
    min: 1,
    max: 1440,
    unit: 'min',
    defaultValue: 15,
  },
  {
    key: 'security.pin.max_attempts',
    group: 'security',
    label: 'Intentos fallidos antes de bloquear PIN',
    description: 'Intentos erróneos de PIN POS antes de bloquear el acceso rápido (3 a 10).',
    valueType: 'NUMBER',
    storeOverrideAllowed: false,
    min: 3,
    max: 10,
    unit: 'intentos',
    defaultValue: 5,
  },
  {
    key: 'security.pin.lockout_minutes',
    group: 'security',
    label: 'Duración del bloqueo de PIN',
    description: 'Minutos que permanece bloqueado el PIN POS tras superar los intentos (1 a 1440).',
    valueType: 'NUMBER',
    storeOverrideAllowed: false,
    min: 1,
    max: 1440,
    unit: 'min',
    defaultValue: 15,
  },
  {
    key: 'security.refresh.ttl_hours',
    group: 'security',
    label: 'Vigencia deslizante del refresh token',
    description: 'Horas de inactividad antes de exigir nuevo inicio de sesión (1 a 72 h).',
    valueType: 'NUMBER',
    storeOverrideAllowed: false,
    min: 1,
    max: 72,
    unit: 'horas',
    defaultValue: 12,
  },
  {
    key: 'security.refresh.absolute_days',
    group: 'security',
    label: 'Tope absoluto de la sesión',
    description: 'Días máximos de vida continua de una familia de refresh tokens (1 a 30 días).',
    valueType: 'NUMBER',
    storeOverrideAllowed: false,
    min: 1,
    max: 30,
    unit: 'días',
    defaultValue: 7,
  },
];
