import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DashboardHourlySales } from '../../api/models/dashboard-hourly-sales';
import { HourlySalesChart } from './hourly-sales-chart';

describe('HourlySalesChart', () => {
  let fixture: ComponentFixture<HourlySalesChart>;

  async function setup(
    salesByHour: readonly DashboardHourlySales[] = [],
    businessDate?: string,
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [HourlySalesChart],
    }).compileComponents();

    fixture = TestBed.createComponent(HourlySalesChart);
    fixture.componentRef.setInput('salesByHour', salesByHour);
    if (businessDate !== undefined) {
      fixture.componentRef.setInput('businessDate', businessDate);
    }
    fixture.detectChanges();
  }

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('renderiza exactamente 24 barras horarias (00..23), escala proporcionalmente y destaca la hora punta', async () => {
    await setup(
      [
        { hour: 10, total: 20000, transactions: 4 },
        { hour: 19, total: 80000, transactions: 12 },
      ],
      '2026-11-20',
    );

    const el = fixture.nativeElement as HTMLElement;
    const bars = el.querySelectorAll<HTMLButtonElement>('[data-hour]');
    expect(bars).toHaveLength(24);

    const bar19 = el.querySelector<HTMLButtonElement>('[data-hour="19"]');
    const bar10 = el.querySelector<HTMLButtonElement>('[data-hour="10"]');
    const bar08 = el.querySelector<HTMLButtonElement>('[data-hour="8"]');

    expect(bar19?.classList.contains('is-peak')).toBe(true);
    expect(bar19?.querySelector<HTMLElement>('.bar-fill')?.style.height).toBe('100%');
    expect(bar10?.querySelector<HTMLElement>('.bar-fill')?.style.height).toBe('25%');
    expect(bar08?.querySelector<HTMLElement>('.bar-fill')?.style.height).toBe('0%');

    const peakSummary = el.querySelector('[data-testid="peak-hour-summary"]')?.textContent;
    expect(peakSummary).toContain('19:00');
    expect(peakSummary).toContain('$80.000');
    expect(peakSummary).toContain('12 ventas');
  });

  it('permite seleccionar una hora y actualiza el detalle vivo con monto y transacciones', async () => {
    await setup([
      { hour: 9, total: 15000, transactions: 1 },
      { hour: 13, total: 60000, transactions: 8 },
    ]);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="hourly-selected-detail"]')?.textContent).toContain(
      '13:00',
    );

    el.querySelector<HTMLButtonElement>('[data-hour="9"]')?.click();
    fixture.detectChanges();

    const detail = el.querySelector('[data-testid="hourly-selected-detail"]')?.textContent;
    expect(detail).toContain('09:00');
    expect(detail).toContain('$15.000');
    expect(detail).toContain('1 venta');
  });

  it('muestra estado vacío cuando ninguna hora tiene ventas ni transacciones', async () => {
    await setup([
      { hour: 0, total: 0, transactions: 0 },
      { hour: 12, total: 0, transactions: 0 },
    ]);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role="status"]')?.textContent).toContain(
      'Sin ventas registradas en las 24 horas del día',
    );
    expect(el.querySelectorAll('[data-hour]')).toHaveLength(0);
  });

  it('permite alternar a la vista tabular de las 24 horas y volver al gráfico', async () => {
    await setup([{ hour: 18, total: 42000, transactions: 5 }]);

    const el = fixture.nativeElement as HTMLElement;
    const toggleBtn = el.querySelector<HTMLButtonElement>('header button');
    expect(toggleBtn?.textContent).toContain('Tabla');

    toggleBtn?.click();
    fixture.detectChanges();

    const rows = el.querySelectorAll('tbody tr');
    expect(rows).toHaveLength(24);
    expect(el.textContent).toContain('18:00');
    expect(el.textContent).toContain('$42.000');

    toggleBtn?.click();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-hour]')).toHaveLength(24);
  });
});
