import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ConfirmDialogService } from './confirm-dialog';

function findButton(label: string): HTMLButtonElement {
  const button = Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
    b.textContent?.includes(label),
  );
  if (!button) {
    throw new Error(`Botón no encontrado: ${label}`);
  }
  return button;
}

describe('ConfirmDialogService', () => {
  let service: ConfirmDialogService;

  beforeEach(() => {
    service = TestBed.inject(ConfirmDialogService);
  });

  afterEach(() => TestBed.inject(MatDialog).closeAll());

  it('muestra título y mensaje, y emite true al aceptar', async () => {
    const result = firstValueFrom(
      service.confirm({ title: 'Eliminar', message: '¿Eliminar el producto?' }),
    );

    await vi.waitFor(() => expect(document.querySelector('pos-confirm-dialog')).not.toBeNull());
    TestBed.tick();
    expect(document.querySelector('h2')?.textContent).toContain('Eliminar');
    expect(document.body.textContent).toContain('¿Eliminar el producto?');
    findButton('Aceptar').click();

    expect(await result).toBe(true);
  });

  it('emite false al cancelar', async () => {
    const result = firstValueFrom(
      service.confirm({ title: 'Anular', message: '¿Anular?', cancelLabel: 'No' }),
    );

    await vi.waitFor(() => expect(document.querySelector('pos-confirm-dialog')).not.toBeNull());
    TestBed.tick();
    findButton('No').click();

    expect(await result).toBe(false);
  });

  it('usa las etiquetas personalizadas', async () => {
    const result = firstValueFrom(
      service.confirm({ title: 'T', message: 'M', confirmLabel: 'Sí, anular', destructive: true }),
    );

    await vi.waitFor(() => expect(document.querySelector('pos-confirm-dialog')).not.toBeNull());
    TestBed.tick();
    findButton('Sí, anular').click();

    expect(await result).toBe(true);
  });
});
