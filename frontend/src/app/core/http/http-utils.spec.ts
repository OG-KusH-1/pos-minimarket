import { apiRelativePath, isApiUrl, isAuthUrl, isGlobalEndpoint, isPublicAuthUrl } from './api-url';
import { generateUuid } from './uuid';

describe('api-url', () => {
  it('reconoce las URL de la API propia y descarta las ajenas', () => {
    expect(apiRelativePath('/api/v1/auth/login')).toBe('/auth/login');
    expect(apiRelativePath('/api/v1/products?q=a#x')).toBe('/products');
    expect(apiRelativePath('/api/v1')).toBe('/');
    expect(apiRelativePath('/api/v2/products')).toBeNull();
    expect(apiRelativePath('/api/v1evil/x')).toBeNull();
    expect(apiRelativePath('/assets/a.svg')).toBeNull();
    expect(apiRelativePath('https://otro.example/api/v1/products')).toBeNull();
    expect(isApiUrl('/api/v1/sales')).toBe(true);
  });

  it('distingue los endpoints de auth y los públicos', () => {
    expect(isAuthUrl('/api/v1/auth/me')).toBe(true);
    expect(isAuthUrl('/api/v1/authors')).toBe(false);
    expect(isPublicAuthUrl('/api/v1/auth/login')).toBe(true);
    expect(isPublicAuthUrl('/api/v1/auth/refresh')).toBe(true);
    expect(isPublicAuthUrl('/api/v1/auth/logout')).toBe(false);
  });

  it('considera globales las URL ajenas a la API (no se les agrega nada)', () => {
    expect(isGlobalEndpoint('https://otro.example/x')).toBe(true);
    expect(isGlobalEndpoint('/api/v1/sales')).toBe(false);
  });
});

describe('generateUuid', () => {
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

  it('genera UUID v4 distintos', () => {
    const a = generateUuid();

    expect(a).toMatch(UUID);
    expect(generateUuid()).not.toBe(a);
  });

  it('funciona sin crypto.randomUUID (contexto no seguro, p. ej. HTTP por IP)', () => {
    const original = globalThis.crypto.randomUUID;
    Object.defineProperty(globalThis.crypto, 'randomUUID', {
      value: undefined,
      configurable: true,
    });
    try {
      expect(generateUuid()).toMatch(UUID);
    } finally {
      Object.defineProperty(globalThis.crypto, 'randomUUID', {
        value: original,
        configurable: true,
      });
    }
  });
});
