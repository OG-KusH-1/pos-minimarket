import { TestBed } from '@angular/core/testing';
import { PosKeyAction } from './pos-keyboard.models';
import { PosKeyboardService } from './pos-keyboard.service';

function makeKeyboardEvent(key: string, init: KeyboardEventInit = {}): KeyboardEvent {
  return new KeyboardEvent('keydown', { key, cancelable: true, ...init });
}

describe('PosKeyboardService', () => {
  let service: PosKeyboardService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(PosKeyboardService);
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('mapea todas las teclas definidas en HU-POS-20 y llama preventDefault', () => {
    const expected: readonly (readonly [string, PosKeyAction])[] = [
      ['F1', 'help'],
      ['F2', 'search'],
      ['F4', 'editQuantity'],
      ['F6', 'discount'],
      ['F7', 'confirmAge'],
      ['F8', 'suspend'],
      ['F9', 'resume'],
      ['F10', 'pay'],
      ['F12', 'finalize'],
      ['Escape', 'cancel'],
      ['Delete', 'removeLine'],
    ];

    const emitted: PosKeyAction[] = [];
    const sub = service.actions$.subscribe((action) => emitted.push(action));

    for (const [key, expectedAction] of expected) {
      const event = makeKeyboardEvent(key);
      const resolved = service.handleKeydown(event);
      expect(resolved).toBe(expectedAction);
      expect(event.defaultPrevented).toBe(true);
    }

    sub.unsubscribe();
    expect(emitted).toEqual(expected.map(([, action]) => action));
  });

  it('no captura Delete cuando el foco está en un input de texto con contenido', () => {
    const scanInput = document.createElement('input');
    scanInput.type = 'text';
    scanInput.dataset['posScanInput'] = 'true';
    scanInput.value = '123';
    document.body.appendChild(scanInput);
    scanInput.focus();

    const genericInput = document.createElement('input');
    genericInput.type = 'text';
    document.body.appendChild(genericInput);

    try {
      const event = makeKeyboardEvent('Delete');
      Object.defineProperty(event, 'target', { value: scanInput });

      const resolved = service.handleKeydown(event, { scanInputFocusedAndEmpty: false });
      expect(resolved).toBeNull();
      expect(event.defaultPrevented).toBe(false);

      const genericEvent = makeKeyboardEvent('Delete');
      Object.defineProperty(genericEvent, 'target', { value: genericInput });
      expect(service.handleKeydown(genericEvent, { scanInputFocusedAndEmpty: true })).toBeNull();

      const emptyScanEvent = makeKeyboardEvent('Delete');
      Object.defineProperty(emptyScanEvent, 'target', { value: scanInput });
      expect(service.handleKeydown(emptyScanEvent, { scanInputFocusedAndEmpty: true })).toBe(
        'removeLine',
      );
    } finally {
      scanInput.remove();
      genericInput.remove();
    }
  });

  it('ignora atajos cuando el servicio está suspendido o hay modificadores activos', () => {
    expect(service.handleKeydown(makeKeyboardEvent('F4', { ctrlKey: true }))).toBeNull();

    service.setSuspended(true);
    const f4 = makeKeyboardEvent('F4');
    expect(service.handleKeydown(f4)).toBeNull();
    expect(f4.defaultPrevented).toBe(false);

    service.setSuspended(false);
    expect(service.handleKeydown(makeKeyboardEvent('F4'))).toBe('editQuantity');
  });

  it('permite personalizar el mapa de teclas y restablecer los valores por defecto', () => {
    service.configureKeymap({ search: 'F3' });

    expect(service.resolveAction(makeKeyboardEvent('F2'))).toBeNull();
    expect(service.resolveAction(makeKeyboardEvent('F3'))).toBe('search');

    service.resetKeymap();
    expect(service.resolveAction(makeKeyboardEvent('F2'))).toBe('search');
  });
});
