import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

/** Datos de entrada para {@link TemporaryPasswordDialog}. */
export interface TemporaryPasswordDialogData {
  readonly username: string;
  readonly temporaryPassword: string;
  readonly mode: 'created' | 'reset';
}

/**
 * Muestra la contraseña temporal generada al crear un usuario o restablecer su clave.
 *
 * Por seguridad, el servidor entrega la contraseña en texto plano una única vez; este diálogo
 * permite copiarla al portapapeles antes de cerrarlo.
 */
@Component({
  selector: 'pos-temporary-password-dialog',
  imports: [MatButtonModule, MatDialogModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>
      {{ data.mode === 'created' ? 'Usuario creado' : 'Contraseña restablecida' }}
    </h2>
    <mat-dialog-content class="content">
      <p>
        Contraseña temporal para <strong>{{ data.username }}</strong>:
      </p>
      <div class="password-box">
        <code data-testid="temp-password">{{ data.temporaryPassword }}</code>
        <button
          mat-icon-button
          type="button"
          aria-label="Copiar contraseña temporal"
          (click)="copy()"
        >
          <mat-icon>{{ copied() ? 'check' : 'content_copy' }}</mat-icon>
        </button>
      </div>
      @if (copied()) {
        <p class="copied-hint" role="status">Contraseña copiada al portapapeles.</p>
      }
      <p class="warning">
        Esta contraseña se muestra una sola vez. El usuario deberá cambiarla en su próximo inicio de
        sesión.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-flat-button type="button" [mat-dialog-close]="true" cdkFocusInitial>
        Entendido
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .content {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .password-box {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      padding: 8px 12px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-high);
      font-size: 1.05rem;
    }
    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      letter-spacing: 0.04em;
      word-break: break-all;
    }
    .copied-hint {
      margin: 0;
      font-size: 0.85rem;
      color: var(--mat-sys-primary);
    }
    .warning {
      margin: 0;
      font-size: 0.875rem;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TemporaryPasswordDialog {
  protected readonly data = inject<TemporaryPasswordDialogData>(MAT_DIALOG_DATA);
  protected readonly copied = signal(false);

  protected async copy(): Promise<void> {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(this.data.temporaryPassword);
      this.copied.set(true);
    }
  }
}
