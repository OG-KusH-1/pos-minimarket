import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Pantalla provisional de «Proveedores».
 *
 * Placeholder creado en la Fase 0 para que el enrutamiento y el menú estén completos desde el
 * inicio. La tarea de la fase que implemente esta sub-feature reemplaza el contenido de este
 * componente (y solo el de su carpeta); la ruta y su carga diferida ya están cableadas.
 */
@Component({
  selector: 'pos-suppliers',
  template: `
    <h1>Proveedores</h1>
    <p>Módulo en construcción.</p>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Suppliers {}
