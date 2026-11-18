import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import {
  EMPTY,
  catchError,
  debounceTime,
  distinctUntilChanged,
  firstValueFrom,
  of,
  switchMap,
  tap,
} from 'rxjs';
import { PosProductResult } from '../../../../api/models/pos-product-result';
import { ScanResultResponse } from '../../../../api/models/scan-result-response';
import { isApiError } from '../../../../core/http/api-error';
import { withoutErrorNotification } from '../../../../core/http/http-context-tokens';
import { ClpPipe, QtyPipe } from '../../../../shared/pipes';
import { extractPosErrorMessage } from '../cart/pos-http.utils';
import { POS_SEARCH_MAX_LIMIT, PosProductService } from './pos-product.service';

/**
 * Barra de escaneo y búsqueda rápida del POS (`HU-POS-01`, `HU-POS-02`, `ADR-28`).
 *
 * Mantiene el foco en el campo principal tras cada operación, ejecuta búsqueda incremental por
 * nombre/SKU/atajo/prefijo de código y resuelve códigos directos o ráfagas del lector sin
 * requerir clics.
 */
@Component({
  selector: 'pos-search-bar',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    QtyPipe,
    ReactiveFormsModule,
  ],
  templateUrl: './pos-search-bar.html',
  styleUrl: './pos-search-bar.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosSearchBar implements OnInit {
  private readonly productService = inject(PosProductService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly scanInput = viewChild<ElementRef<HTMLInputElement>>('scanInput');

  /** Deshabilita resolución/búsqueda cuando falta seleccionar local o terminal. */
  readonly disabled = input(false);

  /** Emite cuando un código fue resuelto exitosamente vía `/pos/products/resolve`. */
  readonly scanResolved = output<{
    readonly result: ScanResultResponse;
    readonly code: string;
  }>();

  /** Emite cuando el cajero selecciona un producto de la lista de búsqueda. */
  readonly productSelected = output<PosProductResult>();

  protected readonly queryControl = new FormControl('', { nonNullable: true });
  protected readonly results = signal<readonly PosProductResult[]>([]);
  protected readonly dropdownOpen = signal(false);
  protected readonly highlightedIndex = signal(-1);
  protected readonly searching = signal(false);
  protected readonly resolving = signal(false);
  protected readonly feedbackError = signal<string | null>(null);

  private searchGeneration = 0;

  constructor() {
    effect(() => {
      if (this.disabled()) {
        this.queryControl.disable({ emitEvent: false });
      } else {
        this.queryControl.enable({ emitEvent: false });
      }
    });
  }

  ngOnInit(): void {
    this.queryControl.valueChanges
      .pipe(
        tap(() => {
          this.feedbackError.set(null);
        }),
        debounceTime(180),
        distinctUntilChanged(),
        switchMap((raw) => {
          const term = raw.trim();
          if (this.disabled() || term.length < 2) {
            this.results.set([]);
            this.dropdownOpen.set(false);
            this.highlightedIndex.set(-1);
            this.searching.set(false);
            return EMPTY;
          }
          const gen = ++this.searchGeneration;
          this.searching.set(true);
          return this.productService
            .searchProducts(term, POS_SEARCH_MAX_LIMIT, withoutErrorNotification())
            .pipe(
              tap((items) => {
                if (gen !== this.searchGeneration) {
                  return;
                }
                this.results.set(items ?? []);
                this.dropdownOpen.set(true);
                this.highlightedIndex.set(items.length > 0 ? 0 : -1);
                this.searching.set(false);
              }),
              catchError(() => {
                if (gen === this.searchGeneration) {
                  this.searching.set(false);
                  this.results.set([]);
                  this.dropdownOpen.set(false);
                }
                return of([]);
              }),
            );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
  }

  /** Enfoca el input de escaneo (llamado al iniciar y después de cada acción del POS). */
  focusScanInput(): void {
    const el = this.scanInput()?.nativeElement;
    if (el) {
      el.focus();
    }
  }

  /** Indica si el input de escaneo tiene el foco y está vacío (para `Supr` en el carrito). */
  isScanInputFocusedAndEmpty(): boolean {
    const el = this.scanInput()?.nativeElement;
    return el !== undefined && document.activeElement === el && el.value.length === 0;
  }

  /** Limpia el campo de búsqueda, cierra sugerencias y conserva el foco. */
  clearAndFocus(): void {
    this.searchGeneration += 1;
    this.queryControl.setValue('');
    this.results.set([]);
    this.dropdownOpen.set(false);
    this.highlightedIndex.set(-1);
    this.searching.set(false);
    this.feedbackError.set(null);
    this.focusScanInput();
  }

  /**
   * Resuelve un código proveniente del lector de códigos de barras o ingresado manualmente.
   * Mantiene el foco en el campo de escaneo incluso ante `CODE_NOT_FOUND` (`HU-POS-01`).
   */
  async resolveCode(rawCode: string): Promise<void> {
    const code = rawCode.trim();
    if (!code || this.disabled()) {
      return;
    }

    this.searchGeneration += 1;
    this.queryControl.setValue('');
    this.results.set([]);
    this.dropdownOpen.set(false);
    this.highlightedIndex.set(-1);
    this.searching.set(false);
    this.feedbackError.set(null);
    this.resolving.set(true);

    try {
      const result = await firstValueFrom(
        this.productService.resolveCode(code, withoutErrorNotification()),
      );
      this.resolving.set(false);
      this.scanResolved.emit({ result, code });
    } catch (error: unknown) {
      this.resolving.set(false);
      this.feedbackError.set(this.formatResolveError(error, code));
    } finally {
      this.focusScanInput();
    }
  }

  /** Maneja teclas dentro del input de búsqueda (navegación de resultados y Enter manual). */
  protected onInputKeydown(event: KeyboardEvent): void {
    if (this.disabled()) {
      return;
    }

    if (event.key === 'ArrowDown' && this.dropdownOpen() && this.results().length > 0) {
      event.preventDefault();
      const next = (this.highlightedIndex() + 1) % this.results().length;
      this.highlightedIndex.set(next);
      return;
    }

    if (event.key === 'ArrowUp' && this.dropdownOpen() && this.results().length > 0) {
      event.preventDefault();
      const len = this.results().length;
      const prev = (this.highlightedIndex() - 1 + len) % len;
      this.highlightedIndex.set(prev);
      return;
    }

    if (event.key === 'Enter' && !event.defaultPrevented) {
      event.preventDefault();
      const list = this.results();
      const idx = this.highlightedIndex();
      const highlighted = this.dropdownOpen() && idx >= 0 ? list[idx] : undefined;
      if (highlighted) {
        this.selectProduct(highlighted);
        return;
      }
      const typed = this.queryControl.value.trim();
      if (typed) {
        void this.resolveCode(typed);
      }
    }
  }

  /** Selecciona un producto de la lista desplegable y devuelve el foco al campo de escaneo. */
  protected selectProduct(product: PosProductResult): void {
    this.searchGeneration += 1;
    this.queryControl.setValue('');
    this.results.set([]);
    this.dropdownOpen.set(false);
    this.highlightedIndex.set(-1);
    this.feedbackError.set(null);
    this.productSelected.emit(product);
    this.focusScanInput();
  }

  private formatResolveError(error: unknown, code: string): string {
    if (isApiError(error)) {
      if (error.code === 'CODE_NOT_FOUND') {
        return `Código no encontrado (${code}) — CODE_NOT_FOUND.`;
      }
      if (error.code === 'PRODUCT_INACTIVE') {
        return `El producto asociado a "${code}" está inactivo (PRODUCT_INACTIVE).`;
      }
    }
    return extractPosErrorMessage(error, `No se pudo resolver el código "${code}".`);
  }
}
