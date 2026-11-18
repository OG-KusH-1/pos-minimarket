import { TestBed } from '@angular/core/testing';
import {
  STORE_ID_STORAGE_KEY,
  StoreContextService,
  TERMINAL_ID_STORAGE_KEY,
} from './store-context.service';

const STORES = [
  { id: 10, code: 'L01', name: 'Centro', active: true },
  { id: 20, code: 'L02', name: 'Norte', active: true },
];

describe('StoreContextService', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  function create(): StoreContextService {
    return TestBed.inject(StoreContextService);
  }

  it('arranca sin local ni terminal si no hay preferencia', () => {
    const service = create();

    expect(service.storeId()).toBeNull();
    expect(service.terminalId()).toBeNull();
  });

  it('persiste local y terminal como preferencia y los recupera al crearse', () => {
    const first = create();
    first.setStore(10);
    first.setTerminal(3);
    expect(localStorage.getItem(STORE_ID_STORAGE_KEY)).toBe('10');
    expect(localStorage.getItem(TERMINAL_ID_STORAGE_KEY)).toBe('3');

    TestBed.resetTestingModule();
    const second = create();

    expect(second.storeId()).toBe(10);
    expect(second.terminalId()).toBe(3);
  });

  it('cambiar de local limpia la terminal; repetir el mismo local no', () => {
    const service = create();
    service.setStore(10);
    service.setTerminal(3);

    service.setStore(10);
    expect(service.terminalId()).toBe(3);

    service.setStore(20);
    expect(service.terminalId()).toBeNull();
    expect(localStorage.getItem(TERMINAL_ID_STORAGE_KEY)).toBeNull();
  });

  it('ignora preferencias corruptas', () => {
    localStorage.setItem(STORE_ID_STORAGE_KEY, 'abc');
    localStorage.setItem(TERMINAL_ID_STORAGE_KEY, '-4');

    const service = create();

    expect(service.storeId()).toBeNull();
    expect(service.terminalId()).toBeNull();
  });

  it('sigue funcionando en memoria si localStorage lanza errores', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('lleno');
    });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });

    const service = create();
    service.setStore(10);
    service.setTerminal(2);

    expect(service.storeId()).toBe(10);
    expect(service.terminalId()).toBe(2);
  });

  it('reconcile conserva la preferencia si el usuario tiene acceso a ese local', () => {
    const service = create();
    service.setStore(20);

    service.reconcile(STORES, 10);

    expect(service.storeId()).toBe(20);
    expect(service.activeStore()?.name).toBe('Norte');
  });

  it('reconcile descarta un local no asignado y usa el local por defecto', () => {
    const service = create();
    service.setStore(99);
    service.setTerminal(7);

    service.reconcile(STORES, 20);

    expect(service.storeId()).toBe(20);
    expect(service.terminalId()).toBeNull();
  });

  it('reconcile usa el único local disponible si no hay uno por defecto', () => {
    const service = create();

    service.reconcile([STORES[0]!], null);

    expect(service.storeId()).toBe(10);
  });

  it('reconcile deja sin local si hay varios y ninguno por defecto', () => {
    const service = create();
    service.setStore(99);

    service.reconcile(STORES, null);

    expect(service.storeId()).toBeNull();
  });
});
