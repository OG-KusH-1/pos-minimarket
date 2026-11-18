import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
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
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { FefoSuggestionResponse } from '../../../api/models/fefo-suggestion-response';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { QtyPipe, QtyUnit } from '../../../shared/pipes';
import { formatLotLocalDate, toLotQtyUnit } from './lots.models';
import { LotsService } from './lots.service';

/**
 * Simulador / consulta de asignación sugerida por FEFO (`GET /api/v1/lots/fefo`, `HU-INV-09`).
 *
 * Permite consultar para un producto base y una cantidad solicitada qué lotes del local activo
 * se consumirían en orden de vencimiento más próximo (`NULLS LAST`) y el faltante eventual.
 */
@Component({
  selector: 'pos-fefo-simulator-tab',
  imports: [
    MatAutocompleteModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatTableModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  template: `
    <div class="fefo-tab">
      <p class="intro">
        Consulta el orden de salida sugerido por vencimiento más próximo (FEFO) para un producto y
        cantidad en el local activo. Esta consulta es de solo lectura y no reserva ni descuenta
        stock.
      </p>

      <form class="fefo-form" [formGroup]="form" (ngSubmit)="submit()">
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="product-field">
          <mat-label>Producto base</mat-label>
          <input
            matInput
            [formControl]="productSearchControl"
            [matAutocomplete]="fefoAuto"
            placeholder="Buscar por nombre o SKU..."
          />
          <mat-icon matPrefix>search</mat-icon>
          @if (selectedProduct()) {
            <button
              mat-icon-button
              matSuffix
              type="button"
              aria-label="Limpiar producto FEFO"
              (click)="clearProduct()"
            >
              <mat-icon>close</mat-icon>
            </button>
          }
          <mat-autocomplete
            #fefoAuto="matAutocomplete"
            [displayWith]="displayProductOption"
            (optionSelected)="onProductSelected($event)"
          >
            @for (option of productOptions(); track option.id) {
              <mat-option [value]="option">
                <span>{{ option.sku }} — {{ option.name }}</span>
              </mat-option>
            }
          </mat-autocomplete>
          @if (form.controls.productId.hasError('required') && form.controls.productId.touched) {
            <mat-error>Selecciona un producto.</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="qty-field">
          <mat-label>Cantidad ({{ selectedUnit() }})</mat-label>
          <input
            matInput
            type="number"
            formControlName="quantity"
            [step]="selectedUnit() === 'kg' ? '0.001' : '1'"
            min="0.001"
          />
          @if (form.controls.quantity.hasError('required')) {
            <mat-error>Ingresa la cantidad.</mat-error>
          } @else if (form.controls.quantity.hasError('positive')) {
            <mat-error>Debe ser mayor a 0.</mat-error>
          } @else if (form.controls.quantity.hasError('wholeNumber')) {
            <mat-error>Solo enteros para productos por unidad.</mat-error>
          }
        </mat-form-field>

        <button mat-flat-button type="submit" [disabled]="loading()">Consultar FEFO</button>
      </form>

      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      }

      @if (errorMessage(); as err) {
        <p class="error-box" role="alert">{{ err }}</p>
      }

      @if (suggestion(); as res) {
        <div class="result-card">
          <div class="summary-strip">
            <div class="summary-item">
              <span class="summary-label">Solicitado</span>
              <strong>{{ res.requestedQty | qty: selectedUnit() }}</strong>
            </div>
            <div class="summary-item">
              <span class="summary-label">Cubierto por lotes</span>
              <strong>{{ coveredQty() | qty: selectedUnit() }}</strong>
            </div>
            <div class="summary-item">
              <span class="summary-label">Faltante</span>
              <strong [class.shortage-text]="(res.shortage ?? 0) > 0">
                {{ res.shortage ?? 0 | qty: selectedUnit() }}
              </strong>
            </div>
          </div>

          @if ((res.shortage ?? 0) > 0) {
            <p class="shortage-warning" role="status">
              Stock en lotes insuficiente para cubrir la cantidad solicitada. Faltante:
              {{ res.shortage | qty: selectedUnit() }}.
            </p>
          }

          @if ((res.allocations ?? []).length === 0) {
            <p class="empty-allocations">
              No hay lotes con saldo disponible para este producto en el local activo.
            </p>
          } @else {
            <div class="table-scroll">
              <table mat-table [dataSource]="res.allocations ?? []" class="alloc-table">
                <ng-container matColumnDef="lotNumber">
                  <th mat-header-cell *matHeaderCellDef>Lote</th>
                  <td mat-cell *matCellDef="let row">{{ row.lotNumber }}</td>
                </ng-container>

                <ng-container matColumnDef="expiryDate">
                  <th mat-header-cell *matHeaderCellDef>Vencimiento</th>
                  <td mat-cell *matCellDef="let row">
                    {{ row.expiryDate ? formatLocalDate(row.expiryDate) : 'Sin vencimiento' }}
                  </td>
                </ng-container>

                <ng-container matColumnDef="quantity">
                  <th mat-header-cell *matHeaderCellDef class="align-end">Cantidad sugerida</th>
                  <td mat-cell *matCellDef="let row" class="align-end">
                    <strong>{{ row.quantity | qty: selectedUnit() }}</strong>
                  </td>
                </ng-container>

                <tr mat-header-row *matHeaderRowDef="allocColumns"></tr>
                <tr mat-row *matRowDef="let row; columns: allocColumns"></tr>
              </table>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .fefo-tab {
      display: flex;
      flex-direction: column;
      gap: 16px;
      padding-top: 16px;
    }
    .intro {
      margin: 0;
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.9rem;
    }
    .fefo-form {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
    }
    .product-field {
      min-width: 280px;
      flex: 1 1 320px;
    }
    .qty-field {
      width: 180px;
    }
    .error-box {
      margin: 0;
      padding: 10px 14px;
      border-radius: 8px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .result-card {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 16px;
      border-radius: 12px;
      border: 1px solid var(--mat-sys-outline-variant);
      background: var(--mat-sys-surface-container-lowest);
    }
    .summary-strip {
      display: flex;
      flex-wrap: wrap;
      gap: 24px;
    }
    .summary-item {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .summary-label {
      font-size: 0.8rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .shortage-text {
      color: var(--mat-sys-error);
    }
    .shortage-warning {
      margin: 0;
      padding: 8px 12px;
      border-radius: 8px;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
      font-size: 0.875rem;
    }
    .empty-allocations {
      margin: 0;
      color: var(--mat-sys-on-surface-variant);
    }
    .table-scroll {
      overflow-x: auto;
    }
    .alloc-table {
      width: 100%;
    }
    .align-end {
      text-align: right;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FefoSimulatorTab {
  private readonly lotsService = inject(LotsService);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly allocColumns = ['lotNumber', 'expiryDate', 'quantity'] as const;
  protected readonly productSearchControl = new FormControl<string | ProductSummary>('', {
    nonNullable: true,
  });
  protected readonly productOptions = signal<readonly ProductSummary[]>([]);
  protected readonly selectedProduct = signal<ProductResponse | null>(null);
  protected readonly selectedUnit = computed<QtyUnit>(() =>
    toLotQtyUnit(this.selectedProduct()?.unitOfMeasure),
  );
  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly suggestion = signal<FefoSuggestionResponse | null>(null);
  protected readonly coveredQty = computed(() => {
    const res = this.suggestion();
    if (!res) {
      return 0;
    }
    return (res.allocations ?? []).reduce((acc, item) => acc + (item.quantity ?? 0), 0);
  });

  protected readonly form = this.fb.group({
    productId: [null as number | null, [Validators.required]],
    quantity: [
      1 as number | null,
      [Validators.required, (control) => this.validateQuantity(control)],
    ],
  });

  constructor() {
    this.productSearchControl.valueChanges
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
          return this.lotsService
            .searchProducts(trimmed, withoutErrorNotification())
            .pipe(catchError(() => of([] as ProductSummary[])));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((items) => this.productOptions.set(items));
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

  protected onProductSelected(event: MatAutocompleteSelectedEvent): void {
    const summary = event.option.value as ProductSummary | null;
    if (!summary || summary.id === undefined) {
      return;
    }
    const productId = summary.id;
    this.form.controls.productId.setValue(productId);
    this.suggestion.set(null);
    this.errorMessage.set(null);

    this.lotsService
      .getProduct(productId, withoutErrorNotification())
      .pipe(catchError(() => of(null)))
      .subscribe((prod) => {
        if (prod && prod.id === productId) {
          this.selectedProduct.set(prod);
          this.form.controls.quantity.updateValueAndValidity();
        }
      });
  }

  protected clearProduct(): void {
    this.selectedProduct.set(null);
    this.productSearchControl.setValue('', { emitEvent: false });
    this.form.controls.productId.setValue(null);
    this.suggestion.set(null);
    this.errorMessage.set(null);
  }

  protected submit(): void {
    this.form.controls.productId.markAsTouched();
    if (this.form.invalid || this.loading()) {
      this.form.markAllAsTouched();
      return;
    }
    const productId = this.form.controls.productId.getRawValue();
    const quantity = this.form.controls.quantity.getRawValue();
    if (productId === null || quantity === null) {
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(null);

    this.lotsService
      .suggestFefo({ productId, quantity: Number(quantity) }, withoutErrorNotification())
      .subscribe({
        next: (res) => {
          this.suggestion.set(res);
          this.loading.set(false);
        },
        error: (err: unknown) => {
          this.loading.set(false);
          this.suggestion.set(null);
          this.errorMessage.set(
            isApiError(err) ? err.message : 'No se pudo calcular la sugerencia FEFO.',
          );
        },
      });
  }

  protected formatLocalDate(iso: string | undefined): string {
    return formatLotLocalDate(iso);
  }

  private validateQuantity(control: AbstractControl<number | null>): ValidationErrors | null {
    const val = control.value;
    if (val === null || val === ('' as unknown)) {
      return null;
    }
    const num = Number(val);
    if (!Number.isFinite(num) || num <= 0) {
      return { positive: true };
    }
    if (this.selectedProduct()?.unitOfMeasure === 'UNIT' && !Number.isInteger(num)) {
      return { wholeNumber: true };
    }
    return null;
  }
}
