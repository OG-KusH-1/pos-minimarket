import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormArray,
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { StockTransferLineResponse } from '../../../api/models/stock-transfer-line-response';
import { StockTransferResponse } from '../../../api/models/stock-transfer-response';
import { TransferReceiveRequest } from '../../../api/models/transfer-receive-request';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { QtyPipe } from '../../../shared/pipes';
import { formatStoreRef, formatTransferLocalDate } from './transfers.models';
import { TransfersService } from './transfers.service';

/** Datos de entrada para {@link TransferReceiveDialog}. */
export interface TransferReceiveDialogData {
  readonly transfer: StockTransferResponse;
}

/** Metadatos de solo lectura de una línea en recepción. */
interface ReceiveLineMeta {
  readonly lineId: number;
  readonly sku: string;
  readonly name: string;
  readonly lotNumber: string | null;
  readonly expiryDate: string | null;
  readonly qtySent: number;
}

/** Grupo reactivo tipado por línea en recepción. */
type ReceiveLineFormGroup = FormGroup<{
  lineId: FormControl<number>;
  qtyReceived: FormControl<number | null>;
  differenceReason: FormControl<string>;
}>;

/** Resumen calculado de diferencias en el formulario de recepción. */
interface ReceiveSummary {
  readonly differenceLines: number;
  readonly totalMissing: number;
}

/**
 * Diálogo para recepcionar una transferencia en tránsito (`SENT`) en el local de destino
 * (`POST /api/v1/stock-transfers/{id}/receive`, `HU-INV-07`).
 *
 * Permite informar `qtyReceived` para todas las líneas y exige `differenceReason` obligatorio
 * cuando `qtyReceived < qtySent`, advirtiendo que la diferencia generará una merma sistémica
 * (`TRANSFER_DIFFERENCE`).
 */
@Component({
  selector: 'pos-transfer-receive-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  templateUrl: './transfer-receive-dialog.html',
  styleUrl: './transfer-receive-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TransferReceiveDialog {
  protected readonly data = inject<TransferReceiveDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<TransferReceiveDialog, StockTransferResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly transfersService = inject(TransfersService);

  protected readonly transfer = this.data.transfer;
  protected readonly originLabel = formatStoreRef(this.transfer.origin);
  protected readonly destinationLabel = formatStoreRef(this.transfer.destination);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly summary = signal<ReceiveSummary>({ differenceLines: 0, totalMissing: 0 });

  protected readonly lineMetas: readonly ReceiveLineMeta[];
  protected readonly linesArray: FormArray<ReceiveLineFormGroup>;
  protected readonly form: FormGroup<{ lines: FormArray<ReceiveLineFormGroup> }>;

  constructor() {
    const sourceLines = this.transfer.lines ?? [];
    this.lineMetas = sourceLines.map((line) => this.toMeta(line));
    const groups = this.lineMetas.map((meta) => this.createLineGroup(meta));
    this.linesArray = this.fb.array<ReceiveLineFormGroup>(groups);
    this.form = this.fb.group({
      lines: this.linesArray,
    });

    this.linesArray.valueChanges.subscribe(() => {
      this.recomputeSummary();
    });
    this.recomputeSummary();
  }

  protected lineGroupAt(index: number): ReceiveLineFormGroup {
    return this.linesArray.at(index);
  }

  protected lineMissingAt(index: number): number {
    const meta = this.lineMetas[index];
    const group = this.linesArray.at(index);
    if (!meta || !group) {
      return 0;
    }
    const raw = group.controls.qtyReceived.value;
    if (raw === null || raw === ('' as unknown) || !Number.isFinite(Number(raw))) {
      return 0;
    }
    const diff = meta.qtySent - Number(raw);
    return diff > 0 ? Math.round(diff * 1000) / 1000 : 0;
  }

  protected hasDifferenceAt(index: number): boolean {
    return this.lineMissingAt(index) > 0;
  }

  protected formatExpiry(isoDate: string | null): string {
    return isoDate ? formatTransferLocalDate(isoDate) : '—';
  }

  protected receiveAllFull(): void {
    this.lineMetas.forEach((meta, idx) => {
      const group = this.linesArray.at(idx);
      group.patchValue({
        qtyReceived: meta.qtySent,
        differenceReason: '',
      });
      group.controls.differenceReason.updateValueAndValidity();
    });
    this.recomputeSummary();
  }

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const transferId = this.transfer.id;
    if (transferId === undefined) {
      return;
    }

    const request: TransferReceiveRequest = {
      lines: this.lineMetas.map((meta, idx) => {
        const group = this.linesArray.at(idx);
        const qtyReceived = Number(group.controls.qtyReceived.value ?? 0);
        const trimmedReason = group.controls.differenceReason.value.trim();
        return {
          lineId: meta.lineId,
          qtyReceived,
          ...(trimmedReason ? { differenceReason: trimmedReason } : {}),
        };
      }),
    };

    this.saving.set(true);
    this.serverError.set(null);

    this.transfersService
      .receiveTransfer(transferId, request, withoutErrorNotification())
      .subscribe({
        next: (updated) => {
          this.saving.set(false);
          this.dialogRef.close(updated);
        },
        error: (err: unknown) => {
          this.saving.set(false);
          if (!isApiError(err)) {
            this.serverError.set('No se pudo registrar la recepción de la transferencia.');
            return;
          }
          if (err.hasFieldErrors) {
            let mappedAny = false;
            for (const fieldErr of err.errors) {
              const path = fieldErr.field.replace(/\[(\d+)\]/g, '.$1');
              const control = this.form.get(path);
              if (control) {
                control.setErrors({ server: fieldErr.message });
                control.markAsTouched();
                mappedAny = true;
              }
            }
            if (!mappedAny) {
              this.serverError.set(err.message);
            }
          } else {
            this.serverError.set(err.message);
          }
        },
      });
  }

  private toMeta(line: StockTransferLineResponse): ReceiveLineMeta {
    return {
      lineId: line.id ?? 0,
      sku: line.sku ?? '—',
      name: line.name ?? 'Producto',
      lotNumber: line.lotNumber ?? null,
      expiryDate: line.expiryDate ?? null,
      qtySent: line.qtySent ?? 0,
    };
  }

  private createLineGroup(meta: ReceiveLineMeta): ReceiveLineFormGroup {
    const group: ReceiveLineFormGroup = this.fb.group({
      lineId: [meta.lineId],
      qtyReceived: [
        meta.qtySent as number | null,
        [Validators.required, Validators.min(0), Validators.max(meta.qtySent)],
      ],
      differenceReason: [
        '',
        [
          Validators.maxLength(250),
          (control) => this.validateDifferenceReason(control, meta.qtySent),
        ],
      ],
    });

    group.controls.qtyReceived.valueChanges.subscribe(() => {
      group.controls.differenceReason.updateValueAndValidity({ emitEvent: false });
    });

    return group;
  }

  private validateDifferenceReason(
    control: AbstractControl<string>,
    qtySent: number,
  ): ValidationErrors | null {
    const parent = control.parent as ReceiveLineFormGroup | null;
    if (!parent) {
      return null;
    }
    const rawQty = parent.controls.qtyReceived.value;
    if (rawQty === null || rawQty === ('' as unknown) || !Number.isFinite(Number(rawQty))) {
      return null;
    }
    const qtyReceived = Number(rawQty);
    if (qtyReceived >= 0 && qtyReceived < qtySent) {
      return control.value.trim().length === 0 ? { differenceRequired: true } : null;
    }
    return null;
  }

  private recomputeSummary(): void {
    let differenceLines = 0;
    let totalMissing = 0;
    for (let i = 0; i < this.lineMetas.length; i++) {
      const missing = this.lineMissingAt(i);
      if (missing > 0) {
        differenceLines++;
        totalMissing += missing;
      }
    }
    this.summary.set({
      differenceLines,
      totalMissing: Math.round(totalMissing * 1000) / 1000,
    });
  }
}
