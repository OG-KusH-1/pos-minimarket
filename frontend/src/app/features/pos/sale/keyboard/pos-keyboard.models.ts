/** Acciones de teclado del punto de venta (`HU-POS-20`). */
export type PosKeyAction =
  | 'help'
  | 'search'
  | 'editQuantity'
  | 'discount'
  | 'confirmAge'
  | 'suspend'
  | 'resume'
  | 'pay'
  | 'finalize'
  | 'cancel'
  | 'removeLine';

/** Definición legible de un atajo de teclado para el mapa y el diálogo de ayuda (`F1`). */
export interface PosKeyBinding {
  readonly action: PosKeyAction;
  readonly key: string;
  readonly keyLabel: string;
  readonly description: string;
}

/** Mapa de atajos por defecto según `HU-POS-20` (configurable vía `PosKeyboardService`). */
export const DEFAULT_POS_KEY_BINDINGS: readonly PosKeyBinding[] = [
  { action: 'help', key: 'F1', keyLabel: 'F1', description: 'Ver ayuda y mapa de atajos' },
  { action: 'search', key: 'F2', keyLabel: 'F2', description: 'Enfocar búsqueda / escaneo' },
  {
    action: 'editQuantity',
    key: 'F4',
    keyLabel: 'F4',
    description: 'Editar cantidad de la línea',
  },
  { action: 'discount', key: 'F6', keyLabel: 'F6', description: 'Aplicar descuento' },
  {
    action: 'confirmAge',
    key: 'F7',
    keyLabel: 'F7',
    description: 'Confirmar mayoría de edad (+18)',
  },
  { action: 'suspend', key: 'F8', keyLabel: 'F8', description: 'Suspender venta en curso' },
  { action: 'resume', key: 'F9', keyLabel: 'F9', description: 'Retomar venta suspendida' },
  { action: 'pay', key: 'F10', keyLabel: 'F10', description: 'Ir al cobro de la venta' },
  { action: 'finalize', key: 'F12', keyLabel: 'F12', description: 'Finalizar e imprimir venta' },
  { action: 'cancel', key: 'Escape', keyLabel: 'Esc', description: 'Cancelar o limpiar búsqueda' },
  {
    action: 'removeLine',
    key: 'Delete',
    keyLabel: 'Supr',
    description: 'Quitar línea seleccionada',
  },
];
