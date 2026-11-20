import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { extractPosErrorMessage } from '../cart/pos-http.utils';
import { SaleEmailService } from './sale-email.service';

/** Datos de apertura del diálogo de envío del comprobante por correo. */
export interface PosEmailTicketDialogData {
  /** Identificador numérico de la venta cuyo comprobante se enviará. */
  readonly saleId: number;
  /** Número legible de la venta para el encabezado del diálogo. */
  readonly saleNumber?: string;
  /** Correo sugerido para prellenar el campo (p. ej. `emailTo` de la venta o del cliente). */
  readonly defaultEmail?: string;
}

/** Duración del snackbar de confirmación de encargo de envío, en milisegundos. */
const SNACKBAR_DURATION_MS = 5000;

/**
 * Diálogo modal para enviar el comprobante de una venta por correo (`HU-POS-11`).
 *
 * Pide el correo del destinatario, lo valida con un formulario reactivo tipado y llama a
 * `POST /sales/{id}/email` (202) mediante {@link SaleEmailService}. El envío es asíncrono en el
 * backend (outbox): el diálogo confirma que el correo quedó en cola, no que llegó a destino.
 *
 * Errores: el `errorInterceptor` ya muestra el snackbar con el mensaje del `ProblemDetail`
 * (excepto `VALIDATION_ERROR` con detalle por campo, que se marca aquí en el formulario); el
 * diálogo además conserva el mensaje visible bajo el campo.
 */
@Component({
  selector: 'pos-email-ticket-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
  templateUrl: './pos-email-ticket-dialog.html',
  styleUrl: './pos-email-ticket-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosEmailTicketDialog {
  protected readonly data = inject<PosEmailTicketDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<PosEmailTicketDialog, void>);
  private readonly emailService = inject(SaleEmailService);
  private readonly snackBar = inject(MatSnackBar);

  /** Formulario reactivo tipado del correo del destinatario. */
  protected readonly emailForm = new FormGroup({
    email: new FormControl(this.data.defaultEmail ?? '', {
      nonNullable: true,
      validators: [Validators.required, Validators.email, Validators.maxLength(150)],
    }),
  });

  /** `true` mientras la petición de envío está en vuelo. */
  protected readonly sending = signal(false);
  /** Último mensaje de error de envío traducido (visible bajo el campo). */
  protected readonly errorMessage = signal<string | null>(null);

  /** Etiqueta de la venta para el título del diálogo. */
  protected get saleLabel(): string {
    return this.data.saleNumber ? `venta ${this.data.saleNumber}` : `venta #${this.data.saleId}`;
  }

  /** Envía el comprobante al correo indicado y cierra el diálogo si el backend lo acepta. */
  async send(): Promise<void> {
    if (this.sending() || this.emailForm.invalid) {
      this.emailForm.markAllAsTouched();
      return;
    }

    const email = this.emailForm.controls.email.value.trim();
    this.sending.set(true);
    this.errorMessage.set(null);
    try {
      await firstValueFrom(this.emailService.sendTicketByEmail(this.data.saleId, email));
      this.snackBar.open(
        `Comprobante de la ${this.saleLabel} en cola de envío a ${email}.`,
        'Cerrar',
        { duration: SNACKBAR_DURATION_MS },
      );
      this.dialogRef.close();
    } catch (error: unknown) {
      this.errorMessage.set(
        extractPosErrorMessage(error, 'No se pudo encargar el envío del comprobante.'),
      );
    } finally {
      this.sending.set(false);
    }
  }

  /** Cierra el diálogo sin enviar. */
  close(): void {
    this.dialogRef.close();
  }
}
