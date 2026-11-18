import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { catchError, of, switchMap } from 'rxjs';
import { InventoryCountRequest } from '../../../api/models/inventory-count-request';
import { InventoryCountResponse } from '../../../api/models/inventory-count-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { CategoryOption, CountTypeCode, flattenCategories } from './counts.models';
import { CountsService } from './counts.service';

/**
 * Diálogo modal para iniciar una nueva toma física de inventario (`FULL` o `CYCLIC`).
 *
 * Exige seleccionar categoría cuando el alcance es cíclico, permite activar o desactivar el modo
 * ciego (`blind`, activado por defecto) y utiliza clave de idempotencia en la creación.
 */
@Component({
  selector: 'pos-create-count-dialog',
  imports: [
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatRadioModule,
    MatSelectModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Iniciar toma de inventario</h2>
    @if (saving() || loadingCategories()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="dialog-body">
        @if (serverError(); as err) {
          <p class="server-error" role="alert">{{ err }}</p>
        }

        <fieldset class="scope-group">
          <legend>Alcance de la toma</legend>
          <mat-radio-group formControlName="countType" class="scope-options">
            <mat-radio-button value="FULL">
              <span class="option-title">Completa</span>
              <span class="option-desc">Todos los productos con stock positivo en el local</span>
            </mat-radio-button>
            <mat-radio-button value="CYCLIC">
              <span class="option-title">Cíclica por categoría</span>
              <span class="option-desc">
                Todos los productos activos de la categoría y sus subcategorías
              </span>
            </mat-radio-button>
          </mat-radio-group>
        </fieldset>

        @if (form.controls.countType.value === 'CYCLIC') {
          <mat-form-field appearance="outline">
            <mat-label>Categoría raíz</mat-label>
            <mat-select formControlName="categoryId">
              @for (cat of categories(); track cat.id) {
                <mat-option [value]="cat.id">{{ cat.label }}</mat-option>
              }
            </mat-select>
            @if (form.controls.categoryId.hasError('required')) {
              <mat-error>Selecciona una categoría para la toma cíclica.</mat-error>
            } @else if (form.controls.categoryId.hasError('server')) {
              <mat-error>{{ form.controls.categoryId.getError('server') }}</mat-error>
            }
          </mat-form-field>

          @if (previewTotal() !== null) {
            <div class="preview-box" role="status">
              <strong>{{ previewTotal() }}</strong> producto(s) activo(s) encontrados en esta
              categoría.
              @if (previewSample().length > 0) {
                <span class="preview-sample">Ej: {{ previewSampleNames() }}</span>
              }
            </div>
          }
        }

        <mat-checkbox formControlName="blind">
          Modo ciego (oculta el stock teórico y diferencias a quienes ejecutan el conteo)
        </mat-checkbox>

        <mat-form-field appearance="outline">
          <mat-label>Notas u observaciones (opcional)</mat-label>
          <textarea
            matInput
            formControlName="notes"
            rows="2"
            maxlength="500"
            placeholder="Ej. Conteo mensual de pasillo central"
          ></textarea>
          @if (form.controls.notes.hasError('maxlength')) {
            <mat-error>Las notas no pueden superar 500 caracteres.</mat-error>
          } @else if (form.controls.notes.hasError('server')) {
            <mat-error>{{ form.controls.notes.getError('server') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="undefined" [disabled]="saving()">
          Cancelar
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">Iniciar toma</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .dialog-body {
      display: flex;
      flex-direction: column;
      gap: 14px;
      min-width: 420px;
      padding-top: 8px;
    }
    .scope-group {
      margin: 0;
      padding: 10px 12px;
      border-radius: 8px;
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .scope-group legend {
      padding: 0 6px;
      font-size: 0.85rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .scope-options {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .option-title {
      display: block;
      font-weight: 500;
    }
    .option-desc {
      display: block;
      font-size: 0.8rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .preview-box {
      padding: 8px 12px;
      border-radius: 6px;
      background: var(--mat-sys-surface-container-low);
      font-size: 0.85rem;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .preview-sample {
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
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreateCountDialog implements OnInit {
  private readonly dialogRef = inject(MatDialogRef<CreateCountDialog, InventoryCountResponse>);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly countsService = inject(CountsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly categories = signal<readonly CategoryOption[]>([]);
  protected readonly loadingCategories = signal(false);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly previewTotal = signal<number | null>(null);
  protected readonly previewSample = signal<readonly ProductSummary[]>([]);

  protected readonly form = this.fb.group({
    countType: ['FULL' as CountTypeCode, [Validators.required]],
    categoryId: [null as number | null],
    blind: [true],
    notes: ['', [Validators.maxLength(500)]],
  });

  ngOnInit(): void {
    this.loadCategories();
    this.syncValidatorsOnTypeChange();
    this.listenCategoryPreview();
  }

  protected previewSampleNames(): string {
    return this.previewSample()
      .map((item) => item.name ?? item.sku ?? '')
      .filter((text) => text !== '')
      .join(', ');
  }

  protected submit(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const trimmedNotes = raw.notes.trim();
    const request: InventoryCountRequest = {
      countType: raw.countType,
      blind: raw.blind,
      ...(raw.countType === 'CYCLIC' && raw.categoryId !== null
        ? { categoryId: raw.categoryId }
        : {}),
      ...(trimmedNotes ? { notes: trimmedNotes } : {}),
    };

    this.saving.set(true);
    this.serverError.set(null);

    this.countsService.createCount(request, withoutErrorNotification()).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.dialogRef.close(created);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (!isApiError(err)) {
          this.serverError.set('No se pudo iniciar la toma de inventario.');
          return;
        }
        if (err.hasFieldErrors) {
          for (const fieldErr of err.errors) {
            const control = this.form.get(fieldErr.field);
            if (control) {
              control.setErrors({ server: fieldErr.message });
              control.markAsTouched();
            }
          }
        } else {
          this.serverError.set(err.message);
        }
      },
    });
  }

  private loadCategories(): void {
    this.loadingCategories.set(true);
    this.countsService
      .listCategories(withoutErrorNotification())
      .pipe(
        catchError(() => of([])),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((tree) => {
        this.loadingCategories.set(false);
        this.categories.set(flattenCategories(tree ?? []));
      });
  }

  private syncValidatorsOnTypeChange(): void {
    this.form.controls.countType.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((countType) => {
        const catControl = this.form.controls.categoryId;
        if (countType === 'CYCLIC') {
          catControl.setValidators([Validators.required]);
        } else {
          catControl.clearValidators();
          catControl.setValue(null);
          this.previewTotal.set(null);
          this.previewSample.set([]);
        }
        catControl.updateValueAndValidity();
      });
  }

  private listenCategoryPreview(): void {
    this.form.controls.categoryId.valueChanges
      .pipe(
        switchMap((categoryId) => {
          if (categoryId === null || this.form.controls.countType.value !== 'CYCLIC') {
            return of(null);
          }
          return this.countsService
            .listProductsByCategory(categoryId, withoutErrorNotification())
            .pipe(catchError(() => of(null)));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => {
        if (!page) {
          this.previewTotal.set(null);
          this.previewSample.set([]);
          return;
        }
        this.previewTotal.set(page.totalElements ?? 0);
        this.previewSample.set(page.content ?? []);
      });
  }
}
