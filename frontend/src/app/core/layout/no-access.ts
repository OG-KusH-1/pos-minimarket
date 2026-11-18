import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Pantalla `/sin-acceso`: destino de los usuarios que no tienen permiso para ningún módulo.
 * Vive dentro del layout pero fuera de `NAV_ITEMS`, por lo que no exige permiso ni aparece en el menú.
 */
@Component({
  selector: 'pos-no-access',
  template: `
    <h1>Sin acceso</h1>
    <p>Tu usuario no tiene módulos asignados. Contacta a un administrador.</p>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NoAccess {}
