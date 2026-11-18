import { TestBed } from '@angular/core/testing';
import { BarcodeScannerDetector } from './barcode-scanner-detector';

function keyEvent(key: string, init: KeyboardEventInit = {}): KeyboardEvent {
  return new KeyboardEvent('keydown', { key, cancelable: true, ...init });
}

describe('BarcodeScannerDetector', () => {
  let detector: BarcodeScannerDetector;
  let clock: number;

  beforeEach(() => {
    clock = 1000;
    TestBed.configureTestingModule({});
    detector = TestBed.inject(BarcodeScannerDetector);
    detector.configure({ maxIntervalMs: 30, minLength: 3, now: () => clock });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('detecta una ráfaga rápida (< 30 ms entre teclas) terminada en Enter y la emite', () => {
    const emitted: string[] = [];
    const sub = detector.scanned$.subscribe((code) => emitted.push(code));

    const chars = ['7', '8', '0', '1', '2', '3'];
    for (const ch of chars) {
      clock += 8;
      expect(detector.feed(keyEvent(ch))).toBeNull();
    }
    clock += 10;
    const result = detector.feed(keyEvent('Enter'));

    sub.unsubscribe();
    expect(result).toBe('780123');
    expect(emitted).toEqual(['780123']);
  });

  it('rechaza tecleo manual con pausas >= 30 ms entre caracteres o antes de Enter', () => {
    for (const ch of ['1', '2', '3', '4']) {
      clock += 45;
      expect(detector.feed(keyEvent(ch))).toBeNull();
    }
    clock += 10;
    expect(detector.feed(keyEvent('Enter'))).toBeNull();

    // Ahora ráfaga rápida pero con Enter lento (>= 30 ms)
    for (const ch of ['9', '8', '7']) {
      clock += 5;
      detector.feed(keyEvent(ch));
    }
    clock += 35;
    expect(detector.feed(keyEvent('Enter'))).toBeNull();
  });

  it('descarta prefijos escritos manualmente y conserva solo la ráfaga final rápida', () => {
    clock += 80;
    detector.feed(keyEvent('a'));
    clock += 80;
    detector.feed(keyEvent('b'));

    // Empieza el lector tras una pausa >= 30 ms con el carácter '7' y continúa < 30 ms
    clock += 50;
    detector.feed(keyEvent('7'));
    clock += 6;
    detector.feed(keyEvent('8'));
    clock += 6;
    detector.feed(keyEvent('0'));
    clock += 6;
    expect(detector.feed(keyEvent('Enter'))).toBe('780');
  });

  it('ignora ráfagas más cortas que minLength o eventos con modificadores', () => {
    clock += 5;
    detector.feed(keyEvent('1'));
    clock += 5;
    detector.feed(keyEvent('2'));
    clock += 5;
    expect(detector.feed(keyEvent('Enter'))).toBeNull();

    clock += 5;
    expect(detector.feed(keyEvent('a', { ctrlKey: true }))).toBeNull();
  });
});
