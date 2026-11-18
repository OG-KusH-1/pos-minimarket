import { SKIP_ERROR_NOTIFICATION } from '../../../core/http/http-context-tokens';
import { HttpClient, HttpParams, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { PageResponse } from '../../models/page-response';
import { PaginatedDataSource, buildPageParams, httpPageFetcher } from './paginated-data-source';

function page<T>(content: T[], totalElements = content.length): PageResponse<T> {
  return { content, page: 0, size: 20, totalElements, totalPages: 1, sort: [] };
}

describe('buildPageParams', () => {
  it('genera page, size y un sort por criterio (repetible)', () => {
    const params = buildPageParams({
      page: 2,
      size: 50,
      sort: [
        { field: 'name', direction: 'asc' },
        { field: 'createdAt', direction: 'desc' },
      ],
      filters: {},
    });

    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('50');
    expect(params.getAll('sort')).toEqual(['name,asc', 'createdAt,desc']);
  });

  it('omite sort cuando no hay criterios', () => {
    const params = buildPageParams({ page: 0, size: 20, sort: [], filters: {} });
    expect(params.has('sort')).toBe(false);
  });

  it('incluye filtros con valor, repite arreglos y omite vacíos', () => {
    const params = buildPageParams({
      page: 0,
      size: 20,
      sort: [],
      filters: {
        q: 'leche',
        active: false,
        stock: 0,
        empty: '',
        none: null,
        undef: undefined,
        ids: [1, 2],
      },
    });

    expect(params.get('q')).toBe('leche');
    expect(params.get('active')).toBe('false');
    expect(params.get('stock')).toBe('0');
    expect(params.getAll('ids')).toEqual(['1', '2']);
    expect(params.has('empty')).toBe(false);
    expect(params.has('none')).toBe(false);
    expect(params.has('undef')).toBe(false);
  });

  it('acota size a 1..200 y page a >= 0', () => {
    expect(buildPageParams({ page: 0, size: 500, sort: [], filters: {} }).get('size')).toBe('200');
    expect(buildPageParams({ page: 0, size: 0, sort: [], filters: {} }).get('size')).toBe('1');
    expect(buildPageParams({ page: -3, size: 20, sort: [], filters: {} }).get('page')).toBe('0');
  });
});

describe('PaginatedDataSource', () => {
  it('usa page 0 y size 20 por defecto y carga las filas', () => {
    const calls: HttpParams[] = [];
    const source = new PaginatedDataSource<string>((params) => {
      calls.push(params);
      return of(page(['a', 'b'], 42));
    });

    source.reload();

    expect(calls[0]?.get('page')).toBe('0');
    expect(calls[0]?.get('size')).toBe('20');
    expect(source.rows()).toEqual(['a', 'b']);
    expect(source.totalElements()).toBe(42);
    expect(source.loading()).toBe(false);
  });

  describe('contra HttpClient', () => {
    let http: HttpTestingController;
    let source: PaginatedDataSource<{ id: number }>;

    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting()],
      });
      http = TestBed.inject(HttpTestingController);
      source = new PaginatedDataSource(
        httpPageFetcher(TestBed.inject(HttpClient), '/api/v1/items'),
      );
    });

    afterEach(() => {
      source.destroy();
      http.verify();
    });

    it('envía page, size y sort repetidos al cambiar página y orden', () => {
      source.setSort([
        { field: 'name', direction: 'asc' },
        { field: 'id', direction: 'desc' },
      ]);
      const first = http.expectOne((r) => r.url === '/api/v1/items');
      expect(first.request.params.get('page')).toBe('0');
      expect(first.request.params.getAll('sort')).toEqual(['name,asc', 'id,desc']);

      source.setPage(3, 50);
      // La petición anterior se cancela al lanzar la nueva.
      expect(first.cancelled).toBe(true);
      const second = http.expectOne((r) => r.url === '/api/v1/items');
      expect(second.request.params.get('page')).toBe('3');
      expect(second.request.params.get('size')).toBe('50');
      expect(second.request.params.getAll('sort')).toEqual(['name,asc', 'id,desc']);
      second.flush(page([{ id: 1 }], 1));

      expect(source.rows()).toEqual([{ id: 1 }]);
    });

    it('vuelve a la página 0 al cambiar filtros', () => {
      source.setPage(4);
      http.expectOne(() => true).flush(page([]));

      source.setFilters({ q: 'pan' });
      const req = http.expectOne(() => true);
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('q')).toBe('pan');
      req.flush(page([]));
    });

    it('guarda el mensaje de error y deja las filas vacías', () => {
      source.reload();
      http.expectOne(() => true).flush(null, { status: 500, statusText: 'Server Error' });

      expect(source.error()).toBeTruthy();
      expect(source.rows()).toEqual([]);
      expect(source.loading()).toBe(false);
    });
  });
});

describe('httpPageFetcher', () => {
  it('suprime la notificación automática de errores', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    httpPageFetcher(TestBed.inject(HttpClient), '/api/v1/items')(new HttpParams()).subscribe();

    const req = http.expectOne('/api/v1/items');
    expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(true);
    req.flush(page([]));
    http.verify();
  });
});
