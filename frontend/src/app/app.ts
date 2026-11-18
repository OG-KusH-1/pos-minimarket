import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Componente raíz: solo aloja el `router-outlet`; el layout vive en `core/layout/shell`. */
@Component({
  imports: [RouterOutlet],
  selector: 'pos-root',
  template: '<router-outlet />',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}
