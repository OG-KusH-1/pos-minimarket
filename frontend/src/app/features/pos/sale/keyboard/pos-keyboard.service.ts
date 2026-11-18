import { Injectable, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { DEFAULT_POS_KEY_BINDINGS, PosKeyAction, PosKeyBinding } from './pos-keyboard.models';

/**
 * Opciones opcionales al procesar un evento `keydown` en {@link PosKeyboardService}.
 */
export interface KeyboardHandleOptions {
  /**
   * Indica si el foco está en el input de escaneo y este se encuentra vacío.
   * Permite que `Supr` (`Delete`) quite la línea seleccionada sin interferir al editar texto.
   */
  readonly scanInputFocusedAndEmpty?: boolean;
}

/**
 * Servicio de atajos de teclado configurables para el POS (`ADR-28`, `HU-POS-20`).
 *
 * Centraliza el mapa completo de teclas (`F1`, `F2`, `F4`, `F6`, `F7`, `F8`, `F9`, `F10`,
 * `F12`, `Esc`, `Supr`), bloquea el comportamiento nativo del navegador en teclas de función y
 * expone los eventos como `Observable<PosKeyAction>`.
 *
 * Puede suspenderse temporalmente mientras hay un diálogo modal abierto (p. ej. `OverrideDialog`)
 * para no disparar atajos detrás de la ventana activa.
 */
@Injectable({ providedIn: 'root' })
export class PosKeyboardService {
  private readonly _bindings = signal<readonly PosKeyBinding[]>(DEFAULT_POS_KEY_BINDINGS);
  private readonly _suspended = signal(false);
  private readonly actionsSubject = new Subject<PosKeyAction>();

  /** Lista actual de atajos configurados, lista para mostrar en la ayuda (`F1`). */
  readonly bindings = this._bindings.asReadonly();
  /** Indica si la escucha global está pausada (p. ej. por un modal abierto). */
  readonly suspended = this._suspended.asReadonly();
  /** Flujo de acciones reconocidas cuando se invoca {@link handleKeydown}. */
  readonly actions$: Observable<PosKeyAction> = this.actionsSubject.asObservable();

  /** Pausa o reanuda el disparo de atajos globales (útil al abrir diálogos modales). */
  setSuspended(suspended: boolean): void {
    this._suspended.set(suspended);
  }

  /**
   * Reemplaza total o parcialmente el mapa de teclas por acción (`ADR-28`).
   * Si se pasa un objeto `{ action: 'F3' }`, solo se actualiza esa tecla manteniendo la descripción.
   */
  configureKeymap(overrides: Partial<Record<PosKeyAction, string>>): void {
    const updated = this._bindings().map((binding) => {
      const nextKey = overrides[binding.action];
      if (!nextKey) {
        return binding;
      }
      return {
        ...binding,
        key: nextKey,
        keyLabel: formatKeyLabel(nextKey),
      };
    });
    this._bindings.set(updated);
  }

  /** Restaura el mapa de atajos por defecto de `HU-POS-20`. */
  resetKeymap(): void {
    this._bindings.set(DEFAULT_POS_KEY_BINDINGS);
  }

  /** Resuelve la acción asociada a un evento sin emitir ni prevenir por defecto. */
  resolveAction(event: KeyboardEvent, options: KeyboardHandleOptions = {}): PosKeyAction | null {
    if (this._suspended() || event.ctrlKey || event.altKey || event.metaKey) {
      return null;
    }

    const normalizedKey = normalizeEventKey(event.key);
    const binding = this._bindings().find((item) => normalizeEventKey(item.key) === normalizedKey);
    if (!binding) {
      return null;
    }

    if (binding.action === 'removeLine' && isEditableTarget(event.target, options)) {
      return null;
    }

    return binding.action;
  }

  /**
   * Evalúa un `KeyboardEvent`, previene la acción nativa del navegador si corresponde y emite la
   * {@link PosKeyAction} en {@link actions$}.
   *
   * @returns La acción ejecutada o `null` si el evento no corresponde a un atajo activo.
   */
  handleKeydown(event: KeyboardEvent, options: KeyboardHandleOptions = {}): PosKeyAction | null {
    if (event.repeat) {
      const candidate = this.resolveAction(event, options);
      if (candidate !== null && candidate !== 'cancel') {
        event.preventDefault();
      }
      return null;
    }

    const action = this.resolveAction(event, options);
    if (action === null) {
      return null;
    }

    event.preventDefault();
    this.actionsSubject.next(action);
    return action;
  }
}

function normalizeEventKey(key: string): string {
  if (key === 'Esc') {
    return 'escape';
  }
  if (key === 'Del') {
    return 'delete';
  }
  return key.toLowerCase();
}

function formatKeyLabel(key: string): string {
  if (key === 'Escape') {
    return 'Esc';
  }
  if (key === 'Delete') {
    return 'Supr';
  }
  return key;
}

function isEditableTarget(target: EventTarget | null, options: KeyboardHandleOptions): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  if (target.dataset['posScanInput'] === 'true') {
    if (options.scanInputFocusedAndEmpty !== undefined) {
      return !options.scanInputFocusedAndEmpty;
    }
    if (target instanceof HTMLInputElement) {
      return target.value.length > 0;
    }
  }
  const tag = target.tagName.toLowerCase();
  return tag === 'input' || tag === 'textarea' || tag === 'select' || target.isContentEditable;
}
