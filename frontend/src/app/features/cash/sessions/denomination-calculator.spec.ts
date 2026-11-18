import { CLP_DENOMINATIONS, DenominationCalculator } from './denomination-calculator';

describe('DenominationCalculator', () => {
  const calculator = new DenominationCalculator();

  it('calcula Σ denominación × cantidad = contado para billetes y monedas CLP', () => {
    const quantities: Record<string, number> = {
      '20000': 2, // 40.000
      '10000': 1, // 10.000
      '5000': 3, // 15.000
      '2000': 2, // 4.000
      '1000': 4, // 4.000
      '500': 3, // 1.500
      '100': 7, // 700
      '50': 2, // 100
      '10': 5, // 50
    };

    const expectedSum = CLP_DENOMINATIONS.reduce(
      (acc, denom) => acc + denom.value * (quantities[String(denom.value)] ?? 0),
      0,
    );

    expect(expectedSum).toBe(75350);
    expect(calculator.calculateTotal(quantities)).toBe(75350);
    expect(calculator.lineSubtotal(20000, 2)).toBe(40000);
    expect(calculator.lineSubtotal(500, 3)).toBe(1500);
  });

  it('normaliza cantidades negativas, decimales o no numéricas a enteros >= 0', () => {
    expect(calculator.normalizeQuantity(-3)).toBe(0);
    expect(calculator.normalizeQuantity(2.9)).toBe(2);
    expect(calculator.normalizeQuantity('4')).toBe(4);
    expect(calculator.normalizeQuantity('abc')).toBe(0);
    expect(calculator.normalizeQuantity(null)).toBe(0);

    expect(
      calculator.calculateTotal({
        '10000': -2,
        '5000': '2',
        '1000': 1.8,
        '999': 10, // denominación desconocida se ignora
      }),
    ).toBe(11000);
  });

  it('construye denominationDetail solo con denominaciones positivas o undefined si todo es 0', () => {
    expect(calculator.buildDetail(calculator.emptyQuantities())).toBeUndefined();

    expect(
      calculator.buildDetail({
        '20000': 1,
        '10000': 0,
        '500': 4,
      }),
    ).toEqual({
      '20000': 1,
      '500': 4,
    });
  });

  it('calcula diferencia = contado - esperado y verifica tolerancia', () => {
    expect(calculator.difference(48000, 50000)).toBe(-2000);
    expect(calculator.difference(50500, 50000)).toBe(500);
    expect(calculator.difference(50000, 50000)).toBe(0);

    expect(calculator.exceedsTolerance(500, 500)).toBe(false);
    expect(calculator.exceedsTolerance(-500, 500)).toBe(false);
    expect(calculator.exceedsTolerance(-501, 500)).toBe(true);
    expect(calculator.exceedsTolerance(-2000, 500)).toBe(true);
  });
});
