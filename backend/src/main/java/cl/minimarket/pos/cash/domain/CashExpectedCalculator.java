package cl.minimarket.pos.cash.domain;

import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.shared.money.Rounding;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Calculadora pura del monto esperado por medio de pago al cierre o consulta de un turno de caja
 * ({@code MODELO_DATOS.md} B.8).
 *
 * <p>Fórmula oficial para efectivo:
 * {@code opening + Σ CASH_IN − Σ CASH_OUT + Σ CashFlowContributor[CASH]}.
 * Para los demás medios de pago: {@code Σ CashFlowContributor[method]}.
 */
public final class CashExpectedCalculator {

    private CashExpectedCalculator() {
    }

    /**
     * Resultado del cálculo esperado del turno.
     *
     * @param expectedCash efectivo esperado en caja (CLP, escala 0)
     * @param expectedByMethod mapa inmutable con el esperado para cada {@link PaymentMethod}
     */
    public record ExpectedTotals(
            BigDecimal expectedCash,
            Map<PaymentMethod, BigDecimal> expectedByMethod
    ) {
    }

    /**
     * Calcula los montos esperados del turno combinando apertura, movimientos manuales y aportes de
     * todos los {@code CashFlowContributor} activos.
     *
     * @param openingAmount monto inicial de apertura ({@code >= 0})
     * @param cashIn suma de ingresos manuales ({@code CASH_IN >= 0})
     * @param cashOut suma de retiros manuales ({@code CASH_OUT >= 0})
     * @param contributorMaps mapas devueltos por los {@code CashFlowContributor}
     * @return totales esperados normalizados a CLP (escala 0)
     */
    public static ExpectedTotals calculate(
            BigDecimal openingAmount,
            BigDecimal cashIn,
            BigDecimal cashOut,
            List<Map<PaymentMethod, BigDecimal>> contributorMaps
    ) {
        BigDecimal opening = normalize(openingAmount);
        BigDecimal in = normalize(cashIn);
        BigDecimal out = normalize(cashOut);

        EnumMap<PaymentMethod, BigDecimal> contributorsNet = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod method : PaymentMethod.values()) {
            contributorsNet.put(method, BigDecimal.ZERO);
        }

        if (contributorMaps != null) {
            for (Map<PaymentMethod, BigDecimal> map : contributorMaps) {
                if (map == null) {
                    continue;
                }
                for (Map.Entry<PaymentMethod, BigDecimal> entry : map.entrySet()) {
                    PaymentMethod method = Objects.requireNonNull(entry.getKey(), "paymentMethod");
                    BigDecimal value = normalize(entry.getValue());
                    contributorsNet.merge(method, value, BigDecimal::add);
                }
            }
        }

        EnumMap<PaymentMethod, BigDecimal> expectedByMethod = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod method : PaymentMethod.values()) {
            BigDecimal net = contributorsNet.get(method);
            if (method == PaymentMethod.CASH) {
                BigDecimal cashExpected = Rounding.clp(opening.add(in).subtract(out).add(net));
                expectedByMethod.put(method, cashExpected);
            } else {
                expectedByMethod.put(method, Rounding.clp(net));
            }
        }

        return new ExpectedTotals(
                expectedByMethod.get(PaymentMethod.CASH),
                Collections.unmodifiableMap(expectedByMethod)
        );
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : Rounding.clp(value);
    }
}
