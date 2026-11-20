import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { ParamsPanel } from './params-panel';
import { RunsPanel } from './runs-panel';

/**
 * Pantalla de «Reabastecimiento» (`HU-PRV-09`, `HU-PRV-10`, `HU-PRV-11`).
 *
 * Organiza el módulo en dos pestañas operables con teclado:
 * - **Parámetros**: bandeja por alcance con upsert y borrado (`ParamsPanel`).
 * - **Corridas**: ejecución y listado de corridas (`RunsPanel`); el detalle de cada corrida
 *   con la revisión de líneas y la generación de borradores vive en `/reabastecimiento/:id`
 *   (`RunDetail`).
 */
@Component({
  selector: 'pos-replenishment',
  imports: [MatTabsModule, ParamsPanel, RunsPanel],
  template: `
    <section class="page">
      <header class="page-header">
        <div>
          <h1>Reabastecimiento</h1>
          <p class="subtitle">
            Configura los parámetros por alcance, ejecuta corridas de sugerencia y genera borradores
            de orden de compra por proveedor.
          </p>
        </div>
      </header>

      <mat-tab-group animationDuration="0ms">
        <mat-tab label="Corridas">
          <pos-replenishment-runs-panel />
        </mat-tab>
        <mat-tab label="Parámetros">
          <pos-replenishment-params-panel />
        </mat-tab>
      </mat-tab-group>
    </section>
  `,
  styles: `
    .page {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .page-header {
      h1 {
        margin: 0;
      }
    }

    .subtitle {
      margin: 4px 0 0;
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.9rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Replenishment {}
