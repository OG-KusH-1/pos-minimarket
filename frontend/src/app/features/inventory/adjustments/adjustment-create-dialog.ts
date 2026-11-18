import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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
import {
  MatAutocompleteModule,
  MatAutocompleteSelectedEvent,
} from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { LotResponse } from '../../../api/models/lot-response';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { StockAdjustmentLineRequest } from '../../../api/models/stock-adjustment-line-request';
import { StockAdjustmentRequest } from '../../../api/models/stock-adjustment-request';
import { StockAdjustmentResponse } from '../../../api/models/stock-adjustment-response';
import { isApiError } from '../../../core/http/api-error';
import { withIdempotency, withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { QtyPipe } from '../../../shared/pipes';
import { ADJUSTMENT_REASON_OPTIONS, AdjustmentReasonCode } from './adjustments.models';
import { AdjustmentsService } from './adjustments.service';

/** Modo de asignación de lote en entradas para productos con `lotControlled = true`. */
export type LineLotMode = 'existing' | 'new';

/** Metadatos cargados del producto y sus lotes para una fila del formulario de ajuste. */
export interface AdjustmentLineMeta {
  readonly product: ProductResponse | null;
  readonly lots: readonly LotResponse[];
  readonly loadingMeta: boolean;
  readonly productOptions: readonly ProductSummary[];
}

/** Grupo reactivo tipado para cada línea del ajuste. */
export type AdjustmentLineFormGroup = FormGroup<{
  productSearch: FormControl<string | ProductSummary>;
  productId: FormControl<number | null>;
  quantityDelta: FormControl<number | null>;
  lotMode: FormControl<LineLotMode>;
  lotId: FormControl<number | null>;
  lotNumber: FormControl<string>;
  expiryDate: FormControl<string>;
}>;

/**
 * Diálogo de creación multi-línea de solicitudes de ajuste manual de stock
 * (`POST /api/v1/stock-adjustments`, `HU-INV-05`).
 *
 * Soporta productos con y sin control de lotes (`lotControlled`):
 * - Salidas (`quantityDelta < 0`) en productos con lote exigen seleccionar un `lotId` con saldo.
 * - Entradas (`quantityDelta > 0`) en productos con lote permiten seleccionar un `lotId` existente
 *   o indicar un `lotNumber` nuevo (con generación automática opcional) y `expiryDate` (obligatoria
 *   si el producto define `shelfLifeDays`).
 * - Envía la petición con `withIdempotency()` para evitar duplicados ante reintentos (`ADR-14`).
 */
@Component({
  selector: 'pos-adjustment-create-dialog',
  imports: [
    MatAutocompleteModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Nuevo ajuste de inventario</h2>
    @if (saving()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="create-content">
        <p class="helper-intro">
          La solicitud quedará en estado <strong>Pendiente de aprobación</strong> y no modificará el
          stock hasta ser aprobada por un supervisor distinto.
        </p>

        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <div class="header-fields">
          <mat-form-field appearance="outline">
            <mat-label>Motivo</mat-label>
            <mat-select formControlName="reasonCode">
              @for (opt of reasonOptions; track opt.value) {
                <mat-option [value]="opt.value">{{ opt.label }}</mat-option>
              }
            </mat-select>
            @if (form.controls.reasonCode.hasError('required')) {
              <mat-error>Selecciona un motivo.</mat-error>
            } @else if (form.controls.reasonCode.hasError('server')) {
              <mat-error>{{ form.controls.reasonCode.getError('server') }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" class="notes-field">
            <mat-label>Observaciones (opcional)</mat-label>
            <input
              matInput
              formControlName="notes"
              maxlength="500"
              placeholder="Respaldo operativo o referencia (máx. 500 caracteres)"
            />
            @if (form.controls.notes.hasError('maxlength')) {
              <mat-error>Máximo 500 caracteres.</mat-error>
            } @else if (form.controls.notes.hasError('server')) {
              <mat-error>{{ form.controls.notes.getError('server') }}</mat-error>
            }
          </mat-form-field>
        </div>

        <div class="lines-header">
          <h3>Líneas del ajuste ({{ linesArray.length }})</h3>
          <button mat-stroked-button type="button" (click)="addLine()" [disabled]="saving()">
            <mat-icon>add</mat-icon>
            Agregar línea
          </button>
        </div>

        <div class="lines-list" formArrayName="lines">
          @for (lineGroup of linesArray.controls; track lineGroup; let i = $index) {
            @let meta = lineMeta()[i];
            @let prod = meta?.product ?? null;
            @let deltaVal = lineGroup.controls.quantityDelta.value;
            @let isOutbound = deltaVal !== null && Number(deltaVal) < 0;

            <div class="line-card" [formGroupName]="i">
              <div class="line-top-row">
                <mat-form-field
                  appearance="outline"
                  subscriptSizing="dynamic"
                  class="product-field"
                >
                  <mat-label>Producto #{{ i + 1 }}</mat-label>
                  <input
                    matInput
                    formControlName="productSearch"
                    [matAutocomplete]="lineAuto"
                    placeholder="Buscar por nombre o SKU..."
                  />
                  @if (lineGroup.controls.productId.value !== null) {
                    <button
                      mat-icon-button
                      matSuffix
                      type="button"
                      aria-label="Limpiar producto de la línea"
                      (click)="clearLineProduct(i)"
                    >
                      <mat-icon>close</mat-icon>
                    </button>
                  }
                  <mat-autocomplete
                    #lineAuto="matAutocomplete"
                    [displayWith]="displayProductOption"
                    (optionSelected)="onLineProductSelected(i, $event)"
                  >
                    @for (option of meta?.productOptions ?? []; track option.id) {
                      <mat-option [value]="option">
                        <span>{{ option.sku }} — {{ option.name }}</span>
                      </mat-option>
                    }
                  </mat-autocomplete>
                  @if (lineGroup.controls.productId.hasError('required')) {
                    <mat-error>Selecciona un producto.</mat-error>
                  } @else if (lineGroup.controls.productId.hasError('server')) {
                    <mat-error>{{ lineGroup.controls.productId.getError('server') }}</mat-error>
                  }
                </mat-form-field>

                <mat-form-field appearance="outline" subscriptSizing="dynamic" class="delta-field">
                  <mat-label>
                    Variación ({{ prod?.unitOfMeasure === 'KG' ? 'kg' : 'u' }})
                  </mat-label>
                  <input
                    matInput
                    type="number"
                    formControlName="quantityDelta"
                    [step]="prod?.unitOfMeasure === 'KG' ? '0.001' : '1'"
                    placeholder="+5 o -2"
                  />
                  <mat-hint>Positivo = entrada, negativo = salida</mat-hint>
                  @if (lineGroup.controls.quantityDelta.hasError('required')) {
                    <mat-error>Ingresa la variación.</mat-error>
                  } @else if (lineGroup.controls.quantityDelta.hasError('nonZero')) {
                    <mat-error>No puede ser cero.</mat-error>
                  } @else if (lineGroup.controls.quantityDelta.hasError('wholeNumber')) {
                    <mat-error>Solo enteros para unidad.</mat-error>
                  } @else if (lineGroup.controls.quantityDelta.hasError('server')) {
                    <mat-error>{{ lineGroup.controls.quantityDelta.getError('server') }}</mat-error>
                  }
                </mat-form-field>

                <button
                  mat-icon-button
                  type="button"
                  [disabled]="linesArray.length <= 1 || saving()"
                  [attr.aria-label]="'Quitar línea ' + (i + 1)"
                  (click)="removeLine(i)"
                >
                  <mat-icon>delete</mat-icon>
                </button>
              </div>

              @if (meta?.loadingMeta) {
                <p class="lot-loading">Cargando configuración de lote del producto...</p>
              } @else if (prod && !prod.lotControlled) {
                <p class="lot-hint">Producto sin control de lotes.</p>
              } @else if (prod && prod.lotControlled) {
                <div class="lot-section">
                  @if (!isOutbound) {
                    <div class="lot-mode-row">
                      <span class="lot-mode-label">Lote (entrada):</span>
                      <mat-button-toggle-group
                        formControlName="lotMode"
                        aria-label="Modo de lote para la entrada"
                      >
                        <mat-button-toggle value="existing">Lote existente</mat-button-toggle>
                        <mat-button-toggle value="new">Nuevo lote</mat-button-toggle>
                      </mat-button-toggle-group>
                    </div>
                  } @else {
                    <span class="lot-mode-label">
                      Lote obligatorio para salida de producto controlado por lote:
                    </span>
                  }

                  @if (isOutbound || lineGroup.controls.lotMode.value === 'existing') {
                    <mat-form-field
                      appearance="outline"
                      subscriptSizing="dynamic"
                      class="lot-select"
                    >
                      <mat-label>Lote</mat-label>
                      <mat-select formControlName="lotId">
                        @for (lot of availableLotsForLine(meta, isOutbound); track lot.id) {
                          <mat-option [value]="lot.id">
                            {{ lot.lotNumber }} · Stock:
                            {{ lot.quantity | qty: (prod.unitOfMeasure === 'KG' ? 'kg' : 'u') }}
                            @if (lot.expiryDate) {
                              · Vence: {{ lot.expiryDate }}
                            }
                          </mat-option>
                        }
                      </mat-select>
                      @if (lineGroup.controls.lotId.hasError('required')) {
                        <mat-error>Selecciona un lote.</mat-error>
                      } @else if (lineGroup.controls.lotId.hasError('server')) {
                        <mat-error>{{ lineGroup.controls.lotId.getError('server') }}</mat-error>
                      }
                    </mat-form-field>
                  } @else {
                    <div class="new-lot-fields">
                      <mat-form-field
                        appearance="outline"
                        subscriptSizing="dynamic"
                        class="lot-number-field"
                      >
                        <mat-label>Número de lote</mat-label>
                        <input matInput formControlName="lotNumber" maxlength="40" />
                        <button
                          mat-icon-button
                          matSuffix
                          type="button"
                          aria-label="Generar número de lote automático"
                          (click)="generateLotNumber(i)"
                        >
                          <mat-icon>auto_fix_high</mat-icon>
                        </button>
                        @if (lineGroup.controls.lotNumber.hasError('required')) {
                          <mat-error>Ingresa el número de lote.</mat-error>
                        } @else if (lineGroup.controls.lotNumber.hasError('maxlength')) {
                          <mat-error>Máximo 40 caracteres.</mat-error>
                        } @else if (lineGroup.controls.lotNumber.hasError('server')) {
                          <mat-error>{{
                            lineGroup.controls.lotNumber.getError('server')
                          }}</mat-error>
                        }
                      </mat-form-field>

                      <mat-form-field
                        appearance="outline"
                        subscriptSizing="dynamic"
                        class="expiry-field"
                      >
                        <mat-label>
                          Vencimiento
                          {{
                            prod.shelfLifeDays !== undefined && prod.shelfLifeDays !== null
                              ? '(obligatorio)'
                              : '(opcional)'
                          }}
                        </mat-label>
                        <input matInput type="date" formControlName="expiryDate" />
                        @if (lineGroup.controls.expiryDate.hasError('required')) {
                          <mat-error>Obligatorio por vida útil del producto.</mat-error>
                        } @else if (lineGroup.controls.expiryDate.hasError('server')) {
                          <mat-error>{{
                            lineGroup.controls.expiryDate.getError('server')
                          }}</mat-error>
                        }
                      </mat-form-field>
                    </div>
                  }
                </div>
              }
            </div>
          }
        </div>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">Crear solicitud</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .create-content {
      display: flex;
      flex-direction: column;
      gap: 14px;
      min-width: min(760px, 88vw);
      max-height: 75vh;
      padding-top: 8px;
    }
    .helper-intro {
      margin: 0;
      font-size: 0.875rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .server-error {
      margin: 0;
      padding: 8px 12px;
      border-radius: 6px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font-size: 0.875rem;
    }
    .header-fields {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
    }
    .notes-field {
      flex: 1 1 280px;
    }
    .lines-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;

      h3 {
        margin: 0;
        font-size: 0.95rem;
        font-weight: 500;
      }
    }
    .lines-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .line-card {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding: 12px;
      border-radius: 10px;
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .line-top-row {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: 10px;
    }
    .product-field {
      flex: 1 1 280px;
    }
    .delta-field {
      width: 190px;
    }
    .lot-loading,
    .lot-hint {
      margin: 0;
      font-size: 0.82rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .lot-section {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding-top: 4px;
      border-top: 1px dashed var(--mat-sys-outline-variant);
    }
    .lot-mode-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }
    .lot-mode-label {
      font-size: 0.82rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .lot-select {
      width: 100%;
    }
    .new-lot-fields {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
    }
    .lot-number-field {
      flex: 1 1 220px;
    }
    .expiry-field {
      width: 210px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdjustmentCreateDialog {
  protected readonly Number = Number;
  private readonly dialogRef = inject(
    MatDialogRef<AdjustmentCreateDialog, StockAdjustmentResponse>,
  );
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly adjustmentsService = inject(AdjustmentsService);

  protected readonly reasonOptions = ADJUSTMENT_REASON_OPTIONS;
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly lineMeta = signal<readonly AdjustmentLineMeta[]>([]);

  protected readonly form = this.fb.group({
    reasonCode: ['COUNT_CORRECTION' as AdjustmentReasonCode, [Validators.required]],
    notes: ['', [Validators.maxLength(500)]],
    lines: this.fb.array<AdjustmentLineFormGroup>([]),
  });

  constructor() {
    this.addLine();
  }

  protected get linesArray(): FormArray<AdjustmentLineFormGroup> {
    return this.form.controls.lines;
  }

  protected displayProductOption = (value: string | ProductSummary | null): string => {
    if (!value) {
      return '';
    }
    if (typeof value === 'string') {
      return value;
    }
    return value.sku ? `${value.sku} — ${value.name ?? ''}` : (value.name ?? '');
  };

  /** Agrega una nueva fila de producto al ajuste y conecta su búsqueda reactiva. */
  protected addLine(): void {
    const index = this.linesArray.length;
    const group: AdjustmentLineFormGroup = this.fb.group({
      productSearch: ['' as string | ProductSummary],
      productId: [null as number | null, [Validators.required]],
      quantityDelta: [
        null as number | null,
        [Validators.required, (control) => this.validateDelta(control)],
      ],
      lotMode: ['new' as LineLotMode],
      lotId: [null as number | null],
      lotNumber: [''],
      expiryDate: [''],
    });

    this.linesArray.push(group);
    this.lineMeta.update((items) => [
      ...items,
      { product: null, lots: [], loadingMeta: false, productOptions: [] },
    ]);

    this.bindLineListeners(group);
    this.syncLineLotValidators(index);
  }

  protected removeLine(index: number): void {
    if (this.linesArray.length <= 1) {
      return;
    }
    this.linesArray.removeAt(index);
    this.lineMeta.update((items) => items.filter((_, idx) => idx !== index));
  }

  protected clearLineProduct(index: number): void {
    const group = this.linesArray.at(index);
    group.patchValue({
      productSearch: '',
      productId: null,
      lotId: null,
      lotNumber: '',
      expiryDate: '',
    });
    this.updateLineMeta(index, { product: null, lots: [], loadingMeta: false });
    this.syncLineLotValidators(index);
  }

  protected onLineProductSelected(index: number, event: MatAutocompleteSelectedEvent): void {
    const summary = event.option.value as ProductSummary | null;
    if (!summary || summary.id === undefined) {
      return;
    }
    const productId = summary.id;
    const group = this.linesArray.at(index);
    group.patchValue({
      productId,
      lotId: null,
      lotNumber: '',
      expiryDate: '',
    });
    this.updateLineMeta(index, { loadingMeta: true, product: null, lots: [] });

    this.adjustmentsService
      .getProduct(productId, withoutErrorNotification())
      .pipe(
        switchMap((product) => {
          if (!product.lotControlled) {
            return of({ product, lots: [] as LotResponse[] });
          }
          return this.adjustmentsService
            .listProductLots(productId, undefined, withoutErrorNotification())
            .pipe(
              catchError(() => of([] as LotResponse[])),
              switchMap((lots) => of({ product, lots })),
            );
        }),
        catchError(() =>
          of({ product: null as ProductResponse | null, lots: [] as LotResponse[] }),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ product, lots }) => {
        const currentIdx = this.linesArray.controls.indexOf(group);
        if (currentIdx < 0) {
          return;
        }
        this.updateLineMeta(currentIdx, { product, lots, loadingMeta: false });
        group.controls.quantityDelta.updateValueAndValidity({ emitEvent: false });
        this.syncLineLotValidators(currentIdx);
      });
  }

  /** Genera un código de lote automático con marca temporal corta cuando se crea un lote nuevo. */
  protected generateLotNumber(index: number): void {
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, '0');
    const d = String(now.getDate()).padStart(2, '0');
    const hh = String(now.getHours()).padStart(2, '0');
    const mm = String(now.getMinutes()).padStart(2, '0');
    const suffix = String(index + 1).padStart(2, '0');
    this.linesArray.at(index).controls.lotNumber.setValue(`AJ-${y}${m}${d}-${hh}${mm}-${suffix}`);
  }

  protected availableLotsForLine(
    meta: AdjustmentLineMeta | undefined,
    isOutbound: boolean,
  ): readonly LotResponse[] {
    const lots = meta?.lots ?? [];
    if (!isOutbound) {
      return lots;
    }
    return lots.filter((lot) => (lot.quantity ?? 0) > 0);
  }

  protected submit(): void {
    this.syncAllLineLotValidators();
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const lines: StockAdjustmentLineRequest[] = raw.lines.map((line, index) => {
      const product = this.lineMeta()[index]?.product;
      const quantityDelta = Number(line.quantityDelta);
      const item: StockAdjustmentLineRequest = {
        productId: Number(line.productId),
        quantityDelta,
      };
      if (!product?.lotControlled) {
        return item;
      }
      if (quantityDelta < 0 || line.lotMode === 'existing') {
        return {
          ...item,
          ...(line.lotId !== null ? { lotId: Number(line.lotId) } : {}),
        };
      }
      const trimmedLot = line.lotNumber.trim();
      const trimmedExpiry = line.expiryDate.trim();
      return {
        ...item,
        ...(trimmedLot ? { lotNumber: trimmedLot } : {}),
        ...(trimmedExpiry ? { expiryDate: trimmedExpiry } : {}),
      };
    });

    const trimmedNotes = raw.notes.trim();
    const request: StockAdjustmentRequest = {
      reasonCode: raw.reasonCode,
      ...(trimmedNotes ? { notes: trimmedNotes } : {}),
      lines,
    };

    this.saving.set(true);
    this.serverError.set(null);

    // Contexto nuevo por envío con idempotencia (ADR-14) y sin snackbar global.
    const context = withIdempotency(withoutErrorNotification());
    this.adjustmentsService.createAdjustment(request, context).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.dialogRef.close(created);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo crear la solicitud de ajuste.');
          return;
        }
        if (err.hasFieldErrors) {
          let mappedAny = false;
          for (const fieldErr of err.errors) {
            const control = this.resolveControlPath(fieldErr.field);
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

  private bindLineListeners(group: AdjustmentLineFormGroup): void {
    group.controls.productSearch.valueChanges
      .pipe(
        debounceTime(150),
        distinctUntilChanged(),
        switchMap((term) => {
          if (typeof term !== 'string') {
            return of([] as ProductSummary[]);
          }
          const trimmed = term.trim();
          if (trimmed.length === 0) {
            return of([] as ProductSummary[]);
          }
          return this.adjustmentsService
            .searchProducts(trimmed, withoutErrorNotification())
            .pipe(catchError(() => of([] as ProductSummary[])));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((options) => {
        const idx = this.linesArray.controls.indexOf(group);
        if (idx >= 0) {
          this.updateLineMeta(idx, { productOptions: options });
        }
      });

    group.controls.quantityDelta.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        const idx = this.linesArray.controls.indexOf(group);
        if (idx >= 0) {
          this.syncLineLotValidators(idx);
        }
      });

    group.controls.lotMode.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      const idx = this.linesArray.controls.indexOf(group);
      if (idx >= 0) {
        this.syncLineLotValidators(idx);
      }
    });
  }

  private validateDelta(control: AbstractControl<number | null>): ValidationErrors | null {
    const val = control.value;
    if (val === null || val === ('' as unknown)) {
      return null;
    }
    const num = Number(val);
    if (!Number.isFinite(num) || num === 0) {
      return { nonZero: true };
    }
    const group = control.parent as AdjustmentLineFormGroup | null;
    if (group) {
      const idx = this.linesArray.controls.indexOf(group);
      const product = idx >= 0 ? this.lineMeta()[idx]?.product : null;
      if (product?.unitOfMeasure === 'UNIT' && !Number.isInteger(num)) {
        return { wholeNumber: true };
      }
    }
    return null;
  }

  private syncAllLineLotValidators(): void {
    for (let i = 0; i < this.linesArray.length; i++) {
      this.syncLineLotValidators(i);
    }
  }

  private syncLineLotValidators(index: number): void {
    const group = this.linesArray.at(index);
    const product = this.lineMeta()[index]?.product;
    const deltaRaw = group.controls.quantityDelta.value;
    const isOutbound = deltaRaw !== null && Number(deltaRaw) < 0;
    const lotMode = group.controls.lotMode.value;

    const lotIdCtrl = group.controls.lotId;
    const lotNumberCtrl = group.controls.lotNumber;
    const expiryDateCtrl = group.controls.expiryDate;

    lotIdCtrl.clearValidators();
    lotNumberCtrl.clearValidators();
    expiryDateCtrl.clearValidators();

    if (product?.lotControlled) {
      if (isOutbound || lotMode === 'existing') {
        lotIdCtrl.setValidators([Validators.required]);
      } else {
        lotNumberCtrl.setValidators([Validators.required, Validators.maxLength(40)]);
        if (product.shelfLifeDays !== undefined && product.shelfLifeDays !== null) {
          expiryDateCtrl.setValidators([Validators.required]);
        }
      }
    }

    lotIdCtrl.updateValueAndValidity({ emitEvent: false });
    lotNumberCtrl.updateValueAndValidity({ emitEvent: false });
    expiryDateCtrl.updateValueAndValidity({ emitEvent: false });
  }

  private updateLineMeta(index: number, patch: Partial<AdjustmentLineMeta>): void {
    this.lineMeta.update((items) =>
      items.map((item, idx) => (idx === index ? { ...item, ...patch } : item)),
    );
  }

  private resolveControlPath(serverField: string): AbstractControl | null {
    const match = /^lines\[(\d+)\]\.(.+)$/.exec(serverField);
    if (match) {
      const idx = Number(match[1]);
      const prop = match[2];
      if (Number.isInteger(idx) && idx >= 0 && idx < this.linesArray.length && prop) {
        return this.linesArray.at(idx).get(prop);
      }
    }
    return this.form.get(serverField);
  }
}
