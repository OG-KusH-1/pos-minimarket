import { ChangeDetectionStrategy, Component, Injectable, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Observable, map } from 'rxjs';

/** Textos y estilo de un diálogo de confirmación. */
export interface ConfirmDialogData {
  readonly title: string;
  readonly message: string;
  /** Texto del botón de aceptar. Por defecto "Aceptar". */
  readonly confirmLabel?: string;
  /** Texto del botón de cancelar. Por defecto "Cancelar". */
  readonly cancelLabel?: string;
  /** Resalta el botón de aceptar con el color de error (borrar, anular). */
  readonly destructive?: boolean;
}

/**
 * Diálogo modal de confirmación. No se instancia directamente: use
 * {@link ConfirmDialogService.confirm}, que devuelve `Observable<boolean>`.
 *
 * Accesibilidad: Material enfoca el diálogo al abrir, `Esc` cancela y el foco se restituye al
 * cerrar. El botón de cancelar recibe el foco inicial (`cdkFocusInitial`) para que un `Enter`
 * accidental no confirme una acción destructiva.
 */
@Component({
  selector: 'pos-confirm-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p class="message">{{ data.message }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="false" cdkFocusInitial>
        {{ data.cancelLabel ?? 'Cancelar' }}
      </button>
      <button
        mat-flat-button
        type="button"
        [mat-dialog-close]="true"
        [class.destructive]="data.destructive"
      >
        {{ data.confirmLabel ?? 'Aceptar' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .message {
      white-space: pre-line;
      margin: 0;
    }
    .destructive {
      --mat-button-filled-container-color: var(--mat-sys-error);
      --mat-button-filled-label-text-color: var(--mat-sys-on-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
}

/** Abre {@link ConfirmDialog} y expone el resultado como `Observable<boolean>`. */
@Injectable({ providedIn: 'root' })
export class ConfirmDialogService {
  private readonly dialog = inject(MatDialog);

  /**
   * Pide confirmación al usuario.
   *
   * @returns Observable que emite una sola vez y completa: `true` si aceptó; `false` si canceló,
   *   pulsó `Esc` o cerró haciendo clic fuera.
   */
  confirm(data: ConfirmDialogData): Observable<boolean> {
    return this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data,
        width: '420px',
      })
      .afterClosed()
      .pipe(map((result) => result === true));
  }
}
