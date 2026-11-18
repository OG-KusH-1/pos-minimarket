import { findNavItem } from './nav-permission.guard';

describe('findNavItem', () => {
  it('encuentra el ítem por ruta exacta o por prefijo de segmentos completos', () => {
    expect(findNavItem('/caja')?.label).toBe('Caja');
    expect(findNavItem('/catalogo/productos/5/editar?x=1#y')?.label).toBe('Productos');
  });

  it('no confunde prefijos parciales ni rutas desconocidas', () => {
    expect(findNavItem('/cajas')).toBeUndefined();
    expect(findNavItem('/no-existe')).toBeUndefined();
    expect(findNavItem('/login')).toBeUndefined();
  });
});
