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
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { EMPTY, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { CustomerResponse } from '../../../../api/models/customer-response';
import { withoutErrorNotification } from '../../../../core/http/http-context-tokens';
import { ClpPipe, RutPipe } from '../../../../shared/pipes';
import { customerCreditState, customerCreditStateLabel } from './pos-customer.models';
import { PosCustomerSearchService } from './pos-customer-search.service';

/**
 * Selector de cliente para la venta POS (`HU-POS-16`).
 *
 * Componente standalone reutilizable: el estado de la selección lo posee el padre. Recibe el
 * cliente ya asociado por `customer` y comunica los cambios mediante `customerSelected` /
 * `customerCleared`, de modo que la pantalla de venta pueda propagarlos a `PosCartStore`
 * (`setCustomerId`) y al cobro.
 *
 * Operable solo con teclado (ADR-28):
 * - La búsqueda incremental arranca desde 2 caracteres (RUT o nombre).
 * - `ArrowDown` / `ArrowUp` mueven el resaltado; `Enter` selecciona; `Escape` cierra la lista.
 * - Con un cliente seleccionado se muestra su ficha de fiado (cupo, deuda y disponible) y un
 *   botón «Quitar» para volver al modo de búsqueda.
 */
@Component({
  selector: 'pos-customer-selector',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
    RutPipe,
  ],
  templateUrl: './pos-customer-selector.html',
  styleUrl: './pos-customer-selector.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosCustomerSelector implements OnInit {
  private readonly customerSearch = inject(PosCustomerSearchService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  /** Cliente actualmente asociado a la venta (estado del padre); `null` = venta sin cliente. */
  readonly customer = input<CustomerResponse | null>(null);

  /** Deshabilita la búsqueda (p. ej. cuando falta local o terminal). */
  readonly disabled = input(false);

  /** Emite el cliente elegido de la lista de resultados. */
  readonly customerSelected = output<CustomerResponse>();

  /** Emite cuando el cajero quita el cliente asociado (la venta vuelve a ser anónima). */
  readonly customerCleared = output<void>();

  protected readonly queryControl = new FormControl('', { nonNullable: true });
  protected readonly results = signal<readonly CustomerResponse[]>([]);
  protected readonly dropdownOpen = signal(false);
  protected readonly highlightedIndex = signal(-1);
  protected readonly searching = signal(false);

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
        debounceTime(200),
        distinctUntilChanged(),
        switchMap((raw) => {
          const term = raw.trim();
          if (this.disabled() || term.length < 2) {
            this.resetResults();
            this.searching.set(false);
            return EMPTY;
          }
          const generation = ++this.searchGeneration;
          this.searching.set(true);
          return this.customerSearch.searchCustomers(term, withoutErrorNotification()).pipe(
            switchMap((items) => {
              if (generation !== this.searchGeneration) {
                return EMPTY;
              }
              this.results.set(items ?? []);
              this.dropdownOpen.set(true);
              this.highlightedIndex.set(items.length > 0 ? 0 : -1);
              this.searching.set(false);
              return of(items);
            }),
            catchError(() => {
              if (generation === this.searchGeneration) {
                this.resetResults();
                this.searching.set(false);
                this.dropdownOpen.set(true);
              }
              return of([] as CustomerResponse[]);
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
  }

  /** Enfoca el campo de búsqueda (lo llama la pantalla de venta tras cada acción del POS). */
  focusSearchInput(): void {
    this.searchInput()?.nativeElement.focus();
  }

  /** Estado del fiado del cliente seleccionado, para colorear la ficha. */
  protected selectedCreditState(): 'ENABLED' | 'DISABLED' {
    const current = this.customer();
    return current ? customerCreditState(current) : 'DISABLED';
  }

  /** Etiqueta en español del estado del fiado del cliente seleccionado. */
  protected selectedCreditLabel(): string {
    const current = this.customer();
    return current ? customerCreditStateLabel(current) : '';
  }

  /** Maneja la navegación por teclado dentro del campo de búsqueda. */
  protected onInputKeydown(event: KeyboardEvent): void {
    if (this.disabled()) {
      return;
    }

    if (event.key === 'Escape') {
      event.preventDefault();
      this.resetResults();
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
      const length = this.results().length;
      const previous = (this.highlightedIndex() - 1 + length) % length;
      this.highlightedIndex.set(previous);
      return;
    }

    if (event.key === 'Enter') {
      event.preventDefault();
      const list = this.results();
      const index = this.highlightedIndex();
      const highlighted = this.dropdownOpen() && index >= 0 ? (list[index] ?? null) : null;
      if (highlighted) {
        this.selectCustomer(highlighted);
      }
    }
  }

  /** Selecciona un cliente del desplegable y notifica al padre. */
  protected selectCustomer(customer: CustomerResponse): void {
    this.searchGeneration += 1;
    this.queryControl.setValue('', { emitEvent: false });
    this.resetResults();
    this.customerSelected.emit(customer);
  }

  /** Quita el cliente asociado y notifica al padre (vuelve al modo de búsqueda). */
  protected clearCustomer(): void {
    this.customerCleared.emit();
    this.focusSearchInputSoon();
  }

  private resetResults(): void {
    this.results.set([]);
    this.dropdownOpen.set(false);
    this.highlightedIndex.set(-1);
  }

  private focusSearchInputSoon(): void {
    // Diferido a un microtask: el input se renderiza recién cuando `customer()` pasa a `null`.
    queueMicrotask(() => this.focusSearchInput());
  }
}
