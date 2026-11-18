/**
 * Definición declarativa del menú lateral.
 *
 * Es la única fuente de verdad del menú: {@link Shell} solo la recorre y dibuja. Cada ítem declara
 * la URL (debe existir en `app.routes.ts`) y el código del permiso que lo habilita, tomado del
 * catálogo D.1 de `docs/ROLES_PERMISOS.md`.
 *
 * El filtrado por permiso NO se hace en este archivo de datos: `Shell` lo calcula con
 * `AuthService.hasAnyPermission` y {@link requiredPermissions}. Un ítem sin ninguno de los
 * permisos requeridos no se muestra, y un grupo sin ítems visibles tampoco.
 *
 * Extensión: para una pantalla nueva basta con agregar un ítem a su grupo (o un grupo nuevo) y la
 * ruta correspondiente; ningún otro archivo del layout cambia. Las rutas públicas (`/login`,
 * `/cambiar-clave`) no son parte del menú.
 */

/** Ítem de menú que enlaza a una ruta de la aplicación. */
export interface NavItem {
  /** Texto visible, en español. */
  readonly label: string;
  /** URL absoluta de la ruta (sin parámetros). */
  readonly path: string;
  /**
   * Código de permiso requerido (formato `modulo:recurso:accion`, catálogo D.1) o lista de
   * códigos con semántica "cualquiera de": basta con poseer uno para ver el ítem. Si el usuario
   * no cumple, el ítem no se muestra.
   */
  readonly permission: string | readonly string[];
}

/** Grupo de ítems relacionados (una feature del frontend). */
export interface NavGroup {
  /** Identificador estable, útil para pruebas y para `track` en las plantillas. */
  readonly id: string;
  /** Título del grupo; se muestra como encabezado solo si el grupo tiene más de un ítem. */
  readonly label: string;
  /** Nombre del ícono de Material Symbols. */
  readonly icon: string;
  readonly items: readonly NavItem[];
}

/**
 * Los 7 permisos de lectura de reportes (D.1, `report:*:read`). No incluye `report:export:execute`,
 * que es de exportación y no da acceso a ver reportes. El ítem Reportes se muestra con cualquiera.
 */
export const REPORT_READ_PERMISSIONS: readonly string[] = [
  'report:dashboard:read',
  'report:sales:read',
  'report:margin:read',
  'report:inventory:read',
  'report:purchasing:read',
  'report:shrinkage:read',
  'report:cash:read',
];

export const NAV_GROUPS: readonly NavGroup[] = [
  {
    id: 'dashboard',
    label: 'Inicio',
    icon: 'dashboard',
    items: [{ label: 'Inicio', path: '/inicio', permission: 'report:dashboard:read' }],
  },
  {
    id: 'pos',
    label: 'Ventas',
    icon: 'point_of_sale',
    items: [
      { label: 'Venta', path: '/pos/venta', permission: 'sales:pos:operate' },
      { label: 'Devoluciones', path: '/pos/devoluciones', permission: 'sales:return:create' },
      {
        label: 'Documentos tributarios',
        path: '/pos/documentos-tributarios',
        permission: 'sales:tax-document:read',
      },
    ],
  },
  {
    id: 'cash',
    label: 'Caja',
    icon: 'payments',
    items: [{ label: 'Caja', path: '/caja', permission: 'cash:session:read' }],
  },
  {
    id: 'catalog',
    label: 'Catálogo',
    icon: 'category',
    items: [
      { label: 'Productos', path: '/catalogo/productos', permission: 'catalog:product:read' },
      { label: 'Categorías', path: '/catalogo/categorias', permission: 'catalog:category:manage' },
      { label: 'Marcas', path: '/catalogo/marcas', permission: 'catalog:brand:manage' },
      { label: 'Impuestos', path: '/catalogo/impuestos', permission: 'config:tax:manage' },
      {
        label: 'Códigos de balanza',
        path: '/catalogo/balanza',
        permission: 'config:scale-barcode:manage',
      },
      {
        label: 'Importaciones',
        path: '/catalogo/importaciones',
        permission: 'catalog:product:import',
      },
    ],
  },
  {
    id: 'promotions',
    label: 'Promociones',
    icon: 'sell',
    items: [
      { label: 'Promociones', path: '/promociones', permission: 'promotions:promotion:read' },
    ],
  },
  {
    id: 'customers',
    label: 'Clientes',
    icon: 'groups',
    items: [{ label: 'Clientes', path: '/clientes', permission: 'customers:customer:read' }],
  },
  {
    id: 'purchasing',
    label: 'Compras',
    icon: 'local_shipping',
    items: [
      {
        label: 'Proveedores',
        path: '/compras/proveedores',
        permission: 'purchasing:supplier:read',
      },
      {
        label: 'Órdenes de compra',
        path: '/compras/ordenes',
        permission: 'purchasing:order:read',
      },
      {
        label: 'Recepciones',
        path: '/compras/recepciones',
        permission: 'purchasing:receipt:create',
      },
    ],
  },
  {
    id: 'replenishment',
    label: 'Reabastecimiento',
    icon: 'autorenew',
    items: [
      {
        label: 'Reabastecimiento',
        path: '/reabastecimiento',
        permission: 'replenishment:run:read',
      },
    ],
  },
  {
    id: 'inventory',
    label: 'Inventario',
    icon: 'inventory_2',
    items: [
      { label: 'Stock', path: '/inventario/stock', permission: 'inventory:stock:read' },
      { label: 'Kardex', path: '/inventario/kardex', permission: 'inventory:kardex:read' },
      {
        label: 'Ajustes',
        path: '/inventario/ajustes',
        permission: 'inventory:adjustment:create',
      },
      { label: 'Tomas', path: '/inventario/tomas', permission: 'inventory:count:execute' },
      {
        label: 'Transferencias',
        path: '/inventario/transferencias',
        permission: 'inventory:transfer:read',
      },
      { label: 'Lotes', path: '/inventario/lotes', permission: 'inventory:expiry:read' },
    ],
  },
  {
    id: 'shrinkage',
    label: 'Mermas',
    icon: 'delete_sweep',
    items: [{ label: 'Mermas', path: '/mermas', permission: 'shrinkage:record:read' }],
  },
  {
    id: 'reports',
    label: 'Reportes',
    icon: 'bar_chart',
    // D.1 define 7 permisos `report:*:read` (uno por tipo de reporte): se muestra con cualquiera.
    items: [{ label: 'Reportes', path: '/reportes', permission: REPORT_READ_PERMISSIONS }],
  },
  {
    id: 'settings',
    label: 'Configuración',
    icon: 'settings',
    items: [
      { label: 'Empresa', path: '/configuracion/empresa', permission: 'config:company:update' },
      { label: 'Locales', path: '/configuracion/locales', permission: 'config:store:manage' },
      {
        label: 'Parámetros',
        path: '/configuracion/parametros',
        permission: 'config:settings:read',
      },
    ],
  },
  {
    id: 'security',
    label: 'Seguridad',
    icon: 'shield',
    items: [
      { label: 'Usuarios', path: '/seguridad/usuarios', permission: 'security:user:read' },
      { label: 'Roles', path: '/seguridad/roles', permission: 'security:role:read' },
      { label: 'Auditoría', path: '/seguridad/auditoria', permission: 'security:audit:read' },
    ],
  },
];

/** Lista plana de todos los ítems del menú, en el orden en que se muestran. */
export const NAV_ITEMS: readonly NavItem[] = NAV_GROUPS.flatMap((group) => group.items);

/** Normaliza el permiso de un ítem a una lista (`permission` admite un código o varios). */
export function requiredPermissions(item: NavItem): readonly string[] {
  return typeof item.permission === 'string' ? [item.permission] : item.permission;
}

/**
 * Filtra el menú dejando solo los ítems permitidos y los grupos que conservan al menos uno.
 * Función pura: `Shell` la usa dentro de un `computed`, que se recalcula al cambiar los permisos.
 *
 * @param groups Menú completo.
 * @param canSeeAny Predicado "el usuario tiene alguno de estos permisos" (`AuthService.hasAnyPermission`).
 */
export function filterNavGroups(
  groups: readonly NavGroup[],
  canSeeAny: (permissions: readonly string[]) => boolean,
): readonly NavGroup[] {
  return groups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => canSeeAny(requiredPermissions(item))),
    }))
    .filter((group) => group.items.length > 0);
}
