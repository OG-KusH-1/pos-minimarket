import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/** Intervalo máximo permitido entre teclas consecutivas de una ráfaga de lector (ADR-28). */
export const DEFAULT_SCAN_MAX_INTERVAL_MS = 30;

/** Largo mínimo de una ráfaga para considerarla un código de lector. */
export const DEFAULT_SCAN_MIN_LENGTH = 3;

/** Opciones de configuración para {@link BarcodeScannerDetector}. */
export interface BarcodeScannerOptions {
  readonly maxIntervalMs?: number;
  readonly minLength?: number;
  readonly now?: () => number;
}

/**
 * Detector de lector de códigos de barras en modo teclado/wedge (`ADR-28`, `HU-POS-01`).
 *
 * Acumula caracteres imprimibles cuando el intervalo entre pulsaciones es estrictamente menor a
 * 30 ms y emite el código completo al recibir `Enter` dentro de la misma ráfaga. Cualquier pausa
 * `>= 30 ms` reinicia el buffer para no confundir escritura manual con lecturas del escáner.
 */
@Injectable({ providedIn: 'root' })
export class BarcodeScannerDetector {
  private maxIntervalMs = DEFAULT_SCAN_MAX_INTERVAL_MS;
  private minLength = DEFAULT_SCAN_MIN_LENGTH;
  private now: () => number = () =>
    typeof performance !== 'undefined' ? performance.now() : Date.now();

  private buffer = '';
  private lastCharAt: number | null = null;
  private readonly scannedSubject = new Subject<string>();

  /** Flujo de códigos completos detectados por ráfaga rápida terminada en `Enter`. */
  readonly scanned$: Observable<string> = this.scannedSubject.asObservable();

  /** Ajusta parámetros de detección o reloj (útil en pruebas deterministas). */
  configure(options: BarcodeScannerOptions): void {
    if (options.maxIntervalMs !== undefined) {
      this.maxIntervalMs = options.maxIntervalMs;
    }
    if (options.minLength !== undefined) {
      this.minLength = options.minLength;
    }
    if (options.now !== undefined) {
      this.now = options.now;
    }
    this.reset();
  }

  /** Reinicia el buffer acumulado. */
  reset(): void {
    this.buffer = '';
    this.lastCharAt = null;
  }

  /**
   * Procesa un `KeyboardEvent` (`keydown`) y devuelve el código detectado si este evento `Enter`
   * cierra una ráfaga válida `< maxIntervalMs`; en caso contrario devuelve `null`.
   */
  feed(event: KeyboardEvent): string | null {
    if (event.ctrlKey || event.altKey || event.metaKey) {
      return null;
    }

    const timestamp = this.now();

    if (event.key === 'Enter') {
      const code = this.buffer;
      const lastAt = this.lastCharAt;
      this.reset();

      if (
        code.length >= this.minLength &&
        lastAt !== null &&
        timestamp - lastAt < this.maxIntervalMs
      ) {
        this.scannedSubject.next(code);
        return code;
      }
      return null;
    }

    if (event.key.length === 1) {
      if (this.lastCharAt !== null && timestamp - this.lastCharAt < this.maxIntervalMs) {
        this.buffer += event.key;
      } else {
        this.buffer = event.key;
      }
      this.lastCharAt = timestamp;
      return null;
    }

    if (!isModifierOnlyKey(event.key)) {
      this.reset();
    }
    return null;
  }
}

function isModifierOnlyKey(key: string): boolean {
  return (
    key === 'Shift' ||
    key === 'Control' ||
    key === 'Alt' ||
    key === 'Meta' ||
    key === 'CapsLock' ||
    key === 'Dead'
  );
}
