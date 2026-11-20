import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { firstValueFrom } from 'rxjs';
import { CategoryResponse } from '../../api/models/category-response';
import { ProductSummary } from '../../api/models/product-summary';
import { ReplenishmentParamsRequest } from '../../api/models/replenishment-params-request';
import { ReplenishmentParamsResponse } from '../../api/models/replenishment-params-response';
import { isApiError } from '../../core/http/api-error';
import { ReplenishmentService } from './replenishment.service';
import { ParamScope, PARAM_SCOPE_OPTIONS } from './replenishment.models';

/** Datos de apertura del diálogo de parámetros por alcance. */
export interface UpsertParamsDialogData {
  /** Fila existente a editar; ausente = creación. */
  readonly existing?: ReplenishmentParamsResponse;
  /** Local activo (obligatorio para filas STORE). */
  readonly storeId: number | null;
  /** Nombre del local activo, para mostrar en el diálogo. */
  readonly storeName: string;
}

/** Opción plana de categoría para el selector de alcance CATEGORY. */
interface CategoryOption {
  readonly id: number;
  readonly label: string;
}

/** Validador de coherencia entre el alcance (`scope`) y su clave requerida (`categoryId`/`productId`). */
const scopeTargetValidator: ValidatorFn = (group): ValidationErrors | null => {
  const scope = group.get('scope')?.value as ParamScope | null;
  if (scope === 'CATEGORY' && !group.get('categoryId')?.value) {
    return { categoryRequired: true };
  }
  if (scope === 'PRODUCT' && !group.get('productId')?.value) {
    return { productRequired: true };
  }
  return null;
};

/**
 * Diálogo de alta/edición de una fila de parámetros por alcance (`HU-PRV-11`,
 * `PUT /replenishment/params`, upsert por alcance y clave).
 *
 * Campos numéricos vacíos = `null` = hereda del nivel superior (modelo B.11). El alcance queda
 * fijo al editar una fila (el upsert identifica por alcance + clave). El local de una fila
 * `STORE` es siempre el local activo.
 */
@Component({
  selector: 'pos-upsert-params-dialog',
  imports: [
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDialogModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>
      {{ existing ? 'Editar parámetros' : 'Nuevo parámetro de reabastecimiento' }}
    </h2>
    <mat-dialog-content>
      <form [formGroup]="form" (ngSubmit)="submit()">
        <mat-form-field appearance="outline">
          <mat-label>Alcance</mat-label>
          <mat-select formControlName="scope" [disabled]="!!existing">
            <mat-option [value]="null">— Elegir alcance —</mat-option>
            @for (opt of scopeOptions; track opt.value) {
              <mat-option [value]="opt.value">{{ opt.label }}</mat-option>
            }
          </mat-select>
          @if (form.controls.scope.hasError('required')) {
            <mat-error>Elige el alcance de la fila.</mat-error>
          }
        </mat-form-field>

        <p class="scope-target">{{ targetLabel() }}</p>

        @if (needsCategory()) {
          <mat-form-field appearance="outline">
            <mat-label>Categoría</mat-label>
            <mat-select formControlName="categoryId">
              <mat-option [value]="null">— Elegir categoría —</mat-option>
              @for (cat of categories(); track cat.id) {
                <mat-option [value]="cat.id">{{ cat.label }}</mat-option>
              }
            </mat-select>
            @if (form.hasError('categoryRequired')) {
              <mat-error>El alcance CATEGORY exige una categoría.</mat-error>
            }
          </mat-form-field>
        }

        @if (needsProduct()) {
          <div class="product-search">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Buscar producto</mat-label>
              <input
                matInput
                type="text"
                [value]="productQuery()"
                (input)="onProductQueryInput($event)"
                (keydown.enter)="$event.preventDefault(); searchProducts()"
                placeholder="SKU, código de barras o nombre"
              />
            </mat-form-field>
            <button
              mat-stroked-button
              type="button"
              (click)="searchProducts()"
              [disabled]="searching()"
            >
              {{ searching() ? 'Buscando…' : 'Buscar' }}
            </button>
          </div>
          <mat-form-field appearance="outline">
            <mat-label>Producto</mat-label>
            <mat-select formControlName="productId">
              <mat-option [value]="null">— Elegir producto —</mat-option>
              @for (product of productOptions(); track product.id) {
                <mat-option [value]="product.id">{{ product.sku }} — {{ product.name }}</mat-option>
              }
            </mat-select>
            @if (form.hasError('productRequired')) {
              <mat-error>El alcance PRODUCT exige un producto.</mat-error>
            }
          </mat-form-field>
        }

        <mat-checkbox formControlName="enabled">Participa en las corridas</mat-checkbox>

        <div class="numeric-grid">
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Ventana W (días)</mat-label>
            <input matInput type="number" formControlName="windowDays" min="7" max="365" />
            <mat-hint>7–365 · Vacío = hereda</mat-hint>
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Mínimo historia (días)</mat-label>
            <input matInput type="number" formControlName="minHistoryDays" min="1" max="90" />
            <mat-hint>1–90 · Vacío = hereda</mat-hint>
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Stock de seguridad (días)</mat-label>
            <input matInput type="number" formControlName="safetyMinDays" min="0" max="60" />
            <mat-hint>0–60 · Vacío = hereda</mat-hint>
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Período R (días)</mat-label>
            <input matInput type="number" formControlName="reviewPeriodDays" min="1" max="60" />
            <mat-hint>1–60 · Vacío = automático</mat-hint>
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Factor z (servicio)</mat-label>
            <input
              matInput
              type="number"
              formControlName="serviceLevelZ"
              min="0"
              max="3.5"
              step="0.05"
            />
            <mat-hint>0–3.5 · Vacío = hereda</mat-hint>
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Multiplicador k (atípicos)</mat-label>
            <input matInput type="number" formControlName="outlierK" min="1" max="10" step="0.5" />
            <mat-hint>1–10 · Vacío = hereda</mat-hint>
          </mat-form-field>

          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Factor vida útil (0.1–1)</mat-label>
            <input
              matInput
              type="number"
              formControlName="shelfLifeFactor"
              min="0.1"
              max="1"
              step="0.05"
            />
            <mat-hint>0.1–1 · Vacío = hereda</mat-hint>
          </mat-form-field>
        </div>

        @if (serverError(); as error) {
          <p class="server-error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" (click)="cancel()">Cancelar</button>
      <button mat-flat-button type="button" [disabled]="form.invalid" (click)="submit()">
        Guardar
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    form {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 380px;
    }

    .scope-target {
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.85rem;
      margin: 0;
    }

    .product-search {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .numeric-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 4px 12px;
      margin-top: 4px;
    }

    .server-error {
      color: var(--mat-sys-error);
      font-size: 0.85rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UpsertParamsDialog {
  private readonly dialogRef =
    inject<MatDialogRef<UpsertParamsDialog, ReplenishmentParamsRequest | undefined>>(MatDialogRef);
  private readonly replenishmentService = inject(ReplenishmentService);
  readonly data = inject<UpsertParamsDialogData>(MAT_DIALOG_DATA);

  /** Fila existente en edición (atajo de lectura). */
  protected readonly existing = this.data.existing ?? null;

  protected readonly scopeOptions = PARAM_SCOPE_OPTIONS;

  /** Categorías aplanadas para el selector CATEGORY (se cargan al abrir). */
  protected readonly categories = signal<readonly CategoryOption[]>([]);

  /** Texto de búsqueda y opciones de producto para el selector PRODUCT. */
  protected readonly productQuery = signal('');
  protected readonly productOptions = signal<readonly ProductSummary[]>([]);
  protected readonly searching = signal(false);

  /** Error del servidor (p. ej. validación de rango) para mostrarlo sin cerrar el diálogo. */
  protected readonly serverError = signal<string | null>(null);

  private readonly fb = inject(FormBuilder);

  /**
   * Formulario tipado: los campos numéricos vacíos quedan en `null` (hereda del nivel superior
   * según el modelo B.11); el alcance y las claves se validan según el alcance elegido.
   */
  protected readonly form = this.fb.group(
    {
      scope: this.fb.control<ParamScope | null>(this.existing?.scope ?? null, {
        validators: [Validators.required],
      }),
      categoryId: this.fb.control<number | null>(this.existing?.categoryId ?? null),
      productId: this.fb.control<number | null>(this.existing?.productId ?? null),
      enabled: this.fb.control<boolean>(this.existing?.enabled ?? true),
      windowDays: this.fb.control<number | null>(this.existing?.windowDays ?? null, {
        validators: [Validators.min(7), Validators.max(365)],
      }),
      minHistoryDays: this.fb.control<number | null>(this.existing?.minHistoryDays ?? null, {
        validators: [Validators.min(1), Validators.max(90)],
      }),
      safetyMinDays: this.fb.control<number | null>(this.existing?.safetyMinDays ?? null, {
        validators: [Validators.min(0), Validators.max(60)],
      }),
      reviewPeriodDays: this.fb.control<number | null>(this.existing?.reviewPeriodDays ?? null, {
        validators: [Validators.min(1), Validators.max(60)],
      }),
      serviceLevelZ: this.fb.control<number | null>(this.existing?.serviceLevelZ ?? null, {
        validators: [Validators.min(0), Validators.max(3.5)],
      }),
      outlierK: this.fb.control<number | null>(this.existing?.outlierK ?? null, {
        validators: [Validators.min(1), Validators.max(10)],
      }),
      shelfLifeFactor: this.fb.control<number | null>(this.existing?.shelfLifeFactor ?? null, {
        validators: [Validators.min(0.1), Validators.max(1)],
      }),
    },
    { validators: [scopeTargetValidator] },
  );

  constructor() {
    void this.loadCategories();
  }

  /** Describe la clave del alcance elegido (local activo, categoría o producto). */
  protected targetLabel(): string {
    const scope = this.form.controls.scope.value;
    switch (scope) {
      case 'GLOBAL':
        return 'Se aplicará a toda la empresa. El GLOBAL no se puede borrar.';
      case 'STORE':
        return `Se aplicará al local activo: ${this.data.storeName} (#${this.data.storeId}).`;
      case 'CATEGORY':
        return 'Se aplicará a los productos de una categoría.';
      case 'PRODUCT':
        return 'Se aplicará a un producto específico.';
      default:
        return 'Elige el alcance para configurar los parámetros.';
    }
  }

  protected needsCategory(): boolean {
    return this.form.controls.scope.value === 'CATEGORY';
  }

  protected needsProduct(): boolean {
    return this.form.controls.scope.value === 'PRODUCT';
  }

  protected onProductQueryInput(event: Event): void {
    const target = event.target;
    if (target instanceof HTMLInputElement) {
      this.productQuery.set(target.value);
    }
  }

  /** Busca productos activos por texto y llena las opciones del selector. */
  protected async searchProducts(): Promise<void> {
    const q = this.productQuery().trim();
    if (!q || this.searching()) {
      return;
    }
    this.searching.set(true);
    try {
      const products = await firstValueFrom(this.replenishmentService.searchProducts(q));
      this.productOptions.set(products);
    } catch (err) {
      this.serverError.set(isApiError(err) ? err.message : 'No se pudieron buscar los productos.');
    } finally {
      this.searching.set(false);
    }
  }

  /**
   * Envía el payload del upsert al llamador (quien ejecuta `PUT /replenishment/params`):
   * `STORE` usa el local activo; los campos vacíos van como `null` (hereda).
   */
  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    const scope = raw.scope as ParamScope;
    const payload: ReplenishmentParamsRequest = {
      scope,
      enabled: raw.enabled ?? undefined,
      storeId:
        scope === 'STORE'
          ? (this.data.storeId ?? undefined)
          : (this.existing?.storeId ?? undefined),
      categoryId: scope === 'CATEGORY' ? (raw.categoryId ?? undefined) : undefined,
      productId: scope === 'PRODUCT' ? (raw.productId ?? undefined) : undefined,
      windowDays: raw.windowDays ?? undefined,
      minHistoryDays: raw.minHistoryDays ?? undefined,
      safetyMinDays: raw.safetyMinDays ?? undefined,
      reviewPeriodDays: raw.reviewPeriodDays ?? undefined,
      serviceLevelZ: raw.serviceLevelZ ?? undefined,
      outlierK: raw.outlierK ?? undefined,
      shelfLifeFactor: raw.shelfLifeFactor ?? undefined,
    };
    this.dialogRef.close(payload);
  }

  protected cancel(): void {
    this.dialogRef.close(undefined);
  }

  /** Carga el árbol de categorías activas y lo aplana como `Padre / Hija`. */
  private async loadCategories(): Promise<void> {
    try {
      const tree = await firstValueFrom(this.replenishmentService.listCategories());
      const options: CategoryOption[] = [];
      const visit = (nodes: readonly CategoryResponse[], prefix: string): void => {
        for (const node of nodes) {
          if (node.active === false) {
            continue;
          }
          const label = prefix && node.name ? `${prefix} / ${node.name}` : (node.name ?? '');
          if (node.id !== undefined && label) {
            options.push({ id: node.id, label });
          }
          if (node.children && node.children.length > 0) {
            visit(node.children, label);
          }
        }
      };
      visit(tree, '');
      this.categories.set(options);
    } catch {
      // Sin categorías el selector queda vacío; el alcance CATEGORY sigue exigiendo una.
    }
  }
}
