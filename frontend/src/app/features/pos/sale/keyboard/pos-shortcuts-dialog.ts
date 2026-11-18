import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { PosKeyboardService } from './pos-keyboard.service';

/**
 * Diálogo de ayuda rápida (`F1`) con la tabla de atajos de teclado configurados en el POS
 * (`HU-POS-20`).
 */
@Component({
  selector: 'pos-shortcuts-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>Atajos de teclado del POS</h2>
    <mat-dialog-content>
      <p class="intro">
        Puedes operar toda la venta sin mouse. Al cerrar cada acción el foco regresa al campo de
        escaneo.
      </p>
      <table class="shortcuts-table">
        <thead>
          <tr>
            <th>Tecla</th>
            <th>Acción</th>
          </tr>
        </thead>
        <tbody>
          @for (item of bindings(); track item.action) {
            <tr>
              <td>
                <kbd>{{ item.keyLabel }}</kbd>
              </td>
              <td>{{ item.description }}</td>
            </tr>
          }
        </tbody>
      </table>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-flat-button type="button" [mat-dialog-close]="true" cdkFocusInitial>
        Entendido (Esc)
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .intro {
      margin: 0 0 12px;
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.9rem;
    }
    .shortcuts-table {
      width: 100%;
      border-collapse: collapse;
    }
    .shortcuts-table th,
    .shortcuts-table td {
      padding: 6px 8px;
      text-align: left;
      border-bottom: 1px solid var(--mat-sys-outline-variant);
    }
    kbd {
      display: inline-block;
      min-width: 48px;
      padding: 2px 8px;
      border-radius: 6px;
      font-family: inherit;
      font-size: 0.85rem;
      font-weight: 600;
      text-align: center;
      background: var(--mat-sys-surface-container-high);
      border: 1px solid var(--mat-sys-outline-variant);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosShortcutsDialog {
  private readonly keyboard = inject(PosKeyboardService);
  protected readonly bindings = this.keyboard.bindings;
}
