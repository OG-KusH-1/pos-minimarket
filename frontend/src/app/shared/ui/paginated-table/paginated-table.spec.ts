import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { PageResponse } from '../../models/page-response';
import { PaginatedDataSource } from './paginated-data-source';
import { PaginatedTable, TableColumn } from './paginated-table';

interface Row {
  readonly name: string;
  readonly price: number;
}

@Component({
  imports: [PaginatedTable],
  template: `<pos-paginated-table [dataSource]="source" [columns]="columns" />`,
})
class Host {
  readonly fetch = vi.fn(() => of(PAGE));
  readonly source = new PaginatedDataSource<Row>(this.fetch);
  readonly columns: TableColumn<Row>[] = [
    { key: 'name', header: 'Nombre', sortField: 'name' },
    { key: 'price', header: 'Precio', cell: (row) => `$${row.price}`, align: 'end' },
  ];
}

let PAGE: PageResponse<Row>;

describe('PaginatedTable', () => {
  beforeEach(() => {
    PAGE = {
      content: [
        { name: 'Pan', price: 1200 },
        { name: 'Leche', price: 990 },
      ],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
      sort: [],
    };
  });

  it('muestra encabezados y filas', () => {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.source.reload();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Nombre');
    expect(text).toContain('Pan');
    expect(text).toContain('$990');
  });

  it('muestra el mensaje de vacío cuando no hay resultados', () => {
    PAGE = { ...PAGE, content: [], totalElements: 0 };
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.source.reload();
    fixture.detectChanges();

    const status = (fixture.nativeElement as HTMLElement).querySelector('[role="status"]');
    expect(status?.textContent).toContain('No hay resultados');
  });

  it('ordena en el servidor al pulsar un encabezado ordenable', () => {
    const fixture = TestBed.createComponent(Host);
    const { source } = fixture.componentInstance;
    source.reload();
    fixture.detectChanges();

    const header = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      'th .mat-sort-header-container, th[mat-sort-header]',
    );
    header?.click();
    fixture.detectChanges();

    expect(source.query().sort).toEqual([{ field: 'name', direction: 'asc' }]);
  });
});

describe('PaginatedTable propiedad del dataSource', () => {
  it('no destruye el dataSource del padre al destruirse', () => {
    const fixture = TestBed.createComponent(Host);
    const destroy = vi.spyOn(fixture.componentInstance.source, 'destroy');
    fixture.detectChanges();

    fixture.destroy();

    expect(destroy).not.toHaveBeenCalled();
  });
});
