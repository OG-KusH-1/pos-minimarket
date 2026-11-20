import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { KeyValuePipe } from '@angular/common';
import {
  AbstractControl,
  FormArray,
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { PromotionItemRequest } from '../../api/models/promotion-item-request';
import { PromotionItemResponse } from '../../api/models/promotion-item-response';
import { PromotionRequest } from '../../api/models/promotion-request';
import { PromotionResponse } from '../../api/models/promotion-response';
import { isApiError } from '../../core/http/api-error';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { StoreContextService } from '../../core/store-context/store-context.service';
import {
  ISO_WEEKDAYS,
  PROMOTION_TYPE_LABELS,
  PROMOTION_TYPE_OPTIONS,
  PromotionTargetType,
  PromotionType,
  TARGET_TYPE_LABELS,
  buyPayRelationValidator,
  integerRangeValidator,
  percentValidator,
  positiveIntegerValidator,
  positiveNumberValidator,
  promotionItemsValidator,
  storesRequiredValidator,
  timePairValidator,
  toDatetimeLocalValue,
  toUtcIso,
  trimmedMinLength,
  validToAfterValidFromValidator,
} from './promotion-validators';
import { PromotionsService } from './promotions.service';

/** Datos de entrada para {@link PromotionFormDialog}. */
export interface PromotionFormDialogData {
  /** `null` para creación; ID de la promoción para edición. */
  readonly promotionId: number | null;
}

/** Controles que dependen del tipo de promoción (`MODELO_DATOS.md` B.10). */
type TypeField = 'percent' | 'amount' | 'buyQty' | 'payQty' | 'minQty' | 'comboPrice';

/** Controles activos por tipo de promoción; el resto se deshabilita y se envía como `undefined`. */
const TYPE_FIELDS: Readonly<Record<PromotionType, readonly TypeField[]>> = {
  PERCENT: ['percent'],
  FIXED_AMOUNT: ['amount'],
  BUY_X_PAY_Y: ['buyQty', 'payQty'],
  QUANTITY_PRICE: ['minQty', 'amount'],
  COMBO: ['comboPrice'],
};

const TYPE_FIELD_VALIDATORS: Readonly<Record<TypeField, readonly ValidatorFn[]>> = {
  percent: [Validators.required, percentValidator()],
  amount: [Validators.required, positiveIntegerValidator()],
  buyQty: [Validators.required, positiveIntegerValidator()],
  payQty: [Validators.required, positiveIntegerValidator(), buyPayRelationValidator()],
  minQty: [Validators.required, positiveNumberValidator()],
  comboPrice: [Validators.required, positiveIntegerValidator()],
};

const TYPE_FIELD_LIST: readonly TypeField[] = [
  'percent',
  'amount',
  'buyQty',
  'payQty',
  'minQty',
  'comboPrice',
];

/** Forma tipada de una fila de objetivo (`promotion_items` en B.10). */
export interface PromotionItemForm {
  targetType: FormControl<PromotionTargetType>;
  targetId: FormControl<number | null>;
  requiredQty: FormControl<number>;
}

/** Grupo tipado de una fila de objetivo. */
export type PromotionItemGroup = FormGroup<PromotionItemForm>;

/**
 * Diálogo modal de creación y edición de promociones (`HU-POS-07`, `API.md` F.2/F.3).
 *
 * El formulario es reactivo tipado y se adapta al `promotionType` elegido: los controles ajenos
 * al tipo se deshabilitan y se envían como `undefined` (B.10 define un CK por tipo). Valida
 * vigencias (`validTo > validFrom`, pareja `timeFrom`/`timeTo`, días ISO-8601), alcance
 * (`allStores` o `storeIds`) y objetivos (`items`: al menos uno; `COMBO` exige dos o más
 * `PRODUCT`). Edición con concurrencia optimista (`version`, `409 CONCURRENT_MODIFICATION`).
 */
@Component({
  selector: 'pos-promotion-form-dialog',
  imports: [
    KeyValuePipe,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    ReactiveFormsModule,
  ],
  templateUrl: './promotion-form-dialog.html',
  styleUrl: './promotion-form-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromotionFormDialog implements OnInit {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly promotionsService = inject(PromotionsService);
  private readonly dialogRef = inject(MatDialogRef<PromotionFormDialog, PromotionResponse | null>);
  protected readonly data = inject<PromotionFormDialogData>(MAT_DIALOG_DATA);
  private readonly storeContext = inject(StoreContextService);

  protected readonly promotionTypeOptions = PROMOTION_TYPE_OPTIONS;
  protected readonly promotionTypeLabels = PROMOTION_TYPE_LABELS;
  protected readonly targetTypeLabels = TARGET_TYPE_LABELS;
  protected readonly weekdays = ISO_WEEKDAYS;

  /** Locales disponibles para el alcance restringido (signal del contexto de sesión). */
  protected readonly storeOptions = this.storeContext.availableStores;

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly concurrentConflict = signal(false);
  protected readonly currentVersion = signal<number | undefined>(undefined);

  protected readonly form = this.fb.group({
    name: ['', [Validators.required, trimmedMinLength(3), Validators.maxLength(120)]],
    description: ['', [Validators.maxLength(500)]],
    promotionType: this.fb.control('PERCENT' as PromotionType, [Validators.required]),
    priority: [100, [Validators.required, integerRangeValidator(1, 1000)]],
    stackable: [false],
    validFrom: ['', [Validators.required]],
    validTo: ['', [validToAfterValidFromValidator()]],
    daysOfWeek: this.fb.control([] as number[]),
    timeFrom: [''],
    timeTo: ['', [timePairValidator()]],
    allStores: [true],
    storeIds: this.fb.control([] as number[], [storesRequiredValidator()]),
    maxApplicationsPerSale: new FormControl<number | null>(null, [
      integerRangeValidator(1, 100000),
    ]),
    percent: new FormControl<number | null>(null, [...TYPE_FIELD_VALIDATORS.percent]),
    amount: new FormControl<number | null>(null, [...TYPE_FIELD_VALIDATORS.amount]),
    buyQty: new FormControl<number | null>(null, [...TYPE_FIELD_VALIDATORS.buyQty]),
    payQty: new FormControl<number | null>(null, [...TYPE_FIELD_VALIDATORS.payQty]),
    minQty: new FormControl<number | null>(null, [...TYPE_FIELD_VALIDATORS.minQty]),
    comboPrice: new FormControl<number | null>(null, [...TYPE_FIELD_VALIDATORS.comboPrice]),
    items: this.fb.array<PromotionItemGroup>([], [promotionItemsValidator()]),
  });

  protected get isEdit(): boolean {
    return this.data.promotionId !== null;
  }

  /** Arreglo tipado de ítems objetivo para la plantilla. */
  protected get items(): FormArray<PromotionItemGroup> {
    return this.form.controls.items;
  }

  ngOnInit(): void {
    // Reacciones cruzadas: los validadores que leen a un hermano deben reevaluarse cuando este
    // cambia (las formas reactivas no revalidan un control cuando cambia otro).
    this.form.controls.buyQty.valueChanges.subscribe(() => {
      this.form.controls.payQty.updateValueAndValidity();
    });
    this.form.controls.validFrom.valueChanges.subscribe(() => {
      this.form.controls.validTo.updateValueAndValidity();
    });
    this.form.controls.timeFrom.valueChanges.subscribe(() => {
      this.form.controls.timeTo.updateValueAndValidity();
    });
    this.form.controls.allStores.valueChanges.subscribe(() => {
      this.form.controls.storeIds.updateValueAndValidity();
    });
    this.form.controls.promotionType.valueChanges.subscribe((type) => {
      this.syncTypeState(type);
    });

    this.syncTypeState(this.form.controls.promotionType.value);
    if (this.data.promotionId !== null) {
      this.loadPromotion(this.data.promotionId);
    }
  }

  /** Agrega una fila vacía al arreglo de objetivos. */
  protected addItem(): void {
    this.form.controls.items.push(this.createItemGroup(null));
  }

  /** Elimina la fila de objetivos en la posición dada. */
  protected removeItem(index: number): void {
    this.form.controls.items.removeAt(index);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    this.errorMessage.set(null);
    this.concurrentConflict.set(false);

    const payload = this.buildPayload();
    const request$ =
      this.data.promotionId === null
        ? this.promotionsService.createPromotion(payload, withoutErrorNotification())
        : this.promotionsService.updatePromotion(
            this.data.promotionId,
            payload,
            withoutErrorNotification(),
          );

    request$.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.dialogRef.close(saved);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.handleError(err);
      },
    });
  }

  /** Recarga la promoción desde la API (útil tras un conflicto `409 CONCURRENT_MODIFICATION`). */
  protected reloadPromotion(): void {
    if (this.data.promotionId !== null) {
      this.loadPromotion(this.data.promotionId);
    }
  }

  private loadPromotion(id: number): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.concurrentConflict.set(false);

    this.promotionsService.getPromotion(id, withoutErrorNotification()).subscribe({
      next: (promotion) => {
        this.loading.set(false);
        this.patchPromotion(promotion);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.errorMessage.set(
          isApiError(err) ? err.message : 'No se pudo cargar la información de la promoción.',
        );
      },
    });
  }

  /** Vuelca la respuesta de la API al formulario, activando solo los campos de su tipo. */
  private patchPromotion(promotion: PromotionResponse): void {
    this.currentVersion.set(promotion.version);
    const type: PromotionType = promotion.promotionType ?? 'PERCENT';

    this.form.patchValue({
      name: promotion.name ?? '',
      description: promotion.description ?? '',
      promotionType: type,
      priority: promotion.priority ?? 100,
      stackable: promotion.stackable ?? false,
      validFrom: toDatetimeLocalValue(promotion.validFrom ?? ''),
      validTo: toDatetimeLocalValue(promotion.validTo ?? ''),
      daysOfWeek: [...(promotion.daysOfWeek ?? [])].sort((a, b) => a - b),
      timeFrom: promotion.timeFrom ?? '',
      timeTo: promotion.timeTo ?? '',
      allStores: promotion.allStores ?? true,
      storeIds: [...(promotion.storeIds ?? [])].sort((a, b) => a - b),
      maxApplicationsPerSale: promotion.maxApplicationsPerSale ?? null,
    });

    this.syncTypeState(type);
    this.form.patchValue({
      percent: promotion.percent ?? null,
      amount: promotion.amount ?? null,
      buyQty: promotion.buyQty ?? null,
      payQty: promotion.payQty ?? null,
      minQty: promotion.minQty ?? null,
      comboPrice: promotion.comboPrice ?? null,
    });

    this.form.controls.items.clear();
    for (const item of promotion.items ?? []) {
      this.form.controls.items.push(this.createItemGroup(item));
    }
    this.form.controls.items.updateValueAndValidity();
    this.form.markAsPristine();
  }

  /** Crea un grupo de ítem objetivo; `item` es `null` para una fila nueva. */
  private createItemGroup(item: PromotionItemResponse | null): PromotionItemGroup {
    return new FormGroup<PromotionItemForm>({
      targetType: new FormControl<PromotionTargetType>(item?.targetType ?? 'PRODUCT', {
        nonNullable: true,
        validators: [Validators.required],
      }),
      targetId: new FormControl<number | null>(item?.targetId ?? null, [Validators.required]),
      requiredQty: new FormControl<number>(item?.requiredQty ?? 1, {
        nonNullable: true,
        validators: [Validators.required, positiveNumberValidator()],
      }),
    });
  }

  /**
   * Habilita los controles del tipo elegido con sus validadores y deshabilita (y limpia) el resto.
   * Los controles deshabilitados se envían como `undefined` en el payload.
   */
  private syncTypeState(type: PromotionType): void {
    const active = new Set<string>(TYPE_FIELDS[type]);
    for (const field of TYPE_FIELD_LIST) {
      const control = this.form.get(field);
      if (!control) {
        continue;
      }
      if (active.has(field)) {
        control.enable({ emitEvent: false });
        control.setValidators([...TYPE_FIELD_VALIDATORS[field]]);
        control.updateValueAndValidity({ emitEvent: false });
      } else {
        control.setValidators([]);
        control.reset(null, { emitEvent: false });
        control.disable({ emitEvent: false });
      }
    }
    // Los objetivos pueden cambiar de significado (COMBO exige dos PRODUCT): revalidar.
    this.form.controls.items.updateValueAndValidity({ emitEvent: false });
  }

  /** Construye el `PromotionRequest` con solo los campos del tipo elegido. */
  private buildPayload(): PromotionRequest {
    const raw = this.form.getRawValue();
    const type = raw.promotionType;
    const items: PromotionItemRequest[] = raw.items.map((item) => ({
      targetType: item.targetType,
      targetId: Number(item.targetId),
      requiredQty: item.requiredQty ?? 1,
    }));
    const daysOfWeek = [...new Set(raw.daysOfWeek)].sort((a, b) => a - b);

    return {
      name: raw.name.trim(),
      description: optionalText(raw.description),
      promotionType: type,
      priority: Number(raw.priority),
      stackable: raw.stackable,
      validFrom: toUtcIso(raw.validFrom),
      validTo: raw.validTo ? toUtcIso(raw.validTo) : undefined,
      daysOfWeek: daysOfWeek.length > 0 ? daysOfWeek : undefined,
      timeFrom: raw.timeFrom || undefined,
      timeTo: raw.timeTo || undefined,
      allStores: raw.allStores,
      storeIds: raw.allStores ? undefined : [...raw.storeIds],
      maxApplicationsPerSale: raw.maxApplicationsPerSale ?? undefined,
      percent: type === 'PERCENT' ? Number(raw.percent) : undefined,
      amount: type === 'FIXED_AMOUNT' || type === 'QUANTITY_PRICE' ? Number(raw.amount) : undefined,
      buyQty: type === 'BUY_X_PAY_Y' ? Number(raw.buyQty) : undefined,
      payQty: type === 'BUY_X_PAY_Y' ? Number(raw.payQty) : undefined,
      minQty: type === 'QUANTITY_PRICE' ? Number(raw.minQty) : undefined,
      comboPrice: type === 'COMBO' ? Number(raw.comboPrice) : undefined,
      items,
      version: this.currentVersion(),
    };
  }

  private handleError(err: unknown): void {
    if (!isApiError(err)) {
      this.errorMessage.set('Error inesperado al guardar la promoción.');
      return;
    }

    if (err.code === 'CONCURRENT_MODIFICATION') {
      this.concurrentConflict.set(true);
      this.errorMessage.set(
        'La promoción fue modificada por otro usuario. Recarga los datos antes de guardar.',
      );
      return;
    }

    if (err.hasFieldErrors) {
      for (const fieldError of err.errors) {
        const control = this.resolveControl(fieldError.field);
        if (control) {
          control.setErrors({ server: fieldError.message });
          control.markAsTouched();
        }
      }
    }

    this.errorMessage.set(err.message);
  }

  /** Resuelve un control por ruta del backend, mapeando `items[n].campo` al arreglo de objetivos. */
  private resolveControl(field: string): AbstractControl | null {
    const itemsMatch = /^items\[(\d+)\]\.(.+)$/.exec(field);
    if (itemsMatch) {
      const index = Number(itemsMatch[1]);
      const subField = itemsMatch[2] ?? '';
      const itemControl = this.form.controls.items.at(index);
      return itemControl?.get(subField) ?? itemControl ?? null;
    }
    if (field === 'items' || field.startsWith('items[')) {
      return this.form.controls.items;
    }
    return this.form.get(field);
  }
}

/** Recorta un texto opcional: devuelve `undefined` si queda vacío. */
function optionalText(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}
