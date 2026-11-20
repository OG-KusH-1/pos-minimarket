import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../../api/api-configuration';
import { CustomerResponse } from '../../../../api/models/customer-response';
import { errorInterceptor } from '../../../../core/http/error.interceptor';
import { storeContextInterceptor } from '../../../../core/store-context/store-context.interceptor';
import { StoreContextService } from '../../../../core/store-context/store-context.service';
import { PosCustomerSelector } from './pos-customer-selector';

/** Envuelve un payload como `Blob` JSON (el cliente generado usa `responseType: 'blob'`). */
function jsonBlob(payload: unknown, type = 'application/json'): Blob {
  return new Blob([JSON.stringify(payload)], { type });
}

/** Cliente de ejemplo con fiado habilitado. */
function creditCustomer(id = 7): CustomerResponse {
  return {
    id,
    rut: '12345678-5',
    name: 'María González',
    email: 'maria@correo.cl',
    phone: '+56912345678',
    address: 'Av. Principal 123',
    creditEnabled: true,
    creditLimit: 50000,
    creditBalance: 45000,
    availableCredit: 5000,
    active: true,
    version: 1,
  };
}

/** Host de prueba que posee el estado de la selección, como lo hará la pantalla de venta. */
@Component({
  selector: 'pos-customer-selector-host',
  imports: [PosCustomerSelector],
  template: `
    <pos-customer-selector
      [customer]="customer()"
      (customerSelected)="onSelected($event)"
      (customerCleared)="onCleared()"
    />
  `,
})
class PosCustomerSelectorHost {
  readonly customer = signal<CustomerResponse | null>(null);
  readonly selectedEvents: CustomerResponse[] = [];
  clearedCount = 0;

  onSelected(customer: CustomerResponse): void {
    this.selectedEvents.push(customer);
    this.customer.set(customer);
  }

  onCleared(): void {
    this.clearedCount += 1;
    this.customer.set(null);
  }
}

describe('PosCustomerSelector', () => {
  let http: HttpTestingController | undefined;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, storeContextInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });

    const storeContext = TestBed.inject(StoreContextService);
    storeContext.setStore(10);
    storeContext.setTerminal(2);

    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('busca clientes por RUT o nombre, navega con flechas y selecciona con Enter', async () => {
    const fixture = TestBed.createComponent(PosCustomerSelectorHost);
    const host = fixture.componentInstance;
    const el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    const input = el.querySelector<HTMLInputElement>('[data-pos-customer-input]')!;
    expect(input).not.toBeNull();
    input.focus();
    input.value = 'gonzález';
    input.dispatchEvent(new Event('input'));

    await vi.waitFor(() => {
      const req = http!.expectOne((r) => r.url === '/api/v1/customers');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('q')).toBe('gonzález');
      expect(req.request.params.get('active')).toBe('true');
      expect(req.request.params.get('size')).toBe('20');
      req.flush(
        jsonBlob({
          content: [
            creditCustomer(7),
            { ...creditCustomer(8), name: 'María Fernández', rut: '11111111-1' },
          ],
          page: 0,
          size: 20,
          sort: ['name,asc'],
          totalElements: 2,
          totalPages: 1,
        }),
      );
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(el.querySelectorAll('.result-row')).toHaveLength(2);
    });

    // La primera fila muestra nombre, RUT formateado y el fiado (cupo y deuda) del resultado.
    const firstRow = el.querySelectorAll('.result-row')[0]!;
    expect(firstRow.textContent).toContain('María González');
    expect(firstRow.textContent).toContain('12.345.678-5');
    expect(firstRow.textContent).toContain('Fiado: cupo $50.000 / deuda $45.000');

    // Flecha abajo resalta la segunda opción y Enter la selecciona.
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true }));
    fixture.detectChanges();
    expect(
      el.querySelectorAll('.result-row')[1]?.classList.contains('result-row--highlighted'),
    ).toBe(true);

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    fixture.detectChanges();

    expect(host.selectedEvents).toHaveLength(1);
    expect(host.selectedEvents[0]?.id).toBe(8);
    expect(input.value).toBe('');
    // El host reflejó la selección: el selector muestra la ficha del cliente.
    expect(el.querySelector('[data-testid="customer-summary"]')).not.toBeNull();
  });

  it('muestra cupo, deuda y estado del fiado del cliente seleccionado', () => {
    const fixture = TestBed.createComponent(PosCustomerSelectorHost);
    const host = fixture.componentInstance;
    host.customer.set(creditCustomer(7));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const summary = el.querySelector<HTMLElement>('[data-testid="customer-summary"]')!;
    expect(summary).not.toBeNull();
    expect(summary.textContent).toContain('María González');
    expect(summary.textContent).toContain('12.345.678-5');
    expect(summary.textContent).toContain('Fiado habilitado');
    expect(summary.textContent).toContain('Cupo: $50.000');
    expect(summary.textContent).toContain('Deuda: $45.000');
    expect(summary.textContent).toContain('Disponible: $5.000');
  });

  it('indica que el cliente no tiene fiado habilitado cuando creditEnabled es falso', () => {
    const fixture = TestBed.createComponent(PosCustomerSelectorHost);
    const host = fixture.componentInstance;
    host.customer.set({
      ...creditCustomer(9),
      name: 'Pedro Soto',
      creditEnabled: false,
      creditLimit: 0,
      creditBalance: 0,
      availableCredit: 0,
    });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const summary = el.querySelector<HTMLElement>('[data-testid="customer-summary"]')!;
    expect(summary.textContent).toContain('Sin fiado habilitado');
    expect(summary.textContent).toContain('Venta solo con pago inmediato');
    expect(summary.textContent).not.toContain('Cupo:');
  });

  it('emite customerCleared al quitar el cliente y vuelve al modo de búsqueda', () => {
    const fixture = TestBed.createComponent(PosCustomerSelectorHost);
    const host = fixture.componentInstance;
    host.customer.set(creditCustomer(7));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const clearBtn = el.querySelector<HTMLButtonElement>('[data-testid="customer-clear-btn"]')!;
    expect(clearBtn).not.toBeNull();
    clearBtn.click();
    fixture.detectChanges();

    expect(host.clearedCount).toBe(1);
    expect(host.customer()).toBeNull();
    // Sin cliente, el selector vuelve a mostrar el campo de búsqueda.
    expect(el.querySelector<HTMLInputElement>('[data-pos-customer-input]')).not.toBeNull();
    expect(el.querySelector('[data-testid="customer-summary"]')).toBeNull();
  });
});
