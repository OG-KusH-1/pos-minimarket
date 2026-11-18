import { Injectable, computed, signal } from '@angular/core';
import type { StoreSummary } from '../auth/auth.models';

/** Clave de `localStorage` del local preferido. */
export const STORE_ID_STORAGE_KEY = 'pos.activeStoreId';
/** Clave de `localStorage` de la terminal preferida. */
export const TERMINAL_ID_STORAGE_KEY = 'pos.activeTerminalId';

/**
 * Contexto operativo activo: local y terminal con los que se ejecutan las peticiones (ADR-12).
 *
 * `storeContextInterceptor` lo lee para enviar `X-Store-Id` / `X-Terminal-Id`. Se expone con
 * signals (ADR-25) para que el layout y las features reaccionen al cambio de local.
 *
 * Decisiones de diseño:
 * - `localStorage` guarda solo una PREFERENCIA para recordar la última elección entre recargas.
 *   La autoridad es el servidor, que revalida cada request (`STORE_ACCESS_DENIED`); por eso,
 *   al iniciar sesión {@link reconcile} descarta una preferencia que el usuario no puede usar.
 * - El acceso a `localStorage` va siempre en try/catch: puede estar bloqueado (modo privado,
 *   políticas del navegador) o lleno, y la app debe seguir funcionando solo en memoria.
 * - Cambiar de local limpia la terminal, porque una terminal pertenece a un único local
 *   (D.4); la UI debe pedir al usuario que elija otra.
 *
 * Punto de extensión: el flujo "elegir local → elegir terminal → `POST /auth/session/terminal`"
 * (D.4) lo implementa la feature de inicio de sesión usando {@link setStore} y {@link setTerminal}.
 */
@Injectable({ providedIn: 'root' })
export class StoreContextService {
  private readonly _storeId = signal<number | null>(this.readPreference(STORE_ID_STORAGE_KEY));
  private readonly _terminalId = signal<number | null>(
    this.readPreference(TERMINAL_ID_STORAGE_KEY),
  );
  private readonly _availableStores = signal<readonly StoreSummary[]>([]);

  /** Identificador del local activo, o `null` si no hay uno elegido. */
  readonly storeId = this._storeId.asReadonly();
  /** Identificador de la terminal activa, o `null` si no hay una elegida. */
  readonly terminalId = this._terminalId.asReadonly();
  /** Locales a los que el usuario autenticado tiene acceso (vacío sin sesión). */
  readonly availableStores = this._availableStores.asReadonly();
  /** Local activo con su nombre, si figura entre los disponibles. */
  readonly activeStore = computed(
    () => this._availableStores().find((store) => store.id === this._storeId()) ?? null,
  );

  /**
   * Establece el local activo. Si cambia, la terminal activa se limpia (pertenece al local).
   *
   * @param storeId Id del local, o `null` para dejar sin local.
   */
  setStore(storeId: number | null): void {
    if (storeId === this._storeId()) {
      return;
    }
    this._storeId.set(storeId);
    this._terminalId.set(null);
    this.writePreference(STORE_ID_STORAGE_KEY, storeId);
    this.writePreference(TERMINAL_ID_STORAGE_KEY, null);
  }

  /**
   * Establece la terminal activa (debe pertenecer al local activo; lo valida el servidor).
   *
   * @param terminalId Id de la terminal, o `null` para dejar sin terminal.
   */
  setTerminal(terminalId: number | null): void {
    this._terminalId.set(terminalId);
    this.writePreference(TERMINAL_ID_STORAGE_KEY, terminalId);
  }

  /**
   * Ajusta el contexto a los locales del usuario recién autenticado.
   *
   * Si el local preferido no está entre los asignados, se usa `defaultStoreId` (o el único
   * local disponible, si solo tiene uno). Así un usuario distinto en el mismo equipo no hereda
   * un local al que no puede acceder.
   *
   * @param stores Locales asignados al usuario (`MeResponse.stores`).
   * @param defaultStoreId Local por defecto del usuario (`MeResponse.defaultStoreId`).
   */
  reconcile(stores: readonly StoreSummary[], defaultStoreId: number | null): void {
    this._availableStores.set(stores);
    const current = this._storeId();
    if (current !== null && stores.some((store) => store.id === current)) {
      return;
    }
    const fallback =
      defaultStoreId !== null && stores.some((store) => store.id === defaultStoreId)
        ? defaultStoreId
        : stores.length === 1
          ? (stores[0]?.id ?? null)
          : null;
    this.setStore(fallback);
  }

  /** Quita la lista de locales disponibles al cerrar sesión (la preferencia persistida se conserva). */
  clearAvailableStores(): void {
    this._availableStores.set([]);
  }

  /** Lee un entero positivo de `localStorage`; ante cualquier problema devuelve `null`. */
  private readPreference(key: string): number | null {
    try {
      const raw = globalThis.localStorage?.getItem(key) ?? null;
      const value = raw === null ? Number.NaN : Number(raw);
      return Number.isSafeInteger(value) && value > 0 ? value : null;
    } catch {
      return null;
    }
  }

  /** Guarda (o elimina, si es `null`) una preferencia; los fallos de almacenamiento se ignoran. */
  private writePreference(key: string, value: number | null): void {
    try {
      if (value === null) {
        globalThis.localStorage?.removeItem(key);
      } else {
        globalThis.localStorage?.setItem(key, String(value));
      }
    } catch {
      // Almacenamiento no disponible: el contexto sigue funcionando solo en memoria.
    }
  }
}
