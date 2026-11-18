import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TerminalRequest } from '../../../api/models/terminal-request';
import { TerminalResponse } from '../../../api/models/terminal-response';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { applyFormApiErrors } from '../settings-api.utils';
import { StoresService } from './stores.service';

/** Datos de entrada para {@link TerminalFormDialog}. */
export interface TerminalFormDialogData {
  readonly storeId: number;
  readonly storeName: string;
  readonly terminal?: TerminalResponse;
}

/** Patrón de código de terminal permitido por la API (`API.md` F.3). */
const TERMINAL_CODE_PATTERN = /^[A-Z0-9-]{2,20}$/;

/**
 * Formulario modal para crear o editar una terminal POS de un local (`HU-CFG-02`).
 */
@Component({
  selector: 'pos-terminal-form-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>
      {{ isEdit ? 'Editar terminal' : 'Nueva terminal' }} — {{ data.storeName }}
    </h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <mat-dialog-content class="form-grid">
        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <mat-form-field appearance="outline">
          <mat-label>Código de terminal</mat-label>
          <input
            matInput
            formControlName="code"
            autocomplete="off"
            placeholder="ej. CAJA-01"
            (blur)="normalizeCode()"
          />
          <mat-hint>2 a 20 caracteres: mayúsculas, números y guion (-).</mat-hint>
          @if (form.controls.code.hasError('required')) {
            <mat-error>El código es obligatorio.</mat-error>
          } @else if (form.controls.code.hasError('pattern')) {
            <mat-error>Solo mayúsculas, números y "-" (2 a 20 caracteres).</mat-error>
          } @else if (form.controls.code.hasError('server')) {
            <mat-error>{{ form.controls.code.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Nombre</mat-label>
          <input
            matInput
            formControlName="name"
            autocomplete="off"
            placeholder="ej. Caja Principal"
          />
          @if (form.controls.name.hasError('required')) {
            <mat-error>El nombre es obligatorio.</mat-error>
          } @else if (form.controls.name.hasError('minlength')) {
            <mat-error>Mínimo 2 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('maxlength')) {
            <mat-error>Máximo 80 caracteres.</mat-error>
          } @else if (form.controls.name.hasError('server')) {
            <mat-error>{{ form.controls.name.getError('server') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">
          {{ isEdit ? 'Guardar cambios' : 'Crear terminal' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 400px;
      padding-top: 8px;
    }
    @media (max-width: 480px) {
      .form-grid {
        min-width: 0;
      }
    }
    .server-error {
      margin: 0 0 8px;
      padding: 8px 12px;
      border-radius: 6px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font-size: 0.875rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TerminalFormDialog {
  protected readonly data = inject<TerminalFormDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<TerminalFormDialog, TerminalResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly storesService = inject(StoresService);

  protected readonly isEdit = Boolean(this.data.terminal?.id);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    code: [
      this.data.terminal?.code ?? '',
      [Validators.required, Validators.pattern(TERMINAL_CODE_PATTERN)],
    ],
    name: [
      this.data.terminal?.name ?? '',
      [Validators.required, Validators.minLength(2), Validators.maxLength(80)],
    ],
  });

  /** Normaliza el código a mayúsculas al salir del campo. */
  protected normalizeCode(): void {
    const normalized = this.form.controls.code.value.trim().toUpperCase();
    if (normalized !== this.form.controls.code.value) {
      this.form.controls.code.setValue(normalized);
    }
  }

  protected submit(): void {
    this.normalizeCode();
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: TerminalRequest = {
      code: raw.code.trim().toUpperCase(),
      name: raw.name.trim(),
    };

    this.saving.set(true);
    this.serverError.set(null);

    const request$ =
      this.isEdit && this.data.terminal?.id !== undefined
        ? this.storesService.updateTerminal(
            this.data.storeId,
            this.data.terminal.id,
            request,
            withoutErrorNotification(),
          )
        : this.storesService.createTerminal(this.data.storeId, request, withoutErrorNotification());

    request$.subscribe({
      next: (terminal) => {
        this.saving.set(false);
        this.dialogRef.close(terminal);
      },
      error: (err) => {
        this.saving.set(false);
        applyFormApiErrors(this.form, err, this.serverError);
      },
    });
  }
}
